// Ported from Jarvis (github.com/AbuZar-Ansarii/Jarvis), MIT Licence, Copyright (c) 2026 AbuZar Ansarii — Dart → Kotlin port for Mira.
// Post-action UI effect verification adapted from ClosePaw (github.com/imoonkey/closepaw, Apache-2.0) and MobileAgent (github.com/X-PLUG/MobileAgent, MIT).
package io.github.ryoaonetsuki.mira.automation.agent

import android.util.Log
import io.github.ryoaonetsuki.mira.automation.MiraAutomationBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

enum class TaskStatus {
    SUCCESS,
    FAILED,
    CANCELLED
}

data class TaskResult(
    val status: TaskStatus,
    val message: String,
    val stepsUsed: Int
)

/**
 * Port decoupling interface: abstraction over screen reading and gesture dispatch.
 * Implemented by ScreenAutomationService; faked in pure-JVM unit tests.
 */
interface AutomationPort {
    fun isServiceEnabled(): Boolean
    fun dumpScreen(): List<ScreenNode>
    fun getScreenElements(): List<ScreenNode> = dumpScreen()
    fun formatNodes(nodes: List<ScreenNode>): String
    fun getCompressedScreenDescription(nodes: List<ScreenNode>, goal: String): String
    fun clickText(text: String, nodes: List<ScreenNode>): Boolean
    fun clickText(
        text: String,
        nodes: List<ScreenNode>,
        fallbackX: Int?,
        fallbackY: Int?
    ): Boolean {
        if (clickText(text, nodes)) return true
        if (fallbackX != null && fallbackY != null && fallbackX > 0 && fallbackY > 0) {
            return clickAt(fallbackX.toFloat(), fallbackY.toFloat())
        }
        return false
    }
    fun clickAt(x: Float, y: Float): Boolean
    /** Long-press at a point (flutter_automate GestureEngine.longClick, MIT). */
    fun longPress(x: Float, y: Float, durationMs: Long = 500): Boolean = false
    /** Two-finger pinch (flutter_automate GestureEngine.pinchIn/pinchOut, MIT): [zoomIn] moves the fingers apart, else together. */
    fun pinch(centerX: Float, centerY: Float, zoomIn: Boolean): Boolean = false
    fun typeText(text: String): Boolean
    fun pressEnter(): Boolean
    fun scroll(direction: String): Boolean
    /** Scroll by a relative fraction of the screen (0.15 = "a bit"). Defaults to the plain scroll. */
    fun scroll(direction: String, fraction: Float): Boolean = scroll(direction)
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean
    fun pressBack(): Boolean
    fun pressHome(): Boolean
    fun openApp(query: String): String?
    /** Opens a URL with ACTION_VIEW (search results, web pages). */
    fun openUrl(url: String): Boolean = false
    /** Opens the dialer (ACTION_DIAL) with the number prefilled; the user confirms the call. */
    fun dialNumber(number: String): Boolean = false
    fun getCurrentPackage(): String? = null
    suspend fun takeScreenshotJpeg(maxWidthPx: Int = 720): ByteArray? = null
}

/**
 * Safety gate evaluated BEFORE any atomic action executes.
 */
/**
 * The heart of autonomous phone control: Observe -> Think -> Act loop ported from Jarvis.
 */
class TaskExecutor(
    private val automation: AutomationPort,
    private val skills: SkillMemoryService,
    private val think: (suspend (goal: String, screenDescription: String, history: List<Pair<String, String>>) -> String)? = null,
    private val maxSteps: Int = 15,
    private val stepDelayMs: Long = 600,
    var onProgress: ((step: Int, description: String) -> Unit)? = null,
    private val thinkWithVision: (suspend (goal: String, screenDescription: String, history: List<Pair<String, String>>, screenshotJpeg: ByteArray) -> String)? = null,
    // Turn F plan-once: ONE planning call returning the FULL command list as
    // JSON. Null in old call sites and in tests that do not plan — the AI
    // loop is then the only fallback.
    private val plan: (suspend (goal: String, screenDescription: String) -> String)? = null,
    // Turn G: the SAME planning call, but WITH the screenshot attached, so
    // the plan is made from what the screen actually shows (home screen
    // first, then scroll/tap as the screen demands) instead of from the
    // node-tree text alone. One vision call per goal — same round-trip
    // count as text planning, just with pixels. Null falls back to [plan].
    private val planWithVision: (suspend (goal: String, screenDescription: String, screenshotJpeg: ByteArray) -> String)? = null,
    private val taskTimeoutMs: Long = DEFAULT_TASK_TIMEOUT_MS,
    private val thinkTimeoutMs: Long = DEFAULT_THINK_TIMEOUT_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val phaseWarningThresholdSec: Long = DEFAULT_PHASE_WARNING_THRESHOLD_SEC,
    // Turn F(a): cold app launch (YouTube commonly 2-5s) is polled instead of
    // a fixed settle + one retry.
    private val openAppPollTimeoutMs: Long = DEFAULT_OPEN_APP_POLL_TIMEOUT_MS,
    private val openAppPollIntervalMs: Long = DEFAULT_OPEN_APP_POLL_INTERVAL_MS
) {
    // Turn F(f): 700ms -> 350ms settle for non-open_app steps.
    private val settleDelayMs: Long = if (stepDelayMs == 0L) 0L else 350L
    // Tests pass stepDelayMs == 0, so polling is a single immediate check
    // there (fast); on the phone the full ~8s cold-start window applies.
    private val effectiveOpenAppPollTimeoutMs: Long =
        if (stepDelayMs == 0L) 0L else openAppPollTimeoutMs
    private val effectiveOpenAppPollIntervalMs: Long =
        if (stepDelayMs == 0L) 0L else openAppPollIntervalMs

    @Volatile
    private var _isRunning: Boolean = false
    val isRunning: Boolean get() = _isRunning

    @Volatile
    private var _cancelRequested: Boolean = false

    @Volatile
    private var _currentStep: Int = 0
    val currentStep: Int get() = _currentStep

    @Volatile
    private var _currentPhase: String = "idle"
    val currentPhase: String get() = _currentPhase

    @Volatile
    private var _phaseStartTimeMs: Long = 0L
    val phaseStartTimeMs: Long get() = _phaseStartTimeMs

    @Volatile
    private var _phaseDetail: String = ""
    val phaseDetail: String get() = _phaseDetail

    @Volatile
    private var _lastDiagnosis: String? = null
    val lastDiagnosis: String? get() = _lastDiagnosis

    @Volatile
    private var _lastError: String? = null
    val lastError: String? get() = _lastError

    @Volatile
    private var lastActionError: String? = null

    private fun enterPhase(phase: String, step: Int, detail: String = "") {
        _currentPhase = phase
        _currentStep = step
        _phaseDetail = detail
        _phaseStartTimeMs = clockMs()
        val text = if (detail.isNotBlank()) detail else phase
        onProgress?.invoke(step, text)
    }

    private fun recordDiagnosis(diag: String) {
        _lastDiagnosis = diag
        _lastError = diag
    }

    private val recovery = RecoveryEngine()

    fun cancel() {
        _cancelRequested = true
    }

    suspend fun executeGoal(goal: String): TaskResult {
        if (_isRunning) {
            return TaskResult(TaskStatus.FAILED, "Another task is already running", 0)
        }
        _isRunning = true
        _cancelRequested = false
        _lastDiagnosis = null
        _lastError = null
        lastActionError = null
        _currentStep = 0
        _currentPhase = "starting"
        _phaseDetail = goal
        _phaseStartTimeMs = clockMs()
        recovery.reset()

        try {
            val timedResult = withTimeoutOrNull(taskTimeoutMs) {
                coroutineScope {
                    val phaseWatcher = launch {
                        while (isActive) {
                            delay(1000L)
                            val start = _phaseStartTimeMs
                            if (start > 0L) {
                                val elapsedSec = (clockMs() - start) / 1000L
                                if (elapsedSec >= phaseWarningThresholdSec) {
                                    val detailSuffix = if (_phaseDetail.isNotBlank()) " — ${_phaseDetail}" else ""
                                    val warning = "Still ${_currentPhase}… (${elapsedSec}s)$detailSuffix"
                                    _lastDiagnosis = warning
                                    onProgress?.invoke(_currentStep, warning)
                                }
                            }
                        }
                    }
                    try {
                        executeGoalInternal(goal)
                    } finally {
                        phaseWatcher.cancel()
                    }
                }
            }
            if (timedResult == null) {
                _cancelRequested = true
                val step = _currentStep
                val phase = _currentPhase
                val timeoutMsg = when (phase) {
                    "observing" -> "Timed out during screen read (step $step, dumpScreen exceeded cap)"
                    "acting" -> "Timed out while executing action (step $step, ${_phaseDetail.ifEmpty { "action hung" }})"
                    else -> "Task timed out after ${taskTimeoutMs / 1000}s during $phase (step $step)"
                }
                recordDiagnosis(timeoutMsg)
                logWarn("Task '$goal' timed out after ${taskTimeoutMs}ms: $timeoutMsg")
                return TaskResult(
                    status = TaskStatus.FAILED,
                    message = timeoutMsg,
                    stepsUsed = step
                )
            }
            return timedResult
        } finally {
            _isRunning = false
            _cancelRequested = false
            _currentPhase = "idle"
            _phaseStartTimeMs = 0L
        }
    }

    private suspend fun executeGoalInternal(goal: String): TaskResult {
        // 1. Accessibility gate
        if (!automation.isServiceEnabled()) {
            return TaskResult(
                status = TaskStatus.FAILED,
                message = "Accessibility service is off — enable Mira Screen Control in Settings > Accessibility",
                stepsUsed = 0
            )
        }

        // 2. Direct fast path (Turn F-direct / Voice Access model): a fixed
        // local command set (open app, home, back, scroll, tap label, search,
        // dial) resolved by the local grammar with ZERO Gemini calls.
        // Pre-built macros are exactly this set — always available, no
        // recording needed.
        executeDirectAction(goal)?.let { return it }

        // 3. Skill-memory fast path (Jarvis task_executor.dart:308-333 behaviour):
        // a saved command list replays directly with zero think calls on the
        // happy path — the learning loop's instant second invocation.
        val matchedSkill = skills.findMatch(goal)
        if (matchedSkill != null) {
            onProgress?.invoke(0, "Found memorized skill (${matchedSkill.steps.size} steps). Replaying…")
            return executeSkillWithResume(matchedSkill)
        }

        // Neither direct action nor saved skill matched. Live voice model executes actions via tool calls.
        return TaskResult(
            status = TaskStatus.FAILED,
            message = "No direct action or saved skill matched for goal '$goal'",
            stepsUsed = 0
        )
    }

    /**
     * Turn F-direct: executes one fixed local command with zero think calls.
     * Returns null when the goal is not a fixed command — the caller falls
     * through to skill replay.
     */
    internal suspend fun executeDirectAction(goal: String): TaskResult? {
        val action = MiraAutomationBridge.parseDirectAction(goal) ?: return null
        return when (action) {
            is MiraAutomationBridge.DirectAction.OpenApp -> executeDirectOpenApp(action.target)
            MiraAutomationBridge.DirectAction.PressHome -> {
                enterPhase("acting", 1, "Going home")
                if (automation.pressHome()) {
                    TaskResult(TaskStatus.SUCCESS, "Went home", 1)
                } else {
                    recordDiagnosis("Direct press_home failed (gesture rejected)")
                    TaskResult(TaskStatus.FAILED, "Could not go home (gesture rejected)", 1)
                }
            }
            MiraAutomationBridge.DirectAction.PressBack -> {
                enterPhase("acting", 1, "Going back")
                if (automation.pressBack()) {
                    TaskResult(TaskStatus.SUCCESS, "Went back", 1)
                } else {
                    recordDiagnosis("Direct press_back failed (gesture rejected)")
                    TaskResult(TaskStatus.FAILED, "Could not go back (gesture rejected)", 1)
                }
            }
            is MiraAutomationBridge.DirectAction.Scroll -> {
                enterPhase("acting", 1, "Scrolling ${action.direction}")
                val ok = if (action.small) {
                    automation.scroll(action.direction, SCROLL_SMALL_FRACTION)
                } else {
                    automation.scroll(action.direction)
                }
                if (ok) {
                    TaskResult(TaskStatus.SUCCESS, "Scrolled ${action.direction}", 1)
                } else {
                    recordDiagnosis("Direct scroll ${action.direction} failed")
                    TaskResult(TaskStatus.FAILED, "Could not scroll ${action.direction}", 1)
                }
            }
            is MiraAutomationBridge.DirectAction.TapLabel -> executeDirectTapLabel(action.label)
            is MiraAutomationBridge.DirectAction.Search -> executeDirectSearch(action)
            is MiraAutomationBridge.DirectAction.Dial -> executeDirectDial(action.number)
        }
    }

    internal suspend fun executeDirectOpenApp(target: String): TaskResult? {
        val step = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to target))
        val prePackage = automation.getCurrentPackage().orEmpty()
        _currentStep = 1
        enterPhase("acting", 1, "Opening $target")
        // startActivity with FLAG_ACTIVITY_NEW_TASK from the service context
        // opens the app from whatever page the user is on.
        val launched = automation.openApp(target)
        if (launched == null) return null // app not resolvable — let skill/AI try
        enterPhase("observing", 1, "waiting for app to open")
        val reached = pollForOpenApp(step, prePackage)
        if (!openAppTargetReached(step, prePackage, reached)) {
            val msg = "Opened $target but it did not come to the foreground (current='$reached')"
            recordDiagnosis(msg)
            return TaskResult(TaskStatus.FAILED, msg, 1)
        }
        // Turn G: the package is right — now prove the app's screen is
        // really there (pixels + node tree). A matching package with a blank
        // screen is still loading, not done; say so honestly instead of
        // declaring success on a black screen.
        val screenNodes = verifyOpenAppScreen(step)
        return if (screenNodes != null) {
            TaskResult(TaskStatus.SUCCESS, "Opened $target", 1)
        } else {
            val msg = "Opened $target but its screen is still blank — it may still be loading; try again in a moment"
            recordDiagnosis(msg)
            TaskResult(TaskStatus.FAILED, msg, 1)
        }
    }

    /**
     * Turn G: after open_app, read the screen (one settle + re-read when
     * blank). Returns the nodes when the target app shows content, null
     * when its screen stays blank. Tests run with stepDelayMs == 0, where
     * the settle is skipped and a single read decides.
     */
    private suspend fun verifyOpenAppScreen(step: TaskStep): List<ScreenNode>? {
        val first = dumpScreenSafe()
        if (first.isNotEmpty()) return first
        if (stepDelayMs > 0L) delay(1500L)
        val second = dumpScreenSafe()
        return second.ifEmpty { null }
    }

    internal suspend fun executeDirectTapLabel(label: String): TaskResult? {
        // Direct accessibility action on the live node tree: no think call,
        // no gesture round-trip. A label that is not on screen falls through
        // to the AI loop (which can scroll / look for it).
        _currentStep = 1
        enterPhase("observing", 1, "looking for '$label'")
        val nodes = dumpScreenSafe()
        if (nodes.isEmpty()) return null
        enterPhase("acting", 1, "tapping '$label'")
        return if (automation.clickText(label, nodes)) {
            TaskResult(TaskStatus.SUCCESS, "Tapped '$label'", 1)
        } else {
            null
        }
    }

    internal suspend fun executeDirectSearch(action: MiraAutomationBridge.DirectAction.Search): TaskResult? {
        // Turn G (verified misroute): a search naming a known app ("search X
        // on WhatsApp") used to open a Google web search in Chrome and call
        // ANY package change success. A named app must NEVER route to a web
        // search — open the app itself and let plan-once (vision) plan the
        // in-app search from the app's real home screen.
        val targetApp = action.targetApp
        if (targetApp != null && targetApp != YOUTUBE_PACKAGE) {
            val prePackage = automation.getCurrentPackage().orEmpty()
            val launched = automation.openApp(targetApp)
            if (launched == null) {
                val msg = "Could not open $targetApp to search for '${action.query}'"
                recordDiagnosis(msg)
                return TaskResult(TaskStatus.FAILED, msg, 1)
            }
            val step = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to targetApp))
            pollForOpenApp(step, prePackage)
            recordDiagnosis(
                "Search for '${action.query}' is inside $targetApp, not the web — opened the app; planning the in-app search from its home screen."
            )
            return null // fall through to plan-once with the app open
        }
        val encoded = URLEncoder.encode(action.query, "UTF-8")
        val url = if (targetApp == YOUTUBE_PACKAGE) {
            "https://www.youtube.com/results?search_query=$encoded"
        } else {
            "https://www.google.com/search?q=$encoded"
        }
        val prePackage = automation.getCurrentPackage().orEmpty()
        _currentStep = 1
        enterPhase("acting", 1, "Searching for '${action.query}'")
        if (!automation.openUrl(url)) return null
        val current = pollForPackageChange(prePackage)
        // Turn G: a search succeeds only when the intended viewer actually
        // opened (browser, or YouTube for a YouTube search) — any other app
        // (or no change) is a failure, not success.
        val expected = targetApp == YOUTUBE_PACKAGE
        val openedRightApp = current != prePackage &&
            (isBrowserPackage(current) || (expected && current == YOUTUBE_PACKAGE))
        return if (openedRightApp) {
            TaskResult(TaskStatus.SUCCESS, "Searched for '${action.query}'", 1)
        } else {
            val msg = "Search did not open in the intended app (current='$current')"
            recordDiagnosis(msg)
            TaskResult(TaskStatus.FAILED, msg, 1)
        }
    }

    internal suspend fun executeDirectDial(number: String): TaskResult {
        // ACTION_DIAL only opens the dialer with the number prefilled — the
        // user confirms the call there. No call is placed automatically.
        val prePackage = automation.getCurrentPackage().orEmpty()
        _currentStep = 1
        enterPhase("acting", 1, "Opening dialer")
        if (!automation.dialNumber(number)) {
            val msg = "No dialer app available"
            recordDiagnosis(msg)
            return TaskResult(TaskStatus.FAILED, msg, 1)
        }
        val current = pollForPackageChange(prePackage)
        // Turn G: the dialer must actually be the app that opened — any
        // other app (e.g. a browser) is a failure, not success.
        return if (current != prePackage && isDialerPackage(current)) {
            TaskResult(TaskStatus.SUCCESS, "Opened dialer for $number", 1)
        } else {
            val msg = "Dialer did not come to the foreground (current='$current')"
            recordDiagnosis(msg)
            TaskResult(TaskStatus.FAILED, msg, 1)
        }
    }

    /** Polls until the foreground package changes from [prePackage] (short window for intents). */
    private suspend fun pollForPackageChange(prePackage: String): String {
        val timeoutMs = if (stepDelayMs == 0L) 0L else DIRECT_INTENT_POLL_TIMEOUT_MS
        val deadline = clockMs() + timeoutMs
        var current = automation.getCurrentPackage().orEmpty()
        while (current == prePackage && !_cancelRequested && clockMs() < deadline) {
            delay(250L)
            current = automation.getCurrentPackage().orEmpty()
        }
        return current
    }

    suspend fun replaySkillDirect(skill: Skill): TaskResult {
        if (_isRunning) {
            return TaskResult(TaskStatus.FAILED, "Another task is already running", 0)
        }
        _isRunning = true
        _cancelRequested = false
        _lastDiagnosis = null
        _lastError = null
        lastActionError = null
        _currentStep = 0
        _currentPhase = "starting"
        _phaseDetail = skill.goal
        _phaseStartTimeMs = clockMs()
        recovery.reset()

        try {
            val timedResult = withTimeoutOrNull(taskTimeoutMs) {
                coroutineScope {
                    val phaseWatcher = launch {
                        while (isActive) {
                            delay(1000L)
                            val start = _phaseStartTimeMs
                            if (start > 0L) {
                                val elapsedSec = (clockMs() - start) / 1000L
                                if (elapsedSec >= phaseWarningThresholdSec) {
                                    val detailSuffix = if (_phaseDetail.isNotBlank()) " — ${_phaseDetail}" else ""
                                    val warning = "Still ${_currentPhase}… (${elapsedSec}s)$detailSuffix"
                                    _lastDiagnosis = warning
                                    onProgress?.invoke(_currentStep, warning)
                                }
                            }
                        }
                    }
                    try {
                        replaySkillDirectInternal(skill)
                    } finally {
                        phaseWatcher.cancel()
                    }
                }
            }
            if (timedResult == null) {
                _cancelRequested = true
                val step = _currentStep
                val phase = _currentPhase
                val timeoutMsg = when {
                    phase == "observing" -> "Timed out during screen read (replay step $step, dumpScreen exceeded cap)"
                    phase == "acting" -> "Timed out while executing action (replay step $step, ${_phaseDetail.ifEmpty { "action hung" }})"
                    phase.startsWith("replaying") -> "Timed out during $phase (${_phaseDetail.ifEmpty { "step hung" }})"
                    else -> "Task timed out after ${taskTimeoutMs / 1000}s during $phase (step $step)"
                }
                recordDiagnosis(timeoutMsg)
                logWarn("Skill replay '${skill.goal}' timed out after ${taskTimeoutMs}ms: $timeoutMsg")
                return TaskResult(
                    status = TaskStatus.FAILED,
                    message = timeoutMsg,
                    stepsUsed = step
                )
            }
            return timedResult
        } finally {
            _isRunning = false
            _cancelRequested = false
            _currentPhase = "idle"
            _phaseStartTimeMs = 0L
        }
    }

    private suspend fun replaySkillDirectInternal(skill: Skill): TaskResult {
        if (!automation.isServiceEnabled()) {
            return TaskResult(
                status = TaskStatus.FAILED,
                message = "Accessibility service is off — enable Mira Screen Control in Settings > Accessibility",
                stepsUsed = 0
            )
        }

        if (skill.steps.isEmpty()) {
            return TaskResult(
                status = TaskStatus.FAILED,
                message = "Skill '${skill.goal}' has no recorded steps. Please re-record the macro.",
                stepsUsed = 0
            )
        }

        onProgress?.invoke(0, "Replaying macro '${skill.goal}' (${skill.steps.size} steps)…")
        return executeSkillWithResume(skill)
    }

    /**
     * Replays a skill with delete-at-3 and retry-once per step.
     * On failure, returns a diagnostic TaskResult so the Live voice model
     * can inspect the failure and issue corrected tool calls.
     */
    internal suspend fun executeSkillWithResume(skill: Skill): TaskResult {
        if (skill.steps.isEmpty()) {
            logWarn("Cannot replay skill '${skill.goal}': no steps recorded")
            return TaskResult(TaskStatus.FAILED, "Skill '${skill.goal}' has no recorded steps", 0)
        }
        val first = replaySkillFrom(skill, 0)
        if (_cancelRequested) {
            return TaskResult(TaskStatus.CANCELLED, "Task cancelled by user", skill.steps.size)
        }
        if (first.success) {
            skills.markReplayOutcome(skill.id, true)
            return TaskResult(
                status = TaskStatus.SUCCESS,
                message = "Task completed via memorized skill",
                stepsUsed = skill.steps.size
            )
        }
        val failedStepIndex = first.failedStepIndex.coerceIn(0, skill.steps.size - 1)
        val failedStepNumber = failedStepIndex + 1
        skills.markReplayOutcome(skill.id, false)
        val diag = _lastDiagnosis ?: "Skill replay failed during execution. Please re-record the macro."
        return TaskResult(
            status = TaskStatus.FAILED,
            message = "Skill replay failed at step $failedStepNumber: $diag",
            stepsUsed = _currentStep
        )
    }

    private fun describeStep(step: TaskStep): String = when (step.action) {
        TaskStep.ACTION_OPEN_APP -> "open the app '${step.stringParam("app") ?: step.stringParam("package") ?: ""}'"
        TaskStep.ACTION_CLICK_TEXT -> "tap '${step.stringParam("text") ?: ""}' on screen"
        TaskStep.ACTION_CLICK_AT -> "tap at (${step.intParam("x")}, ${step.intParam("y")})"
        TaskStep.ACTION_TYPE_TEXT -> "type '${step.stringParam("text") ?: ""}'"
        TaskStep.ACTION_PRESS_ENTER -> "press Enter"
        TaskStep.ACTION_SCROLL -> "scroll ${step.stringParam("direction") ?: "down"}"
        TaskStep.ACTION_SWIPE -> "swipe on screen"
        TaskStep.ACTION_LONG_PRESS -> "long-press at (${step.intParam("x")}, ${step.intParam("y")})"
        TaskStep.ACTION_PINCH -> "pinch ${step.stringParam("direction") ?: "in"} to zoom"
        TaskStep.ACTION_PRESS_BACK -> "press Back"
        TaskStep.ACTION_PRESS_HOME -> "press Home"
        TaskStep.ACTION_WAIT -> "wait a moment"
        TaskStep.ACTION_DONE -> "finish"
        else -> step.action
    }

    private fun dumpScreenSafe(): List<ScreenNode> {
        return try {
            automation.getScreenElements()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Turn F(a): after an open_app dispatch, poll the foreground package
     * until the target is reached (cold start can take 2-5s) instead of a
     * fixed settle. Returns the last observed package. A package check is
     * cheap — no screen dump happens inside the poll.
     */
    private suspend fun pollForOpenApp(step: TaskStep, prePackage: String): String {
        val deadline = clockMs() + effectiveOpenAppPollTimeoutMs
        var current = automation.getCurrentPackage().orEmpty()
        while (true) {
            if (openAppTargetReached(step, prePackage, current)) return current
            if (_cancelRequested) return current
            if (clockMs() >= deadline) return current
            delay(effectiveOpenAppPollIntervalMs)
            current = automation.getCurrentPackage().orEmpty()
        }
    }

    /**
     * Turn F(e/f): one settle + one screen read per verification. open_app
     * polls for the target package first; every other action waits the short
     * settle and dumps once. Returns (postNodes, postPackage, postSignature).
     * Callers set the observing phase before invoking this.
     */
    private suspend fun settleAndDump(step: TaskStep, prePackage: String): Triple<List<ScreenNode>, String, Int> {
        if (step.action == TaskStep.ACTION_OPEN_APP) {
            pollForOpenApp(step, prePackage)
        } else {
            delay(settleDelayMs)
        }
        val postNodes = dumpScreenSafe()
        val postPackage = automation.getCurrentPackage().orEmpty()
        return Triple(postNodes, postPackage, computeNodeSignature(postNodes))
    }

    data class ReplayOutcome(
        val success: Boolean,
        /** 0-based index of the step that failed, or -1 when the range completed. */
        val failedStepIndex: Int = -1,
        val stepsCompleted: Int = 0
    )

    /**
     * Turn F(b): replays recorded steps starting at [startIndex] so an AI
     * fix for one failed step can RESUME the macro instead of restarting
     * the whole goal. Returns which step failed, if any.
     */
    /**
     * Direct replay of a saved skill's step list, with no think calls and no
     * AI resume — the boolean form used by macro play and tests. True only
     * when the replay succeeds (every critical step verified; at most half
     * the steps skipped as non-critical no-effect). False for empty skills.
     */
    suspend fun replaySkill(skill: Skill): Boolean {
        if (skill.steps.isEmpty()) return false
        return replaySkillFrom(skill, 0).success
    }

    suspend fun replaySkillFrom(skill: Skill, startIndex: Int): ReplayOutcome {
        if (skill.steps.isEmpty()) {
            logWarn("Cannot replay skill '${skill.goal}': no steps recorded")
            return ReplayOutcome(success = false, failedStepIndex = startIndex.coerceAtLeast(0))
        }
        val from = startIndex.coerceIn(0, skill.steps.size)
        val range = skill.steps.subList(from, skill.steps.size)
        val totalSteps = range.size
        var stepCount = 0
        var skippedCount = 0
        var lastSkippedIndex = -1
        // Turn F(e): the previous step's post-verification dump is carried
        // forward as the next step's pre-dump — one screen read per step
        // instead of two. A retry always re-dumps, so a stale carry
        // self-heals on the retry path.
        var carriedNodes: List<ScreenNode>? = null
        var carriedPackage: String? = null
        var carriedSignature: Int? = null
        for ((rangeIndex, step) in range.withIndex()) {
            val stepIndex = from + rangeIndex
            val stepNumber = stepIndex + 1
            if (_cancelRequested) {
                return ReplayOutcome(success = false, failedStepIndex = stepIndex, stepsCompleted = stepCount)
            }
            stepCount++
            _currentStep = stepNumber
            enterPhase("replaying step $stepNumber", stepNumber, "Replaying: ${step.action}")

            var preNodes: List<ScreenNode>
            var prePackage: String
            var preSignature: Int
            val carried = carriedNodes
            if (carried != null && carriedPackage != null && carriedSignature != null) {
                preNodes = carried
                prePackage = carriedPackage
                preSignature = carriedSignature
                carriedNodes = null
                carriedPackage = null
                carriedSignature = null
            } else {
                enterPhase("observing", stepNumber, "reading screen (step $stepNumber)")
                preNodes = dumpScreenSafe()
                prePackage = automation.getCurrentPackage().orEmpty()
                preSignature = computeNodeSignature(preNodes)
            }

            // Turn G: no artificial action restrictions (owner order).
            if (step.action == TaskStep.ACTION_WAIT || step.action == TaskStep.ACTION_DONE) {
                enterPhase("acting", stepNumber, "executing ${step.action} (step $stepNumber)")
                _performAction(step, preNodes)
                delay(stepDelayMs)
                carriedNodes = null
                carriedPackage = null
                carriedSignature = null
                continue
            }

            // (i) Dispatch with one retry after re-dumping the screen
            enterPhase("acting", stepNumber, "executing ${step.action} (step $stepNumber)")
            var ok = _performAction(step, preNodes)
            if (!ok) {
                val reason = lastActionError ?: "${step.action} dispatch failed"
                logWarn("Replayed step $stepNumber/$totalSteps (${step.action}) dispatch failed ($reason) on attempt 1 — re-dumping screen and retrying once")
                delay(stepDelayMs)
                if (_cancelRequested) {
                    return ReplayOutcome(success = false, failedStepIndex = stepIndex, stepsCompleted = stepCount)
                }
                enterPhase("observing", stepNumber, "re-reading screen for retry (step $stepNumber)")
                preNodes = dumpScreenSafe()
                prePackage = automation.getCurrentPackage().orEmpty()
                preSignature = computeNodeSignature(preNodes)
                enterPhase("acting", stepNumber, "retrying ${step.action} (step $stepNumber)")
                ok = _performAction(step, preNodes)
            }
            if (!ok) {
                val reason = lastActionError ?: "${step.action} dispatch failed"
                recordDiagnosis("Macro replay step $stepNumber failed: $reason")
                logWarn("Replayed step $stepNumber/$totalSteps (${step.action}) dispatch failed after retry ($reason) — aborting replay")
                return ReplayOutcome(success = false, failedStepIndex = stepIndex, stepsCompleted = stepCount)
            }

            // (ii) Verify effect; if no effect, retry once, then skip non-critical or fail critical.
            // Turn F(a): open_app polls for the target package (cold start)
            // instead of a fixed 1200ms settle.
            enterPhase(
                "observing",
                stepNumber,
                if (step.action == TaskStep.ACTION_OPEN_APP) "waiting for app to open (step $stepNumber)"
                else "verifying effect (step $stepNumber)"
            )
            var (postNodes, postPackage, postSignature) = settleAndDump(step, prePackage)
            var effectVerified = verifyEffect(step, prePackage, preSignature, postPackage, postSignature, goal = skill.goal)

            if (!effectVerified) {
                logWarn("Replayed step $stepNumber/$totalSteps (${step.action}) had no verified effect — re-dumping screen and retrying once")
                if (_cancelRequested) {
                    return ReplayOutcome(success = false, failedStepIndex = stepIndex, stepsCompleted = stepCount)
                }
                preNodes = postNodes
                prePackage = postPackage
                preSignature = postSignature
                enterPhase("acting", stepNumber, "retrying ${step.action} for effect verification (step $stepNumber)")
                val retryDispatched = _performAction(step, preNodes)
                if (retryDispatched) {
                    enterPhase(
                        "observing",
                        stepNumber,
                        if (step.action == TaskStep.ACTION_OPEN_APP) "waiting for app to open on retry (step $stepNumber)"
                        else "verifying effect on retry (step $stepNumber)"
                    )
                    val retried = settleAndDump(step, prePackage)
                    postNodes = retried.first
                    postPackage = retried.second
                    postSignature = retried.third
                    effectVerified = verifyEffect(step, prePackage, preSignature, postPackage, postSignature, goal = skill.goal)
                }
            }

            if (!effectVerified) {
                if (isCriticalReplayStep(step.action)) {
                    val detail = if (step.action == TaskStep.ACTION_OPEN_APP) {
                        val app = (step.stringParam("app") ?: step.stringParam("package") ?: "app").trim()
                        "failed to open $app (app did not open or enter foreground; current='$postPackage')"
                    } else {
                        "critical step ${step.action} had no verified effect"
                    }
                    recordDiagnosis("Macro replay step $stepNumber failed: $detail")
                    logWarn("Replayed critical step $stepNumber/$totalSteps (${step.action}) had no verified effect after retry — aborting replay: $detail")
                    return ReplayOutcome(success = false, failedStepIndex = stepIndex, stepsCompleted = stepCount)
                }
                skippedCount++
                lastSkippedIndex = stepIndex
                logWarn("Replayed non-critical step $stepNumber/$totalSteps (${step.action}) had no verified effect after retry — skipping ($skippedCount/$totalSteps skipped)")
            }

            // Carry this step's verified post-dump forward as the next
            // step's pre-dump (Turn F/e).
            carriedNodes = postNodes
            carriedPackage = postPackage
            carriedSignature = postSignature
        }

        // (iii) If skipped steps are more than half of all steps, replay overall fails.
        // The AI resume path can still fix the last skipped step.
        if (skippedCount * 2 > totalSteps) {
            val diag = "Macro replay failed: skipped $skippedCount of $totalSteps steps (>50%) for '${skill.goal}'"
            recordDiagnosis(diag)
            logWarn(diag)
            return ReplayOutcome(success = false, failedStepIndex = lastSkippedIndex, stepsCompleted = stepCount)
        }
        return ReplayOutcome(success = stepCount > 0, stepsCompleted = stepCount)
    }

    fun shouldAttachScreenshot(
        nodes: List<ScreenNode>,
        lastStepHadNoEffect: Boolean,
        lastStepWasOpenApp: Boolean = false
    ): Boolean =
        Companion.shouldAttachScreenshot(nodes, lastStepHadNoEffect, lastStepWasOpenApp)

    private suspend fun _performAction(step: TaskStep, nodes: List<ScreenNode>): Boolean {
        lastActionError = null
        return try {
            when (step.action) {
                TaskStep.ACTION_CLICK_TEXT -> {
                    val text = step.stringParam("text") ?: ""
                    val fallbackX = step.intParam("fallbackX") ?: step.intParam("x")
                    val fallbackY = step.intParam("fallbackY") ?: step.intParam("y")
                    val success = automation.clickText(text, nodes, fallbackX, fallbackY)
                    if (!success) {
                        lastActionError = if (fallbackX != null && fallbackY != null) {
                            "clickText '$text' not found and fallback coordinate tap ($fallbackX, $fallbackY) rejected/timed out"
                        } else {
                            "clickText '$text' not found on screen and no fallback coordinates"
                        }
                    }
                    success
                }
                TaskStep.ACTION_CLICK_AT -> {
                    val x = step.intParam("x") ?: run {
                        lastActionError = "click_at missing x coordinate"
                        return false
                    }
                    val y = step.intParam("y") ?: run {
                        lastActionError = "click_at missing y coordinate"
                        return false
                    }
                    val success = automation.clickAt(x.toFloat(), y.toFloat())
                    if (!success) {
                        lastActionError = "click_at ($x, $y) failed (gesture rejected or callback timed out)"
                    }
                    success
                }
                TaskStep.ACTION_TYPE_TEXT -> {
                    val text = step.stringParam("text") ?: run {
                        lastActionError = "type_text missing text param"
                        return false
                    }
                    val success = automation.typeText(text)
                    if (!success) {
                        lastActionError = "type_text failed (no focused editable field)"
                    }
                    success
                }
                TaskStep.ACTION_PRESS_ENTER -> {
                    val success = automation.pressEnter()
                    if (!success) {
                        lastActionError = "press_enter failed"
                    }
                    success
                }
                TaskStep.ACTION_SCROLL -> {
                    val dir = step.stringParam("direction") ?: "down"
                    val success = automation.scroll(dir)
                    if (!success) {
                        lastActionError = "scroll '$dir' failed (gesture rejected or callback timed out)"
                    }
                    success
                }
                TaskStep.ACTION_SWIPE -> {
                    val sx = step.intParam("startX") ?: run { lastActionError = "swipe missing startX"; return false }
                    val sy = step.intParam("startY") ?: run { lastActionError = "swipe missing startY"; return false }
                    val ex = step.intParam("endX") ?: run { lastActionError = "swipe missing endX"; return false }
                    val ey = step.intParam("endY") ?: run { lastActionError = "swipe missing endY"; return false }
                    val duration = (step.intParam("durationMs") ?: 350).toLong()
                    val success = automation.swipe(sx.toFloat(), sy.toFloat(), ex.toFloat(), ey.toFloat(), duration)
                    if (!success) {
                        lastActionError = "swipe failed (gesture rejected or callback timed out)"
                    }
                    success
                }
                TaskStep.ACTION_LONG_PRESS -> {
                    val x = step.intParam("x") ?: run { lastActionError = "long_press missing x coordinate"; return false }
                    val y = step.intParam("y") ?: run { lastActionError = "long_press missing y coordinate"; return false }
                    val duration = (step.intParam("durationMs") ?: 500).toLong()
                    val success = automation.longPress(x.toFloat(), y.toFloat(), duration)
                    if (!success) {
                        lastActionError = "long_press ($x, $y) failed (gesture rejected or callback timed out)"
                    }
                    success
                }
                TaskStep.ACTION_PINCH -> {
                    val cx = step.intParam("centerX") ?: step.intParam("x")
                    val cy = step.intParam("centerY") ?: step.intParam("y")
                    if (cx == null || cy == null) {
                        lastActionError = "pinch missing centerX/centerY"
                        return false
                    }
                    val zoomIn = (step.stringParam("direction") ?: "in").lowercase() != "out"
                    val success = automation.pinch(cx.toFloat(), cy.toFloat(), zoomIn)
                    if (!success) {
                        lastActionError = "pinch failed (gesture rejected or callback timed out)"
                    }
                    success
                }
                TaskStep.ACTION_PRESS_BACK -> {
                    val success = automation.pressBack()
                    if (!success) lastActionError = "press_back failed"
                    success
                }
                TaskStep.ACTION_PRESS_HOME -> {
                    val success = automation.pressHome()
                    if (!success) lastActionError = "press_home failed"
                    success
                }
                TaskStep.ACTION_OPEN_APP -> {
                    val query = (step.stringParam("app") ?: step.stringParam("package") ?: "").trim()
                    if (query.isEmpty()) {
                        lastActionError = "open_app target not specified"
                        return false
                    }
                    val launched = automation.openApp(query)
                    if (launched == null) {
                        lastActionError = "could not resolve or launch app '$query'"
                        false
                    } else {
                        true
                    }
                }
                TaskStep.ACTION_WAIT -> {
                    val ms = (step.intParam("ms") ?: 1500).coerceIn(100, 10000).toLong()
                    delay(ms)
                    true
                }
                TaskStep.ACTION_DONE -> true
                else -> {
                    lastActionError = "unknown action: ${step.action}"
                    false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lastActionError = "${step.action} threw: ${e.message}"
            logError("Action ${step.action} threw: ${e.message}", e)
            false
        }
    }

    private fun pushTurn(
        history: MutableList<Pair<String, String>>,
        assistant: String,
        userNote: String
    ) {
        history.add(Pair("assistant", assistant))
        history.add(Pair("user", userNote))
        while (history.size > 12) {
            history.removeAt(0)
            history.removeAt(0)
        }
    }

    private fun logInfo(msg: String) {
        try { Log.i(TAG, msg) } catch (_: Throwable) {}
    }

    private fun logWarn(msg: String) {
        try { Log.w(TAG, msg) } catch (_: Throwable) {}
    }

    private fun logError(msg: String, e: Throwable? = null) {
        try { Log.e(TAG, msg, e) } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "TaskExecutor"
        const val DEFAULT_TASK_TIMEOUT_MS: Long = 120_000L
        const val DEFAULT_THINK_TIMEOUT_MS: Long = 45_000L
        const val DEFAULT_PHASE_WARNING_THRESHOLD_SEC: Long = 20L
        const val MAX_SIGNATURE_NODES: Int = 200
        /** Turn F(a): how long an open_app step waits for the target package. */
        const val DEFAULT_OPEN_APP_POLL_TIMEOUT_MS: Long = 8_000L
        /** Turn F(a): how often the foreground package is re-checked while polling. */
        const val DEFAULT_OPEN_APP_POLL_INTERVAL_MS: Long = 500L
        /** Turn F(b): max AI turns spent fixing ONE failed recorded step. */
        const val AI_RESUME_MAX_ATTEMPTS: Int = 3
        /** Turn F-direct: swipe fraction for an "a bit" scroll (half the 0.30f fallback). */
        const val SCROLL_SMALL_FRACTION: Float = 0.15f
        /** Turn F-direct: how long a search/dial intent waits for the foreground package to change. */
        const val DIRECT_INTENT_POLL_TIMEOUT_MS: Long = 3_000L
        /** Turn F-direct: YouTube package, for search URLs. */
        const val YOUTUBE_PACKAGE: String = "com.google.android.youtube"

        /** Turn G: packages that count as "a browser opened" for a web search. */
        fun isBrowserPackage(pkg: String): Boolean {
            val lower = pkg.lowercase()
            return lower.contains("chrome") || lower.contains("browser") ||
                lower.contains("firefox") || lower.contains("brave") ||
                lower.contains("opera") || lower.contains("edge") ||
                lower.contains("samsung") && lower.contains("internet") ||
                lower == "com.sec.android.app.sbrowser" ||
                lower.contains("duckduckgo")
        }

        /** Turn G: packages that count as "the dialer opened" for a dial action. */
        fun isDialerPackage(pkg: String): Boolean {
            val lower = pkg.lowercase()
            return lower.contains("dialer") || lower.contains("phone") ||
                lower.contains("contacts")
        }

        fun isLauncherPackage(pkg: String): Boolean {
            val lower = pkg.lowercase()
            return lower.contains("launcher") || lower.contains("home") ||
                lower == "com.google.android.apps.nexuslauncher" ||
                lower == "com.android.launcher3" ||
                lower == "com.sec.android.app.launcher" ||
                lower == "com.mi.android.globallauncher"
        }

        fun matchesTarget(targetQuery: String, pkg: String): Boolean {
            if (targetQuery.isEmpty() || pkg.isEmpty()) return false
            if (pkg == "io.github.ryoaonetsuki.mira" && !targetQuery.contains("mira", ignoreCase = true)) {
                return false
            }
            val resolvedCandidates = ScreenAutomationService.resolveKnownAppPackages(targetQuery)
            return pkg.contains(targetQuery, ignoreCase = true) ||
                resolvedCandidates.any {
                    pkg.equals(it, ignoreCase = true) || pkg.contains(it, ignoreCase = true)
                }
        }

        fun isWebGoal(goal: String?): Boolean {
            if (goal.isNullOrBlank()) return false
            val lower = goal.lowercase()
            if (lower.contains("http://") || lower.contains("https://") || lower.contains("www.") || lower.contains(".com")) return true
            if (lower.contains("google") || lower.contains("search") || lower.contains("browse") || lower.contains("website") || lower.contains("web")) return true
            if (lower.contains("খুঁজো") || lower.contains("সার্চ") || lower.contains("ব্রাউজ")) return true
            val target = MiraAutomationBridge.extractTargetApp(goal)
            return target != null && isBrowserPackage(target)
        }

        fun isCriticalReplayStep(action: String): Boolean =
            action == TaskStep.ACTION_OPEN_APP || action == TaskStep.ACTION_TYPE_TEXT

        fun computeNodeSignature(nodes: List<ScreenNode>): Int =
            nodes.take(MAX_SIGNATURE_NODES).joinToString("|") {
                "${it.className}:${it.text}:${it.contentDescription}:${it.left},${it.top},${it.right},${it.bottom}:${it.isClickable}:${it.isEditable}"
            }.hashCode()

        /**
         * Turn F(a): does the current foreground package show the app an
         * open_app step asked for? "Already in target package" counts as
         * success — a cold start that is still launching is not a failure.
         * Turn G: ONLY the target app counts. The old
         * `postPackage != prePackage` fallback declared success whenever ANY
         * app opened — Chrome opening after a WhatsApp request was reported
         * as "Opened WhatsApp". A wrong package is a failure, never success.
         */
        fun openAppTargetReached(step: TaskStep, prePackage: String, postPackage: String): Boolean {
            val targetQuery = (step.stringParam("app") ?: step.stringParam("package") ?: "").trim()
            return matchesTarget(targetQuery, postPackage)
        }

        fun verifyEffect(
            step: TaskStep,
            prePackage: String,
            preSignature: Int,
            postPackage: String,
            postSignature: Int,
            goal: String? = null,
            targetApp: String? = null
        ): Boolean {
            val target = targetApp ?: goal?.let { MiraAutomationBridge.extractTargetApp(it) }
            val isWeb = isWebGoal(goal) || (target != null && isBrowserPackage(target))
            return when (step.action) {
                TaskStep.ACTION_OPEN_APP -> openAppTargetReached(step, prePackage, postPackage)
                TaskStep.ACTION_CLICK_TEXT,
                TaskStep.ACTION_CLICK_AT,
                TaskStep.ACTION_TYPE_TEXT,
                TaskStep.ACTION_SCROLL,
                TaskStep.ACTION_SWIPE,
                TaskStep.ACTION_LONG_PRESS,
                TaskStep.ACTION_PINCH,
                TaskStep.ACTION_PRESS_BACK,
                TaskStep.ACTION_PRESS_HOME,
                TaskStep.ACTION_PRESS_ENTER -> {
                    if (postPackage != prePackage && postPackage.isNotEmpty()) {
                        if (step.action == TaskStep.ACTION_PRESS_HOME && isLauncherPackage(postPackage)) {
                            true
                        } else if (target != null) {
                            if (matchesTarget(target, postPackage)) {
                                true
                            } else if (isWeb && isBrowserPackage(postPackage)) {
                                true
                            } else {
                                false // Turn G: A wrong-package change is a failure, never an effect!
                            }
                        } else if (isWeb) {
                            isBrowserPackage(postPackage)
                        } else {
                            true
                        }
                    } else {
                        postSignature != preSignature
                    }
                }
                else -> true
            }
        }

        /**
         * Turn G: when does a decision need the screenshot (pixels) as well
         * as the node-tree text? The agent must SEE the screen at decision
         * points — after landing in a new app, and whenever the node tree
         * says nothing at all (WebView / game / loading screen: pixels are
         * the only signal). The old rule waited for a failed step AND
         * insufficient text first, so the normal path decided blind.
         * Vision is NOT paid on every micro-step: the fast direct path never
         * calls it, and the AI loop pays it at most once per decision point.
         */
        fun shouldAttachScreenshot(
            nodes: List<ScreenNode>,
            lastStepHadNoEffect: Boolean,
            lastStepWasOpenApp: Boolean = false
        ): Boolean {
            // (1) Just opened an app — look at its home screen before deciding.
            if (lastStepWasOpenApp) return true
            // (2) No nodes at all — pixels are the only signal.
            if (nodes.isEmpty()) return true
            // (3) Previous step had NO effect AND the text is insufficient.
            if (!lastStepHadNoEffect) return false
            val clickableNodes = nodes.filter { it.isClickable }
            if (clickableNodes.isNotEmpty()) {
                val unlabeledCount = clickableNodes.count { it.text.isBlank() && it.contentDescription.isBlank() }
                if (unlabeledCount * 2 > clickableNodes.size) return true
            }
            return false
        }
    }
}

/**
 * Best-of-all (Tapilot): repeated-gesture / loop guard.
 *
 * Tapilot's adapter (tapilot-ai/tapilot, MIT © 2024-2026 Quanru,
 * packages/android-accessibility/src/device.ts `guardRepeatedGesture` +
 * screen-progress.ts fingerprint) stops an alternating swipe/drag loop and
 * any identical gesture repeated past a cap, because returning to the same
 * observed state is not progress. This is a DESIGN port to Kotlin, not a
 * copy: Tapilot compares screenshot pixels; Mira compares the node-tree
 * signature ([TaskExecutor.computeNodeSignature]) — the same "same screen"
 * test without a screenshot. Only no-effect steps feed the guard; any
 * verified effect resets it.
 */


// Ported from Jarvis (github.com/AbuZar-Ansarii/Jarvis), MIT Licence, Copyright (c) 2026 AbuZar Ansarii — Dart → Kotlin port for Mira.
// Post-action UI effect verification adapted from ClosePaw (github.com/imoonkey/closepaw, Apache-2.0) and MobileAgent (github.com/X-PLUG/MobileAgent, MIT).
package io.github.ryoaonetsuki.mira.automation.agent

import android.util.Log
import io.github.ryoaonetsuki.mira.automation.MiraAutomationBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
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

enum class ExecutorState {
    IDLE,
    RUNNING,
    PAUSED
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
    fun clickNumber(number: Int, nodes: List<ScreenNode>): Boolean {
        val node = UiNodeMatcher.tappableNumbered(nodes).firstOrNull { it.first == number }?.second ?: return false
        return clickAt(node.centerX.toFloat(), node.centerY.toFloat())
    }
    fun clickNumber(number: Int): Boolean = clickNumber(number, dumpScreen())
    fun showTapNumbers(): List<Pair<Int, ScreenNode>> = emptyList()
    fun showTapNumbersDetailed(): TapNumbersResult {
        val list = showTapNumbers()
        return if (list.isNotEmpty()) TapNumbersResult.Success(list) else TapNumbersResult.NoTappableElements
    }
    fun hideTapNumbers() {}
    fun clickAt(x: Float, y: Float): Boolean
    fun isInsideImeArea(x: Int, y: Int): Boolean = false
    fun getScreenDimensions(): Pair<Int, Int>? = null
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
    fun isInputFocused(): Boolean = false
    fun setTextOnFocused(text: String): Boolean = typeText(text)
    fun getFocusedText(): String? = null
}

sealed class TapNumbersResult {
    data class Success(val numbered: List<Pair<Int, ScreenNode>>) : TapNumbersResult()
    object NoTappableElements : TapNumbersResult()
    object PermissionMissing : TapNumbersResult()
    data class Error(val message: String) : TapNumbersResult()
}

data class TypeFlowResult(
    val success: Boolean,
    val message: String,
    val textTyped: String = ""
)

/**
 * Safety gate evaluated BEFORE any atomic action executes.
 */
/**
 * The heart of autonomous phone control: Observe -> Think -> Act loop ported from Jarvis.
 */
class TaskExecutor(
    private val automation: AutomationPort,
    private val skills: SkillMemoryService,
    private val stepDelayMs: Long = 600,
    var onProgress: ((step: Int, description: String) -> Unit)? = null,
    private val taskTimeoutMs: Long = DEFAULT_TASK_TIMEOUT_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val phaseWarningThresholdSec: Long = DEFAULT_PHASE_WARNING_THRESHOLD_SEC,
    // Turn F(a): cold app launch (YouTube commonly 2-5s) is polled instead of
    // a fixed settle + one retry.
    private val openAppPollTimeoutMs: Long = DEFAULT_OPEN_APP_POLL_TIMEOUT_MS,
    private val openAppPollIntervalMs: Long = DEFAULT_OPEN_APP_POLL_INTERVAL_MS,
    private val historyStore: TaskHistoryStore? = null,
    private val apiKeyProvider: (suspend () -> String)? = null,
    var visionLocator: (suspend (target: String, nodes: List<ScreenNode>) -> Pair<Int, Int>?)? = null,
    private val appKnowledge: io.github.ryoaonetsuki.mira.automation.AppKnowledge? = null
) {
    // Turn F(f): 700ms -> 350ms settle for non-open_app steps.
    private val settleDelayMs: Long = if (stepDelayMs == 0L) 0L else 350L
    // Tests pass stepDelayMs == 0, so polling is a single immediate check
    // there (fast); on the phone the full ~8s cold-start window applies.
    private val effectiveOpenAppPollTimeoutMs: Long =
        if (stepDelayMs == 0L) 0L else openAppPollTimeoutMs
    private val effectiveOpenAppPollIntervalMs: Long =
        if (stepDelayMs == 0L) 0L else openAppPollIntervalMs

    private val pauseLock = Any()

    @Volatile
    private var _isRunning: Boolean = false
    val isRunning: Boolean get() = _isRunning && !_isPaused

    @Volatile
    private var _isPaused: Boolean = false
    val isPaused: Boolean get() = _isPaused

    val state: ExecutorState
        get() = when {
            _isPaused -> ExecutorState.PAUSED
            _isRunning -> ExecutorState.RUNNING
            else -> ExecutorState.IDLE
        }

    val isBusy: Boolean get() = _isRunning || _isPaused

    @Volatile
    private var taskStartMs: Long = 0L

    @Volatile
    private var totalPausedMs: Long = 0L

    @Volatile
    private var pauseStartMs: Long = 0L

    private val _runStepDescriptions = mutableListOf<String>()

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

    fun pause() {
        synchronized(pauseLock) {
            if (!_isRunning || _isPaused) return
            _isPaused = true
            pauseStartMs = clockMs()
            onProgress?.invoke(_currentStep, "Paused")
        }
    }

    fun resume() {
        synchronized(pauseLock) {
            if (!_isPaused) return
            if (pauseStartMs > 0L) {
                totalPausedMs += (clockMs() - pauseStartMs).coerceAtLeast(0L)
                pauseStartMs = 0L
            }
            _isPaused = false
            onProgress?.invoke(_currentStep, "Resumed")
        }
    }

    fun cancel() {
        _cancelRequested = true
        synchronized(pauseLock) {
            if (_isPaused) {
                if (pauseStartMs > 0L) {
                    totalPausedMs += (clockMs() - pauseStartMs).coerceAtLeast(0L)
                    pauseStartMs = 0L
                }
                _isPaused = false
            }
        }
    }

    fun activeElapsedMs(): Long {
        val start = taskStartMs
        if (start <= 0L) return 0L
        val now = clockMs()
        val currentPauseSpan = synchronized(pauseLock) {
            if (_isPaused && pauseStartMs > 0L) {
                (now - pauseStartMs).coerceAtLeast(0L)
            } else {
                0L
            }
        }
        return ((now - start) - (totalPausedMs + currentPauseSpan)).coerceAtLeast(0L)
    }

    fun pausedDurationMs(): Long {
        val now = clockMs()
        return synchronized(pauseLock) {
            val currentPauseSpan = if (_isPaused && pauseStartMs > 0L) {
                (now - pauseStartMs).coerceAtLeast(0L)
            } else {
                0L
            }
            totalPausedMs + currentPauseSpan
        }
    }

    private suspend fun awaitIfPaused(): Boolean {
        while (_isPaused) {
            if (_cancelRequested) return false
            delay(200L)
        }
        return !_cancelRequested
    }

    private fun recordStepDescription(desc: String) {
        synchronized(_runStepDescriptions) {
            if (_runStepDescriptions.size < 50) {
                _runStepDescriptions.add(desc)
            }
        }
    }

    suspend fun replaySkillDirect(skill: Skill): TaskResult {
        if (_isRunning || _isPaused) {
            return TaskResult(TaskStatus.FAILED, "Another task is already running", 0)
        }
        _isRunning = true
        _isPaused = false
        _cancelRequested = false
        _lastDiagnosis = null
        _lastError = null
        lastActionError = null
        _currentStep = 0
        _currentPhase = "starting"
        _phaseDetail = skill.goal
        _phaseStartTimeMs = clockMs()
        taskStartMs = clockMs()
        totalPausedMs = 0L
        pauseStartMs = 0L
        recovery.reset()
        synchronized(_runStepDescriptions) {
            _runStepDescriptions.clear()
        }

        val startedAtWallMs = System.currentTimeMillis()
        var finalResult: TaskResult? = null

        try {
            var timedOut = false
            coroutineScope {
                val replayJob = async {
                    replaySkillDirectInternal(skill)
                }
                val timeoutWatcher = launch {
                    while (replayJob.isActive) {
                        delay(100L)
                        if (activeElapsedMs() >= taskTimeoutMs) {
                            timedOut = true
                            replayJob.cancel()
                            break
                        }
                    }
                }
                val phaseWatcher = launch {
                    while (replayJob.isActive) {
                        delay(1000L)
                        if (_isPaused) continue
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
                    finalResult = replayJob.await()
                } catch (e: CancellationException) {
                    if (timedOut) {
                        finalResult = null
                    } else {
                        throw e
                    }
                } finally {
                    timeoutWatcher.cancel()
                    phaseWatcher.cancel()
                }
            }

            if (finalResult == null) {
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
                val timeoutResult = TaskResult(
                    status = TaskStatus.FAILED,
                    message = timeoutMsg,
                    stepsUsed = step
                )
                finalResult = timeoutResult
                return timeoutResult
            }
            return finalResult!!
        } catch (e: CancellationException) {
            if (finalResult == null) {
                finalResult = TaskResult(
                    status = TaskStatus.CANCELLED,
                    message = "Task cancelled by user",
                    stepsUsed = _currentStep
                )
            }
            throw e
        } catch (e: Throwable) {
            if (finalResult == null) {
                finalResult = TaskResult(
                    status = TaskStatus.FAILED,
                    message = e.message ?: "Task failed",
                    stepsUsed = _currentStep
                )
            }
            throw e
        } finally {
            val resultToRecord = finalResult ?: TaskResult(
                status = if (_cancelRequested) TaskStatus.CANCELLED else TaskStatus.FAILED,
                message = _lastError ?: _lastDiagnosis ?: "Task finished",
                stepsUsed = _currentStep
            )
            val finishedAtWallMs = System.currentTimeMillis()
            val descriptions = synchronized(_runStepDescriptions) { _runStepDescriptions.toList() }
            try {
                historyStore?.record(
                    TaskHistoryEntry(
                        goal = skill.goal,
                        status = resultToRecord.status,
                        stepsUsed = resultToRecord.stepsUsed,
                        startedAtMs = startedAtWallMs,
                        finishedAtMs = finishedAtWallMs,
                        stepDescriptions = descriptions
                    )
                )
            } catch (e: Throwable) {
                logWarn("Failed to record task history: ${e.message}")
            }

            _isRunning = false
            _isPaused = false
            _cancelRequested = false
            _currentPhase = "idle"
            _phaseStartTimeMs = 0L
            taskStartMs = 0L
            totalPausedMs = 0L
            pauseStartMs = 0L
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
        TaskStep.ACTION_CLICK_NUMBER -> "tap #${step.intParam("number") ?: ""}"
        TaskStep.ACTION_SHOW_NUMBERS -> "show tap numbers on screen"
        TaskStep.ACTION_HIDE_NUMBERS -> "hide tap numbers"
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
        TaskStep.ACTION_DIAL -> "dial ${step.stringParam("number") ?: ""}"
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
        var remainingMs = effectiveOpenAppPollTimeoutMs
        var current = automation.getCurrentPackage().orEmpty()
        while (true) {
            if (openAppTargetReached(step, prePackage, current)) return current
            if (_cancelRequested) return current
            if (!awaitIfPaused()) return current
            val pollStart = clockMs()
            delay(effectiveOpenAppPollIntervalMs)
            remainingMs -= (clockMs() - pollStart)
            if (remainingMs <= 0L) return current
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
            if (!awaitIfPaused() || _cancelRequested) {
                return ReplayOutcome(success = false, failedStepIndex = stepIndex, stepsCompleted = stepCount)
            }
            recordStepDescription(describeStep(step))
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
                if (!awaitIfPaused() || _cancelRequested) {
                    return ReplayOutcome(success = false, failedStepIndex = stepIndex, stepsCompleted = stepCount)
                }
                _performAction(step, preNodes)
                delay(stepDelayMs)
                carriedNodes = null
                carriedPackage = null
                carriedSignature = null
                continue
            }

            // (i) Dispatch with one retry after re-dumping the screen
            enterPhase("acting", stepNumber, "executing ${step.action} (step $stepNumber)")
            if (!awaitIfPaused() || _cancelRequested) {
                return ReplayOutcome(success = false, failedStepIndex = stepIndex, stepsCompleted = stepCount)
            }
            var ok = _performAction(step, preNodes)
            if (!ok) {
                val reason = lastActionError ?: "${step.action} dispatch failed"
                logWarn("Replayed step $stepNumber/$totalSteps (${step.action}) dispatch failed ($reason) on attempt 1 — re-dumping screen and retrying once")
                delay(stepDelayMs)
                if (!awaitIfPaused() || _cancelRequested) {
                    return ReplayOutcome(success = false, failedStepIndex = stepIndex, stepsCompleted = stepCount)
                }
                enterPhase("observing", stepNumber, "re-reading screen for retry (step $stepNumber)")
                preNodes = dumpScreenSafe()
                prePackage = automation.getCurrentPackage().orEmpty()
                preSignature = computeNodeSignature(preNodes)
                enterPhase("acting", stepNumber, "retrying ${step.action} (step $stepNumber)")
                if (!awaitIfPaused() || _cancelRequested) {
                    return ReplayOutcome(success = false, failedStepIndex = stepIndex, stepsCompleted = stepCount)
                }
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
                if (step.action == TaskStep.ACTION_SCROLL || step.action == TaskStep.ACTION_SWIPE) {
                    effectVerified = true
                } else {
                    logWarn("Replayed step $stepNumber/$totalSteps (${step.action}) had no verified effect — re-dumping screen and retrying once")
                    if (!awaitIfPaused() || _cancelRequested) {
                        return ReplayOutcome(success = false, failedStepIndex = stepIndex, stepsCompleted = stepCount)
                    }
                    preNodes = postNodes
                    prePackage = postPackage
                    preSignature = postSignature
                    enterPhase("acting", stepNumber, "retrying ${step.action} for effect verification (step $stepNumber)")
                    if (!awaitIfPaused() || _cancelRequested) {
                        return ReplayOutcome(success = false, failedStepIndex = stepIndex, stepsCompleted = stepCount)
                    }
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
                if (step.action != TaskStep.ACTION_SCROLL && step.action != TaskStep.ACTION_SWIPE) {
                    skippedCount++
                    lastSkippedIndex = stepIndex
                    logWarn("Replayed non-critical step $stepNumber/$totalSteps (${step.action}) had no verified effect after retry — skipping ($skippedCount/$totalSteps skipped)")
                }
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

    internal suspend fun resolveVisionCoordinates(target: String, nodes: List<ScreenNode>): Pair<Int, Int>? {
        visionLocator?.let { return it(target, nodes) }
        val jpeg = automation.takeScreenshotJpeg(720) ?: run {
            logWarn("TaskExecutor vision locator failed: screenshot capture returned null")
            return null
        }
        val key = apiKeyProvider?.invoke()?.takeIf { it.isNotBlank() } ?: run {
            logWarn("TaskExecutor vision locator failed: apiKeyProvider returned null or blank")
            return null
        }
        val dimensions = automation.getScreenDimensions() ?: Pair(1080, 2400)
        val nodeText = automation.formatNodes(nodes)
        return try {
            val rest = io.github.ryoaonetsuki.mira.network.GeminiRestClient()
            val res = rest.locateElementWithVision(
                apiKey = key,
                imageBytes = jpeg,
                targetDescription = target,
                nodeDump = nodeText,
                screenWidth = dimensions.first,
                screenHeight = dimensions.second
            )
            res.getOrNull()
        } catch (e: Exception) {
            logWarn("TaskExecutor vision locator failed for '$target': ${e.message}")
            null
        }
    }

    private suspend fun _performAction(step: TaskStep, nodes: List<ScreenNode>): Boolean {
        if (!awaitIfPaused() || _cancelRequested) return false
        lastActionError = null
        return try {
            when (step.action) {
                TaskStep.ACTION_CLICK_TEXT -> {
                    val text = step.stringParam("text") ?: ""
                    var fallbackX = step.intParam("fallbackX") ?: step.intParam("x")
                    var fallbackY = step.intParam("fallbackY") ?: step.intParam("y")
                    var success = automation.clickText(text, nodes, fallbackX, fallbackY)
                    if (!success) {
                        val visionCoords = resolveVisionCoordinates(text, nodes)
                        if (visionCoords != null) {
                            fallbackX = visionCoords.first
                            fallbackY = visionCoords.second
                            success = automation.clickAt(fallbackX.toFloat(), fallbackY.toFloat())
                        }
                    }
                    if (!success) {
                        lastActionError = if (fallbackX != null && fallbackY != null) {
                            "clickText '$text' not found and fallback coordinate tap ($fallbackX, $fallbackY) rejected/timed out"
                        } else {
                            "clickText '$text' not found on screen and no fallback coordinates"
                        }
                    }
                    success
                }
                TaskStep.ACTION_CLICK_NUMBER -> {
                    val number = step.intParam("number") ?: run {
                        lastActionError = "click_number missing number param"
                        return false
                    }
                    val success = automation.clickNumber(number)
                    if (!success) {
                        lastActionError = "click_number $number failed (no tappable element with that number)"
                    }
                    success
                }
                TaskStep.ACTION_SHOW_NUMBERS -> {
                    val detailed = automation.showTapNumbersDetailed()
                    when (detailed) {
                        is TapNumbersResult.Success -> true
                        is TapNumbersResult.PermissionMissing -> {
                            lastActionError = "show_numbers failed: 'Display over other apps' permission is not granted (enable it in setup)"
                            false
                        }
                        is TapNumbersResult.NoTappableElements -> {
                            lastActionError = "show_numbers failed: no tappable elements found on screen"
                            false
                        }
                        is TapNumbersResult.Error -> {
                            lastActionError = "show_numbers failed: ${detailed.message}"
                            false
                        }
                    }
                }
                TaskStep.ACTION_HIDE_NUMBERS -> {
                    automation.hideTapNumbers()
                    true
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

                    // Symptom 3 fix: re-validate recorded tap coordinate against fresh screen state
                    // (1) Check if coordinates fall inside the keyboard / IME area
                    if (automation.isInsideImeArea(x, y)) {
                        logWarn("Dropping coordinate tap ($x, $y) inside keyboard/IME area to prevent tapping keyboard settings")
                        if (automation.isInputFocused()) {
                            logInfo("Replacing coordinate tap ($x, $y) inside keyboard area with pressEnter")
                            return automation.pressEnter()
                        }
                        return true
                    }

                    // (2) Check if layout shifted or target node moved
                    val targetNode = nodes.firstOrNull { it.isClickable && x in it.left..it.right && y in it.top..it.bottom }
                    val targetText = step.stringParam("text") ?: step.stringParam("target")
                    val viewId = step.stringParam("viewId")

                    val actualX: Float
                    val actualY: Float
                    if (targetNode != null) {
                        actualX = x.toFloat()
                        actualY = y.toFloat()
                    } else if (!targetText.isNullOrBlank() || !viewId.isNullOrBlank()) {
                        val reResolved = nodes.firstOrNull { node ->
                            node.isClickable && (
                                (!targetText.isNullOrBlank() && (node.text.contains(targetText, ignoreCase = true) || node.contentDescription.contains(targetText, ignoreCase = true))) ||
                                (!viewId.isNullOrBlank() && node.viewId.equals(viewId, ignoreCase = true))
                            )
                        }
                        if (reResolved != null && reResolved.centerX > 0 && reResolved.centerY > 0) {
                            logInfo("Re-resolved moved node '$targetText' from ($x, $y) to (${reResolved.centerX}, ${reResolved.centerY})")
                            actualX = reResolved.centerX.toFloat()
                            actualY = reResolved.centerY.toFloat()
                        } else {
                            logWarn("Stale recorded coordinate ($x, $y): target '$targetText' not found at coordinates in fresh node dump — skipping blind tap")
                            return true
                        }
                    } else if (nodes.isNotEmpty() && !nodes.any { x in it.left..it.right && y in it.top..it.bottom }) {
                        logWarn("Stale recorded coordinate ($x, $y): no clickable element found at coordinates in fresh node dump — skipping blind tap")
                        return true
                    } else {
                        actualX = x.toFloat()
                        actualY = y.toFloat()
                    }

                    val success = automation.clickAt(actualX, actualY)
                    if (!success) {
                        lastActionError = "click_at ($actualX, $actualY) failed (gesture rejected or callback timed out)"
                    }
                    success
                }
                TaskStep.ACTION_TYPE_TEXT -> {
                    val text = step.stringParam("text") ?: run {
                        lastActionError = "type_text missing text param"
                        return false
                    }
                    val fallbackX = step.intParam("fallbackX") ?: step.intParam("x")
                    val fallbackY = step.intParam("fallbackY") ?: step.intParam("y")
                    val pressEnter = step.booleanParam("pressEnter") ?: true
                    val result = executeTypeFlow(
                        automation = automation,
                        text = text,
                        targetQuery = step.stringParam("target"),
                        fallbackX = fallbackX,
                        fallbackY = fallbackY,
                        pressEnter = pressEnter,
                        visionLocator = { target, currentNodes ->
                            resolveVisionCoordinates(target, currentNodes)
                        },
                        pollTimeoutMs = if (stepDelayMs == 0L) 0L else 2000L,
                        pollIntervalMs = if (stepDelayMs == 0L) 0L else 100L
                    )
                    if (!result.success) {
                        lastActionError = result.message
                    }
                    result.success
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
                    if (isLauncherPackage(query)) {
                        try {
                            automation.pressHome()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            logWarn("pressHome threw for launcher package '$query': ${e.message}")
                        }
                        logWarn("Target query '$query' is a launcher package — going home instead of launching app")
                        return true
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
                TaskStep.ACTION_DIAL -> {
                    val number = (step.stringParam("number") ?: step.stringParam("phone") ?: "").trim()
                    if (number.isEmpty()) {
                        lastActionError = "dial missing number param"
                        return false
                    }
                    val success = try {
                        automation.dialNumber(number)
                    } catch (e: CancellationException) {
                        throw e
                    }
                    if (!success) {
                        lastActionError = "dial '$number' failed"
                    }
                    success
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
        const val DEFAULT_PHASE_WARNING_THRESHOLD_SEC: Long = 20L
        const val MAX_SIGNATURE_NODES: Int = 200
        /** Turn F(a): how long an open_app step waits for the target package. */
        const val DEFAULT_OPEN_APP_POLL_TIMEOUT_MS: Long = 8_000L
        /** Turn F(a): how often the foreground package is re-checked while polling. */
        const val DEFAULT_OPEN_APP_POLL_INTERVAL_MS: Long = 500L
        /** Turn F(b): max AI turns spent fixing ONE failed recorded step. */
        const val AI_RESUME_MAX_ATTEMPTS: Int = 3

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
            if (isLauncherPackage(targetQuery)) {
                return isLauncherPackage(postPackage)
            }
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
                TaskStep.ACTION_DIAL -> isDialerPackage(postPackage)
                TaskStep.ACTION_SCROLL,
                TaskStep.ACTION_SWIPE -> {
                    if (postPackage != prePackage && postPackage.isNotEmpty()) {
                        if (target != null) {
                            matchesTarget(target, postPackage) || (isWeb && isBrowserPackage(postPackage))
                        } else if (isWeb) {
                            isBrowserPackage(postPackage)
                        } else {
                            true
                        }
                    } else {
                        // Scrolling within the same package is valid progress!
                        // In feeds (Facebook, YouTube), headers keep preSignature == postSignature,
                        // which must not fail or be treated as no-effect.
                        true
                    }
                }
                TaskStep.ACTION_CLICK_TEXT,
                TaskStep.ACTION_CLICK_NUMBER,
                TaskStep.ACTION_CLICK_AT,
                TaskStep.ACTION_TYPE_TEXT,
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
         * Complete keyboard/type flow helper (Task 2):
         * (a) tap the text field (by vision coordinates OR node match on editable/focusable nodes, class EditText or isEditable);
         * (b) WAIT for focus and soft keyboard to appear (poll for a focused editable node / input method visible, with bounded timeout);
         * (c) set text via ACTION_SET_TEXT on focused node (fall back to typeText if unavailable);
         * (d) READ BACK field text and verify it equals intended text (retry set once if not matched);
         * (e) then press search/enter (if pressEnter is true).
         * If the field cannot be found after a tap attempt, RE-SCAN screen (fresh node dump + vision screenshot) before failing.
         */
        suspend fun executeTypeFlow(
            automation: AutomationPort,
            text: String,
            targetQuery: String? = null,
            fallbackX: Int? = null,
            fallbackY: Int? = null,
            pressEnter: Boolean = true,
            visionLocator: (suspend (target: String, nodes: List<ScreenNode>) -> Pair<Int, Int>?)? = null,
            pollTimeoutMs: Long = 2000L,
            pollIntervalMs: Long = 100L
        ): TypeFlowResult {
            var currentNodes = automation.dumpScreen()

            fun findEditableCandidate(nodes: List<ScreenNode>): ScreenNode? {
                if (targetQuery != null) {
                    val match = nodes.firstOrNull {
                        (it.isEditable || it.isClickable) &&
                            (it.text.contains(targetQuery, ignoreCase = true) || it.contentDescription.contains(targetQuery, ignoreCase = true))
                    }
                    if (match != null) return match
                }
                val editable = nodes.firstOrNull { it.isEditable || it.className.contains("EditText", ignoreCase = true) }
                if (editable != null) return editable

                return nodes.firstOrNull {
                    it.isClickable && (
                        it.text.contains("Search", ignoreCase = true) ||
                            it.contentDescription.contains("Search", ignoreCase = true) ||
                            it.viewId.contains("search", ignoreCase = true)
                    )
                }
            }

            fun readCurrentText(): String {
                return automation.getFocusedText()
                    ?: automation.dumpScreen().firstOrNull { it.isEditable || it.className.contains("EditText", ignoreCase = true) }?.text
                    ?: ""
            }

            // (a) tap the text field if not already focused
            if (!automation.isInputFocused()) {
                var candidate = findEditableCandidate(currentNodes)
                var tapped = false

                if (candidate != null && candidate.centerX > 0 && candidate.centerY > 0) {
                    tapped = automation.clickAt(candidate.centerX.toFloat(), candidate.centerY.toFloat())
                } else if (fallbackX != null && fallbackY != null && fallbackX > 0 && fallbackY > 0) {
                    tapped = automation.clickAt(fallbackX.toFloat(), fallbackY.toFloat())
                } else if (visionLocator != null) {
                    val coords = visionLocator(targetQuery ?: "search text field", currentNodes)
                    if (coords != null) {
                        tapped = automation.clickAt(coords.first.toFloat(), coords.second.toFloat())
                    }
                }

                // If field not found or tap did not focus, RE-SCAN screen (fresh node dump + vision)
                if (!tapped) {
                    currentNodes = automation.dumpScreen()
                    candidate = findEditableCandidate(currentNodes)
                    if (candidate != null && candidate.centerX > 0 && candidate.centerY > 0) {
                        tapped = automation.clickAt(candidate.centerX.toFloat(), candidate.centerY.toFloat())
                    } else if (visionLocator != null) {
                        val coords = visionLocator(targetQuery ?: "search text field", currentNodes)
                        if (coords != null) {
                            tapped = automation.clickAt(coords.first.toFloat(), coords.second.toFloat())
                        }
                    }
                }

                if (!tapped && !automation.isInputFocused() && !currentNodes.any { it.isEditable }) {
                    return TypeFlowResult(false, "Could not find or tap editable text field on screen")
                }
            }

            // (b) WAIT for focus and soft keyboard to appear (poll with bounded timeout)
            if (pollTimeoutMs > 0L) {
                val deadline = System.currentTimeMillis() + pollTimeoutMs
                var isFocused = automation.isInputFocused()
                while (!isFocused && System.currentTimeMillis() < deadline) {
                    delay(pollIntervalMs)
                    isFocused = automation.isInputFocused() || automation.dumpScreen().any { it.isEditable }
                }
            }

            // (c) set the text via ACTION_SET_TEXT on focused node (fall back to typeText)
            var setOk = automation.setTextOnFocused(text)
            if (!setOk) {
                setOk = automation.typeText(text)
            }

            // (d) READ BACK field text and verify it equals intended text (retry set once if not matched)
            var readBack = readCurrentText()
            if (readBack != text) {
                // Retry set once when readback mismatches
                setOk = automation.setTextOnFocused(text)
                if (!setOk) {
                    setOk = automation.typeText(text)
                }
                readBack = readCurrentText()
            }

            // (e) then press search/enter
            if (pressEnter) {
                automation.pressEnter()
            }

            val success = setOk || readBack == text
            return if (success) {
                TypeFlowResult(true, "Successfully typed '$text'", textTyped = readBack.ifEmpty { text })
            } else {
                TypeFlowResult(false, "typeText failed to set text '$text' (read back: '$readBack')")
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


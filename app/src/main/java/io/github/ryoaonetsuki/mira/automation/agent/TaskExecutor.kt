// Ported from Jarvis (github.com/AbuZar-Ansarii/Jarvis), MIT Licence, Copyright (c) 2026 AbuZar Ansarii — Dart → Kotlin port for Mira.
// Post-action UI effect verification adapted from ClosePaw (github.com/imoonkey/closepaw, Apache-2.0) and MobileAgent (github.com/X-PLUG/MobileAgent, MIT).
package io.github.ryoaonetsuki.mira.automation.agent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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
    fun typeText(text: String): Boolean
    fun pressEnter(): Boolean
    fun scroll(direction: String): Boolean
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean
    fun pressBack(): Boolean
    fun pressHome(): Boolean
    fun openApp(query: String): String?
    fun getCurrentPackage(): String? = null
    suspend fun takeScreenshotJpeg(maxWidthPx: Int = 720): ByteArray? = null
}

/**
 * Safety gate evaluated BEFORE any atomic action executes.
 */
object AutomationSafety {
    private val BANKING_PACKAGES = setOf(
        "com.google.android.apps.walletnfcrel",
        "com.bkash",
        "com.nagad",
        "com.rocket"
    )

    private val BANKING_PACKAGE_KEYWORDS = listOf(
        "bank",
        "wallet",
        "bkash",
        "nagad",
        "rocket",
        "payment"
    )

    private val BLOCKED_WORD_PATTERNS = listOf(
        Regex("""\bpay\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpayment\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpurchase\b""", RegexOption.IGNORE_CASE),
        Regex("""\bbuy\s+now\b""", RegexOption.IGNORE_CASE),
        Regex("""\bcheckout\b""", RegexOption.IGNORE_CASE),
        Regex("""\bconfirm\s+payment\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdelete\b""", RegexOption.IGNORE_CASE),
        Regex("""\bremove\b""", RegexOption.IGNORE_CASE),
        Regex("""\bremove\s+account\b""", RegexOption.IGNORE_CASE),
        Regex("""\bconfirm\s+delete\b""", RegexOption.IGNORE_CASE)
    )

    private val BLOCKED_BANGLA_WORDS = listOf(
        "পেমেন্ট",
        "কিনুন",
        "মুছুন",
        "ডিলিট"
    )

    private val BANKING_ALLOWED_ACTIONS = setOf(
        TaskStep.ACTION_PRESS_BACK,
        TaskStep.ACTION_PRESS_HOME,
        TaskStep.ACTION_WAIT,
        TaskStep.ACTION_DONE
    )

    fun isBlocked(step: TaskStep, currentPackage: String?): String? {
        // (b) Current foreground package is banking/finance
        if (currentPackage != null) {
            val pkgLower = currentPackage.lowercase()
            val isBankingApp = BANKING_PACKAGES.contains(pkgLower) ||
                BANKING_PACKAGE_KEYWORDS.any { pkgLower.contains(it) }

            if (isBankingApp && !BANKING_ALLOWED_ACTIONS.contains(step.action)) {
                return "Restricted action '${step.action}' inside banking/finance app: $currentPackage"
            }
        }

        // (c) Any click_text whose target text contains "confirm" + ("pay" | "purchase" | "delete")
        if (step.action == TaskStep.ACTION_CLICK_TEXT) {
            val targetText = (step.stringParam("text") ?: "").lowercase()
            if (targetText.contains("confirm") &&
                (targetText.contains("pay") || targetText.contains("purchase") || targetText.contains("delete"))
            ) {
                return "Confirmation of sensitive operation: '$targetText'"
            }
        }

        // (a) Any step whose target text/params match sensitive payment/delete keywords
        for ((_, value) in step.params) {
            if (value == null) continue
            val text = value.toString()
            if (containsBlockedWording(text)) {
                return "Contains sensitive keyword in parameter: '$text'"
            }
        }

        return null
    }

    private fun containsBlockedWording(text: String): Boolean {
        for (pattern in BLOCKED_WORD_PATTERNS) {
            if (pattern.containsMatchIn(text)) return true
        }
        for (bangla in BLOCKED_BANGLA_WORDS) {
            if (text.contains(bangla)) return true
        }
        return false
    }
}

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
                    "thinking" -> "Timed out while waiting for the AI reply (step $step, think call exceeded ${thinkTimeoutMs / 1000}s)"
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

        // 2. Skill-memory fast path (Jarvis task_executor.dart:308-333 behaviour)
        val matchedSkill = skills.findMatch(goal)
        if (matchedSkill != null) {
            onProgress?.invoke(0, "Found memorized skill (${matchedSkill.steps.size} steps). Replaying…")
            // Turn F(b): a failed replay is RESUMED with AI for the failed
            // step only — it no longer restarts the whole goal via runAiLoop.
            return executeSkillWithResume(matchedSkill)
        }

        // 3. AI loop
        return runAiLoop(goal)
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
                    // Turn F: the replay path can now spend time in "thinking"
                    // (AI resume). Name it, like executeGoal already does —
                    // the generic else-branch hid it behind "during thinking".
                    phase == "thinking" -> "Timed out while waiting for the AI reply (replay step $step, think call exceeded ${thinkTimeoutMs / 1000}s)"
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
        // Turn F(b): a failed replay is RESUMED with AI for the failed step
        // only — the whole goal is never restarted from scratch.
        return executeSkillWithResume(skill)
    }

    /**
     * Turn F(b): direct replay first; on failure, AI fixes ONLY the failed
     * recorded step (bounded turns, text-only think), then replay RESUMES
     * with the remaining recorded steps. The macro is never discarded and
     * re-done from scratch via runAiLoop.
     */
    private suspend fun executeSkillWithResume(skill: Skill): TaskResult {
        // Stale/empty macro (e.g. a legacy recording with 0 steps): there is
        // nothing to resume — fall through to the AI loop, as before.
        if (skill.steps.isEmpty()) {
            logWarn("Cannot replay skill '${skill.goal}': no steps recorded — falling back to AI loop")
            return runAiLoop(skill.goal)
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
        if (think == null && thinkWithVision == null) {
            skills.markReplayOutcome(skill.id, false)
            val diag = _lastDiagnosis ?: "Skill replay failed during execution. Please re-record the macro."
            return TaskResult(
                status = TaskStatus.FAILED,
                message = diag,
                stepsUsed = _currentStep
            )
        }

        onProgress?.invoke(0, "Saved skill failed at step $failedStepNumber — AI fixing that step, then resuming macro…")
        logInfo("Saved skill failed at step $failedStepNumber — resuming with AI for that step only")
        val resume = resumeSkillWithAi(skill, failedStepIndex)
        if (!resume.success) {
            skills.markReplayOutcome(skill.id, false)
            val replayDiag = _lastDiagnosis?.let { " ($it)" }.orEmpty()
            return TaskResult(
                status = TaskStatus.FAILED,
                message = "Skill replay failed at step $failedStepNumber$replayDiag and AI fallback could not complete that step: ${resume.message}",
                stepsUsed = _currentStep
            )
        }

        // Resume replay with the remaining recorded steps.
        val rest = if (failedStepIndex + 1 < skill.steps.size) {
            replaySkillFrom(skill, failedStepIndex + 1)
        } else {
            ReplayOutcome(success = true, stepsCompleted = 0)
        }
        if (_cancelRequested) {
            return TaskResult(TaskStatus.CANCELLED, "Task cancelled by user", skill.steps.size)
        }
        if (rest.success) {
            // Learn: replace the failed recorded step with the AI's working
            // step so the next replay of this macro is fully direct.
            val updatedSteps = skill.steps.toMutableList()
            resume.aiStep?.let { updatedSteps[failedStepIndex] = it }
            skills.saveOrUpdate(skill.goal, updatedSteps)
            skills.markReplayOutcome(skill.id, true)
            return TaskResult(
                status = TaskStatus.SUCCESS,
                message = "Skill replay failed at step $failedStepNumber; AI completed that step and replay resumed — completed via recorded macro + AI fallback.",
                stepsUsed = skill.steps.size
            )
        }
        skills.markReplayOutcome(skill.id, false)
        val restDiag = _lastDiagnosis?.let { " ($it)" }.orEmpty()
        return TaskResult(
            status = TaskStatus.FAILED,
            message = "AI fixed step $failedStepNumber but replay of the remaining steps failed$restDiag",
            stepsUsed = _currentStep
        )
    }

    private data class AiResumeResult(
        val success: Boolean,
        val aiStep: TaskStep?,
        val message: String
    )

    /**
     * Turn F(b): uses the AI for ONE failed recorded step only — at most
     * [AI_RESUME_MAX_ATTEMPTS] think turns, each under the think deadline.
     * Think is TEXT-ONLY here: recorded steps already carry coordinates and
     * labels, so no screenshot tax. Returns the AI step that fixed it, for
     * learning back into the macro.
     */
    private suspend fun resumeSkillWithAi(skill: Skill, failedStepIndex: Int): AiResumeResult {
        val failedStep = skill.steps[failedStepIndex]
        val stepNumber = failedStepIndex + 1
        val history = mutableListOf<Pair<String, String>>()
        val goalText = "Complete this one recorded step from the macro '${skill.goal}' " +
            "(step $stepNumber of ${skill.steps.size}): ${describeStep(failedStep)}. " +
            "Reply with ONLY one JSON action object for that step. " +
            "If the step is already done on screen, reply done."
        var lastReason = "no AI attempt made"
        var attempts = 0
        while (attempts < AI_RESUME_MAX_ATTEMPTS) {
            if (_cancelRequested) return AiResumeResult(false, null, "cancelled by user")
            attempts++
            _currentStep = stepNumber
            enterPhase("thinking", stepNumber, "AI fixing recorded step $stepNumber (attempt $attempts/$AI_RESUME_MAX_ATTEMPTS)")
            val nodes = dumpScreenSafe()
            val description = if (nodes.isEmpty()) {
                "Screen appears empty (use open_app to launch the target app)"
            } else {
                automation.getCompressedScreenDescription(nodes, goalText)
            }
            val raw: String
            try {
                raw = withTimeout(thinkTimeoutMs) {
                    val fn = think
                    if (fn != null) {
                        fn(goalText, description, history)
                    } else {
                        // No text think available: allow the vision route to
                        // attach a screenshot only when the text is
                        // insufficient (see shouldAttachScreenshot).
                        _decideNextStep(goalText, description, nodes, history, lastStepHadNoEffect = true)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                val msg = "Timed out while waiting for the AI reply (step $stepNumber, think call exceeded ${thinkTimeoutMs / 1000}s)"
                recordDiagnosis(msg)
                logWarn(msg)
                return AiResumeResult(false, null, msg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = "AI request failed: ${e.message}"
                recordDiagnosis(msg)
                logError("AI resume think failed: ${e.message}", e)
                return AiResumeResult(false, null, msg)
            }

            val decision = TaskStep.tryParse(raw)
            if (decision == null) {
                lastReason = "AI replied non-JSON"
                logWarn("AI resume step $stepNumber: could not parse AI output as action JSON (attempt $attempts): $raw")
                pushTurn(
                    history,
                    raw,
                    "Your last reply was not valid JSON. Reply with ONLY the single JSON action object: {\"action\": \"<action>\", \"params\": {...}, \"reasoning\": \"<explanation>\", \"is_complete\": false}"
                )
                continue
            }

            val blockedReason = AutomationSafety.isBlocked(decision, automation.getCurrentPackage())
            if (blockedReason != null) {
                val msg = "Blocked for safety: $blockedReason"
                recordDiagnosis(msg)
                logWarn(msg)
                return AiResumeResult(false, null, msg)
            }

            val prePackage = automation.getCurrentPackage().orEmpty()
            val preSignature = computeNodeSignature(nodes)

            if (decision.action == TaskStep.ACTION_DONE) {
                // AI says the step is already done — verify the recorded
                // step's effect on the current screen before believing it.
                val postNodes = dumpScreenSafe()
                val postPackage = automation.getCurrentPackage().orEmpty()
                if (verifyEffect(failedStep, prePackage, preSignature, postPackage, computeNodeSignature(postNodes))) {
                    return AiResumeResult(true, failedStep, "AI confirmed step $stepNumber is already complete")
                }
                lastReason = "AI said done but the recorded step's effect is not visible"
                pushTurn(
                    history,
                    raw,
                    "ACTION RESULT: [NO_EFFECT] The recorded step is not complete yet. Take one concrete action to complete it."
                )
                continue
            }

            enterPhase("acting", stepNumber, decision.reasoning.ifEmpty { decision.action })
            val dispatched = _performAction(decision, nodes)
            if (!dispatched) {
                lastReason = lastActionError ?: "${decision.action} dispatch failed"
                pushTurn(
                    history,
                    raw,
                    "ACTION RESULT: [NO_EFFECT] $lastReason. Pick a different approach."
                )
                continue
            }
            if (decision.action == TaskStep.ACTION_WAIT) {
                return AiResumeResult(true, decision, "AI waited for step $stepNumber")
            }

            enterPhase(
                "observing",
                stepNumber,
                if (decision.action == TaskStep.ACTION_OPEN_APP) "waiting for app to open (step $stepNumber)"
                else "verifying effect of ${decision.action} (step $stepNumber)"
            )
            val (postNodes, postPackage, postSignature) = settleAndDump(decision, prePackage)
            if (verifyEffect(decision, prePackage, preSignature, postPackage, postSignature)) {
                return AiResumeResult(true, decision, "AI completed step $stepNumber")
            }
            lastReason = "${decision.action} was dispatched but the screen did not change"
            pushTurn(
                history,
                raw,
                "ACTION RESULT: [NO_EFFECT] ${decision.action} was dispatched but the screen did not change. Pick a different approach."
            )
        }
        val msg = "AI could not complete recorded step $stepNumber (${failedStep.action}) after $AI_RESUME_MAX_ATTEMPTS attempts: $lastReason"
        recordDiagnosis(msg)
        logWarn(msg)
        return AiResumeResult(false, null, msg)
    }

    private fun describeStep(step: TaskStep): String = when (step.action) {
        TaskStep.ACTION_OPEN_APP -> "open the app '${step.stringParam("app") ?: step.stringParam("package") ?: ""}'"
        TaskStep.ACTION_CLICK_TEXT -> "tap '${step.stringParam("text") ?: ""}' on screen"
        TaskStep.ACTION_CLICK_AT -> "tap at (${step.intParam("x")}, ${step.intParam("y")})"
        TaskStep.ACTION_TYPE_TEXT -> "type '${step.stringParam("text") ?: ""}'"
        TaskStep.ACTION_PRESS_ENTER -> "press Enter"
        TaskStep.ACTION_SCROLL -> "scroll ${step.stringParam("direction") ?: "down"}"
        TaskStep.ACTION_SWIPE -> "swipe on screen"
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

            val blocked = AutomationSafety.isBlocked(step, prePackage.ifEmpty { null })
            if (blocked != null) {
                recordDiagnosis("Replayed step blocked for safety: $blocked")
                logWarn("Replayed step blocked for safety: $blocked")
                return ReplayOutcome(success = false, failedStepIndex = stepIndex, stepsCompleted = stepCount)
            }

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
                val retryBlocked = AutomationSafety.isBlocked(step, prePackage.ifEmpty { null })
                if (retryBlocked != null) {
                    recordDiagnosis("Replayed step blocked for safety on retry: $retryBlocked")
                    logWarn("Replayed step blocked for safety on retry: $retryBlocked")
                    return ReplayOutcome(success = false, failedStepIndex = stepIndex, stepsCompleted = stepCount)
                }
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
            var effectVerified = verifyEffect(step, prePackage, preSignature, postPackage, postSignature)

            if (!effectVerified) {
                logWarn("Replayed step $stepNumber/$totalSteps (${step.action}) had no verified effect — re-dumping screen and retrying once")
                if (_cancelRequested) {
                    return ReplayOutcome(success = false, failedStepIndex = stepIndex, stepsCompleted = stepCount)
                }
                preNodes = postNodes
                prePackage = postPackage
                preSignature = postSignature
                val retryBlocked = AutomationSafety.isBlocked(step, prePackage.ifEmpty { null })
                if (retryBlocked != null) {
                    recordDiagnosis("Replayed step blocked for safety on effect retry: $retryBlocked")
                    logWarn("Replayed step blocked for safety on effect retry: $retryBlocked")
                    return ReplayOutcome(success = false, failedStepIndex = stepIndex, stepsCompleted = stepCount)
                }
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
                    effectVerified = verifyEffect(step, prePackage, preSignature, postPackage, postSignature)
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

    private suspend fun runAiLoop(goal: String): TaskResult {
        if (think == null && thinkWithVision == null) {
            return TaskResult(
                status = TaskStatus.FAILED,
                message = "No think function provided",
                stepsUsed = 0
            )
        }

        val history = mutableListOf<Pair<String, String>>()
        val executedSteps = mutableListOf<TaskStep>()
        var parseFailures = 0
        var step = 0
        var lastStepHadNoEffect = false
        // Turn F(e): the previous step's verification dump is carried into
        // the next step's observe — one screen read per AI step instead of
        // two. Invalidated whenever anything else touches the screen.
        var carriedNodes: List<ScreenNode>? = null
        var carriedDescription: String? = null

        while (step < maxSteps) {
            if (_cancelRequested) {
                return TaskResult(TaskStatus.CANCELLED, "Task cancelled by user", step)
            }

            step++
            _currentStep = step

            // --- Observe ---
            // Reuse the previous verification dump when it is non-empty;
            // otherwise read the screen fresh.
            val carried = carriedNodes?.takeIf { it.isNotEmpty() }
            val nodes: List<ScreenNode>
            val description: String
            if (carried != null) {
                nodes = carried
                description = carriedDescription
                    ?: automation.getCompressedScreenDescription(nodes, goal)
                carriedNodes = null
                carriedDescription = null
            } else {
                enterPhase("observing", step, "Observing screen (step $step/$maxSteps)...")
                val freshNodes = try {
                    automation.getScreenElements()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logWarn("Failed to read screen: ${e.message}")
                    delay(stepDelayMs)
                    continue
                }
                nodes = freshNodes
                description = if (nodes.isEmpty()) {
                    if (executedSteps.isEmpty()) {
                        logInfo("Screen appears empty on step 1 — passing open_app hint to AI")
                        "Screen appears empty (use open_app to launch the target app)"
                    } else {
                        logInfo("Screen appears empty (locked?). Waiting...")
                        delay(1500)
                        continue
                    }
                } else {
                    recovery.recordScreen(automation.formatNodes(nodes))
                    automation.getCompressedScreenDescription(nodes, goal)
                }
            }

            // --- Think ---
            enterPhase("thinking", step, "waiting for AI reply (step $step)")
            val raw: String
            try {
                raw = withTimeout(thinkTimeoutMs) {
                    _decideNextStep(goal, description, nodes, history, lastStepHadNoEffect)
                }
            } catch (e: TimeoutCancellationException) {
                val detail = "think call exceeded ${thinkTimeoutMs / 1000}s"
                val thinkMsg = "Timed out while waiting for the AI reply (step $step, $detail)"
                recordDiagnosis(thinkMsg)
                logWarn(thinkMsg)
                return TaskResult(
                    status = TaskStatus.FAILED,
                    message = thinkMsg,
                    stepsUsed = step
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logError("Think step failed: ${e.message}", e)
                val errMsg = "AI request failed: ${e.message}"
                recordDiagnosis(errMsg)
                return TaskResult(TaskStatus.FAILED, errMsg, step)
            }

            val decision = TaskStep.tryParse(raw)
            if (decision == null) {
                parseFailures++
                logWarn("Could not parse AI output as action JSON (attempt $parseFailures): $raw")
                if (parseFailures >= 3) {
                    val giveUpMsg = "Giving up: AI kept replying non-JSON."
                    recordDiagnosis(giveUpMsg)
                    return TaskResult(
                        status = TaskStatus.FAILED,
                        message = giveUpMsg,
                        stepsUsed = step
                    )
                }
                pushTurn(
                    history,
                    raw,
                    "Your last reply was not valid JSON. Reply with ONLY the single JSON action object: {\"action\": \"<action>\", \"params\": {...}, \"reasoning\": \"<explanation>\", \"is_complete\": false}"
                )
                continue
            }
            parseFailures = 0

            // --- Safety Gate (checked BEFORE action executes) ---
            val blockedReason = AutomationSafety.isBlocked(decision, automation.getCurrentPackage())
            if (blockedReason != null) {
                val blockMessage = "Blocked for safety: $blockedReason — ask me first."
                recordDiagnosis(blockMessage)
                logWarn(blockMessage)
                return TaskResult(TaskStatus.FAILED, blockMessage, step)
            }

            // --- Act ---
            enterPhase("acting", step, decision.reasoning.ifEmpty { decision.action })

            // F-04 & F-07: Never return SUCCESS on ACTION_DONE when executedSteps (verified effects) is empty
            if (decision.action == TaskStep.ACTION_DONE) {
                if (executedSteps.isEmpty()) {
                    logWarn("Rejected premature ACTION_DONE with zero verified executed steps")
                    lastStepHadNoEffect = true
                    pushTurn(
                        history,
                        raw,
                        "ACTION RESULT: [NO_EFFECT] Cannot complete task before executing at least one action with a verified effect on screen. Use open_app or interact with the screen first."
                    )
                    continue
                }
                skills.saveOrUpdate(goal, executedSteps)
                return TaskResult(
                    status = TaskStatus.SUCCESS,
                    message = "Goal completed in $step step(s).",
                    stepsUsed = step
                )
            }

            val prePackage = automation.getCurrentPackage().orEmpty()
            val preSignature = computeNodeSignature(nodes)

            val dispatched = _performAction(decision, nodes)
            recovery.recordAction(decision)

            if (!dispatched) {
                lastStepHadNoEffect = true
                val reason = lastActionError ?: "${decision.action} dispatch failed"
                recordDiagnosis("Step $step: $reason")
                pushTurn(
                    history,
                    raw,
                    "ACTION RESULT: [NO_EFFECT] $reason. Pick a different approach."
                )
            } else if (decision.action == TaskStep.ACTION_WAIT) {
                lastStepHadNoEffect = false
                pushTurn(
                    history,
                    raw,
                    "ACTION RESULT: [OK] waited."
                )
            } else {
                // Turn F(a/f): open_app polls for the target package; other
                // actions wait the short settle. One dump, then carry it
                // forward as the next step's observe (Turn F/e).
                enterPhase(
                    "observing",
                    step,
                    if (decision.action == TaskStep.ACTION_OPEN_APP) "waiting for app to open (step $step)"
                    else "verifying effect of ${decision.action} (step $step)"
                )
                val (postNodes, postPackage, postSignature) = settleAndDump(decision, prePackage)
                val effectVerified = verifyEffect(decision, prePackage, preSignature, postPackage, postSignature)
                if (postNodes.isNotEmpty()) {
                    carriedNodes = postNodes
                    carriedDescription = automation.getCompressedScreenDescription(postNodes, goal)
                } else {
                    carriedNodes = null
                    carriedDescription = null
                }

                if (effectVerified) {
                    lastStepHadNoEffect = false
                    executedSteps.add(decision)
                    pushTurn(
                        history,
                        raw,
                        "ACTION RESULT: [OK] for ${decision.action}."
                    )
                    if (decision.isComplete && executedSteps.isNotEmpty()) {
                        skills.saveOrUpdate(goal, executedSteps)
                        return TaskResult(
                            status = TaskStatus.SUCCESS,
                            message = "Goal completed in $step step(s).",
                            stepsUsed = step
                        )
                    }
                } else {
                    lastStepHadNoEffect = true
                    pushTurn(
                        history,
                        raw,
                        "ACTION RESULT: [NO_EFFECT] ${decision.action} was dispatched but the screen did not change. Pick a different approach."
                    )
                }
            }

            // Recovery safeguard
            if (recovery.shouldRecover) {
                val recoveryStep = recovery.suggestRecovery()
                val recBlocked = AutomationSafety.isBlocked(recoveryStep, automation.getCurrentPackage())
                if (recBlocked == null) {
                    onProgress?.invoke(step, "Recovery: ${recoveryStep.reasoning}")
                    _performAction(recoveryStep, nodes)
                    // The recovery action changed the screen — the carried
                    // verification dump is stale, re-read next step.
                    carriedNodes = null
                    carriedDescription = null
                }
            }

            delay(stepDelayMs)
        }

        return TaskResult(
            status = TaskStatus.FAILED,
            message = "Reached the maximum of $maxSteps steps without completing the goal.",
            stepsUsed = step
        )
    }

    fun shouldAttachScreenshot(nodes: List<ScreenNode>, lastStepHadNoEffect: Boolean): Boolean =
        Companion.shouldAttachScreenshot(nodes, lastStepHadNoEffect)

    private suspend fun _decideNextStep(
        goal: String,
        screenDescription: String,
        nodes: List<ScreenNode>,
        history: List<Pair<String, String>>,
        lastStepHadNoEffect: Boolean
    ): String {
        val screenshot = if (shouldAttachScreenshot(nodes, lastStepHadNoEffect)) {
            try {
                automation.takeScreenshotJpeg()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logWarn("Screenshot capture skipped: ${e.message}")
                null
            }
        } else {
            null
        }
        val visionFn = thinkWithVision
        if (screenshot != null && visionFn != null) {
            return visionFn(goal, screenDescription, history, screenshot)
        }
        val thinkFn = think ?: throw IllegalStateException("No think function provided")
        return thinkFn(goal, screenDescription, history)
    }

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
         */
        fun openAppTargetReached(step: TaskStep, prePackage: String, postPackage: String): Boolean {
            val targetQuery = (step.stringParam("app") ?: step.stringParam("package") ?: "").trim()
            if (targetQuery.isEmpty() || postPackage.isEmpty()) return false
            if (postPackage == "io.github.ryoaonetsuki.mira" && !targetQuery.contains("mira", ignoreCase = true)) {
                return false
            }
            val resolvedCandidates = ScreenAutomationService.resolveKnownAppPackages(targetQuery)
            val matchesTarget = postPackage.contains(targetQuery, ignoreCase = true) ||
                resolvedCandidates.any {
                    postPackage.equals(it, ignoreCase = true) || postPackage.contains(it, ignoreCase = true)
                }
            return postPackage != prePackage || matchesTarget
        }

        fun verifyEffect(
            step: TaskStep,
            prePackage: String,
            preSignature: Int,
            postPackage: String,
            postSignature: Int
        ): Boolean {
            return when (step.action) {
                TaskStep.ACTION_OPEN_APP -> openAppTargetReached(step, prePackage, postPackage)
                TaskStep.ACTION_CLICK_TEXT,
                TaskStep.ACTION_CLICK_AT,
                TaskStep.ACTION_TYPE_TEXT,
                TaskStep.ACTION_SCROLL,
                TaskStep.ACTION_SWIPE,
                TaskStep.ACTION_PRESS_BACK,
                TaskStep.ACTION_PRESS_HOME,
                TaskStep.ACTION_PRESS_ENTER -> {
                    postPackage != prePackage || postSignature != preSignature
                }
                else -> true
            }
        }

        fun shouldAttachScreenshot(nodes: List<ScreenNode>, lastStepHadNoEffect: Boolean): Boolean {
            // Turn F(d): a screenshot is a per-step tax (capture + base64 +
            // vision call). Only pay it when the previous step had NO effect
            // AND the text description is insufficient to decide the next
            // action (empty screen, or most clickable nodes unlabeled).
            if (!lastStepHadNoEffect) return false
            if (nodes.isEmpty()) return true
            val clickableNodes = nodes.filter { it.isClickable }
            if (clickableNodes.isNotEmpty()) {
                val unlabeledCount = clickableNodes.count { it.text.isBlank() && it.contentDescription.isBlank() }
                if (unlabeledCount * 2 > clickableNodes.size) return true
            }
            return false
        }
    }
}

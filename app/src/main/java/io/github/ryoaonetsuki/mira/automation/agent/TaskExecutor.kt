// Ported from Jarvis (github.com/AbuZar-Ansarii/Jarvis), MIT Licence, Copyright (c) 2026 AbuZar Ansarii — Dart → Kotlin port for Mira.
package io.github.ryoaonetsuki.mira.automation.agent

import android.util.Log
import kotlinx.coroutines.delay

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
    fun formatNodes(nodes: List<ScreenNode>): String
    fun getCompressedScreenDescription(nodes: List<ScreenNode>, goal: String): String
    fun clickText(text: String, nodes: List<ScreenNode>): Boolean
    fun clickAt(x: Float, y: Float): Boolean
    fun typeText(text: String): Boolean
    fun pressEnter(): Boolean
    fun scroll(direction: String): Boolean
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean
    fun pressBack(): Boolean
    fun pressHome(): Boolean
    fun openApp(query: String): String?
    fun getCurrentPackage(): String? = null
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
    private val onProgress: ((step: Int, description: String) -> Unit)? = null
) {
    @Volatile
    private var _isRunning: Boolean = false
    val isRunning: Boolean get() = _isRunning

    @Volatile
    private var _cancelRequested: Boolean = false

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
        recovery.reset()

        try {
            // 1. Accessibility gate
            if (!automation.isServiceEnabled()) {
                return TaskResult(
                    status = TaskStatus.FAILED,
                    message = "Accessibility service is off — enable Mira Screen Control in Settings > Accessibility",
                    stepsUsed = 0
                )
            }

            // 2. Skill-memory fast path (Jarvis behaviour)
            val matchedSkill = skills.findMatch(goal)
            if (matchedSkill != null) {
                onProgress?.invoke(0, "Found memorized skill (${matchedSkill.steps.size} steps). Replaying…")
                val replaySuccess = replaySkill(matchedSkill)
                if (_cancelRequested) {
                    return TaskResult(TaskStatus.CANCELLED, "Task cancelled by user", matchedSkill.steps.size)
                }
                if (replaySuccess) {
                    skills.markReplayOutcome(matchedSkill.id, true)
                    return TaskResult(
                        status = TaskStatus.SUCCESS,
                        message = "Task completed via memorized skill",
                        stepsUsed = matchedSkill.steps.size
                    )
                }
                // Stale skill failed — delete it and fall through to AI loop
                skills.markReplayOutcome(matchedSkill.id, false)
            }

            // 3. AI loop
            return runAiLoop(goal)
        } finally {
            _isRunning = false
            _cancelRequested = false
        }
    }

    private suspend fun replaySkill(skill: Skill): Boolean {
        var stepCount = 0
        for (step in skill.steps) {
            if (_cancelRequested) return false
            stepCount++
            onProgress?.invoke(stepCount, "Replaying: ${step.action}")

            val nodes = try {
                automation.dumpScreen()
            } catch (_: Throwable) {
                emptyList()
            }

            val blocked = AutomationSafety.isBlocked(step, automation.getCurrentPackage())
            if (blocked != null) {
                logWarn("Replayed step blocked for safety: $blocked")
                return false
            }

            val ok = _performAction(step, nodes)
            if (!ok && step.action != TaskStep.ACTION_WAIT) {
                logWarn("Replayed step failed: ${step.action}")
                return false
            }
            delay(stepDelayMs)
        }
        return true
    }

    private suspend fun runAiLoop(goal: String): TaskResult {
        val thinkFn = think ?: return TaskResult(
            status = TaskStatus.FAILED,
            message = "No think function provided",
            stepsUsed = 0
        )

        val history = mutableListOf<Pair<String, String>>()
        val executedSteps = mutableListOf<TaskStep>()
        var parseFailures = 0
        var step = 0

        while (step < maxSteps) {
            if (_cancelRequested) {
                return TaskResult(TaskStatus.CANCELLED, "Task cancelled by user", step)
            }

            step++
            onProgress?.invoke(step, "Observing screen (step $step/$maxSteps)...")

            // --- Observe ---
            val nodes = try {
                automation.dumpScreen()
            } catch (e: Throwable) {
                logWarn("Failed to read screen: ${e.message}")
                delay(stepDelayMs)
                continue
            }

            if (nodes.isEmpty()) {
                logInfo("Screen appears empty (locked?). Waiting...")
                delay(1500)
                continue
            }

            val fullDump = automation.formatNodes(nodes)
            recovery.recordScreen(fullDump)
            val description = automation.getCompressedScreenDescription(nodes, goal)

            // --- Think ---
            onProgress?.invoke(step, "Thinking...")
            val raw: String
            try {
                raw = thinkFn(goal, description, history)
            } catch (e: Throwable) {
                logError("Think step failed: ${e.message}", e)
                return TaskResult(TaskStatus.FAILED, "AI request failed: ${e.message}", step)
            }

            val decision = TaskStep.tryParse(raw)
            if (decision == null) {
                parseFailures++
                logWarn("Could not parse AI output as action JSON (attempt $parseFailures): $raw")
                if (parseFailures >= 3) {
                    return TaskResult(
                        status = TaskStatus.FAILED,
                        message = "Giving up: AI kept replying non-JSON.",
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
                logWarn(blockMessage)
                return TaskResult(TaskStatus.FAILED, blockMessage, step)
            }

            // --- Act ---
            onProgress?.invoke(step, decision.reasoning.ifEmpty { decision.action })

            if (decision.action == TaskStep.ACTION_DONE || decision.isComplete) {
                if (executedSteps.isNotEmpty()) {
                    skills.saveOrUpdate(goal, executedSteps)
                }
                return TaskResult(
                    status = TaskStatus.SUCCESS,
                    message = "Goal completed in $step step(s).",
                    stepsUsed = step
                )
            }

            val ok = _performAction(decision, nodes)
            executedSteps.add(decision)
            recovery.recordAction(decision)
            pushTurn(
                history,
                raw,
                "ACTION RESULT: ${if (ok) "success" else "FAILED"} for ${decision.action}. If the screen did not change, pick a different approach."
            )

            // Recovery safeguard
            if (recovery.shouldRecover) {
                val recoveryStep = recovery.suggestRecovery()
                val recBlocked = AutomationSafety.isBlocked(recoveryStep, automation.getCurrentPackage())
                if (recBlocked == null) {
                    onProgress?.invoke(step, "Recovery: ${recoveryStep.reasoning}")
                    _performAction(recoveryStep, nodes)
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

    private suspend fun _performAction(step: TaskStep, nodes: List<ScreenNode>): Boolean {
        return try {
            when (step.action) {
                TaskStep.ACTION_CLICK_TEXT -> {
                    val text = step.stringParam("text") ?: ""
                    automation.clickText(text, nodes)
                }
                TaskStep.ACTION_CLICK_AT -> {
                    val x = step.intParam("x") ?: return false
                    val y = step.intParam("y") ?: return false
                    automation.clickAt(x.toFloat(), y.toFloat())
                }
                TaskStep.ACTION_TYPE_TEXT -> {
                    val text = step.stringParam("text") ?: return false
                    automation.typeText(text)
                }
                TaskStep.ACTION_PRESS_ENTER -> automation.pressEnter()
                TaskStep.ACTION_SCROLL -> {
                    val dir = step.stringParam("direction") ?: "down"
                    automation.scroll(dir)
                }
                TaskStep.ACTION_SWIPE -> {
                    val sx = step.intParam("startX") ?: return false
                    val sy = step.intParam("startY") ?: return false
                    val ex = step.intParam("endX") ?: return false
                    val ey = step.intParam("endY") ?: return false
                    val duration = (step.intParam("durationMs") ?: 350).toLong()
                    automation.swipe(sx.toFloat(), sy.toFloat(), ex.toFloat(), ey.toFloat(), duration)
                }
                TaskStep.ACTION_PRESS_BACK -> automation.pressBack()
                TaskStep.ACTION_PRESS_HOME -> automation.pressHome()
                TaskStep.ACTION_OPEN_APP -> {
                    val query = step.stringParam("app") ?: step.stringParam("package") ?: ""
                    automation.openApp(query) != null
                }
                TaskStep.ACTION_WAIT -> {
                    val ms = (step.intParam("ms") ?: 1500).coerceIn(100, 10000).toLong()
                    delay(ms)
                    true
                }
                TaskStep.ACTION_DONE -> true
                else -> false
            }
        } catch (e: Throwable) {
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
    }
}

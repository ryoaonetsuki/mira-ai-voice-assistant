package io.github.ryoaonetsuki.mira.voice

import io.github.ryoaonetsuki.mira.automation.MiraAutomationBridge
import io.github.ryoaonetsuki.mira.automation.agent.TaskStep
import io.github.ryoaonetsuki.mira.voice.pipecat.SessionManagerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class WatchdogState {
    IDLE,
    ARMED,
    FIRED,
    CANCELLED
}

enum class WatchdogKind {
    USER_TURN,
    POST_FUNCTION_CALL
}

enum class WatchdogAction {
    INJECT_LIVE_NUDGE,
    RUN_DIRECT_PATH_NO_TTS,
    RUN_DIRECT_PATH_WITH_TTS,
    NONE
}

object WatchdogActionResolver {
    /**
     * Resolves the execution action when a turn watchdog fires.
     * Invariant: Never speak TTS while Live is connected.
     * Fallback TTS is allowed ONLY if Live is disconnected AND the command was user-initiated.
     */
    fun resolve(
        isLiveConnected: Boolean,
        isUserInitiated: Boolean = true
    ): WatchdogAction {
        return if (isLiveConnected) {
            WatchdogAction.INJECT_LIVE_NUDGE
        } else {
            val mayTts = VoiceTurnRouter.mayUseFallbackTts(
                liveConnected = false,
                userInitiated = isUserInitiated
            )
            if (mayTts) {
                WatchdogAction.RUN_DIRECT_PATH_WITH_TTS
            } else {
                WatchdogAction.RUN_DIRECT_PATH_NO_TTS
            }
        }
    }
}

/**
 * Pure Kotlin silence watchdog state machine for voice turns.
 * Used for:
 * 1) User turns: arm on final transcript; cancel on function call, model transcript, or bot speaking.
 *    If it fires while Live is connected, injects transcript text as a nudge.
 * 2) Post-function-call: arm on sendFunctionResponse; cancel on model transcript or bot speaking.
 *    If it fires while Live is connected, injects a nudge carrying the function execution outcome.
 */
class TurnWatchdog(
    private val scope: CoroutineScope? = null,
    val kind: WatchdogKind = WatchdogKind.USER_TURN,
    private var onFireCallback: ((text: String) -> Unit)? = null
) {
    var state: WatchdogState = WatchdogState.IDLE
        private set

    var armedText: String? = null
        private set

    var armedDeadlineMs: Long = 0L
        private set

    private var job: Job? = null

    fun setOnFire(callback: (text: String) -> Unit) {
        onFireCallback = callback
    }

    fun arm(text: String, deadlineMs: Long = if (kind == WatchdogKind.USER_TURN) DEFAULT_USER_TURN_TIMEOUT_MS else DEFAULT_POST_CALL_TIMEOUT_MS) {
        cancel()
        armedText = text
        armedDeadlineMs = deadlineMs
        state = WatchdogState.ARMED
        val s = scope
        if (s != null) {
            job = s.launch {
                delay(deadlineMs)
                fire()
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        if (state == WatchdogState.ARMED) {
            state = WatchdogState.CANCELLED
        }
    }

    fun fire() {
        if (state != WatchdogState.ARMED) return
        state = WatchdogState.FIRED
        job?.cancel()
        job = null
        val text = armedText.orEmpty()
        onFireCallback?.invoke(text)
    }

    companion object {
        const val DEFAULT_USER_TURN_TIMEOUT_MS: Long = 5000L
        const val DEFAULT_POST_CALL_TIMEOUT_MS: Long = 8000L

        fun formatPostCallNudge(outcome: String): String =
            "[System: The action completed with result: \"$outcome\". Please briefly speak this outcome to the user.]"

        fun extractOutcomeFromMap(map: Map<String, Any?>): String {
            (map["result"] as? String)?.let { if (it.isNotBlank()) return it }
            (map["message"] as? String)?.let { if (it.isNotBlank()) return it }
            (map["error"] as? String)?.let { if (it.isNotBlank()) return "Error: $it" }
            val success = map["success"] as? Boolean
            val target = (map["package"] as? String) ?: (map["target"] as? String)
            if (success == true) {
                return if (!target.isNullOrBlank()) "Successfully opened $target" else "Action completed successfully"
            } else if (success == false) {
                return if (!target.isNullOrBlank()) "Failed to open $target" else "Action failed"
            }
            return target ?: "Action finished"
        }

        fun directActionToSteps(action: MiraAutomationBridge.DirectAction): List<TaskStep> {
            return when (action) {
                is MiraAutomationBridge.DirectAction.OpenApp -> listOf(
                    TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to action.target))
                )
                is MiraAutomationBridge.DirectAction.PressHome -> listOf(
                    TaskStep(action = TaskStep.ACTION_PRESS_HOME)
                )
                is MiraAutomationBridge.DirectAction.PressBack -> listOf(
                    TaskStep(action = TaskStep.ACTION_PRESS_BACK)
                )
                is MiraAutomationBridge.DirectAction.Scroll -> listOf(
                    TaskStep(action = TaskStep.ACTION_SCROLL, params = mapOf("direction" to action.direction))
                )
                is MiraAutomationBridge.DirectAction.TapNumber -> listOf(
                    TaskStep(action = TaskStep.ACTION_CLICK_NUMBER, params = mapOf("number" to action.number))
                )
                is MiraAutomationBridge.DirectAction.TapLabel -> listOf(
                    TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = mapOf("text" to action.label))
                )
                is MiraAutomationBridge.DirectAction.Dial -> listOf(
                    TaskStep(action = TaskStep.ACTION_DIAL, params = mapOf("number" to action.number))
                )
                is MiraAutomationBridge.DirectAction.Search -> {
                    val app = action.targetApp ?: "com.android.chrome"
                    listOf(
                        TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to app)),
                        TaskStep(action = TaskStep.ACTION_TYPE_TEXT, params = mapOf("text" to action.query)),
                        TaskStep(action = TaskStep.ACTION_PRESS_ENTER)
                    )
                }
            }
        }
    }
}

/**
 * Pure decision: suppress the user-turn nudge when a function call is in flight.
 * No Android imports — JVM-testable with plain values.
 */
internal fun shouldSuppressNudgeWhileInFlight(inFlightCallCount: Int): Boolean {
    return inFlightCallCount > 0
}

/**
 * Pure decision: allow escalation/reconnect only when no function call is in flight.
 */
internal fun shouldEscalateWhileInFlight(inFlightCallCount: Int): Boolean {
    return inFlightCallCount == 0
}

/**
 * Pure decision: run the disconnected direct path only when the session is dead
 * (STOPPED or ERROR). Skip while RECONNECTING or CONFIGURING to wait for resumption.
 */
internal fun shouldRunDisconnectedDirectPath(sessionState: SessionManagerState): Boolean {
    return sessionState == SessionManagerState.STOPPED || sessionState == SessionManagerState.ERROR
}

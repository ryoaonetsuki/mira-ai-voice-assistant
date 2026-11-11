package io.github.ryoaonetsuki.mira.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Second-stage escalation helper for user-turn watchdog (Symptom 3b).
 *
 * After a user turn watchdog injects a nudge into the Live session, if the model
 * still does not respond (no transcript, bot-speaking, or function call) within [timeoutMs],
 * this escalator fires [onEscalate] to request a reconnect.
 * Any server activity cancels the pending escalation.
 */
class UserTurnEscalator(
    private val scope: CoroutineScope? = null,
    val timeoutMs: Long = DEFAULT_ESCALATION_TIMEOUT_MS,
    private val onEscalate: (reason: String) -> Unit = {}
) {
    @Volatile
    var isPending: Boolean = false
        private set

    @Volatile
    var armedTimeMs: Long = 0L
        private set

    private var job: Job? = null

    fun arm(reason: String = "no-model-response-after-nudge", clockMs: () -> Long = { System.currentTimeMillis() }) {
        cancel()
        isPending = true
        armedTimeMs = clockMs()
        val s = scope ?: return
        job = s.launch {
            delay(timeoutMs)
            fire(reason)
        }
    }

    fun fire(reason: String = "no-model-response-after-nudge") {
        if (!isPending) return
        isPending = false
        job?.cancel()
        job = null
        onEscalate(reason)
    }

    fun cancel() {
        job?.cancel()
        job = null
        isPending = false
        armedTimeMs = 0L
    }

    companion object {
        const val DEFAULT_ESCALATION_TIMEOUT_MS: Long = 10_000L
    }
}

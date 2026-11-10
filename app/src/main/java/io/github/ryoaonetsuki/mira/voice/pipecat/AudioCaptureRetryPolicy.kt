package io.github.ryoaonetsuki.mira.voice.pipecat

/**
 * Turn G (Round 25): bounded retry policy for [AudioIn] capture failures.
 *
 * On this phone (custom ROM) `AudioRecord.read` can start returning errors
 * when the foreground app changes (e.g. a voice command opens Facebook /
 * YouTube) — previously that threw out of the capture thread and the mic
 * died silently forever while the session stayed "connected" but deaf.
 * [AudioIn] now recreates the `AudioRecord` and retries with this policy;
 * only after [MAX_CONSECUTIVE_FAILURES] consecutive failures does it give
 * up and surface the error so the session manager can restart the call.
 *
 * Pure Kotlin (no Android APIs) so it is unit-testable on the JVM.
 */
class AudioCaptureRetryPolicy(
    val maxConsecutiveFailures: Int = MAX_CONSECUTIVE_FAILURES
) {
    companion object {
        const val MAX_CONSECUTIVE_FAILURES = 10
        const val BASE_DELAY_MS = 250L
        const val MAX_DELAY_MS = 2_000L
    }

    /** True while another capture attempt is worth making. */
    fun shouldRetry(consecutiveFailures: Int): Boolean =
        consecutiveFailures < maxConsecutiveFailures

    /** Backoff before the next capture attempt (1-based failure count). */
    fun delayBeforeRetryMs(consecutiveFailures: Int): Long {
        val attempt = consecutiveFailures.coerceAtLeast(1)
        return (BASE_DELAY_MS * attempt).coerceAtMost(MAX_DELAY_MS)
    }
}

package io.github.ryoaonetsuki.mira.voice.pipecat

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure JVM session coordinator guaranteeing:
 * 1. Single voice session invariant (max 1 active session slot).
 * 2. Un-cancellable start race / zombie session prevention via monotonic generation tokens.
 */
class VoiceSessionGuard(val maxConcurrentSessions: Int = 1) {
    private val activeSessions = AtomicInteger(0)
    private val generationToken = AtomicLong(0L)

    /**
     * Issues a new session generation token. Any earlier in-flight session starts
     * become obsolete.
     */
    fun nextGeneration(): Long = generationToken.incrementAndGet()

    /**
     * Invalidates active sessions and pending starts by advancing the generation token.
     */
    fun invalidateActiveSessions(): Long = generationToken.incrementAndGet()

    /**
     * Checks if the given [generation] matches the current valid session generation.
     */
    fun isGenerationValid(generation: Long): Boolean = generation == generationToken.get()

    /**
     * Atomically acquires a live session slot. Returns true if within [maxConcurrentSessions].
     */
    fun acquireSessionSlot(): Boolean {
        val current = activeSessions.incrementAndGet()
        if (current > maxConcurrentSessions) {
            activeSessions.decrementAndGet()
            return false
        }
        return true
    }

    /**
     * Releases an active session slot, clamped at 0.
     */
    fun releaseSessionSlot() {
        activeSessions.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    /**
     * Returns the number of currently active live session slots.
     */
    fun getActiveSessionCount(): Int = activeSessions.get()

    /**
     * Resets all session counters for testing.
     */
    fun reset() {
        activeSessions.set(0)
        generationToken.set(0L)
    }
}

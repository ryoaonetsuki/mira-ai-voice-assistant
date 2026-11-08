package io.github.ryoaonetsuki.mira.voice.pipecat

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow

/**
 * Speaking-state echo gate safety net for Mira Gemini Live voice (ROUND 20 Turns 3-4).
 *
 * Prevents Mira's loudspeaker playback from leaking back into the mic and triggering
 * premature Gemini server-side VAD interruption (serverContent.interrupted=true), which
 * causes Mira to cut herself off mid-sentence and respond to her own voice.
 *
 * Rules:
 * - If [echoGuardEnabled] is false -> true (gate disabled, forwards unconditionally).
 * - If [isBotTalking] is false -> true (Mira is silent, forwards user speech unconditionally).
 * - If [isBotTalking] is true -> forwards ONLY when microphone energy clearly exceeds
 *   Mira's own playback level: micRms > (effectiveBotLevel * RATIO + FLOOR).
 *
 * Decay Model:
 * During inter-phrase or inter-sentence pauses in Mira's speech, AudioOut does not write chunks,
 * leaving the latest bot audio level stale-high while [isBotTalking] remains true.
 * To allow natural user interruption during these pauses, [computeEffectiveBotLevel] decays
 * the bot playback level exponentially with half-life [BOT_LEVEL_HALF_LIFE_MS] (150ms),
 * dropping to 0 once age exceeds [BOT_LEVEL_MAX_AGE_MS] (300ms).
 */
object EchoGate {
    /**
     * Safety multiplier over playback level (+3.5 dB).
     *
     * Acoustic coupling from the device loudspeaker into the microphone is physically < 1.0.
     * A ratio of 1.5f provides a 50% safety margin above direct coupling to prevent acoustic
     * resonance and echo peaks from triggering Gemini VAD, while easily permitting deliberate
     * near-field barge-in interruptions by the user.
     */
    const val RATIO: Float = 1.5f

    /**
     * Absolute noise floor in normalized RMS [0.0, 1.0].
     *
     * Prevents low-level room noise or microphone hiss (~0.01 - 0.03) from leaking through
     * during inter-word pauses or quiet playback moments when botLevel approaches 0.
     */
    const val FLOOR: Float = 0.05f

    /**
     * Half-life in milliseconds for exponential bot playback level decay (-6 dB per 150ms).
     * Matches typical domestic room acoustic reverberation dissipation (RT60 ~150-250ms).
     */
    const val BOT_LEVEL_HALF_LIFE_MS: Long = 150L

    /**
     * Maximum age in milliseconds after which playback level is treated as completely silent (0.0f).
     * Normal conversational inter-sentence pauses exceed 250-400ms.
     */
    const val BOT_LEVEL_MAX_AGE_MS: Long = 300L

    /**
     * Computes the effective bot playback level decaying exponentially with age.
     *
     * @param lastLevel Normalized RMS [0.0, 1.0] of latest bot speaker chunk.
     * @param lastLevelTimestampMs Timestamp (ms) when [lastLevel] was recorded.
     * @param nowMs Current timestamp (ms), defaults to [System.currentTimeMillis].
     * @return Effective decayed level in [0.0, 1.0]. Returns [lastLevel] if fresh,
     *         decays smoothly by 50% every 150ms, and drops to 0.0f at age >= 300ms.
     */
    fun computeEffectiveBotLevel(
        lastLevel: Float,
        lastLevelTimestampMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Float {
        if (lastLevel <= 0f || lastLevelTimestampMs <= 0L) return 0f
        val ageMs = nowMs - lastLevelTimestampMs
        if (ageMs <= 0L) return lastLevel
        if (ageMs >= BOT_LEVEL_MAX_AGE_MS) return 0f
        val decay = (0.5).pow(ageMs.toDouble() / BOT_LEVEL_HALF_LIFE_MS.toDouble()).toFloat()
        return lastLevel * decay
    }

    /**
     * Evaluates whether an incoming microphone PCM frame should be forwarded to Gemini.
     *
     * @param micRms Normalized RMS [0.0, 1.0] of current mic frame from calculateAudioLevel.
     * @param botLevel Normalized RMS [0.0, 1.0] of latest bot speaker playback (effective level).
     * @param isBotTalking True if Mira is currently speaking/streaming audio out.
     * @param echoGuardEnabled Master toggle preference for the echo gate.
     * @return True if the audio frame should be forwarded; false if it should be dropped.
     */
    fun shouldForward(
        micRms: Float,
        botLevel: Float,
        isBotTalking: Boolean,
        echoGuardEnabled: Boolean = true
    ): Boolean {
        if (!echoGuardEnabled) return true
        if (!isBotTalking) return true
        return micRms > (botLevel * RATIO + FLOOR)
    }
}

/**
 * Stateful controller coordinating echo-gated frame routing for Gemini Live.
 * Tracks bot talking state, latest playback levels, and timestamps to evaluate
 * whether incoming microphone PCM frames should be forwarded to Gemini or dropped.
 */
class EchoFrameRouter(
    val echoGuardEnabled: Boolean = true
) {
    private val isBotTalking = AtomicBoolean(false)
    private val latestBotAudioLevel = AtomicReference(0f)
    private val latestBotAudioLevelTimestamp = AtomicLong(0L)

    fun onBotTalking(talking: Boolean) {
        isBotTalking.set(talking)
        if (!talking) {
            latestBotAudioLevel.set(0f)
            latestBotAudioLevelTimestamp.set(0L)
        }
    }

    fun onBotAudioLevel(level: Float, timestampMs: Long = System.currentTimeMillis()) {
        latestBotAudioLevel.set(level)
        latestBotAudioLevelTimestamp.set(timestampMs)
    }

    fun onInterrupted() {
        isBotTalking.set(false)
        latestBotAudioLevel.set(0f)
        latestBotAudioLevelTimestamp.set(0L)
    }

    fun onTurnComplete() {
        isBotTalking.set(false)
        latestBotAudioLevel.set(0f)
        latestBotAudioLevelTimestamp.set(0L)
    }

    fun isBotTalking(): Boolean = isBotTalking.get()

    fun getRawBotLevel(): Float = latestBotAudioLevel.get()

    fun getEffectiveBotLevel(nowMs: Long = System.currentTimeMillis()): Float {
        return EchoGate.computeEffectiveBotLevel(
            latestBotAudioLevel.get(),
            latestBotAudioLevelTimestamp.get(),
            nowMs
        )
    }

    /**
     * Evaluates whether an incoming mic frame with pre-computed [micRms] should be forwarded.
     */
    fun shouldForward(
        micRms: Float,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val effectiveBot = getEffectiveBotLevel(nowMs)
        return EchoGate.shouldForward(
            micRms = micRms,
            botLevel = effectiveBot,
            isBotTalking = isBotTalking.get(),
            echoGuardEnabled = echoGuardEnabled
        )
    }

    /**
     * Evaluates whether an incoming mic PCM buffer should be forwarded.
     */
    fun shouldForward(
        micPcm: ByteArray,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val micRms = calculateAudioLevel(micPcm)
        return shouldForward(micRms, nowMs)
    }
}

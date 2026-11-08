package io.github.ryoaonetsuki.mira.voice.pipecat

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Calculates normalized RMS audio level [0.0f, 1.0f] from 16-bit PCM mono data.
 */
internal fun calculateAudioLevel(pcmData: ByteArray): Float {
    var sumSquares = 0.0
    val numSamples = pcmData.size / 2
    if (numSamples == 0) return 0f

    for (i in 0 until numSamples) {
        val low = pcmData[i * 2].toInt() and 0xFF
        val high = pcmData[i * 2 + 1].toInt() and 0xFF
        val sample = ((high shl 8) or low).toShort()
        val sampleValue = sample.toDouble()
        sumSquares += sampleValue * sampleValue
    }

    val rms = sqrt(sumSquares / numSamples)
    return (rms / 32768.0).toFloat().coerceIn(0.0f, 1.0f)
}

/**
 * Speaking-state echo gate safety net for Mira Gemini Live voice (ROUND 20 Turns 3-4, ROUND 22 Turn 5).
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
 * Drain-Aware Decay Model:
 * During active playback and while AudioTrack drains buffered audio (chunk duration + 250ms margin),
 * [computeEffectiveBotLevel] preserves the bot playback level without decay. Only after this drain
 * window has elapsed does the level decay exponentially with half-life [BOT_LEVEL_HALF_LIFE_MS] (150ms),
 * dropping to 0 once age exceeds [BOT_LEVEL_MAX_AGE_MS] (600ms).
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
     * Raised from 300ms to 600ms (Round 22 Turn 5) to account for AudioTrack buffering.
     */
    const val BOT_LEVEL_MAX_AGE_MS: Long = 600L

    /**
     * Computes the effective bot playback level decaying exponentially with age after the drain window.
     *
     * @param lastLevel Normalized RMS [0.0, 1.0] of latest bot speaker chunk.
     * @param lastLevelTimestampMs Timestamp (ms) when [lastLevel] was recorded.
     * @param nowMs Current timestamp (ms), defaults to [System.currentTimeMillis].
     * @param audioActiveUntilMs Timestamp (ms) until which audio is actively playing or draining.
     * @return Effective level in [0.0, 1.0]. Returns [lastLevel] (no decay) while nowMs < audioActiveUntilMs,
     *         decays smoothly by 50% every 150ms thereafter, and drops to 0.0f at age >= 600ms.
     */
    fun computeEffectiveBotLevel(
        lastLevel: Float,
        lastLevelTimestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        audioActiveUntilMs: Long = lastLevelTimestampMs
    ): Float {
        if (lastLevel <= 0f || lastLevelTimestampMs <= 0L) return 0f
        val effectiveStartMs = maxOf(lastLevelTimestampMs, audioActiveUntilMs)
        if (nowMs < effectiveStartMs) {
            return lastLevel
        }
        val ageMs = nowMs - effectiveStartMs
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
    companion object {
        const val DRAIN_MARGIN_MS: Long = 250L
        const val DEFAULT_CHUNK_DURATION_MS: Long = 100L
    }

    private val isBotTalking = AtomicBoolean(false)
    private val latestBotAudioLevel = AtomicReference(0f)
    private val latestBotAudioLevelTimestamp = AtomicLong(0L)
    private val audioActiveUntil = AtomicLong(0L)

    fun onBotTalking(talking: Boolean) {
        isBotTalking.set(talking)
        if (!talking) {
            latestBotAudioLevel.set(0f)
            latestBotAudioLevelTimestamp.set(0L)
            audioActiveUntil.set(0L)
        }
    }

    fun onBotAudioLevel(
        level: Float,
        timestampMs: Long = System.currentTimeMillis(),
        chunkDurationMs: Long = DEFAULT_CHUNK_DURATION_MS
    ) {
        latestBotAudioLevel.set(level)
        latestBotAudioLevelTimestamp.set(timestampMs)
        val activeUntil = timestampMs + chunkDurationMs + DRAIN_MARGIN_MS
        audioActiveUntil.set(activeUntil)
    }

    fun onInterrupted() {
        isBotTalking.set(false)
        latestBotAudioLevel.set(0f)
        latestBotAudioLevelTimestamp.set(0L)
        audioActiveUntil.set(0L)
    }

    fun onTurnComplete() {
        isBotTalking.set(false)
        latestBotAudioLevel.set(0f)
        latestBotAudioLevelTimestamp.set(0L)
        audioActiveUntil.set(0L)
    }

    fun isBotTalking(): Boolean = isBotTalking.get()

    fun getRawBotLevel(): Float = latestBotAudioLevel.get()

    fun getAudioActiveUntilMs(): Long = audioActiveUntil.get()

    fun getEffectiveBotLevel(nowMs: Long = System.currentTimeMillis()): Float {
        return EchoGate.computeEffectiveBotLevel(
            lastLevel = latestBotAudioLevel.get(),
            lastLevelTimestampMs = latestBotAudioLevelTimestamp.get(),
            nowMs = nowMs,
            audioActiveUntilMs = audioActiveUntil.get()
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

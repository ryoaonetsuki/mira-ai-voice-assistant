package io.github.ryoaonetsuki.mira.voice

import android.util.Log
import kotlin.math.max
import kotlin.math.sqrt

/**
 * On-device zero-dependency Voice Activity Detector using Root-Mean-Square (RMS)
 * amplitude per 16-bit PCM frame with an adaptive background noise baseline.
 *
 * Implements Maya's exact timing constants:
 * - Speech start trigger: 150 ms continuous audio above threshold -> logs t-speech
 * - Endpoint trigger: 300 ms continuous silence below threshold -> logs t-endpoint
 */
class VoiceActivityDetector(
    private val listener: VadListener? = null,
    private val speechTriggerMs: Long = VAD_SPEECH_TRIGGER_MS,
    private val endpointSilenceMs: Long = VAD_ENDPOINT_SILENCE_MS
) {
    interface VadListener {
        fun onSpeechStart()
        fun onSpeechEndpoint()
    }

    private var inSpeechState = false
    private var continuousSpeechMs = 0L
    private var continuousSilenceMs = 0L

    // Adaptive noise floor baseline (initialized to reasonable quiet room level)
    private var noiseFloorRms = DEFAULT_NOISE_FLOOR

    /**
     * Whether audio transmission is currently active (speech has started and
     * has not reached trailing silence suppression).
     */
    @Volatile
    var isVoiceActive: Boolean = false
        private set

    /**
     * Processes a 16-bit PCM audio frame and updates detection state.
     * @param pcmData 16-bit mono PCM bytes
     * @param sampleRate audio sample rate (default 16,000 Hz)
     * @return true if the frame is deemed to be active speech or within the speech turn
     */
    fun processFrame(pcmData: ByteArray, sampleRate: Int = 16000): Boolean {
        if (pcmData.size < 2) return inSpeechState

        // Calculate RMS amplitude for 16-bit PCM samples
        var sumSquares = 0.0
        val sampleCount = pcmData.size / 2
        var i = 0
        while (i < pcmData.size - 1) {
            val sample = ((pcmData[i + 1].toInt() shl 8) or (pcmData[i].toInt() and 0xFF)).toShort()
            sumSquares += sample.toDouble() * sample.toDouble()
            i += 2
        }
        val currentRms = if (sampleCount > 0) sqrt(sumSquares / sampleCount) else 0.0

        // Frame duration in milliseconds
        // (sampleCount samples / sampleRate) * 1000
        val frameDurationMs = (sampleCount * 1000L) / sampleRate

        // Adaptive threshold: at least MIN_THRESHOLD, dynamically above noise floor
        val threshold = max(MIN_THRESHOLD, noiseFloorRms * THRESHOLD_MULTIPLIER)

        val isAbove = currentRms >= threshold

        if (isAbove) {
            continuousSpeechMs += frameDurationMs
            continuousSilenceMs = 0L

            if (!inSpeechState && continuousSpeechMs >= speechTriggerMs) {
                inSpeechState = true
                isVoiceActive = true
                Log.i(TAG, "t-speech (speechMs=$continuousSpeechMs, rms=${currentRms.toInt()}, thr=${threshold.toInt()})")
                try {
                    listener?.onSpeechStart()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onSpeechStart: ${e.message}")
                }
            }
        } else {
            continuousSilenceMs += frameDurationMs
            continuousSpeechMs = 0L

            // Smooth background noise floor during silence periods
            noiseFloorRms = (noiseFloorRms * 0.95) + (currentRms * 0.05)

            if (inSpeechState && continuousSilenceMs >= endpointSilenceMs) {
                inSpeechState = false
                isVoiceActive = false
                Log.i(TAG, "t-endpoint (silenceMs=$continuousSilenceMs, rms=${currentRms.toInt()}, thr=${threshold.toInt()})")
                try {
                    listener?.onSpeechEndpoint()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onSpeechEndpoint: ${e.message}")
                }
            }
        }

        return inSpeechState
    }

    fun reset() {
        inSpeechState = false
        isVoiceActive = false
        continuousSpeechMs = 0L
        continuousSilenceMs = 0L
        noiseFloorRms = DEFAULT_NOISE_FLOOR
    }

    companion object {
        private const val TAG = "MiraVAD"
        const val VAD_SPEECH_TRIGGER_MS = 150L
        const val VAD_ENDPOINT_SILENCE_MS = 300L
        private const val DEFAULT_NOISE_FLOOR = 350.0
        private const val MIN_THRESHOLD = 600.0
        private const val THRESHOLD_MULTIPLIER = 1.8
    }
}

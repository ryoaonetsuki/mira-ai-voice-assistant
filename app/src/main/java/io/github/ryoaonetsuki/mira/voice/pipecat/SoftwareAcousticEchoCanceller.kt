package io.github.ryoaonetsuki.mira.voice.pipecat

import android.util.Log
import java.util.ArrayDeque

/**
 * Interface representing an Acoustic Echo Canceller (AEC) DSP engine.
 */
interface AcousticEchoCancellerEngine {
    /**
     * Processes near-end mic samples against far-end reference samples.
     *
     * @param nearEnd Input microphone PCM samples (containing near-end speech + far-end echo).
     * @param farEnd Reference playback PCM samples (from loudspeaker output).
     * @param out Output buffer where echo-cancelled samples are written.
     * @param length Number of samples to process (typically a multiple of frame size).
     */
    fun process(nearEnd: ShortArray, farEnd: ShortArray, out: ShortArray, length: Int)

    /** Resets the adaptive filter weights and convergence state. */
    fun reset()

    /** Releases any underlying native or memory resources. */
    fun release()
}

/**
 * Interface representing an audio resampler.
 */
interface AudioResampler {
    /**
     * Resamples 16-bit mono PCM samples from [inRate] to [outRate].
     */
    fun resample(input: ShortArray, inRate: Int, outRate: Int): ShortArray

    /** Resets resampler state or history. */
    fun reset()

    /** Releases resampler resources. */
    fun release()
}

/**
 * Native SpeexDSP-backed AEC engine using JNI.
 */
internal class NativeSpeexAecEngine(
    private val nativeHandle: Long,
    val frameSize: Int = 160
) : AcousticEchoCancellerEngine {

    override fun process(nearEnd: ShortArray, farEnd: ShortArray, out: ShortArray, length: Int) {
        val res = SpeexAecNative.nativeProcess(
            handle = nativeHandle,
            nearEnd = nearEnd,
            farEnd = farEnd,
            out = out,
            length = length
        )
        if (res != 0) {
            // Bypass cleanly on error without dropping audio
            System.arraycopy(nearEnd, 0, out, 0, length)
        }
    }

    override fun reset() {
        SpeexAecNative.nativeReset(nativeHandle)
    }

    override fun release() {
        SpeexAecNative.nativeDestroy(nativeHandle)
    }
}

/**
 * Native SpeexDSP-backed resampler using JNI.
 */
internal class NativeSpeexResampler(
    private val nativeHandle: Long
) : AudioResampler {

    override fun resample(input: ShortArray, inRate: Int, outRate: Int): ShortArray {
        if (input.isEmpty()) return ShortArray(0)
        val outCapacity = ((input.size.toLong() * outRate) / inRate + 32).toInt()
        val out = ShortArray(outCapacity)
        val produced = SpeexAecNative.nativeResample(
            handle = nativeHandle,
            input = input,
            inLen = input.size,
            output = out,
            outCapacity = outCapacity
        )
        return if (produced > 0) out.copyOf(produced) else ShortArray(0)
    }

    override fun reset() {}

    override fun release() {}
}

/**
 * Pass-through AEC engine used when the native SpeexDSP library is unavailable.
 *
 * Copies near-end microphone samples directly to output unchanged without cancellation.
 * The downstream EchoGate remains the defense-in-depth protection in this fallback mode.
 */
class PassThroughAecEngine : AcousticEchoCancellerEngine {
    override fun process(nearEnd: ShortArray, farEnd: ShortArray, out: ShortArray, length: Int) {
        System.arraycopy(nearEnd, 0, out, 0, length)
    }

    override fun reset() {}

    override fun release() {}
}

/**
 * Pure-Kotlin linear interpolation audio resampler.
 */
class LinearAudioResampler : AudioResampler {
    override fun resample(input: ShortArray, inRate: Int, outRate: Int): ShortArray {
        if (inRate == outRate || input.isEmpty()) return input.copyOf()
        val outLen = ((input.size.toLong() * outRate) / inRate).toInt()
        val out = ShortArray(outLen)
        val ratio = inRate.toDouble() / outRate.toDouble()
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx
            val s0 = input[srcIdx.coerceIn(0, input.size - 1)].toDouble()
            val s1 = input[(srcIdx + 1).coerceIn(0, input.size - 1)].toDouble()
            val interpolated = s0 + frac * (s1 - s0)
            out[i] = interpolated.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    override fun reset() {}

    override fun release() {}
}

/**
 * Complete software Acoustic Echo Canceller coordinating far-end playback reference buffering,
 * sample-rate conversion (24 kHz -> 16 kHz), frame chunking (160 samples / 10 ms), clock-drift
 * handling, and adaptive echo subtraction.
 */
class SoftwareAcousticEchoCanceller(
    val engine: AcousticEchoCancellerEngine,
    val resampler: AudioResampler,
    val sampleRateHz: Int = 16000,
    val playbackSampleRateHz: Int = 24000,
    val frameSizeSamples: Int = 160,
    val maxBufferSamples: Int = 8000 // 500ms max far-end drift buffer
) {
    companion object {
        private const val TAG = "SoftwareAEC"

        /**
         * Creates an operational [SoftwareAcousticEchoCanceller] using SpeexDSP native code
         * when available, or falling back gracefully to pass-through without throwing.
         */
        fun create(
            sampleRateHz: Int = 16000,
            playbackSampleRateHz: Int = 24000,
            frameSizeSamples: Int = 160,
            filterLengthSamples: Int = 3200
        ): SoftwareAcousticEchoCanceller {
            if (SpeexAecNative.isAvailable()) {
                try {
                    val handle = SpeexAecNative.nativeCreate(
                        sampleRate = sampleRateHz,
                        frameSize = frameSizeSamples,
                        filterLength = filterLengthSamples,
                        resampleInRate = playbackSampleRateHz
                    )
                    if (handle != 0L) {
                        Log.i(TAG, "Initialized native SpeexDSP AEC engine (handle=$handle)")
                        return SoftwareAcousticEchoCanceller(
                            engine = NativeSpeexAecEngine(handle, frameSizeSamples),
                            resampler = NativeSpeexResampler(handle),
                            sampleRateHz = sampleRateHz,
                            playbackSampleRateHz = playbackSampleRateHz,
                            frameSizeSamples = frameSizeSamples
                        )
                    } else {
                        Log.w(TAG, "nativeCreate returned 0, falling back to pass-through (no echo cancellation, EchoGate only)")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to initialize Speex native AEC: ${t.message}", t)
                }
            } else {
                Log.i(TAG, "Speex native library not available, falling back to pass-through (no echo cancellation, EchoGate only)")
            }

            return SoftwareAcousticEchoCanceller(
                engine = PassThroughAecEngine(),
                resampler = LinearAudioResampler(),
                sampleRateHz = sampleRateHz,
                playbackSampleRateHz = playbackSampleRateHz,
                frameSizeSamples = frameSizeSamples
            )
        }
    }

    private val farEndLock = Any()
    private val farEndBuffer = ArrayDeque<Short>()

    /**
     * Enqueues playback audio chunks from the speaker (far-end reference).
     * Resamples from [playbackSampleRateHz] to [sampleRateHz] and appends to the reference FIFO.
     */
    fun pushPlaybackAudio(pcm24k: ByteArray) {
        if (pcm24k.isEmpty()) return
        val numSamples = pcm24k.size / 2
        val shorts24k = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val low = pcm24k[i * 2].toInt() and 0xFF
            val high = pcm24k[i * 2 + 1].toInt() and 0xFF
            shorts24k[i] = ((high shl 8) or low).toShort()
        }

        val shorts16k = resampler.resample(shorts24k, playbackSampleRateHz, sampleRateHz)

        synchronized(farEndLock) {
            for (s in shorts16k) {
                farEndBuffer.addLast(s)
            }
            // Discard oldest if far-end playback buffer exceeds max drift capacity
            while (farEndBuffer.size > maxBufferSamples) {
                farEndBuffer.removeFirst()
            }
        }
    }

    /**
     * Processes near-end microphone PCM audio (16 kHz mono) through the AEC.
     * Subtracts the far-end echo reference and returns the cleaned audio PCM byte array.
     */
    fun processMicrophoneAudio(micPcm16k: ByteArray): ByteArray {
        val numSamples = micPcm16k.size / 2
        if (numSamples == 0) return micPcm16k

        val nearEnd = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val low = micPcm16k[i * 2].toInt() and 0xFF
            val high = micPcm16k[i * 2 + 1].toInt() and 0xFF
            nearEnd[i] = ((high shl 8) or low).toShort()
        }

        val farEnd = ShortArray(numSamples)
        synchronized(farEndLock) {
            val available = minOf(farEndBuffer.size, numSamples)
            for (i in 0 until available) {
                farEnd[i] = farEndBuffer.removeFirst()
            }
            // Remaining samples default to 0 (silence)
        }

        val out = ShortArray(numSamples)

        // Process in multiples of frameSizeSamples
        val processable = (numSamples / frameSizeSamples) * frameSizeSamples
        if (processable > 0) {
            engine.process(nearEnd, farEnd, out, processable)
        }
        // Copy remainder directly if any
        if (processable < numSamples) {
            System.arraycopy(nearEnd, processable, out, processable, numSamples - processable)
        }

        val cleanedBytes = ByteArray(numSamples * 2)
        for (i in 0 until numSamples) {
            val sample = out[i].toInt()
            cleanedBytes[i * 2] = (sample and 0xFF).toByte()
            cleanedBytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }

        return cleanedBytes
    }

    /**
     * Resets the adaptive filter and clears the far-end reference queue.
     * Called on interruption, turn complete, or session start/stop.
     */
    fun reset() {
        synchronized(farEndLock) {
            farEndBuffer.clear()
        }
        try {
            engine.reset()
            resampler.reset()
        } catch (t: Throwable) {
            Log.w(TAG, "Error resetting AEC: ${t.message}")
        }
    }

    /**
     * Releases all native/allocated resources.
     */
    fun release() {
        reset()
        try {
            engine.release()
            resampler.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Error releasing AEC: ${t.message}")
        }
    }
}

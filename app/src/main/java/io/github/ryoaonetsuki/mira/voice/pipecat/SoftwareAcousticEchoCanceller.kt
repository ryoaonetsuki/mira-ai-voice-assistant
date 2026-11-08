package io.github.ryoaonetsuki.mira.voice.pipecat

import android.os.SystemClock
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
    private var nativeHandle: Long,
    val frameSize: Int = 160
) : AcousticEchoCancellerEngine {

    override fun process(nearEnd: ShortArray, farEnd: ShortArray, out: ShortArray, length: Int) {
        val handle = nativeHandle
        if (handle == 0L) {
            System.arraycopy(nearEnd, 0, out, 0, length)
            return
        }
        val res = SpeexAecNative.nativeProcess(
            handle = handle,
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
        val handle = nativeHandle
        if (handle != 0L) {
            SpeexAecNative.nativeReset(handle)
        }
    }

    override fun release() {
        val handle = nativeHandle
        if (handle != 0L) {
            nativeHandle = 0L
            SpeexAecNative.nativeDestroy(handle)
        }
    }
}

/**
 * Native SpeexDSP-backed resampler using JNI.
 */
internal class NativeSpeexResampler(
    private var nativeHandle: Long
) : AudioResampler {

    override fun resample(input: ShortArray, inRate: Int, outRate: Int): ShortArray {
        val handle = nativeHandle
        if (handle == 0L || input.isEmpty()) return ShortArray(0)
        val outCapacity = ((input.size.toLong() * outRate) / inRate + 32).toInt()
        val out = ShortArray(outCapacity)
        val produced = SpeexAecNative.nativeResample(
            handle = handle,
            input = input,
            inLen = input.size,
            output = out,
            outCapacity = outCapacity
        )
        return if (produced > 0) out.copyOf(produced) else ShortArray(0)
    }

    override fun reset() {}

    override fun release() {
        nativeHandle = 0L
    }
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
 * handling, delay compensation, and adaptive echo subtraction.
 */
class SoftwareAcousticEchoCanceller(
    val engine: AcousticEchoCancellerEngine,
    val resampler: AudioResampler,
    val sampleRateHz: Int = 16000,
    val playbackSampleRateHz: Int = 24000,
    val frameSizeSamples: Int = 160,
    val maxBufferSamples: Int = 4800, // ~300ms bounded buffer at 16 kHz
    referenceDelayMs: Int = 0,
    private val clock: () -> Long = {
        try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.nanoTime() / 1_000_000L
        }
    }
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
            filterLengthSamples: Int = 3200,
            defaultReferenceDelayMs: Int = 120
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
                        try { Log.i(TAG, "Initialized native SpeexDSP AEC engine (handle=$handle)") } catch (_: Throwable) {}
                        return SoftwareAcousticEchoCanceller(
                            engine = NativeSpeexAecEngine(handle, frameSizeSamples),
                            resampler = NativeSpeexResampler(handle),
                            sampleRateHz = sampleRateHz,
                            playbackSampleRateHz = playbackSampleRateHz,
                            frameSizeSamples = frameSizeSamples,
                            referenceDelayMs = defaultReferenceDelayMs
                        )
                    } else {
                        try { Log.w(TAG, "nativeCreate returned 0, falling back to pass-through (no echo cancellation, EchoGate only)") } catch (_: Throwable) {}
                    }
                } catch (t: Throwable) {
                    try { Log.w(TAG, "Failed to initialize Speex native AEC: ${t.message}", t) } catch (_: Throwable) {}
                }
            } else {
                try { Log.i(TAG, "Speex native library not available, falling back to pass-through (no echo cancellation, EchoGate only)") } catch (_: Throwable) {}
            }

            return SoftwareAcousticEchoCanceller(
                engine = PassThroughAecEngine(),
                resampler = LinearAudioResampler(),
                sampleRateHz = sampleRateHz,
                playbackSampleRateHz = playbackSampleRateHz,
                frameSizeSamples = frameSizeSamples,
                referenceDelayMs = defaultReferenceDelayMs
            )
        }
    }

    @Volatile
    var referenceDelayMs: Int = referenceDelayMs.coerceIn(0, 300)
        set(value) {
            field = value.coerceIn(0, 300)
        }

    @Volatile
    private var released: Boolean = false

    fun isReleased(): Boolean = released

    val engineType: String = when (engine) {
        is NativeSpeexAecEngine -> "NATIVE_SPEEX"
        is PassThroughAecEngine -> "PASSTHROUGH_FALLBACK"
        else -> engine.javaClass.simpleName
    }

    private data class DelayedChunk(
        val readyTimeMs: Long,
        val samples: ShortArray
    )

    private val farEndLock = Any()
    private val delayedChunks = ArrayDeque<DelayedChunk>()
    private val consumableBuffer = ArrayDeque<Short>()
    private var nextPlaybackStartTimeMs: Long = 0L

    internal fun getNextPlaybackStartTimeMs(): Long = synchronized(farEndLock) { nextPlaybackStartTimeMs }

    /**
     * Enqueues playback audio chunks from the speaker (far-end reference).
     * Resamples from [playbackSampleRateHz] to [sampleRateHz] and schedules into the reference delay line.
     * Safe no-op after [release].
     */
    fun pushPlaybackAudio(pcm24k: ByteArray) {
        if (released || pcm24k.isEmpty()) return
        val numSamples = pcm24k.size / 2
        val shorts24k = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val low = pcm24k[i * 2].toInt() and 0xFF
            val high = pcm24k[i * 2 + 1].toInt() and 0xFF
            shorts24k[i] = ((high shl 8) or low).toShort()
        }

        val shorts16k = resampler.resample(shorts24k, playbackSampleRateHz, sampleRateHz)
        if (shorts16k.isEmpty()) return

        val now = clock()
        val delay = referenceDelayMs
        val earliestStart = now + delay
        val chunkDurationMs = (shorts16k.size.toLong() * 1000L) / sampleRateHz

        synchronized(farEndLock) {
            if (released) return
            val readyAt = if (delay <= 0) {
                0L
            } else {
                val start = if (earliestStart > nextPlaybackStartTimeMs) earliestStart else nextPlaybackStartTimeMs
                nextPlaybackStartTimeMs = start + chunkDurationMs
                start
            }

            if (delay <= 0 || readyAt <= 0L) {
                for (s in shorts16k) {
                    consumableBuffer.addLast(s)
                }
            } else {
                delayedChunks.addLast(DelayedChunk(readyAt, shorts16k))
            }
        }
    }

    /**
     * Processes near-end microphone PCM audio (16 kHz mono) through the AEC.
     * Subtracts the delay-compensated far-end echo reference and returns cleaned audio PCM bytes.
     * Safe no-op after [release] (returns input unchanged).
     */
    fun processMicrophoneAudio(micPcm16k: ByteArray): ByteArray {
        if (released || micPcm16k.isEmpty()) return micPcm16k

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
            if (released) return micPcm16k

            val now = clock()
            // Transfer ready chunks from delayedChunks to consumableBuffer
            while (delayedChunks.isNotEmpty()) {
                val head = delayedChunks.peekFirst() ?: break
                if (head.readyTimeMs <= now) {
                    val chunk = delayedChunks.removeFirst()
                    for (s in chunk.samples) {
                        consumableBuffer.addLast(s)
                    }
                } else {
                    break
                }
            }

            // Gradual drift compensation: drop at most one frame (frameSizeSamples) per mic frame when overfull
            if (consumableBuffer.size > maxBufferSamples) {
                val dropCount = minOf(consumableBuffer.size - maxBufferSamples, frameSizeSamples)
                repeat(dropCount) {
                    consumableBuffer.removeFirst()
                }
            }

            val available = minOf(consumableBuffer.size, numSamples)
            for (i in 0 until available) {
                farEnd[i] = consumableBuffer.removeFirst()
            }
            // Remaining samples in farEnd default to 0 (underflow padding zeros)
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
     * Resets the adaptive filter and clears far-end reference queues.
     * Safe no-op after [release].
     */
    fun reset() {
        if (released) return
        synchronized(farEndLock) {
            delayedChunks.clear()
            consumableBuffer.clear()
            nextPlaybackStartTimeMs = 0L
        }
        try {
            engine.reset()
            resampler.reset()
        } catch (t: Throwable) {
            try { Log.w(TAG, "Error resetting AEC: ${t.message}") } catch (_: Throwable) {}
        }
    }

    /**
     * Releases all native/allocated resources. Idempotent: repeated calls are safe no-ops.
     */
    fun release() {
        if (released) return
        synchronized(this) {
            if (released) return
            released = true
        }
        synchronized(farEndLock) {
            delayedChunks.clear()
            consumableBuffer.clear()
            nextPlaybackStartTimeMs = 0L
        }
        try {
            engine.release()
            resampler.release()
        } catch (t: Throwable) {
            try { Log.w(TAG, "Error releasing AEC: ${t.message}") } catch (_: Throwable) {}
        }
    }
}

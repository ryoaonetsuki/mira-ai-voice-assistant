package io.github.ryoaonetsuki.mira.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming AudioTrack player for Gemini Live 24 kHz PCM16 mono audio (ROUND 12 spec §3.2).
 * Operates with a bounded queue and supports low latency where available.
 * Implements instant barge-in flushing on user speech or server interruption.
 */
class AudioPlayer(
    private val onSpeakingStateChanged: (Boolean) -> Unit
) {
    private var audioTrack: AudioTrack? = null
    // Bounded queue to prevent unbounded memory growth if playback stalls
    private val audioQueue = LinkedBlockingQueue<ByteArray>(200)
    private val isPlaying = AtomicBoolean(false)
    private var playbackThread: Thread? = null

    @Volatile
    private var isSpeakingState: Boolean = false

    fun isSpeaking(): Boolean = isSpeakingState

    private var totalAudioBytesWritten: Long = 0L
    private var totalAudioChunksWritten: Long = 0L

    var lastPlaybackDiag: String = "chunks=0, bytes=0"
        private set

    init {
        initAudioTrack()
    }

    private fun initAudioTrack(): Boolean {
        if (isPlaying.get() && audioTrack != null && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
            return true
        }
        try {
            val sampleRate = SAMPLE_RATE
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBufferSize, 8192)

            val trackBuilder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            val track = trackBuilder.build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "MIRA_LIVE_VOICE: AudioTrack init failed (state != STATE_INITIALIZED: ${track.state})")
                try { track.release() } catch (_: Exception) {}
                audioTrack = null
                return false
            }

            // Pre-primed: play() called immediately
            track.play()
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                Log.e(TAG, "MIRA_LIVE_VOICE: AudioTrack.play() failed (playState=${track.playState})")
                try { track.release() } catch (_: Exception) {}
                audioTrack = null
                return false
            }

            audioTrack = track
            startPlaybackLoop()
            Log.i(TAG, "MIRA_LIVE_VOICE: AudioTrack initialized at 24kHz mono (low-latency streaming)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "MIRA_LIVE_VOICE: Failed to init AudioTrack: ${e.message}", e)
            audioTrack = null
            return false
        }
    }

    private fun startPlaybackLoop() {
        if (playbackThread != null && playbackThread?.isAlive == true) return
        isPlaying.set(true)
        playbackThread = Thread({
            while (isPlaying.get()) {
                try {
                    if (!isSpeakingState) {
                        // Wait for first chunk of an utterance
                        val firstChunk = audioQueue.take()
                        if (firstChunk.isEmpty()) continue

                        // Short pre-buffer (wait up to 150ms or 4,800 bytes for smooth start)
                        val startMs = System.currentTimeMillis()
                        val batch = mutableListOf<ByteArray>()
                        batch.add(firstChunk)
                        var batchBytes = firstChunk.size

                        while (batchBytes < PREBUFFER_MAX_BYTES) {
                            val elapsed = System.currentTimeMillis() - startMs
                            val remaining = PREBUFFER_TIMEOUT_MS - elapsed
                            if (remaining <= 0) break

                            val next = audioQueue.poll(remaining, TimeUnit.MILLISECONDS)
                            if (next != null && next.isNotEmpty()) {
                                batch.add(next)
                                batchBytes += next.size
                            } else {
                                break
                            }
                        }

                        isSpeakingState = true
                        onSpeakingStateChanged(true)
                        Log.i(TAG, "MIRA_LIVE_VOICE: Playback started (prebuffer=${System.currentTimeMillis() - startMs}ms, bytes=$batchBytes)")

                        for (chunk in batch) {
                            if (!isPlaying.get() || !isSpeakingState) break
                            writeChunkToTrack(chunk)
                        }
                    } else {
                        // Mid-utterance: poll with 250ms grace period
                        val chunk = audioQueue.poll(250, TimeUnit.MILLISECONDS)
                        if (chunk == null) {
                            isSpeakingState = false
                            onSpeakingStateChanged(false)
                            Log.d(TAG, "MIRA_LIVE_VOICE: Utterance playback ended (queue drained)")
                            continue
                        }
                        if (chunk.isNotEmpty()) {
                            writeChunkToTrack(chunk)
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "MIRA_LIVE_VOICE: Error in AudioPlayer loop: ${e.message}", e)
                }
            }
            if (isSpeakingState) {
                isSpeakingState = false
                onSpeakingStateChanged(false)
            }
        }, "MiraAudioPlaybackThread")
        playbackThread?.start()
    }

    private fun writeChunkToTrack(chunk: ByteArray) {
        val track = audioTrack
        if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
            val written = track.write(chunk, 0, chunk.size)
            totalAudioChunksWritten++
            if (written >= 0) {
                totalAudioBytesWritten += written
                lastPlaybackDiag = "chunks=$totalAudioChunksWritten, bytes=$totalAudioBytesWritten"
            } else {
                val errMeaning = when (written) {
                    AudioTrack.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                    AudioTrack.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                    AudioTrack.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                    AudioTrack.ERROR -> "ERROR"
                    else -> "UNKNOWN ($written)"
                }
                Log.e(TAG, "MIRA_LIVE_VOICE: AudioTrack.write error: $errMeaning ($written)")
            }
        }
    }

    fun playAudioChunk(pcmData: ByteArray) {
        if (!isPlaying.get() || audioTrack == null) {
            val initialized = initAudioTrack()
            if (!initialized) return
        }
        audioQueue.offer(pcmData)
    }

    /**
     * Instantly halts audio playback, clears queued PCM frames, and resets track for barge-in.
     */
    fun stopImmediatelyAndFlush() {
        audioQueue.clear()
        if (isSpeakingState) {
            isSpeakingState = false
            onSpeakingStateChanged(false)
        }
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
            Log.i(TAG, "MIRA_LIVE_VOICE: AudioTrack playback flushed (barge-in)")
        } catch (e: Exception) {
            Log.e(TAG, "MIRA_LIVE_VOICE: Error flushing AudioTrack: ${e.message}")
        }
    }

    fun release() {
        isPlaying.set(false)
        audioQueue.clear()
        if (isSpeakingState) {
            isSpeakingState = false
            onSpeakingStateChanged(false)
        }
        try {
            playbackThread?.interrupt()
            playbackThread = null
        } catch (_: Exception) {}

        val track = audioTrack
        audioTrack = null

        if (track != null) {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "MIRA_LIVE_VOICE: Error stopping AudioTrack: ${e.message}")
            } finally {
                try {
                    track.release()
                } catch (e: Exception) {
                    Log.w(TAG, "MIRA_LIVE_VOICE: Error releasing AudioTrack: ${e.message}")
                }
            }
        }
        Log.i(TAG, "MIRA_LIVE_VOICE: AudioPlayer released ($totalAudioChunksWritten chunks, $totalAudioBytesWritten bytes)")
    }

    /**
     * Preview-only: Prepares AudioTrack for voice preview playback.
     * Primes the track by setting initial volume to 0.0 to eliminate onset pop,
     * then ramps volume 0 -> 1 over ~80ms in a background thread.
     */
    fun beginPreview() {
        try {
            audioTrack?.setVolume(0.0f)
        } catch (e: Exception) {
            Log.w(TAG, "AudioPlayer.beginPreview: failed to set volume to 0: ${e.message}")
        }
        Thread({
            val steps = 10
            val stepDelay = 8L // ~80ms total
            for (i in 1..steps) {
                if (!isPlaying.get()) break
                val vol = i.toFloat() / steps
                try {
                    audioTrack?.setVolume(vol)
                } catch (_: Exception) {}
                try {
                    Thread.sleep(stepDelay)
                } catch (_: InterruptedException) {
                    break
                }
            }
            if (isPlaying.get()) {
                try {
                    audioTrack?.setVolume(1.0f)
                } catch (_: Exception) {}
            }
        }, "MiraPreviewFadeInThread").start()
    }

    /**
     * Preview-only: Quickly fades volume down to 0 over [durationMs] (default 40ms)
     * to eliminate clicks/pops before stopImmediatelyAndFlush() and release() are invoked.
     */
    fun fadeOutPreview(durationMs: Long = 40L) {
        val track = audioTrack ?: return
        if (track.state != AudioTrack.STATE_INITIALIZED || track.playState != AudioTrack.PLAYSTATE_PLAYING) return
        try {
            val steps = 5
            val stepDelay = maxOf(1L, durationMs / steps)
            for (i in (steps - 1) downTo 0) {
                track.setVolume(i.toFloat() / steps)
                try {
                    Thread.sleep(stepDelay)
                } catch (_: InterruptedException) {
                    break
                }
            }
            track.setVolume(0.0f)
        } catch (_: Exception) {}
    }

    /**
     * Preview-only: Fades out quickly first, then stopImmediatelyAndFlush() + release().
     * Used when the user stops early, selects another voice, or the screen is disposed.
     */
    fun stopPreviewCleanly(fadeDurationMs: Long = 40L) {
        fadeOutPreview(fadeDurationMs)
        stopImmediatelyAndFlush()
        release()
    }

    /**
     * Preview-only: On natural end (all chunks fed), waits for the audio queue and AudioTrack buffer
     * to drain, fades volume 1 -> 0 over ~100ms, then stops, flushes, and releases the track.
     * Never leaves the AudioTrack running after the preview ends.
     */
    suspend fun endPreviewCleanly() = withContext(Dispatchers.IO) {
        val track = audioTrack
        try {
            // 1. Wait for audioQueue to drain
            val queueDrainTimeoutMs = 15000L
            val startQueueWait = System.currentTimeMillis()
            while (audioQueue.isNotEmpty() && isPlaying.get()) {
                if (System.currentTimeMillis() - startQueueWait > queueDrainTimeoutMs) break
                delay(20)
            }

            // 2. Wait for AudioTrack buffer to drain
            if (track != null && track.state == AudioTrack.STATE_INITIALIZED && isPlaying.get()) {
                val totalFrames = totalAudioBytesWritten / 2
                val trackDrainTimeoutMs = 5000L
                val startTrackWait = System.currentTimeMillis()
                while (isPlaying.get() && (System.currentTimeMillis() - startTrackWait) < trackDrainTimeoutMs) {
                    val currentHead = try {
                        track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    } catch (_: Exception) {
                        totalFrames
                    }
                    if (currentHead >= totalFrames) {
                        break
                    }
                    delay(25)
                }

                // 3. Fade volume 1 -> 0 over ~100ms
                val steps = 10
                val stepDelay = 10L // 100ms total
                for (i in (steps - 1) downTo 0) {
                    if (!isPlaying.get()) break
                    try {
                        track.setVolume(i.toFloat() / steps)
                    } catch (_: Exception) {}
                    delay(stepDelay)
                }
                try {
                    track.setVolume(0.0f)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "MIRA_LIVE_VOICE: Error during endPreviewCleanly: ${e.message}")
        } finally {
            // 4. Stop + flush + release
            stopImmediatelyAndFlush()
            release()
        }
    }

    companion object {
        private const val TAG = "MIRA_LIVE_VOICE"
        const val SAMPLE_RATE = 24000
        const val PREBUFFER_MAX_BYTES = 4800 // ~100ms at 24kHz PCM16 mono
        const val PREBUFFER_TIMEOUT_MS = 150L

        /**
         * Preview-only helper: Applies linear fade-in and fade-out ramps directly to 24kHz PCM16 mono samples.
         * Math:
         * - Fade-in: first [fadeInDurationMs] (default 80ms = 1920 samples) ramp linearly from 0.0 to 1.0.
         * - Fade-out: last [fadeOutDurationMs] (default 100ms = 2400 samples) ramp linearly from 1.0 to 0.0.
         * Eliminates DC offset clicks at onset and trailing waveform pops at cutoff.
         */
        fun applyPreviewFadeRamps(
            pcmData: ByteArray,
            fadeInDurationMs: Int = 80,
            fadeOutDurationMs: Int = 100,
            sampleRate: Int = SAMPLE_RATE
        ): ByteArray {
            if (pcmData.size < 4) return pcmData
            val result = pcmData.clone()
            val bytesPerSample = 2 // 16-bit mono
            val totalSamples = result.size / bytesPerSample

            // Fade-in ramp: 0.0 -> 1.0
            val fadeInSamples = minOf(totalSamples / 2, (sampleRate * fadeInDurationMs) / 1000)
            if (fadeInSamples > 0) {
                for (i in 0 until fadeInSamples) {
                    val factor = i.toFloat() / fadeInSamples
                    val offset = i * bytesPerSample
                    val low = result[offset].toInt() and 0xFF
                    val high = result[offset + 1].toInt()
                    val sample = ((high shl 8) or low).toShort()
                    val ramped = (sample * factor).toInt().coerceIn(-32768, 32767).toShort()
                    result[offset] = (ramped.toInt() and 0xFF).toByte()
                    result[offset + 1] = ((ramped.toInt() shr 8) and 0xFF).toByte()
                }
            }

            // Fade-out ramp: 1.0 -> 0.0
            val fadeOutSamples = minOf(totalSamples / 2, (sampleRate * fadeOutDurationMs) / 1000)
            if (fadeOutSamples > 0) {
                val startIdx = totalSamples - fadeOutSamples
                for (i in 0 until fadeOutSamples) {
                    val factor = 1.0f - (i.toFloat() / fadeOutSamples)
                    val offset = (startIdx + i) * bytesPerSample
                    val low = result[offset].toInt() and 0xFF
                    val high = result[offset + 1].toInt()
                    val sample = ((high shl 8) or low).toShort()
                    val ramped = (sample * factor).toInt().coerceIn(-32768, 32767).toShort()
                    result[offset] = (ramped.toInt() and 0xFF).toByte()
                    result[offset + 1] = ((ramped.toInt() shr 8) and 0xFF).toByte()
                }
            }

            return result
        }
    }
}

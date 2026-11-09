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
 * Controller abstraction over android.media.AudioTrack, allowing clean unit testing of
 * playback lifecycle, barge-in, and teardown sequences without a real audio hardware HAL.
 */
interface AudioTrackController {
    val state: Int
    val playState: Int
    val playbackHeadPosition: Long
    fun play()
    fun pause()
    fun flush()
    fun stop()
    fun release()
    fun write(buffer: ByteArray, offset: Int, size: Int, writeMode: Int = 0): Int
    fun setVolume(gain: Float): Int

    companion object {
        const val STATE_UNINITIALIZED = AudioTrack.STATE_UNINITIALIZED
        const val STATE_INITIALIZED = AudioTrack.STATE_INITIALIZED

        const val PLAYSTATE_STOPPED = AudioTrack.PLAYSTATE_STOPPED
        const val PLAYSTATE_PAUSED = AudioTrack.PLAYSTATE_PAUSED
        const val PLAYSTATE_PLAYING = AudioTrack.PLAYSTATE_PLAYING
    }
}

private class AndroidAudioTrackController(private val track: AudioTrack) : AudioTrackController {
    override val state: Int get() = track.state
    override val playState: Int get() = track.playState
    override val playbackHeadPosition: Long
        get() = try {
            track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        } catch (_: Exception) {
            -1L
        }
    override fun play() = track.play()
    override fun pause() = track.pause()
    override fun flush() = track.flush()
    override fun stop() = track.stop()
    override fun release() = track.release()
    override fun write(buffer: ByteArray, offset: Int, size: Int, writeMode: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && writeMode == AudioTrack.WRITE_NON_BLOCKING) {
            track.write(buffer, offset, size, AudioTrack.WRITE_NON_BLOCKING)
        } else {
            track.write(buffer, offset, size)
        }
    override fun setVolume(gain: Float): Int = track.setVolume(gain)
}

/**
 * Streaming AudioTrack player for Gemini Live 24 kHz PCM16 mono audio (ROUND 12 spec §3.2).
 * Operates with a bounded queue and supports low latency where available.
 * Implements instant barge-in flushing on user speech or server interruption.
 */
class AudioPlayer(
    private val onSpeakingStateChanged: (Boolean) -> Unit,
    private val controllerFactory: (() -> AudioTrackController?)? = null
) {

    private var audioTrackController: AudioTrackController? = null
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

    fun getPlayState(): Int = audioTrackController?.playState ?: AudioTrackController.PLAYSTATE_STOPPED

    private fun playStateToString(state: Int): String = when (state) {
        AudioTrackController.PLAYSTATE_PLAYING -> "PLAYING"
        AudioTrackController.PLAYSTATE_PAUSED -> "PAUSED"
        AudioTrackController.PLAYSTATE_STOPPED -> "STOPPED"
        else -> "UNKNOWN($state)"
    }

    init {
        initAudioTrack()
    }

    private fun initAudioTrack(): Boolean {
        if (isPlaying.get() && audioTrackController != null && audioTrackController?.state == AudioTrackController.STATE_INITIALIZED) {
            return true
        }
        if (controllerFactory != null) {
            try {
                val controller = controllerFactory.invoke()
                if (controller == null || controller.state != AudioTrackController.STATE_INITIALIZED) {
                    audioTrackController = null
                    return false
                }
                controller.play()
                if (controller.playState != AudioTrackController.PLAYSTATE_PLAYING) {
                    try { controller.release() } catch (_: Exception) {}
                    audioTrackController = null
                    return false
                }
                audioTrackController = controller
                startPlaybackLoop()
                Log.i(TAG, "MIRA_LIVE_VOICE: AudioTrackController initialized via factory")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "MIRA_LIVE_VOICE: Failed to init AudioTrackController from factory: ${e.message}", e)
                audioTrackController = null
                return false
            }
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
                audioTrackController = null
                return false
            }

            // Pre-primed: play() called immediately
            track.play()
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                Log.e(TAG, "MIRA_LIVE_VOICE: AudioTrack.play() failed (playState=${track.playState})")
                try { track.release() } catch (_: Exception) {}
                audioTrackController = null
                return false
            }

            audioTrackController = AndroidAudioTrackController(track)
            startPlaybackLoop()
            Log.i(TAG, "MIRA_LIVE_VOICE: AudioTrack initialized at 24kHz mono (low-latency streaming)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "MIRA_LIVE_VOICE: Failed to init AudioTrack: ${e.message}", e)
            audioTrackController = null
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
        val controller = audioTrackController
        if (controller != null && controller.state == AudioTrackController.STATE_INITIALIZED) {
            val written = controller.write(chunk, 0, chunk.size)
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
        if (!isPlaying.get() || audioTrackController == null) {
            val initialized = initAudioTrack()
            if (!initialized) return
        }
        audioQueue.offer(pcmData)
    }

    /**
     * Instantly halts audio playback, clears queued PCM frames, and resets track for barge-in.
     * When [restartTrack] is true (default, Live barge-in), the track is restarted in PLAYING state.
     * When [restartTrack] is false (preview teardown), the track is stopped without restarting.
     */
    fun stopImmediatelyAndFlush(restartTrack: Boolean = true) {
        val t0 = System.currentTimeMillis()
        audioQueue.clear()
        if (isSpeakingState) {
            isSpeakingState = false
            onSpeakingStateChanged(false)
        }
        val controller = audioTrackController
        val initialPlayState = controller?.playState?.let { playStateToString(it) } ?: "NULL"
        Log.i(TAG, "MIRA_LIVE_VOICE: stopImmediatelyAndFlush(restart=$restartTrack) ENTER: playState=$initialPlayState [elapsed=0ms]")
        try {
            controller?.pause()
            controller?.flush()
            if (restartTrack) {
                controller?.play()
                val afterPlayState = controller?.playState?.let { playStateToString(it) } ?: "NULL"
                Log.i(TAG, "MIRA_LIVE_VOICE: stopImmediatelyAndFlush(restart=true) EXIT (barge-in restart): playState=$afterPlayState [elapsed=${System.currentTimeMillis() - t0}ms]")
            } else {
                try {
                    controller?.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "MIRA_LIVE_VOICE: Error stopping AudioTrack during release teardown: ${e.message}")
                }
                val afterStopState = controller?.playState?.let { playStateToString(it) } ?: "NULL"
                Log.i(TAG, "MIRA_LIVE_VOICE: stopImmediatelyAndFlush(restart=false) EXIT (release teardown, stopped): playState=$afterStopState [elapsed=${System.currentTimeMillis() - t0}ms]")
            }
        } catch (e: Exception) {
            Log.e(TAG, "MIRA_LIVE_VOICE: Error flushing AudioTrack (restart=$restartTrack): ${e.message}")
        }
    }

    /**
     * Preview/teardown-only: Pauses, flushes, and stops the AudioTrack without restarting it,
     * leaving it in STOPPED state ready for release().
     */
    fun stopAndFlushForRelease() {
        stopImmediatelyAndFlush(restartTrack = false)
    }

    fun release() {
        val t0 = System.currentTimeMillis()
        val controller = audioTrackController
        val enterPlayState = controller?.playState?.let { playStateToString(it) } ?: "NULL"
        Log.i(TAG, "MIRA_LIVE_VOICE: release() ENTER: playState=$enterPlayState [elapsed=0ms]")

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

        audioTrackController = null

        if (controller != null) {
            try {
                if (controller.playState == AudioTrackController.PLAYSTATE_PLAYING) {
                    controller.stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "MIRA_LIVE_VOICE: Error stopping AudioTrack: ${e.message}")
            } finally {
                try {
                    controller.release()
                } catch (e: Exception) {
                    Log.w(TAG, "MIRA_LIVE_VOICE: Error releasing AudioTrack: ${e.message}")
                }
            }
        }
        val finalPlayState = try {
            controller?.playState?.let { playStateToString(it) } ?: "NULL"
        } catch (_: Exception) {
            "RELEASED"
        }
        Log.i(TAG, "MIRA_LIVE_VOICE: release() EXIT: AudioPlayer released ($totalAudioChunksWritten chunks, $totalAudioBytesWritten bytes), exit playState=$finalPlayState [elapsed=${System.currentTimeMillis() - t0}ms]")
    }

    /**
     * Preview-only: Prepares AudioTrack for voice preview playback.
     * Primes the track by setting initial volume to 0.0 to eliminate onset pop,
     * then ramps volume 0 -> 1 over ~80ms in a background thread.
     */
    fun beginPreview() {
        try {
            audioTrackController?.setVolume(0.0f)
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
                    audioTrackController?.setVolume(vol)
                } catch (_: Exception) {}
                try {
                    Thread.sleep(stepDelay)
                } catch (_: InterruptedException) {
                    break
                }
            }
            if (isPlaying.get()) {
                try {
                    audioTrackController?.setVolume(1.0f)
                } catch (_: Exception) {}
            }
        }, "MiraPreviewFadeInThread").start()
    }

    /**
     * Preview-only: Quickly fades volume down to 0 over [durationMs] (default 40ms)
     * to eliminate clicks/pops before stopAndFlushForRelease() and release() are invoked.
     */
    fun fadeOutPreview(durationMs: Long = 40L) {
        val controller = audioTrackController ?: return
        if (controller.state != AudioTrackController.STATE_INITIALIZED || controller.playState != AudioTrackController.PLAYSTATE_PLAYING) return
        try {
            val steps = 5
            val stepDelay = maxOf(1L, durationMs / steps)
            for (i in (steps - 1) downTo 0) {
                controller.setVolume(i.toFloat() / steps)
                try {
                    Thread.sleep(stepDelay)
                } catch (_: InterruptedException) {
                    break
                }
            }
            controller.setVolume(0.0f)
            // Digital silence frames before stopping so HAL never sees a hard transition
            try {
                val silence = ByteArray(512)
                controller.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    /**
     * Preview-only: Fades out quickly first, then stopAndFlushForRelease() (no restart) + release().
     * Used when the user stops early, selects another voice, or the screen is disposed.
     */
    fun stopPreviewCleanly(fadeDurationMs: Long = 40L) {
        val tStart = System.currentTimeMillis()
        val controller = audioTrackController
        val initialPlayState = controller?.playState?.let { playStateToString(it) } ?: "NULL"
        Log.i(TAG, "MIRA_LIVE_VOICE: stopPreviewCleanly: START (fade=${fadeDurationMs}ms, playState=$initialPlayState) [elapsed=0ms]")
        fadeOutPreview(fadeDurationMs)
        val tFadeDone = System.currentTimeMillis() - tStart
        val fadePlayState = controller?.playState?.let { playStateToString(it) } ?: "NULL"
        Log.i(TAG, "MIRA_LIVE_VOICE: stopPreviewCleanly: fade done, playState=$fadePlayState [elapsed=${tFadeDone}ms]")
        stopAndFlushForRelease()
        val tFlushDone = System.currentTimeMillis() - tStart
        Log.i(TAG, "MIRA_LIVE_VOICE: stopPreviewCleanly: stopAndFlushForRelease done, calling release() [elapsed=${tFlushDone}ms]")
        release()
        val tReleaseDone = System.currentTimeMillis() - tStart
        Log.i(TAG, "MIRA_LIVE_VOICE: stopPreviewCleanly: COMPLETE (release done) [elapsed=${tReleaseDone}ms]")
    }

    /**
     * Preview-only: On natural end (all chunks fed), waits for the audio queue and AudioTrack buffer
     * to drain, fades volume 1 -> 0 over ~100ms, then stops and flushes without restart, and releases the track.
     * Never leaves the AudioTrack running or restarted after the preview ends.
     */
    suspend fun endPreviewCleanly() = withContext(Dispatchers.IO) {
        val tStart = System.currentTimeMillis()
        val controller = audioTrackController
        val initialPlayState = controller?.playState?.let { playStateToString(it) } ?: "NULL"
        Log.i(TAG, "MIRA_LIVE_VOICE: endPreviewCleanly: START (playState=$initialPlayState) [elapsed=0ms]")
        try {
            // 1. Wait for audioQueue to drain
            val queueDrainTimeoutMs = 15000L
            val startQueueWait = System.currentTimeMillis()
            while (audioQueue.isNotEmpty() && isPlaying.get()) {
                if (System.currentTimeMillis() - startQueueWait > queueDrainTimeoutMs) break
                delay(20)
            }
            val tQueueDrained = System.currentTimeMillis() - tStart
            val queuePlayState = controller?.playState?.let { playStateToString(it) } ?: "NULL"
            Log.i(TAG, "MIRA_LIVE_VOICE: endPreviewCleanly: queue drained (remaining=${audioQueue.size}), playState=$queuePlayState [elapsed=${tQueueDrained}ms]")

            // 2. Wait for AudioTrack buffer to drain
            if (controller != null && controller.state == AudioTrackController.STATE_INITIALIZED && isPlaying.get()) {
                val totalFrames = totalAudioBytesWritten / 2
                val trackDrainTimeoutMs = 5000L
                val startTrackWait = System.currentTimeMillis()
                while (isPlaying.get() && (System.currentTimeMillis() - startTrackWait) < trackDrainTimeoutMs) {
                    val currentHead = try {
                        controller.playbackHeadPosition
                    } catch (_: Exception) {
                        totalFrames
                    }
                    if (currentHead >= totalFrames) {
                        break
                    }
                    delay(25)
                }
                val tTrackDrained = System.currentTimeMillis() - tStart
                val trackPlayState = playStateToString(controller.playState)
                Log.i(TAG, "MIRA_LIVE_VOICE: endPreviewCleanly: track drained (head=${try { controller.playbackHeadPosition } catch (_: Exception) { -1L }}/$totalFrames), playState=$trackPlayState [elapsed=${tTrackDrained}ms]")

                // 3. Fade volume 1 -> 0 over ~100ms
                val steps = 10
                val stepDelay = 10L // 100ms total
                for (i in (steps - 1) downTo 0) {
                    if (!isPlaying.get()) break
                    try {
                        controller.setVolume(i.toFloat() / steps)
                    } catch (_: Exception) {}
                    delay(stepDelay)
                }
                try {
                    controller.setVolume(0.0f)
                } catch (_: Exception) {}

                // Digital silence frames before stopping so HAL never sees a hard transition
                try {
                    val silence = ByteArray(512)
                    controller.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
                } catch (_: Exception) {}

                val tFadeDone = System.currentTimeMillis() - tStart
                val fadePlayState = playStateToString(controller.playState)
                Log.i(TAG, "MIRA_LIVE_VOICE: endPreviewCleanly: fade done (volume=0.0f), playState=$fadePlayState [elapsed=${tFadeDone}ms]")
            }
        } catch (e: Exception) {
            Log.w(TAG, "MIRA_LIVE_VOICE: Error during endPreviewCleanly: ${e.message}")
        } finally {
            // 4. Stop + flush for release (DO NOT restart track) + release
            val tTeardownStart = System.currentTimeMillis() - tStart
            Log.i(TAG, "MIRA_LIVE_VOICE: endPreviewCleanly: teardown entered, calling stopAndFlushForRelease() [elapsed=${tTeardownStart}ms]")
            stopAndFlushForRelease()
            val tFlushDone = System.currentTimeMillis() - tStart
            Log.i(TAG, "MIRA_LIVE_VOICE: endPreviewCleanly: stopAndFlushForRelease done, calling release() [elapsed=${tFlushDone}ms]")
            release()
            val tReleaseDone = System.currentTimeMillis() - tStart
            Log.i(TAG, "MIRA_LIVE_VOICE: endPreviewCleanly: COMPLETE (release done) [elapsed=${tReleaseDone}ms]")
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

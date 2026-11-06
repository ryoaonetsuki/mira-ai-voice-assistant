package io.github.ryoaonetsuki.mira.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
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

    companion object {
        private const val TAG = "MIRA_LIVE_VOICE"
        const val SAMPLE_RATE = 24000
        const val PREBUFFER_MAX_BYTES = 4800 // ~100ms at 24kHz PCM16 mono
        const val PREBUFFER_TIMEOUT_MS = 150L
    }
}

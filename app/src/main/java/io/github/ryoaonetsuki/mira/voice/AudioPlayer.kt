package io.github.ryoaonetsuki.mira.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayer(
    private val onSpeakingStateChanged: (Boolean) -> Unit
) {
    private var audioTrack: AudioTrack? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val isPlaying = AtomicBoolean(false)
    private var playbackThread: Thread? = null

    @Volatile
    private var isSpeakingState: Boolean = false

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

            val track = AudioTrack.Builder()
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
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack initialization failed (state != STATE_INITIALIZED: ${track.state})")
                try { track.release() } catch (_: Exception) {}
                audioTrack = null
                return false
            }

            track.play()
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                Log.e(TAG, "AudioTrack.play() failed to enter PLAYSTATE_PLAYING (playState=${track.playState})")
                try { track.release() } catch (_: Exception) {}
                audioTrack = null
                return false
            }

            audioTrack = track
            startPlaybackLoop()
            Log.i(TAG, "AudioTrack initialized successfully at 24kHz mono (USAGE_ASSISTANT, state=STATE_INITIALIZED, playState=PLAYSTATE_PLAYING)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init AudioTrack: ${e.message}", e)
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
                    // Grace period check: if currently speaking, wait up to 300ms for next chunk
                    // before declaring utterance finished. If not speaking, block on take().
                    val chunk = if (isSpeakingState) {
                        audioQueue.poll(300, TimeUnit.MILLISECONDS)
                    } else {
                        audioQueue.take()
                    }

                    if (chunk == null) {
                        // Queue has stayed empty for 300ms grace period mid-utterance
                        if (isSpeakingState) {
                            isSpeakingState = false
                            onSpeakingStateChanged(false)
                            Log.d(TAG, "Speaking grace period expired (300ms queue empty) — switched speaking state to false")
                        }
                        continue
                    }

                    if (chunk.isNotEmpty()) {
                        if (!isSpeakingState) {
                            isSpeakingState = true
                            onSpeakingStateChanged(true)
                        }

                        val track = audioTrack
                        if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                            val written = track.write(chunk, 0, chunk.size)
                            totalAudioChunksWritten++
                            if (written >= 0) {
                                totalAudioBytesWritten += written
                                lastPlaybackDiag = "chunks=$totalAudioChunksWritten, bytes=$totalAudioBytesWritten"
                                if (written < chunk.size) {
                                    Log.w(TAG, "AudioTrack.write partial write: $written/${chunk.size} bytes (chunk #$totalAudioChunksWritten)")
                                } else if (totalAudioChunksWritten == 1L || totalAudioChunksWritten % 50L == 0L) {
                                    Log.d(TAG, "AudioTrack wrote chunk #$totalAudioChunksWritten: $written bytes (total: $totalAudioBytesWritten bytes)")
                                }
                            } else {
                                val errMeaning = when (written) {
                                    AudioTrack.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                                    AudioTrack.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                                    AudioTrack.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                                    AudioTrack.ERROR -> "ERROR"
                                    else -> "UNKNOWN ($written)"
                                }
                                lastPlaybackDiag = "chunks=$totalAudioChunksWritten, bytes=$totalAudioBytesWritten, writeError=$errMeaning"
                                Log.e(TAG, "AudioTrack.write failed: code $written ($errMeaning) for chunk of ${chunk.size} bytes (chunk #$totalAudioChunksWritten)")
                            }
                        } else {
                            Log.e(TAG, "Cannot write audio: AudioTrack is null or not initialized")
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in AudioPlayer playback loop: ${e.message}", e)
                }
            }
            if (isSpeakingState) {
                isSpeakingState = false
                onSpeakingStateChanged(false)
            }
        }, "MiraAudioPlaybackThread")
        playbackThread?.start()
    }

    fun playAudioChunk(pcmData: ByteArray) {
        if (!isPlaying.get() || audioTrack == null) {
            val initialized = initAudioTrack()
            if (!initialized) {
                Log.e(TAG, "playAudioChunk dropped ${pcmData.size} bytes because AudioTrack could not be initialized")
                return
            }
        }
        audioQueue.offer(pcmData)
    }

    /**
     * Instantly stops audio playback, clears all queued PCM frames, and resets buffer for barge-in.
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
            Log.i(TAG, "Barge-in: AudioTrack flushed and playback halted immediately")
        } catch (e: Exception) {
            Log.e(TAG, "Error during AudioTrack flush: ${e.message}")
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
                Log.w(TAG, "Error stopping AudioTrack: ${e.message}")
            } finally {
                try {
                    track.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing AudioTrack: ${e.message}")
                }
            }
        }
        Log.i(TAG, "AudioPlayer released (total written: $totalAudioChunksWritten chunks, $totalAudioBytesWritten bytes)")
    }

    companion object {
        private const val TAG = "MiraAudioPlayer"
        const val SAMPLE_RATE = 24000
    }
}

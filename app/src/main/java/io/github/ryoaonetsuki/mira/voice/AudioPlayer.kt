package io.github.ryoaonetsuki.mira.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayer(
    private val onSpeakingStateChanged: (Boolean) -> Unit
) {
    private var audioTrack: AudioTrack? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val isPlaying = AtomicBoolean(false)
    private var playbackThread: Thread? = null

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        if (isPlaying.get() && audioTrack != null) return
        try {
            val sampleRate = SAMPLE_RATE
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufferSize = maxOf(minBufferSize, 8192)

            audioTrack = AudioTrack.Builder()
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

            audioTrack?.play()
            startPlaybackLoop()
            Log.d(TAG, "AudioTrack initialized at 24kHz mono (USAGE_ASSISTANT)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init AudioTrack: ${e.message}", e)
        }
    }

    private fun startPlaybackLoop() {
        isPlaying.set(true)
        playbackThread = Thread({
            while (isPlaying.get()) {
                try {
                    val chunk = audioQueue.take()
                    if (chunk.isNotEmpty()) {
                        onSpeakingStateChanged(true)
                        audioTrack?.write(chunk, 0, chunk.size)
                        if (audioQueue.isEmpty()) {
                            onSpeakingStateChanged(false)
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing audio to track: ${e.message}")
                }
            }
            onSpeakingStateChanged(false)
        }, "MiraAudioPlaybackThread")
        playbackThread?.start()
    }

    fun playAudioChunk(pcmData: ByteArray) {
        if (!isPlaying.get()) {
            initAudioTrack()
        }
        audioQueue.offer(pcmData)
    }

    /**
     * Instantly stops audio playback, clears all queued PCM frames, and resets buffer for barge-in.
     */
    fun stopImmediatelyAndFlush() {
        audioQueue.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
            onSpeakingStateChanged(false)
            Log.d(TAG, "Barge-in: AudioTrack flushed and playback halted immediately")
        } catch (e: Exception) {
            Log.e(TAG, "Error during AudioTrack flush: ${e.message}")
        }
    }

    fun release() {
        isPlaying.set(false)
        audioQueue.clear()
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
        onSpeakingStateChanged(false)
        Log.d(TAG, "AudioPlayer released")
    }

    companion object {
        private const val TAG = "MiraAudioPlayer"
        const val SAMPLE_RATE = 24000
    }
}

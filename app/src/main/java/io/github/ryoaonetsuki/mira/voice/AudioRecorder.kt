package io.github.ryoaonetsuki.mira.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AudioRecorder(
    private val onAudioChunk: (ByteArray) -> Unit,
    private val onAmplitude: (Float) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isRecording.get()) return true

        val sampleRate = SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, CHUNK_SIZE_BYTES * 2)

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed (state != STATE_INITIALIZED)")
                try { record.release() } catch (_: Exception) {}
                audioRecord = null
                return false
            }

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord failed to enter RECORDING state")
                try { record.stop() } catch (_: Exception) {}
                try { record.release() } catch (_: Exception) {}
                audioRecord = null
                return false
            }

            audioRecord = record
            isRecording.set(true)

            recordingThread = Thread({
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                while (isRecording.get()) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        // Calculate RMS amplitude
                        var sum = 0.0
                        var i = 0
                        while (i < read - 1) {
                            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                            sum += sample * sample
                            i += 2
                        }
                        val sampleCount = read / 2
                        val rms = if (sampleCount > 0) sqrt(sum / sampleCount) else 0.0
                        val normalized = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
                        onAmplitude(normalized)

                        // Deliver audio chunk
                        val chunkCopy = buffer.copyOf(read)
                        onAudioChunk(chunkCopy)
                    }
                }
            }, "MiraAudioRecordThread")

            recordingThread?.start()
            Log.d(TAG, "Audio recording started successfully at 16kHz mono")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord: ${e.message}", e)
            stopRecording()
            return false
        }
    }

    fun stopRecording() {
        isRecording.set(false)
        try {
            recordingThread?.interrupt()
            recordingThread = null
        } catch (_: Exception) {}

        val record = audioRecord
        audioRecord = null

        if (record != null) {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
            } finally {
                try {
                    record.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
                }
            }
        }
        try {
            onAmplitude(0f)
        } catch (_: Exception) {}
        Log.d(TAG, "Audio recording stopped")
    }

    companion object {
        private const val TAG = "MiraAudioRecorder"
        const val SAMPLE_RATE = 16000
        // 1600 samples @ 16kHz 16-bit mono = 3200 bytes = 100ms chunk
        const val CHUNK_SIZE_BYTES = 3200
    }
}

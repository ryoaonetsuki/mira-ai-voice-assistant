package io.github.ryoaonetsuki.mira.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Audio recorder for Gemini Live native voice pipeline (ROUND 12 spec §3.2).
 * Captures 16 kHz mono 16-bit PCM in 20–40 ms chunks (640 bytes = 20 ms).
 * Operates on a dedicated background thread with guarded AEC and noise suppression.
 * Frames are streamed continuously including silence.
 */
class AudioRecorder(
    private val onAudioChunk: (ByteArray) -> Unit,
    private val onAmplitude: (Float) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private val isLiveForwarding = AtomicBoolean(true)
    private val preRollRing = PreRollRingBuffer()

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private var chunksRecorded: Long = 0L
    private var totalRecordedBytes: Long = 0L

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (!isRecording.compareAndSet(false, true)) {
            Log.d(TAG, "MIRA_LIVE_VOICE: AudioRecorder already recording")
            return true
        }

        val sampleRate = SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = maxOf(minBufferSize, CHUNK_SIZE_BYTES * 4)

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                // Try fallback to MIC source if VOICE_COMMUNICATION is not supported on device
                Log.w(TAG, "MIRA_LIVE_VOICE: AudioRecord VOICE_COMMUNICATION init failed, trying MediaRecorder.AudioSource.MIC")
                try { record.release() } catch (_: Exception) {}
                val fallbackRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                if (fallbackRecord.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "MIRA_LIVE_VOICE: AudioRecord initialization failed on both sources (state=${fallbackRecord.state})")
                    try { fallbackRecord.release() } catch (_: Exception) {}
                    isRecording.set(false)
                    return false
                }
                audioRecord = fallbackRecord
            } else {
                audioRecord = record
            }

            val currentRecord = audioRecord!!

            // Hardware Acoustic Echo Canceler (AEC) if available
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(currentRecord.audioSessionId)?.apply {
                        enabled = true
                    }
                    Log.i(TAG, "MIRA_LIVE_VOICE: AcousticEchoCanceler enabled on session ${currentRecord.audioSessionId}")
                } catch (e: Exception) {
                    Log.w(TAG, "MIRA_LIVE_VOICE: Could not create AcousticEchoCanceler: ${e.message}")
                }
            }

            // Hardware Noise Suppressor if available
            if (NoiseSuppressor.isAvailable()) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(currentRecord.audioSessionId)?.apply {
                        enabled = true
                    }
                    Log.i(TAG, "MIRA_LIVE_VOICE: NoiseSuppressor enabled on session ${currentRecord.audioSessionId}")
                } catch (e: Exception) {
                    Log.w(TAG, "MIRA_LIVE_VOICE: Could not create NoiseSuppressor: ${e.message}")
                }
            }

            currentRecord.startRecording()
            if (currentRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "MIRA_LIVE_VOICE: AudioRecord failed to enter RECORDSTATE_RECORDING (state=${currentRecord.recordingState})")
                stopRecording()
                return false
            }

            chunksRecorded = 0L
            totalRecordedBytes = 0L

            recordingThread = Thread({
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                while (isRecording.get()) {
                    val recordRef = audioRecord ?: break
                    val read = recordRef.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        chunksRecorded++
                        totalRecordedBytes += read

                        // Calculate real RMS amplitude for orb animation
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

                        val chunkCopy = buffer.copyOf(read)
                        preRollRing.write(chunkCopy)

                        if (isLiveForwarding.get()) {
                            onAudioChunk(chunkCopy)
                        }
                    } else if (read < 0) {
                        val errorDetail = when (read) {
                            AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                            AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                            AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                            AudioRecord.ERROR -> "ERROR"
                            else -> "UNKNOWN ($read)"
                        }
                        if (isRecording.get()) {
                            Log.e(TAG, "MIRA_LIVE_VOICE: AudioRecord.read error: $errorDetail ($read)")
                        }
                    }
                }
            }, "MiraAudioRecordThread")

            recordingThread?.start()
            Log.i(TAG, "MIRA_LIVE_VOICE: Audio recording started at 16kHz mono (20ms chunks, 640B)")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "MIRA_LIVE_VOICE: SecurityException starting AudioRecord: ${e.message}", e)
            stopRecording()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "MIRA_LIVE_VOICE: Failed to start AudioRecord: ${e.message}", e)
            stopRecording()
            return false
        }
    }

    fun flushPreRoll(): List<ByteArray> {
        val flushed = preRollRing.flush(PreRollRingBuffer.PRE_ROLL_MAX_AGE_MS)
        isLiveForwarding.set(true)
        return flushed
    }

    fun enableLiveForwarding(enabled: Boolean) {
        isLiveForwarding.set(enabled)
    }

    fun stopRecording() {
        if (!isRecording.compareAndSet(true, false)) return

        isLiveForwarding.set(false)
        preRollRing.clear()

        try {
            recordingThread?.interrupt()
            recordingThread = null
        } catch (_: Exception) {}

        try {
            echoCanceler?.enabled = false
            echoCanceler?.release()
        } catch (_: Exception) {}
        echoCanceler = null

        try {
            noiseSuppressor?.enabled = false
            noiseSuppressor?.release()
        } catch (_: Exception) {}
        noiseSuppressor = null

        val record = audioRecord
        audioRecord = null

        if (record != null) {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "MIRA_LIVE_VOICE: Error stopping AudioRecord: ${e.message}")
            } finally {
                try {
                    record.release()
                } catch (e: Exception) {
                    Log.w(TAG, "MIRA_LIVE_VOICE: Error releasing AudioRecord: ${e.message}")
                }
            }
        }

        try {
            onAmplitude(0f)
        } catch (_: Exception) {}

        Log.i(TAG, "MIRA_LIVE_VOICE: Audio recording stopped ($chunksRecorded chunks, $totalRecordedBytes bytes)")
    }

    companion object {
        private const val TAG = "MIRA_LIVE_VOICE"
        const val SAMPLE_RATE = 16000
        // 320 samples @ 16kHz 16-bit mono = 640 bytes = 20ms chunk (spec §0.9, 20–40ms)
        const val CHUNK_SIZE_BYTES = 640
    }
}

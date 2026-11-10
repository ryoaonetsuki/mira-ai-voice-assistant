/*
 * Copyright (c) 2024, Daily
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.github.ryoaonetsuki.mira.voice.pipecat

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.annotation.RequiresPermission
import io.github.ryoaonetsuki.mira.voice.LiveVoiceDiagnostics
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

private const val TAG = "AudioIn"
private const val BUFFER_MS = 100


internal class AudioIn @RequiresPermission(android.Manifest.permission.RECORD_AUDIO) constructor(
    sampleRateHz: Int,
    onAudioCaptured: (ByteArray) -> Unit,
    onAudioLevelUpdate: (Float) -> Unit,
    onError: (Throwable) -> Unit,
    onStopped: () -> Unit,
    initialMicEnabled: Boolean,
    private val retryPolicy: AudioCaptureRetryPolicy = AudioCaptureRetryPolicy()
) {

    private val thread: Thread
    private val mute = AtomicBoolean(!initialMicEnabled)
    private val stop = AtomicBoolean(false)

    init {
        thread = thread(name = "MiraAudioIn") {
            // Turn G: a failed AudioRecord.read used to throw out of this
            // thread and kill the mic FOREVER — the session stayed
            // "connected" but deaf (e.g. when opening another app on this
            // custom ROM preempts capture). Now a failed attempt recreates
            // the AudioRecord with bounded retries; onError fires ONLY when
            // the retries are exhausted, so the session manager can restart
            // the call instead of going silently deaf.
            var consecutiveFailures = 0
            var recoveryPending = false
            try {
                while (!stop.get()) {
                    while (mute.get() && !stop.get()) {
                        synchronized(mute) {
                            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                            (mute as Object).wait()
                        }
                    }

                    if (stop.get()) {
                        break
                    }

                    try {
                        val frameSizeBytes = 2 // 16-bit, mono
                        val bufferBytes = (frameSizeBytes * sampleRateHz * BUFFER_MS) / 1000

                        val record = AudioRecord(
                            AudioSource.VOICE_COMMUNICATION,
                            sampleRateHz,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferBytes
                        )

                        var aec: AcousticEchoCanceler? = null
                        var ns: NoiseSuppressor? = null

                        try {
                            record.startRecording()

                        // AcousticEchoCanceler (AEC)
                        val aecAvailable = try {
                            AcousticEchoCanceler.isAvailable()
                        } catch (e: Throwable) {
                            Log.w(TAG, "Error checking AcousticEchoCanceler availability", e)
                            false
                        }

                        var aecEnabled = false
                        if (aecAvailable) {
                            try {
                                val created = AcousticEchoCanceler.create(record.audioSessionId)
                                if (created != null) {
                                    val status = created.setEnabled(true)
                                    if (status != AudioEffect.SUCCESS) {
                                        Log.w(TAG, "AcousticEchoCanceler.setEnabled(true) failed with status $status")
                                    }
                                    aec = created
                                    aecEnabled = created.enabled
                                } else {
                                    Log.w(TAG, "AcousticEchoCanceler.create returned null on session ${record.audioSessionId}")
                                }
                            } catch (e: Throwable) {
                                Log.w(TAG, "Failed to create or enable AcousticEchoCanceler on session ${record.audioSessionId}", e)
                            }
                        }

                        Log.i(
                            TAG,
                            "AcousticEchoCanceler: available=$aecAvailable, enabled=$aecEnabled, sessionId=${record.audioSessionId}"
                        )
                        LiveVoiceDiagnostics.updateAecState(available = aecAvailable, enabled = aecEnabled)

                        // NoiseSuppressor (NS)
                        val nsAvailable = try {
                            NoiseSuppressor.isAvailable()
                        } catch (e: Throwable) {
                            Log.w(TAG, "Error checking NoiseSuppressor availability", e)
                            false
                        }

                        var nsEnabled = false
                        if (nsAvailable) {
                            try {
                                val created = NoiseSuppressor.create(record.audioSessionId)
                                if (created != null) {
                                    val status = created.setEnabled(true)
                                    if (status != AudioEffect.SUCCESS) {
                                        Log.w(TAG, "NoiseSuppressor.setEnabled(true) failed with status $status")
                                    }
                                    ns = created
                                    nsEnabled = created.enabled
                                } else {
                                    Log.w(TAG, "NoiseSuppressor.create returned null on session ${record.audioSessionId}")
                                }
                            } catch (e: Throwable) {
                                Log.w(TAG, "Failed to create or enable NoiseSuppressor on session ${record.audioSessionId}", e)
                            }
                        }

                        Log.i(
                            TAG,
                            "NoiseSuppressor: available=$nsAvailable, enabled=$nsEnabled, sessionId=${record.audioSessionId}"
                        )

                        val buf = ByteArray(bufferBytes)

                        while (!stop.get() && !mute.get()) {
                            val readResult =
                                record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)

                            if (readResult <= 0) {
                                throw RuntimeException("record.read returned $readResult")
                            }

                            // A frame made it through: the capture path is
                            // alive again — clear the failure streak and
                            // record the self-healing recovery once.
                            if (consecutiveFailures > 0 || recoveryPending) {
                                consecutiveFailures = 0
                                if (recoveryPending) {
                                    recoveryPending = false
                                    Log.i(TAG, "Audio capture recovered after failure — mic restarted")
                                    LiveVoiceDiagnostics.recordMicRestart()
                                }
                            }

                            onAudioLevelUpdate(calculateAudioLevel(buf))
                            onAudioCaptured(buf.copyOf(readResult))
                        }
                    } finally {
                        try {
                            aec?.setEnabled(false)
                            aec?.release()
                        } catch (e: Throwable) {
                            Log.w(TAG, "Error releasing AcousticEchoCanceler: ${e.message}")
                        }
                        aec = null

                        try {
                            ns?.setEnabled(false)
                            ns?.release()
                        } catch (e: Throwable) {
                            Log.w(TAG, "Error releasing NoiseSuppressor: ${e.message}")
                        }
                        ns = null

                        LiveVoiceDiagnostics.updateAecEnabled(false)

                        try {
                            record.stop()
                        } catch (e: Throwable) {
                            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
                        }
                        try {
                            record.release()
                        } catch (e: Throwable) {
                            Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
                        }
                    }
                    } catch (e: Exception) {
                        // One capture attempt died (read error, failed start,
                        // uninitialised record). Recreate instead of dying:
                        // the mic is re-opened on the next loop iteration.
                        consecutiveFailures++
                        if (stop.get()) break
                        if (!retryPolicy.shouldRetry(consecutiveFailures)) throw e
                        Log.w(
                            TAG,
                            "Audio capture failed (attempt $consecutiveFailures/${retryPolicy.maxConsecutiveFailures}): ${e.message} — recreating AudioRecord"
                        )
                        recoveryPending = true
                        val delayMs = retryPolicy.delayBeforeRetryMs(consecutiveFailures)
                        var sleptMs = 0L
                        while (sleptMs < delayMs && !stop.get()) {
                            val slice = minOf(50L, delayMs - sleptMs)
                            try {
                                Thread.sleep(slice)
                            } catch (_: InterruptedException) {
                                break
                            }
                            sleptMs += slice
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception when capturing audio", e)
                onError(e)
            } finally {
                onStopped()
            }
        }
    }

    fun stop() {
        stop.set(true)
        synchronized(mute) {
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (mute as Object).notifyAll()
        }
    }

    var muted: Boolean
        get() = mute.get()
        set(value) {
            synchronized(mute) {
                mute.set(value)
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (mute as Object).notifyAll()
            }
        }
}

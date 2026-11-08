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
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

private const val TAG = "AudioIn"
private const val BUFFER_MS = 100

/**
 * Calculates normalized RMS audio level [0.0f, 1.0f] from 16-bit PCM mono data.
 */
internal fun calculateAudioLevel(pcmData: ByteArray): Float {
    var sumSquares = 0.0
    val numSamples = pcmData.size / 2
    if (numSamples == 0) return 0f

    for (i in 0 until numSamples) {
        val low = pcmData[i * 2].toInt() and 0xFF
        val high = pcmData[i * 2 + 1].toInt() and 0xFF
        val sample = ((high shl 8) or low).toShort()
        val sampleValue = sample.toDouble()
        sumSquares += sampleValue * sampleValue
    }

    val rms = sqrt(sumSquares / numSamples)
    return (rms / 32768.0).toFloat().coerceIn(0.0f, 1.0f)
}

internal class AudioIn @RequiresPermission(android.Manifest.permission.RECORD_AUDIO) constructor(
    sampleRateHz: Int,
    onAudioCaptured: (ByteArray) -> Unit,
    onAudioLevelUpdate: (Float) -> Unit,
    onError: (Throwable) -> Unit,
    onStopped: () -> Unit,
    initialMicEnabled: Boolean
) {

    private val thread: Thread
    private val mute = AtomicBoolean(!initialMicEnabled)
    private val stop = AtomicBoolean(false)

    init {
        thread = thread(name = "MiraAudioIn") {
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

                    val frameSizeBytes = 2 // 16-bit, mono
                    val bufferBytes = (frameSizeBytes * sampleRateHz * BUFFER_MS) / 1000

                    val record = AudioRecord(
                        AudioSource.VOICE_COMMUNICATION,
                        sampleRateHz,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferBytes
                    )

                    try {
                        record.startRecording()
                        val buf = ByteArray(bufferBytes)

                        while (!stop.get() && !mute.get()) {
                            val readResult =
                                record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)

                            if (readResult <= 0) {
                                throw RuntimeException("record.read returned $readResult")
                            }

                            onAudioLevelUpdate(calculateAudioLevel(buf))
                            onAudioCaptured(buf.copyOf(readResult))
                        }
                    } finally {
                        record.release()
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

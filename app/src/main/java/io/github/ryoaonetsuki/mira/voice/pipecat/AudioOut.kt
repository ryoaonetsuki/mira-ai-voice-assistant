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

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.LinkedList
import kotlin.concurrent.thread

private const val BUFFER_MS = 100
private const val TAG = "AudioOut"

internal class AudioOut(
    sampleRateHz: Int,
    onAudioLevelUpdate: (Float) -> Unit,
    private val onAudioPlayback: ((ByteArray) -> Unit)? = null,
    private val onLatencyMeasured: ((Int) -> Unit)? = null
) {

    @Volatile
    var playbackLatencyMs: Int = 120
        private set

    private val queueLock = Object()
    private val queue = LinkedList<ByteArray?>()
    private var stopped = false

    init {
        thread(name = "MiraAudioOut") {
            val audioFormat = AudioFormat.Builder().setSampleRate(sampleRateHz)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()

            val frameSizeBytes = 2 // 16-bit, mono
            val bufferBytes = (frameSizeBytes * sampleRateHz * BUFFER_MS) / 1000

            Log.i(
                TAG,
                "Creating AudioOut: sampleRateHz = $sampleRateHz, bufferBytes = $bufferBytes"
            )

            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                audioFormat,
                bufferBytes,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            try {
                track.play()

                fun measureLatency(): Int {
                    return try {
                        val method = AudioTrack::class.java.getMethod("getLatency")
                        val lat = (method.invoke(track) as? Int) ?: 120
                        if (lat <= 0) 120 else lat.coerceIn(40, 300)
                    } catch (_: Throwable) {
                        120
                    }
                }

                playbackLatencyMs = measureLatency()
                Log.i(TAG, "AudioTrack output latency initial measurement: $playbackLatencyMs ms")
                onLatencyMeasured?.invoke(playbackLatencyMs)

                var firstChunkWritten = false

                while (true) {
                    val item = synchronized(queueLock) {
                        while (queue.isEmpty()) {
                            queueLock.wait()
                        }
                        queue.removeFirst()
                    }

                    if (item == null) {
                        Log.i(TAG, "Terminating AudioOut")
                        return@thread
                    }

                    onAudioLevelUpdate(calculateAudioLevel(item))
                    onAudioPlayback?.invoke(item)

                    var writePtr = 0
                    while (writePtr < item.size) {
                        val writeRes = track.write(item, writePtr, item.size - writePtr)

                        if (writeRes <= 0) {
                            throw RuntimeException("track.write returned $writeRes")
                        }

                        writePtr += writeRes
                    }

                    if (!firstChunkWritten) {
                        firstChunkWritten = true
                        val refined = measureLatency()
                        if (refined != playbackLatencyMs) {
                            playbackLatencyMs = refined
                            Log.i(TAG, "AudioTrack output latency refined after write: $playbackLatencyMs ms")
                            onLatencyMeasured?.invoke(playbackLatencyMs)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "AudioOut playback error", e)
            } finally {
                try {
                    track.stop()
                } catch (_: Exception) {}
                track.release()
            }
        }
    }

    fun write(samples: ByteArray) {
        synchronized(queueLock) {
            if (!stopped) {
                queue.offer(samples)
                queueLock.notifyAll()
            }
        }
    }

    fun stop() {
        synchronized(queueLock) {
            if (!stopped) {
                queue.offer(null)
                queueLock.notifyAll()
                stopped = true
            }
        }
    }

    fun interrupt() {
        synchronized(queueLock) {
            queue.clear()
            if (stopped) {
                queue.add(null)
            }
            queueLock.notifyAll()
        }
    }
}

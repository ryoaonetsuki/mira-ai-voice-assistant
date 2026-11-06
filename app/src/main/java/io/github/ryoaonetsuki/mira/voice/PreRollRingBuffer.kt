package io.github.ryoaonetsuki.mira.voice

import android.util.Log

/**
 * Thread-safe circular timestamped pre-roll buffer holding up to 8,000 ms of
 * 16kHz PCM16 mono audio frames. Captured continuously while WebSocket handshake
 * progresses, and flushed upon setupComplete to prevent dropping user speech.
 */
class PreRollRingBuffer(
    private val windowMs: Long = PRE_ROLL_WINDOW_MS,
    private val maxBytes: Int = MAX_WINDOW_BYTES
) {
    private data class TimestampedChunk(
        val data: ByteArray,
        val timestampMs: Long
    )

    private val lock = Any()
    private val buffer = ArrayDeque<TimestampedChunk>()
    private var currentByteCount: Int = 0

    fun write(pcmData: ByteArray) {
        if (pcmData.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val chunkCopy = pcmData.copyOf()
            buffer.addLast(TimestampedChunk(chunkCopy, now))
            currentByteCount += chunkCopy.size

            // Prune chunks older than windowMs or exceeding byte capacity
            val minTimestamp = now - windowMs
            while (buffer.isNotEmpty()) {
                val oldest = buffer.first()
                if (oldest.timestampMs < minTimestamp || currentByteCount > maxBytes) {
                    buffer.removeFirst()
                    currentByteCount -= oldest.data.size
                } else {
                    break
                }
            }
        }
    }

    /**
     * Drains all frames not older than maxAgeMs (default 20,000 ms).
     * Computes flushed duration in ms and logs the required marker:
     * "t2.7 preroll-flushed ms=<flushed_ms>".
     */
    fun flush(maxAgeMs: Long = PRE_ROLL_MAX_AGE_MS): List<ByteArray> {
        val now = System.currentTimeMillis()
        val minAllowedTimestamp = now - maxAgeMs
        val flushedList = mutableListOf<ByteArray>()
        var flushedBytes = 0L

        synchronized(lock) {
            while (buffer.isNotEmpty()) {
                val item = buffer.removeFirst()
                currentByteCount -= item.data.size
                if (item.timestampMs >= minAllowedTimestamp) {
                    flushedList.add(item.data)
                    flushedBytes += item.data.size
                }
            }
            currentByteCount = 0
        }

        // 16 kHz 16-bit mono = 32,000 bytes/sec => 32 bytes per millisecond
        val flushedMs = if (flushedBytes > 0) flushedBytes / 32L else 0L
        Log.i(TAG, "t2.7 preroll-flushed ms=$flushedMs chunks=${flushedList.size} bytes=$flushedBytes")
        return flushedList
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            currentByteCount = 0
        }
    }

    fun sizeBytes(): Int {
        synchronized(lock) {
            return currentByteCount
        }
    }

    companion object {
        private const val TAG = "MiraPreRoll"
        const val PRE_ROLL_WINDOW_MS = 8_000L
        const val PRE_ROLL_MAX_AGE_MS = 20_000L
        // 16,000 samples/s * 2 bytes/sample * 8s = 256,000 bytes
        const val MAX_WINDOW_BYTES = 256_000
    }
}

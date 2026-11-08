package io.github.ryoaonetsuki.mira.voice.pipecat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying far-end reference delay compensation and gradual drift handling (Round 22 Turn 5).
 */
class AecAlignmentTest {

    private class RecordingAecEngine : AcousticEchoCancellerEngine {
        val calls = mutableListOf<Pair<ShortArray, ShortArray>>() // (nearEnd, farEnd)

        override fun process(nearEnd: ShortArray, farEnd: ShortArray, out: ShortArray, length: Int) {
            calls.add(nearEnd.copyOf(length) to farEnd.copyOf(length))
            System.arraycopy(nearEnd, 0, out, 0, length)
        }

        override fun reset() {}
        override fun release() {}
    }

    private class IdentityResampler : AudioResampler {
        override fun resample(input: ShortArray, inRate: Int, outRate: Int): ShortArray = input.copyOf()
        override fun reset() {}
        override fun release() {}
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val s = shorts[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    @Test
    fun testReferenceDelay_FarEndFedToEngineIsDelayedReferenceNotNewestSamples() {
        val engine = RecordingAecEngine()
        var testTimeMs = 1000L
        val delayMs = 100 // 100 ms reference delay

        val aec = SoftwareAcousticEchoCanceller(
            engine = engine,
            resampler = IdentityResampler(),
            sampleRateHz = 16000,
            playbackSampleRateHz = 16000,
            frameSizeSamples = 160,
            referenceDelayMs = delayMs,
            clock = { testTimeMs }
        )

        // At t = 1000ms: push chunk 1 (160 samples = 10ms at 16kHz) with sample values = 100
        val chunk1Shorts = ShortArray(160) { 100.toShort() }
        aec.pushPlaybackAudio(shortsToBytes(chunk1Shorts))

        // Process mic frame at t = 1000ms (< 1000 + 100ms = 1100ms)
        // Delay has NOT elapsed yet! Far-end reference must be silence (all zeros)
        val micFrame1 = ShortArray(160) { 10.toShort() }
        aec.processMicrophoneAudio(shortsToBytes(micFrame1))

        assertEquals(1, engine.calls.size)
        val (_, farEndAt1000) = engine.calls[0]
        for (s in farEndAt1000) {
            assertEquals("Far-end must be silence (0) before reference delay elapses", 0.toShort(), s)
        }

        // Advance time to t = 1050ms (< 1100ms) and process another mic frame
        testTimeMs = 1050L
        aec.processMicrophoneAudio(shortsToBytes(micFrame1))

        assertEquals(2, engine.calls.size)
        val (_, farEndAt1050) = engine.calls[1]
        for (s in farEndAt1050) {
            assertEquals("Far-end must still be silence at t=1050ms", 0.toShort(), s)
        }

        // At t = 1100ms: delay of 100ms has now elapsed!
        testTimeMs = 1100L

        // ALSO at t = 1100ms, push a BRAND NEW chunk 2 with sample values = 999
        val chunk2Shorts = ShortArray(160) { 999.toShort() }
        aec.pushPlaybackAudio(shortsToBytes(chunk2Shorts))

        // Now process mic frame at t = 1100ms
        aec.processMicrophoneAudio(shortsToBytes(micFrame1))

        assertEquals(3, engine.calls.size)
        val (_, farEndAt1100) = engine.calls[2]

        // PROOF: The far-end samples fed to the engine for this frame MUST be chunk 1 (value 100, delayed by 100ms),
        // NOT zeros, and NOT the newest pushed chunk 2 (value 999)!
        var foundDelayedSampleCount = 0
        var foundNewestSampleCount = 0
        for (s in farEndAt1100) {
            if (s == 100.toShort()) foundDelayedSampleCount++
            if (s == 999.toShort()) foundNewestSampleCount++
        }

        assertEquals("Engine must receive all 160 samples from delayed chunk 1", 160, foundDelayedSampleCount)
        assertEquals("Engine must NOT receive the newly pushed chunk 2 samples", 0, foundNewestSampleCount)

        // At t = 1200ms: chunk 2 (pushed at 1100ms) delay has now elapsed
        testTimeMs = 1200L
        aec.processMicrophoneAudio(shortsToBytes(micFrame1))

        assertEquals(4, engine.calls.size)
        val (_, farEndAt1200) = engine.calls[3]
        var foundChunk2Count = 0
        for (s in farEndAt1200) {
            if (s == 999.toShort()) foundChunk2Count++
        }
        assertEquals("Engine must now receive chunk 2 samples after its delay has elapsed", 160, foundChunk2Count)
    }

    @Test
    fun testGradualDriftCompensation_NoSuddenJumps() {
        val engine = RecordingAecEngine()
        var testTimeMs = 1000L

        // Max buffer 480 samples = 3 frames of 160
        val aec = SoftwareAcousticEchoCanceller(
            engine = engine,
            resampler = IdentityResampler(),
            sampleRateHz = 16000,
            playbackSampleRateHz = 16000,
            frameSizeSamples = 160,
            maxBufferSamples = 480,
            referenceDelayMs = 0,
            clock = { testTimeMs }
        )

        // Push 800 samples (> 480 max) in one burst
        val bigPlayback = ShortArray(800) { it.toShort() }
        aec.pushPlaybackAudio(shortsToBytes(bigPlayback))

        // Process a 160-sample mic frame
        val micFrame = ShortArray(160)
        aec.processMicrophoneAudio(shortsToBytes(micFrame))

        // Overfull drop is capped to at most one frame (160 samples) per mic frame call,
        // so it compensates gradually rather than discarding hundreds of samples in one jump.
        assertEquals(1, engine.calls.size)
        val (_, farEnd) = engine.calls[0]
        assertEquals(160, farEnd.size)
    }

    @Test
    fun testSequentialPlaybackTiming_RapidChunksScheduledSequentiallyWithoutOverlap() {
        val engine = RecordingAecEngine()
        var testTimeMs = 1000L
        val delayMs = 120

        val aec = SoftwareAcousticEchoCanceller(
            engine = engine,
            resampler = IdentityResampler(),
            sampleRateHz = 16000,
            playbackSampleRateHz = 16000,
            frameSizeSamples = 160,
            referenceDelayMs = delayMs,
            clock = { testTimeMs }
        )

        // Chunk 1: 1600 samples (100ms) with value 100, pushed at t = 1000ms.
        // Starts at 1000 + 120 = 1120ms, ends at 1120 + 100 = 1220ms.
        val chunk1 = ShortArray(1600) { 100.toShort() }
        aec.pushPlaybackAudio(shortsToBytes(chunk1))

        assertEquals("Next playback end time must be 1220ms", 1220L, aec.getNextPlaybackStartTimeMs())

        // Chunk 2: 1600 samples (100ms) with value 200, pushed rapidly at t = 1005ms (5ms later).
        // Since earliestStart (1005 + 120 = 1125ms) < 1220ms, Chunk 2 must start at 1220ms and end at 1320ms!
        testTimeMs = 1005L
        val chunk2 = ShortArray(1600) { 200.toShort() }
        aec.pushPlaybackAudio(shortsToBytes(chunk2))

        assertEquals("Next playback end time must be 1320ms after sequential chunk 2", 1320L, aec.getNextPlaybackStartTimeMs())

        // At t = 1120ms: Chunk 1 becomes ready.
        testTimeMs = 1120L
        val micFrame = ShortArray(1600)
        aec.processMicrophoneAudio(shortsToBytes(micFrame))

        assertEquals(1, engine.calls.size)
        val (_, farEnd1) = engine.calls[0]
        assertEquals(1600, farEnd1.size)
        assertTrue("Chunk 1 samples must be fed to engine", farEnd1.all { it == 100.toShort() })

        // At t = 1150ms: Chunk 2 is NOT ready yet (it is scheduled for 1220ms)!
        testTimeMs = 1150L
        aec.processMicrophoneAudio(shortsToBytes(micFrame))
        assertEquals(2, engine.calls.size)
        val (_, farEndMid) = engine.calls[1]
        assertTrue("Far end must be silence at 1150ms before chunk 2 is ready", farEndMid.all { it == 0.toShort() })

        // At t = 1220ms: Chunk 2 becomes ready sequentially!
        testTimeMs = 1220L
        aec.processMicrophoneAudio(shortsToBytes(micFrame))
        assertEquals(3, engine.calls.size)
        val (_, farEnd2) = engine.calls[2]
        assertTrue("Chunk 2 samples must be fed to engine at 1220ms", farEnd2.all { it == 200.toShort() })
    }
}

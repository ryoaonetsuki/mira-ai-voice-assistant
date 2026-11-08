package io.github.ryoaonetsuki.mira.voice.pipecat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Verifies lifecycle safety, idempotence, and double-free/use-after-free prevention (Round 22 Turn 5).
 */
class AecLifecycleTest {

    private class FakeAecEngine : AcousticEchoCancellerEngine {
        var processCalls = 0
        var resetCalls = 0
        var releaseCalls = 0

        override fun process(nearEnd: ShortArray, farEnd: ShortArray, out: ShortArray, length: Int) {
            processCalls++
            System.arraycopy(nearEnd, 0, out, 0, length)
        }

        override fun reset() {
            resetCalls++
        }

        override fun release() {
            releaseCalls++
        }
    }

    private class FakeResampler : AudioResampler {
        var resampleCalls = 0
        var resetCalls = 0
        var releaseCalls = 0

        override fun resample(input: ShortArray, inRate: Int, outRate: Int): ShortArray {
            resampleCalls++
            return input.copyOf()
        }

        override fun reset() {
            resetCalls++
        }

        override fun release() {
            releaseCalls++
        }
    }

    @Test
    fun testRelease_TwiceDoesNotThrowAndDoesNotCallEngineTwice() {
        val fakeEngine = FakeAecEngine()
        val fakeResampler = FakeResampler()
        val aec = SoftwareAcousticEchoCanceller(
            engine = fakeEngine,
            resampler = fakeResampler
        )

        assertFalse("Must not be released initially", aec.isReleased())

        // First release
        aec.release()
        assertTrue("Must be marked released", aec.isReleased())
        assertEquals("Engine release must be called exactly once", 1, fakeEngine.releaseCalls)
        assertEquals("Resampler release must be called exactly once", 1, fakeResampler.releaseCalls)

        // Second release: must be safe no-op
        aec.release()
        assertTrue("Must still be marked released", aec.isReleased())
        assertEquals("Engine release must NOT be called a second time", 1, fakeEngine.releaseCalls)
        assertEquals("Resampler release must NOT be called a second time", 1, fakeResampler.releaseCalls)
    }

    @Test
    fun testAfterRelease_ProcessPushResetAreSafeNoOps() {
        val fakeEngine = FakeAecEngine()
        val fakeResampler = FakeResampler()
        val aec = SoftwareAcousticEchoCanceller(
            engine = fakeEngine,
            resampler = fakeResampler
        )

        aec.release()

        // 1. pushPlaybackAudio after release is safe no-op
        val playbackData = ByteArray(320) { 42 }
        aec.pushPlaybackAudio(playbackData)
        assertEquals("Resampler should not be called after release", 0, fakeResampler.resampleCalls)

        // 2. processMicrophoneAudio after release returns input unchanged and does not call engine
        val micData = ByteArray(320) { it.toByte() }
        val processed = aec.processMicrophoneAudio(micData)
        assertEquals("Process calls must not increase after release", 0, fakeEngine.processCalls)
        assertTrue("Returned byte array must match input unchanged", micData.contentEquals(processed))

        // 3. reset after release is safe no-op
        aec.reset()
        assertEquals("Engine reset must not be called after release", 0, fakeEngine.resetCalls)
        assertEquals("Resampler reset must not be called after release", 0, fakeResampler.resetCalls)
    }

    @Test
    fun testStopAudio_DoubleTeardownIsIdempotent() {
        val fakeEngine = FakeAecEngine()
        val fakeResampler = FakeResampler()
        val aec = SoftwareAcousticEchoCanceller(
            engine = fakeEngine,
            resampler = fakeResampler
        )

        val isAudioStopped = AtomicBoolean(false)
        var teardownExecutionCount = 0

        fun stopAudio() {
            if (!isAudioStopped.compareAndSet(false, true)) return
            teardownExecutionCount++
            aec.release()
        }

        // Simulate Stop event
        stopAudio()
        assertEquals(1, teardownExecutionCount)
        assertEquals(1, fakeEngine.releaseCalls)
        assertTrue(aec.isReleased())

        // Simulate WebsocketClosed event (triggers stopAudio again)
        stopAudio()
        assertEquals("Second stopAudio call must be a no-op", 1, teardownExecutionCount)
        assertEquals("Engine release must not be called again", 1, fakeEngine.releaseCalls)

        // Simulate WebsocketFailed event (triggers stopAudio again)
        stopAudio()
        assertEquals("Third stopAudio call must be a no-op", 1, teardownExecutionCount)
        assertEquals("Engine release must not be called again", 1, fakeEngine.releaseCalls)
    }

    @Test
    fun testNativeSpeexAecEngine_ZeroHandleIsSafeNoOp() {
        // NativeSpeexAecEngine with 0 handle (as set after destroy) must be a safe no-op without JNI calls
        val engine = NativeSpeexAecEngine(0L, 160)
        val nearEnd = ShortArray(160) { (it * 10).toShort() }
        val farEnd = ShortArray(160)
        val out = ShortArray(160)

        // process should pass nearEnd directly to out
        engine.process(nearEnd, farEnd, out, 160)
        for (i in 0 until 160) {
            assertEquals("out must match nearEnd when handle is 0", nearEnd[i], out[i])
        }

        // reset and release should not throw
        engine.reset()
        engine.release()
    }
}

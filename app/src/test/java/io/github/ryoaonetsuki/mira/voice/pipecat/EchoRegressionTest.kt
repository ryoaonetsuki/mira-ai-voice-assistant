package io.github.ryoaonetsuki.mira.voice.pipecat

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression test suite for EchoGate and EchoFrameRouter (ROUND 20 Turn 4).
 *
 * Directly tests the owner's exact symptom and requirements:
 * (a) Echo-back: simulate Mira speaking ("hello" audio out) with the mic hearing it back
 *     at echo level (mic RMS at/below bot level) -> frame must be DROPPED, and no user turn
 *     or premature interruption is produced.
 * (b) Real barge-in: while Mira speaks, a loud user voice (mic RMS well above bot level)
 *     -> frame must be FORWARDED so the user can interrupt Mira mid-sentence.
 * (c) Bot-level decay: during an inter-sentence pause where no audio out chunks arrive
 *     while isBotTalking remains true, the effective bot level decays to 0 after 300ms,
 *     allowing a normal-volume user interruption to forward cleanly.
 * (d) Interruption & Turn Complete: resets speaking state and bot level to 0.
 * (e) Echo Guard disabled: preserves unconditional pass-through when toggle is off.
 */
class EchoRegressionTest {

    /**
     * Synthesizes a 16-bit PCM mono buffer with uniform sample amplitudes
     * corresponding to the specified target normalized RMS [0.0, 1.0].
     *
     * @param targetRms Target RMS level in [0.0, 1.0].
     * @param sampleCount Number of samples (default 1600 = 100ms at 16kHz, matching AudioIn buffer).
     */
    private fun createPcmFrame(targetRms: Float, sampleCount: Int = 1600): ByteArray {
        val sampleValue = (targetRms * 32767).toInt().coerceIn(-32768, 32767).toShort()
        val bytes = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            bytes[i * 2] = (sampleValue.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sampleValue.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * Simulated test pipeline mirroring the exact frame routing in [MiraGeminiClient].
     */
    private class TestFramePipeline(echoGuardEnabled: Boolean = true) {
        val router = EchoFrameRouter(echoGuardEnabled)
        val forwardedFrames = mutableListOf<ByteArray>()
        val droppedFrames = mutableListOf<ByteArray>()
        var interruptedCount = 0

        fun onMiraAudioOutChunk(pcm: ByteArray, timestampMs: Long = 1000L) {
            val level = calculateAudioLevel(pcm)
            router.onBotTalking(true)
            router.onBotAudioLevel(level, timestampMs)
        }

        fun onMicCaptureFrame(pcm: ByteArray, timestampMs: Long = 1000L): Boolean {
            val shouldForward = router.shouldForward(pcm, timestampMs)
            if (shouldForward) {
                forwardedFrames.add(pcm)
            } else {
                droppedFrames.add(pcm)
            }
            return shouldForward
        }

        fun onServerInterrupted() {
            interruptedCount++
            router.onInterrupted()
        }

        fun onServerTurnComplete() {
            router.onTurnComplete()
        }
    }

    @Test
    fun testPcmSynthesisLevelAccuracy() {
        // Verify that createPcmFrame produces the expected calculateAudioLevel RMS
        val pcm20 = createPcmFrame(0.20f)
        assertEquals(0.20f, calculateAudioLevel(pcm20), 0.005f)

        val pcm50 = createPcmFrame(0.50f)
        assertEquals(0.50f, calculateAudioLevel(pcm50), 0.005f)

        val pcm65 = createPcmFrame(0.65f)
        assertEquals(0.65f, calculateAudioLevel(pcm65), 0.005f)

        val pcmZero = createPcmFrame(0.0f)
        assertEquals(0.0f, calculateAudioLevel(pcmZero), 0.0001f)
    }

    /**
     * Scenario (a): Echo-back.
     * Mira speaks ("hello" audio out chunk), and the loudspeaker bleeds into the mic
     * at echo level (mic RMS <= bot level).
     *
     * Expected: The frame is DROPPED. No forward event is emitted. Mira is NOT interrupted.
     */
    @Test
    fun testOwnerSymptom_EchoBackDropped_NoInterruptionProduced() {
        val pipeline = TestFramePipeline(echoGuardEnabled = true)

        // 1. Mira speaks: AudioOut chunk arrives ("hello", level ≈ 0.25f)
        val botAudioOut = createPcmFrame(0.25f)
        pipeline.onMiraAudioOutChunk(botAudioOut, timestampMs = 1000L)

        assertTrue("Bot must be marked talking", pipeline.router.isBotTalking())
        assertEquals("Bot level must match chunk level", 0.25f, pipeline.router.getRawBotLevel(), 0.005f)

        // 2. Microphone captures acoustic echo back from loudspeaker:
        // Due to acoustic attenuation / room coupling, mic RMS is <= bot level (e.g. 0.20f)
        val echoMicFrame = createPcmFrame(0.20f)
        val forwarded = pipeline.onMicCaptureFrame(echoMicFrame, timestampMs = 1000L)

        // 3. Verification: Frame must be DROPPED
        assertFalse("Echo frame at 0.20f with bot at 0.25f MUST be dropped", forwarded)
        assertEquals("Forwarded frames must be empty", 0, pipeline.forwardedFrames.size)
        assertEquals("Dropped frames must have 1 frame", 1, pipeline.droppedFrames.size)
        assertEquals("Interruption count must be 0", 0, pipeline.interruptedCount)
        assertTrue("Mira must still be talking (not cut off)", pipeline.router.isBotTalking())

        // 4. Test exact 1:1 coupling (mic RMS == bot level == 0.25f)
        val equalCouplingMicFrame = createPcmFrame(0.25f)
        val forwardedEqual = pipeline.onMicCaptureFrame(equalCouplingMicFrame, timestampMs = 1000L)
        assertFalse("Echo frame at 0.25f matching bot level 0.25f MUST be dropped", forwardedEqual)
        assertEquals(0, pipeline.forwardedFrames.size)
        assertEquals(2, pipeline.droppedFrames.size)
    }

    /**
     * Scenario (b): Real barge-in.
     * While Mira is speaking, a loud user voice (mic RMS well above bot level + safety threshold) arrives.
     *
     * Expected: The frame is FORWARDED so the server VAD can interrupt Mira.
     */
    @Test
    fun testOwnerSymptom_RealBargeInForwards_AllowsInterruption() {
        val pipeline = TestFramePipeline(echoGuardEnabled = true)

        // 1. Mira speaks: bot level ≈ 0.25f (threshold = 0.25 * 1.0 + 0.02 = 0.27f)
        val botAudioOut = createPcmFrame(0.25f)
        pipeline.onMiraAudioOutChunk(botAudioOut, timestampMs = 1000L)

        // 2. User interrupts loudly: mic RMS = 0.65f (> 0.27f threshold)
        val loudUserBargeIn = createPcmFrame(0.65f)
        val forwarded = pipeline.onMicCaptureFrame(loudUserBargeIn, timestampMs = 1000L)

        // 3. Verification: Frame must be FORWARDED
        assertTrue("Loud barge-in frame (0.65 > 0.27) MUST be forwarded", forwarded)
        assertEquals("Forwarded frames must have 1 frame", 1, pipeline.forwardedFrames.size)
        assertEquals("Dropped frames must be 0", 0, pipeline.droppedFrames.size)

        // 4. Server receives forwarded user audio and acknowledges interruption:
        pipeline.onServerInterrupted()
        assertFalse("Bot talking state must reset to false after interruption", pipeline.router.isBotTalking())
        assertEquals("Bot level must reset to 0 after interruption", 0.0f, pipeline.router.getRawBotLevel(), 0.0001f)

        // 5. Subsequent user speech continues forwarding unconditionally
        val normalUserSpeech = createPcmFrame(0.15f)
        val forwardedSubsequent = pipeline.onMicCaptureFrame(normalUserSpeech, timestampMs = 1050L)
        assertTrue("Subsequent user speech must forward unconditionally", forwardedSubsequent)
        assertEquals(2, pipeline.forwardedFrames.size)
    }

    /**
     * Scenario (c): Inter-sentence pause decay.
     * Mira speaks, then pauses between sentences. No new audio out chunks arrive,
     * but isBotTalking remains true.
     *
     * Expected:
     * - Before 300ms, level decays exponentially.
     * - After 300ms, effective bot level decays to 0.
     * - Normal-volume user speech (e.g. 0.18f) during this pause is FORWARDED (not dropped by stale level).
     */
    @Test
    fun testOwnerSymptom_DecayDuringPauseAllowsNormalInterruption() {
        val pipeline = TestFramePipeline(echoGuardEnabled = true)

        // 1. Mira finishes a loud sentence chunk at t = 1000ms: bot level = 0.35f
        // Active until 1000 + 100 + 250 = 1350ms (chunk duration + drain margin)
        val botAudioOut = createPcmFrame(0.35f)
        pipeline.onMiraAudioOutChunk(botAudioOut, timestampMs = 1000L)

        // 2. Inside drain window (t = 1150ms, t = 1350ms): effective level stays at full 0.35f (no premature decay)
        val effective150 = pipeline.router.getEffectiveBotLevel(1150L)
        assertEquals("Effective level must not prematurely decay during drain window", 0.35f, effective150, 0.005f)

        // 3. 150ms after drain window (t = 1500ms): effective level halved to ~0.175f
        val effectivePostDrain150 = pipeline.router.getEffectiveBotLevel(1500L)
        assertEquals(0.175f, effectivePostDrain150, 0.005f)

        // 4. 600ms after drain window (t = 1950ms): pause exceeds 600ms cutoff -> 0.0f
        val effectiveMaxAge = pipeline.router.getEffectiveBotLevel(1950L)
        assertEquals("Effective level must be 0 after 600ms cutoff", 0.0f, effectiveMaxAge, 0.0001f)
        assertTrue("isBotTalking is still true during inter-sentence pause", pipeline.router.isBotTalking())

        // 5. User speaks at normal conversational volume (mic RMS = 0.18f) after pause
        val normalUserPcm = createPcmFrame(0.18f)
        val forwarded = pipeline.onMicCaptureFrame(normalUserPcm, timestampMs = 1950L)

        // 6. With decayed floor 0.02f, 0.18 > 0.02 -> FORWARDED
        assertTrue("Normal user speech during bot pause MUST be forwarded", forwarded)
        assertEquals(1, pipeline.forwardedFrames.size)
        assertEquals(0, pipeline.droppedFrames.size)
    }

    /**
     * Scenario (d): Turn Complete with AudioTrack drain window.
     * Mira finishes her response and Gemini sends turnComplete.
     *
     * Expected:
     * - During the active AudioTrack drain window (t = 1050ms, within chunk duration + 250ms margin),
     *   leaked acoustic echo is dropped while loud barge-in is passed.
     * - After the drain window and reverberation decay have elapsed (t = 2000ms),
     *   subsequent user input (even quiet speech) passes through unconditionally.
     */
    @Test
    fun testOwnerSymptom_TurnCompleteAllowsImmediateUserTurn() {
        val pipeline = TestFramePipeline(echoGuardEnabled = true)

        val botAudioOut = createPcmFrame(0.30f)
        pipeline.onMiraAudioOutChunk(botAudioOut, timestampMs = 1000L)
        assertTrue(pipeline.router.isBotTalking(1000L))

        // Server signals turnComplete
        pipeline.onServerTurnComplete()

        // 1. During drain window (1050ms): audio is still playing out of speaker.
        // Leaked echo at 0.20f must be dropped so Mira does not respond to her own words
        val echoDuringDrain = createPcmFrame(0.20f)
        val forwardedEcho = pipeline.onMicCaptureFrame(echoDuringDrain, timestampMs = 1050L)
        assertFalse("Echo during AudioTrack drain after turnComplete MUST be dropped", forwardedEcho)

        // 2. Loud user barge-in during drain (0.85f) must still be forwarded
        val bargeInDuringDrain = createPcmFrame(0.85f)
        val forwardedBargeIn = pipeline.onMicCaptureFrame(bargeInDuringDrain, timestampMs = 1050L)
        assertTrue("Loud user barge-in during drain window must still be forwarded", forwardedBargeIn)

        // 3. After drain window and decay have completed (2000ms):
        // Quiet user turn (mic RMS = 0.02f) passes unconditionally
        val quietUser = createPcmFrame(0.02f)
        val forwardedQuiet = pipeline.onMicCaptureFrame(quietUser, timestampMs = 2000L)
        assertTrue("User turn after drain window has elapsed must forward unconditionally", forwardedQuiet)
    }

    /**
     * Scenario (e): Echo Guard Disabled.
     * User has toggled Echo Guard OFF in Voice Engine settings.
     *
     * Expected: Echo frames pass through unconditionally (matching legacy behavior).
     */
    @Test
    fun testEchoGuardDisabled_AlwaysForwards() {
        val pipeline = TestFramePipeline(echoGuardEnabled = false)

        val botAudioOut = createPcmFrame(0.40f)
        pipeline.onMiraAudioOutChunk(botAudioOut, timestampMs = 1000L)

        // Echo frame that would normally be dropped
        val echoFrame = createPcmFrame(0.15f)
        val forwarded = pipeline.onMicCaptureFrame(echoFrame, timestampMs = 1000L)

        assertTrue("When echoGuardEnabled is false, echo frame must forward unconditionally", forwarded)
        assertEquals(1, pipeline.forwardedFrames.size)
        assertEquals(0, pipeline.droppedFrames.size)
    }

    /**
     * Multiple sequential frames: simulates 5 frames of echo during playback,
     * followed by 3 frames of loud user interruption.
     */
    @Test
    fun testSequentialFrames_EchoDroppedThenInterrupted() {
        val pipeline = TestFramePipeline(echoGuardEnabled = true)

        // Mira speaking at 0.30f (threshold = 0.50f)
        val botAudioOut = createPcmFrame(0.30f)
        pipeline.onMiraAudioOutChunk(botAudioOut, timestampMs = 1000L)

        // 5 consecutive echo frames (RMS 0.20f)
        val echoPcm = createPcmFrame(0.20f)
        for (i in 0 until 5) {
            val f = pipeline.onMicCaptureFrame(echoPcm, timestampMs = 1000L + i * 20L)
            assertFalse("Echo frame #$i must be dropped", f)
        }
        assertEquals(0, pipeline.forwardedFrames.size)
        assertEquals(5, pipeline.droppedFrames.size)

        // Now user interrupts loudly (RMS 0.70f)
        val interruptPcm = createPcmFrame(0.70f)
        for (i in 0 until 3) {
            val f = pipeline.onMicCaptureFrame(interruptPcm, timestampMs = 1100L + i * 20L)
            assertTrue("Interruption frame #$i must be forwarded", f)
        }
        assertEquals(3, pipeline.forwardedFrames.size)
        assertEquals(5, pipeline.droppedFrames.size)
    }
}

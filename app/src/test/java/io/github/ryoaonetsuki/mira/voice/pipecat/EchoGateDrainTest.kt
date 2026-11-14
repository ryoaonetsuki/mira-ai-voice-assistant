package io.github.ryoaonetsuki.mira.voice.pipecat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying drain-aware bot level retention and barge-in forwarding (Round 22 Turn 5).
 */
class EchoGateDrainTest {

    @Test
    fun testDrainWindow_BotLevelDoesNotDecayInsideDrainWindow() {
        val router = EchoFrameRouter(echoGuardEnabled = true)
        router.onBotTalking(true)

        val startTimeMs = 1000L
        val chunkDurationMs = 100L
        val botLevel = 0.8f

        // Audio chunk of 100ms arrives at 1000ms.
        // Drain margin = 250ms, so audioActiveUntil = 1000 + 100 + 250 = 1350ms.
        router.onBotAudioLevel(botLevel, startTimeMs, chunkDurationMs)

        assertEquals(1350L, router.getAudioActiveUntilMs())

        // Inside the chunk duration (1050ms): full bot level, no decay
        assertEquals(0.8f, router.getEffectiveBotLevel(1050L), 0.0001f)

        // At end of chunk duration (1100ms): full bot level, no decay
        assertEquals(0.8f, router.getEffectiveBotLevel(1100L), 0.0001f)

        // 150ms into drain window (1250ms): still full bot level, no premature decay!
        assertEquals(0.8f, router.getEffectiveBotLevel(1250L), 0.0001f)

        // At exact boundary of drain window (1350ms): still full bot level
        assertEquals(0.8f, router.getEffectiveBotLevel(1350L), 0.0001f)
    }

    @Test
    fun testPostDrainDecay_DecaysAndDropsToZeroAfterMaxAge() {
        val router = EchoFrameRouter(echoGuardEnabled = true)
        router.onBotTalking(true)

        val startTimeMs = 1000L
        val chunkDurationMs = 100L
        val botLevel = 0.8f

        // audioActiveUntil = 1350ms
        router.onBotAudioLevel(botLevel, startTimeMs, chunkDurationMs)

        // 1 half-life (150ms) after drain window (1350 + 150 = 1500ms): level drops by 50% to ~0.4f
        assertEquals(0.4f, router.getEffectiveBotLevel(1500L), 0.005f)

        // 2 half-lives (300ms) after drain window (1350 + 300 = 1650ms): level drops to ~0.2f
        assertEquals(0.2f, router.getEffectiveBotLevel(1650L), 0.005f)

        // At max age (600ms) after drain window (1350 + 600 = 1950ms): level drops to 0.0f
        assertEquals(0.0f, router.getEffectiveBotLevel(1950L), 0.0001f)

        // Beyond max age (2500ms): remains 0.0f
        assertEquals(0.0f, router.getEffectiveBotLevel(2500L), 0.0001f)
    }

    @Test
    fun testBargeIn_LoudSpeechPassesWhileBotTalking() {
        // While bot is talking at high level (botLevel = 0.5f)
        // Threshold formula: botLevel * RATIO (1.0) + FLOOR (0.02) = 0.5 * 1.0 + 0.02 = 0.52f

        // 1. Acoustic echo or normal ambient sound (micRms = 0.40f) is below threshold: must be dropped
        assertFalse(
            "Echo frame below barge-in threshold must be dropped",
            EchoGate.shouldForward(micRms = 0.40f, botLevel = 0.5f, isBotTalking = true, echoGuardEnabled = true)
        )

        // 2. User speaking loudly right into the mic (micRms = 0.90f > 0.52f): must be forwarded!
        assertTrue(
            "Loud user speech exceeding bot level * ratio + floor must be forwarded for barge-in",
            EchoGate.shouldForward(micRms = 0.90f, botLevel = 0.5f, isBotTalking = true, echoGuardEnabled = true)
        )

        // 3. Very loud user speech (micRms = 0.98f): forwarded
        assertTrue(
            "Strong user barge-in must pass cleanly",
            EchoGate.shouldForward(micRms = 0.98f, botLevel = 0.5f, isBotTalking = true, echoGuardEnabled = true)
        )
    }

    @Test
    fun testDrainWindow_EchoGateRejectsEchoDuringDrain() {
        val router = EchoFrameRouter(echoGuardEnabled = true)
        router.onBotTalking(true)
        router.onBotAudioLevel(0.4f, timestampMs = 1000L, chunkDurationMs = 100L)
        // Drain window active until 1350L.
        // During drain at 1200L, speaker is still outputting audio from AudioTrack buffer.
        val effectiveBot = router.getEffectiveBotLevel(1200L)
        assertEquals(0.4f, effectiveBot, 0.0001f)

        // Mic hears echo (e.g. 0.3f < 0.4 * 1.0 + 0.02 = 0.42): frame must be dropped!
        val shouldForwardEcho = EchoGate.shouldForward(
            micRms = 0.3f,
            botLevel = effectiveBot,
            isBotTalking = router.isBotTalking(),
            echoGuardEnabled = true
        )
        assertFalse("Echo during AudioTrack drain must be blocked by the gate", shouldForwardEcho)

        // But loud user barge-in (0.85f > 0.42) still gets through!
        val shouldForwardUser = EchoGate.shouldForward(
            micRms = 0.85f,
            botLevel = effectiveBot,
            isBotTalking = router.isBotTalking(),
            echoGuardEnabled = true
        )
        assertTrue("Loud user barge-in during drain window must still be forwarded", shouldForwardUser)
    }

    @Test
    fun testDrainAccumulation_RapidChunksAccumulateDrainWindow() {
        val router = EchoFrameRouter(echoGuardEnabled = true)
        router.onBotTalking(true)

        // Chunk 1: 100ms at t = 1000ms. Drain margin = 250ms.
        // End time without second chunk = 1000 + 100 + 250 = 1350ms.
        router.onBotAudioLevel(0.5f, timestampMs = 1000L, chunkDurationMs = 100L)
        assertEquals(1350L, router.getAudioActiveUntilMs())

        // Chunk 2: 100ms at t = 1005ms (rapid burst 5ms later).
        // Since Chunk 1 plays until 1100ms, Chunk 2 plays from 1100ms to 1200ms.
        // Drain margin = 250ms, so activeUntil must accumulate to 1200 + 250 = 1450ms!
        router.onBotAudioLevel(0.5f, timestampMs = 1005L, chunkDurationMs = 100L)
        assertEquals("Rapid chunks must accumulate playback duration into drain window", 1450L, router.getAudioActiveUntilMs())

        // At t = 1360ms: without accumulation, chunk 1's drain window would have expired (1350ms).
        // With accumulation, chunk 2 is still draining (until 1450ms)!
        assertTrue("Bot must still be talking at 1360ms due to accumulated drain", router.isBotTalking(1360L))
        assertEquals(0.5f, router.getEffectiveBotLevel(1360L), 0.001f)
    }

    @Test
    fun testTurnComplete_DrainWindowProtectsTailUntilElapsed() {
        val router = EchoFrameRouter(echoGuardEnabled = true)
        router.onBotTalking(true)
        router.onBotAudioLevel(0.4f, timestampMs = 1000L, chunkDurationMs = 100L)
        // audioActiveUntil = 1350ms

        // Server signals turn complete at t = 1000ms
        router.onTurnComplete()

        // 1. During drain at 1050ms: bot is still physically outputting audio
        assertTrue("isBotTalking must remain true during drain after turnComplete", router.isBotTalking(1050L))
        val echoBlocked = !router.shouldForward(micRms = 0.20f, nowMs = 1050L)
        assertTrue("Echo during drain must be blocked after turnComplete", echoBlocked)

        // 2. Loud barge-in still passes during drain
        val bargeInPassed = router.shouldForward(micRms = 0.85f, nowMs = 1050L)
        assertTrue("Loud barge-in must pass during drain", bargeInPassed)

        // 3. Beyond drain and decay cutoff (t = 2000ms):
        assertFalse("isBotTalking must become false after drain has elapsed", router.isBotTalking(2000L))
        val quietUserPassed = router.shouldForward(micRms = 0.02f, nowMs = 2000L)
        assertTrue("Quiet user turn must pass unconditionally after drain window", quietUserPassed)
    }
}

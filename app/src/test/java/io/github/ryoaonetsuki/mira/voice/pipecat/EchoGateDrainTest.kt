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
        // Threshold formula: botLevel * RATIO (1.5) + FLOOR (0.05) = 0.5 * 1.5 + 0.05 = 0.80f

        // 1. Acoustic echo or normal ambient sound (micRms = 0.40f) is below threshold: must be dropped
        assertFalse(
            "Echo frame below barge-in threshold must be dropped",
            EchoGate.shouldForward(micRms = 0.40f, botLevel = 0.5f, isBotTalking = true, echoGuardEnabled = true)
        )

        // 2. User speaking loudly right into the mic (micRms = 0.90f > 0.80f): must be forwarded!
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

        // Mic hears echo (e.g. 0.3f < 0.4 * 1.5 + 0.05 = 0.65): frame must be dropped!
        val shouldForwardEcho = EchoGate.shouldForward(
            micRms = 0.3f,
            botLevel = effectiveBot,
            isBotTalking = router.isBotTalking(),
            echoGuardEnabled = true
        )
        assertFalse("Echo during AudioTrack drain must be blocked by the gate", shouldForwardEcho)

        // But loud user barge-in (0.85f > 0.65) still gets through!
        val shouldForwardUser = EchoGate.shouldForward(
            micRms = 0.85f,
            botLevel = effectiveBot,
            isBotTalking = router.isBotTalking(),
            echoGuardEnabled = true
        )
        assertTrue("Loud user barge-in during drain window must still be forwarded", shouldForwardUser)
    }
}

package io.github.ryoaonetsuki.mira.voice.pipecat

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [EchoGate] speaking-state safety net (ROUND 20 Turn 3).
 *
 * Verifies frame-forwarding gating logic across all required scenarios:
 * 1. Gate disabled (echoGuardEnabled = false) -> always forwards unconditionally.
 * 2. Bot silent (isBotTalking = false) -> always forwards user speech unconditionally.
 * 3. Bot talking + mic at echo level (micRms <= botLevel, or slightly above botLevel but below threshold) -> DROPS frame.
 * 4. Bot talking + loud user speech (micRms well above botLevel and threshold) -> FORWARDS frame.
 * 5. Boundary values (0f, 1f).
 */
class EchoGateTest {

    @Test
    fun testConstants() {
        assertEquals("Ratio should be 1.5f (+3.5 dB safety multiplier)", 1.5f, EchoGate.RATIO, 0.0001f)
        assertEquals("Floor should be 0.05f (5% RMS noise floor)", 0.05f, EchoGate.FLOOR, 0.0001f)
        assertEquals("Half-life should be 150ms", 150L, EchoGate.BOT_LEVEL_HALF_LIFE_MS)
        assertEquals("Max age cutoff should be 600ms", 600L, EchoGate.BOT_LEVEL_MAX_AGE_MS)
    }

    @Test
    fun testGateOff_AlwaysForwards() {
        // When echoGuardEnabled is false, old behaviour is preserved: all frames pass
        assertTrue(
            "Gate off must forward even when bot talking and mic is quiet",
            EchoGate.shouldForward(micRms = 0.01f, botLevel = 0.30f, isBotTalking = true, echoGuardEnabled = false)
        )
        assertTrue(
            "Gate off must forward when mic is at echo level",
            EchoGate.shouldForward(micRms = 0.20f, botLevel = 0.20f, isBotTalking = true, echoGuardEnabled = false)
        )
        assertTrue(
            "Gate off must forward when mic is 0",
            EchoGate.shouldForward(micRms = 0.0f, botLevel = 0.50f, isBotTalking = true, echoGuardEnabled = false)
        )
        assertTrue(
            "Gate off must forward when bot is silent",
            EchoGate.shouldForward(micRms = 0.10f, botLevel = 0.0f, isBotTalking = false, echoGuardEnabled = false)
        )
    }

    @Test
    fun testBotSilent_AlwaysForwards() {
        // While Mira is silent, the initial user turn and ongoing conversational turns pass unconditionally
        assertTrue(
            "Bot silent must forward normal user speech",
            EchoGate.shouldForward(micRms = 0.25f, botLevel = 0.0f, isBotTalking = false, echoGuardEnabled = true)
        )
        assertTrue(
            "Bot silent must forward whisper / low-energy speech",
            EchoGate.shouldForward(micRms = 0.01f, botLevel = 0.0f, isBotTalking = false, echoGuardEnabled = true)
        )
        assertTrue(
            "Bot silent must forward zero-level mic frames",
            EchoGate.shouldForward(micRms = 0.0f, botLevel = 0.0f, isBotTalking = false, echoGuardEnabled = true)
        )
        assertTrue(
            "Bot silent must forward even if stale botLevel exists in holder",
            EchoGate.shouldForward(micRms = 0.03f, botLevel = 0.40f, isBotTalking = false, echoGuardEnabled = true)
        )
    }

    @Test
    fun testBotTalking_EchoLevelMic_DropsFrame() {
        // Threshold formula: botLevel * 1.5f + 0.05f
        // For botLevel = 0.20f: threshold = 0.20 * 1.5 + 0.05 = 0.35f

        // Case A: micRms <= botLevel (direct acoustic coupling / attenuated echo)
        assertFalse(
            "Mic energy lower than bot speaker level must be dropped",
            EchoGate.shouldForward(micRms = 0.15f, botLevel = 0.20f, isBotTalking = true, echoGuardEnabled = true)
        )
        assertFalse(
            "Mic energy equal to bot speaker level must be dropped",
            EchoGate.shouldForward(micRms = 0.20f, botLevel = 0.20f, isBotTalking = true, echoGuardEnabled = true)
        )

        // Case B: micRms slightly above botLevel but below threshold (0.20 < micRms <= 0.35)
        assertFalse(
            "Mic energy slightly above botLevel (0.25 vs threshold 0.35) must be dropped",
            EchoGate.shouldForward(micRms = 0.25f, botLevel = 0.20f, isBotTalking = true, echoGuardEnabled = true)
        )
        assertFalse(
            "Mic energy just below threshold (0.34 vs threshold 0.35) must be dropped",
            EchoGate.shouldForward(micRms = 0.34f, botLevel = 0.20f, isBotTalking = true, echoGuardEnabled = true)
        )
        assertFalse(
            "Mic energy exactly at threshold (0.35 vs threshold 0.35) must be dropped",
            EchoGate.shouldForward(micRms = 0.35f, botLevel = 0.20f, isBotTalking = true, echoGuardEnabled = true)
        )

        // Case C: Quiet playback (botLevel = 0.04f, threshold = 0.04 * 1.5 + 0.05 = 0.11f)
        assertFalse(
            "Quiet speaker output with ambient room noise (0.07 vs threshold 0.11) must be dropped",
            EchoGate.shouldForward(micRms = 0.07f, botLevel = 0.04f, isBotTalking = true, echoGuardEnabled = true)
        )
    }

    @Test
    fun testBotTalking_LoudUserSpeech_ForwardsFrame() {
        // User deliberately interrupts Mira: mic energy clearly exceeds the safety threshold

        // For botLevel = 0.20f (threshold = 0.35f), user speaking near the mic at micRms = 0.50f
        assertTrue(
            "Loud user speech exceeding threshold (0.50 > 0.35) must be forwarded to trigger interruption",
            EchoGate.shouldForward(micRms = 0.50f, botLevel = 0.20f, isBotTalking = true, echoGuardEnabled = true)
        )

        // Quiet bot playback (botLevel = 0.04f, threshold = 0.11f), normal user speech at micRms = 0.25f
        assertTrue(
            "Normal user speech during quiet playback (0.25 > 0.11) must be forwarded",
            EchoGate.shouldForward(micRms = 0.25f, botLevel = 0.04f, isBotTalking = true, echoGuardEnabled = true)
        )

        // Loud bot playback (botLevel = 0.40f, threshold = 0.65f), loud close-mic shout at micRms = 0.80f
        assertTrue(
            "Loud user barge-in during loud playback (0.80 > 0.65) must be forwarded",
            EchoGate.shouldForward(micRms = 0.80f, botLevel = 0.40f, isBotTalking = true, echoGuardEnabled = true)
        )
    }

    @Test
    fun testBoundaryValues() {
        // micRms = 0, botLevel = 0: threshold = 0 * 1.5 + 0.05 = 0.05 -> 0 > 0.05 is false
        assertFalse(
            "mic=0, bot=0 with bot talking must DROP",
            EchoGate.shouldForward(micRms = 0.0f, botLevel = 0.0f, isBotTalking = true, echoGuardEnabled = true)
        )

        // micRms = 1, botLevel = 0: threshold = 0.05 -> 1.0 > 0.05 is true
        assertTrue(
            "mic=1, bot=0 with bot talking must FORWARD",
            EchoGate.shouldForward(micRms = 1.0f, botLevel = 0.0f, isBotTalking = true, echoGuardEnabled = true)
        )

        // micRms = 1, botLevel = 1: threshold = 1 * 1.5 + 0.05 = 1.55 -> 1.0 > 1.55 is false
        assertFalse(
            "mic=1, bot=1 with bot talking must DROP (echo exceeds maximum scale)",
            EchoGate.shouldForward(micRms = 1.0f, botLevel = 1.0f, isBotTalking = true, echoGuardEnabled = true)
        )

        // micRms = 0, botLevel = 1: threshold = 1.55 -> 0 > 1.55 is false
        assertFalse(
            "mic=0, bot=1 with bot talking must DROP",
            EchoGate.shouldForward(micRms = 0.0f, botLevel = 1.0f, isBotTalking = true, echoGuardEnabled = true)
        )

        // Bot silent with extremes: always forward
        assertTrue(
            "mic=0, bot=0 with bot silent must FORWARD",
            EchoGate.shouldForward(micRms = 0.0f, botLevel = 0.0f, isBotTalking = false, echoGuardEnabled = true)
        )
        assertTrue(
            "mic=1, bot=1 with bot silent must FORWARD",
            EchoGate.shouldForward(micRms = 1.0f, botLevel = 1.0f, isBotTalking = false, echoGuardEnabled = true)
        )

        // Gate off with extremes: always forward
        assertTrue(
            "mic=0, bot=1 with gate off must FORWARD",
            EchoGate.shouldForward(micRms = 0.0f, botLevel = 1.0f, isBotTalking = true, echoGuardEnabled = false)
        )
        assertTrue(
            "mic=1, bot=1 with gate off must FORWARD",
            EchoGate.shouldForward(micRms = 1.0f, botLevel = 1.0f, isBotTalking = true, echoGuardEnabled = false)
        )
    }

    @Test
    fun testDefaultParameterValues() {
        // shouldForward has default echoGuardEnabled = true
        assertFalse(
            "Default echoGuardEnabled must be true (active)",
            EchoGate.shouldForward(micRms = 0.10f, botLevel = 0.20f, isBotTalking = true)
        )
        assertTrue(
            "Default echoGuardEnabled with loud speech must forward",
            EchoGate.shouldForward(micRms = 0.60f, botLevel = 0.20f, isBotTalking = true)
        )
    }

    @Test
    fun testComputeEffectiveBotLevel_FreshLevelPassesUnchanged() {
        val baseTime = 10000L
        // Fresh level at same timestamp (age = 0ms)
        assertEquals(0.40f, EchoGate.computeEffectiveBotLevel(0.40f, baseTime, baseTime), 0.0001f)
        // Future timestamp / clock skew (age < 0ms)
        assertEquals(0.40f, EchoGate.computeEffectiveBotLevel(0.40f, baseTime, baseTime - 50L), 0.0001f)
        // Whisper level fresh
        assertEquals(0.08f, EchoGate.computeEffectiveBotLevel(0.08f, baseTime, baseTime), 0.0001f)
    }

    @Test
    fun testComputeEffectiveBotLevel_HalfLifeDecay() {
        val baseTime = 10000L
        // Exactly 1 half-life (150ms): level drops by 50%
        assertEquals(0.20f, EchoGate.computeEffectiveBotLevel(0.40f, baseTime, baseTime + 150L), 0.001f)
        // Half of a half-life (75ms): level drops by 2^(-0.5) ≈ 0.7071
        val expected75ms = (0.40f * Math.pow(0.5, 0.5)).toFloat()
        assertEquals(expected75ms, EchoGate.computeEffectiveBotLevel(0.40f, baseTime, baseTime + 75L), 0.001f)
        // 225ms (1.5 half-lives): level drops by 2^(-1.5) ≈ 0.3535
        val expected225ms = (0.40f * Math.pow(0.5, 1.5)).toFloat()
        assertEquals(expected225ms, EchoGate.computeEffectiveBotLevel(0.40f, baseTime, baseTime + 225L), 0.001f)
    }

    @Test
    fun testComputeEffectiveBotLevel_OldLevelDecaysToZero() {
        val baseTime = 10000L
        // Age exactly at cutoff (600ms) -> 0.0f
        assertEquals(0.0f, EchoGate.computeEffectiveBotLevel(0.40f, baseTime, baseTime + 600L), 0.0001f)
        // Age well beyond cutoff (650ms, 800ms, 2000ms) -> 0.0f
        assertEquals(0.0f, EchoGate.computeEffectiveBotLevel(0.40f, baseTime, baseTime + 650L), 0.0001f)
        assertEquals(0.0f, EchoGate.computeEffectiveBotLevel(0.40f, baseTime, baseTime + 800L), 0.0001f)
        assertEquals(0.0f, EchoGate.computeEffectiveBotLevel(0.40f, baseTime, baseTime + 2000L), 0.0001f)
        // Zero timestamp or zero level -> 0.0f
        assertEquals(0.0f, EchoGate.computeEffectiveBotLevel(0.40f, 0L, baseTime), 0.0001f)
        assertEquals(0.0f, EchoGate.computeEffectiveBotLevel(0.0f, baseTime, baseTime), 0.0001f)
        assertEquals(0.0f, EchoGate.computeEffectiveBotLevel(-0.1f, baseTime, baseTime), 0.0001f)
    }

    @Test
    fun testBotPause_NormalUserSpeechForwards() {
        val botChunkTime = 10000L
        val initialBotLevel = 0.40f

        // 1. During active bot speech (age = 0ms), normal user voice (0.20f) is dropped because threshold is 0.40 * 1.5 + 0.05 = 0.65f
        val freshEffective = EchoGate.computeEffectiveBotLevel(initialBotLevel, botChunkTime, botChunkTime)
        assertFalse(
            "During active playback, normal mic RMS is below high threshold and dropped",
            EchoGate.shouldForward(micRms = 0.20f, botLevel = freshEffective, isBotTalking = true, echoGuardEnabled = true)
        )

        // 2. During inter-sentence pause (650ms later, no new chunks arrived, isBotTalking still true)
        val pauseTime = botChunkTime + 650L
        val decayedEffective = EchoGate.computeEffectiveBotLevel(initialBotLevel, botChunkTime, pauseTime)
        assertEquals("Decayed effective level during 650ms pause must be 0", 0.0f, decayedEffective, 0.0001f)

        // 3. Normal user conversational voice (micRms = 0.20f) easily exceeds the decayed floor (0.05f)
        assertTrue(
            "Normal user voice during bot pause MUST forward even while isBotTalking remains true",
            EchoGate.shouldForward(micRms = 0.20f, botLevel = decayedEffective, isBotTalking = true, echoGuardEnabled = true)
        )

        // 4. Whisper / quiet speech (micRms = 0.08f) during pause also forwards
        assertTrue(
            "Quiet user speech (0.08 > 0.05) during pause must forward",
            EchoGate.shouldForward(micRms = 0.08f, botLevel = decayedEffective, isBotTalking = true, echoGuardEnabled = true)
        )

        // 5. Ambient room noise (micRms = 0.02f) during pause is still safely rejected by the noise floor
        assertFalse(
            "Ambient room noise (0.02 <= 0.05) during pause must be dropped",
            EchoGate.shouldForward(micRms = 0.02f, botLevel = decayedEffective, isBotTalking = true, echoGuardEnabled = true)
        )
    }

    @Test
    fun testEchoFrameRouter_StateTransitions() {
        val router = EchoFrameRouter(echoGuardEnabled = true)
        assertFalse(router.isBotTalking())
        assertEquals(0.0f, router.getRawBotLevel(), 0.0001f)
        assertEquals(0.0f, router.getEffectiveBotLevel(), 0.0001f)

        // Bot starts talking and plays audio (100ms chunk + 250ms drain margin = active until 1350L)
        router.onBotTalking(true)
        router.onBotAudioLevel(0.30f, 1000L)
        assertTrue(router.isBotTalking())
        assertEquals(0.30f, router.getRawBotLevel(), 0.0001f)
        // Inside drain window: no decay
        assertEquals(0.30f, router.getEffectiveBotLevel(1000L), 0.0001f)
        assertEquals(0.30f, router.getEffectiveBotLevel(1150L), 0.0001f)
        assertEquals(0.30f, router.getEffectiveBotLevel(1350L), 0.0001f)
        // After drain window (1350L): decays with 150ms half-life
        assertEquals(0.15f, router.getEffectiveBotLevel(1500L), 0.001f)
        assertEquals(0.0f, router.getEffectiveBotLevel(1950L), 0.0001f)

        // Interrupted resets state
        router.onInterrupted()
        assertFalse(router.isBotTalking())
        assertEquals(0.0f, router.getRawBotLevel(), 0.0001f)
        assertEquals(0.0f, router.getEffectiveBotLevel(1000L), 0.0001f)

        // Bot starts talking again, then turn completes — Round 24 Turn 5:
        // onTurnComplete preserves the drain window (audioActiveUntil = 2000 + 100 + 250 = 2350L)
        // so the AudioTrack speaker tail keeps the gate closed; the raw level is preserved too.
        router.onBotTalking(true)
        router.onBotAudioLevel(0.25f, 2000L)
        assertTrue(router.isBotTalking(2100L))
        router.onTurnComplete()
        assertTrue(router.isBotTalking(2100L))
        assertEquals(0.25f, router.getRawBotLevel(), 0.0001f)
        // After the drain window expires, the gate opens again
        assertFalse(router.isBotTalking(3000L))
    }
}

package io.github.ryoaonetsuki.mira.voice.pipecat

import io.github.ryoaonetsuki.mira.identity.MiraIdentityInterceptor
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure JVM unit test suite verifying Part B:
 * 1. Single voice session slot invariant (MAX_CONCURRENT_SESSIONS == 1).
 * 2. Un-cancellable start race / zombie session prevention via generation tokens (VoiceSessionGuard).
 * 3. Echo detection and suppression in VoiceEchoDetector.
 * 4. Identity canned answer suppression when mic captures model's own speech echo.
 */
class SingleVoiceSessionTest {

    @Test
    fun testMaxConcurrentSessions_IsConfiguredToExactlyOne() {
        val guard = VoiceSessionGuard()
        assertEquals(
            "Guard must enforce exactly 1 max concurrent session",
            1,
            guard.maxConcurrentSessions
        )
    }

    @Test
    fun testSingleVoiceSlotInvariant_AcquireAndRelease() {
        val guard = VoiceSessionGuard(maxConcurrentSessions = 1)
        assertEquals(0, guard.getActiveSessionCount())

        // 1st session acquires slot -> succeeds
        assertTrue("First session must successfully acquire live session slot", guard.acquireSessionSlot())
        assertEquals(1, guard.getActiveSessionCount())

        // 2nd session attempts to acquire slot concurrently -> must fail
        assertFalse("Second concurrent session must be blocked", guard.acquireSessionSlot())
        assertEquals("Slot count must remain clamped to 1", 1, guard.getActiveSessionCount())

        // 1st session finishes / stops -> releases slot
        guard.releaseSessionSlot()
        assertEquals("Slot count must return to 0 after release", 0, guard.getActiveSessionCount())

        // Next session can now acquire slot
        assertTrue("Next session must be able to acquire slot once previous has stopped", guard.acquireSessionSlot())
        assertEquals(1, guard.getActiveSessionCount())
        guard.releaseSessionSlot()
    }

    @Test
    fun testZombieSessionStartRace_GenerationTokenPreventsZombieSession() {
        val guard = VoiceSessionGuard(maxConcurrentSessions = 1)
        var liveSessionsCreated = 0

        // Step 1: User taps voice button -> start requested (gen = 1)
        val gen1 = guard.nextGeneration()
        assertEquals(1L, gen1)

        // Step 2: While async I/O / DB prefs are loading, user taps STOP -> gen = 2
        val gen2 = guard.invalidateActiveSessions()
        assertEquals(2L, gen2)

        // Step 3: When gen1 async block finishes, it checks token
        val shouldProceedGen1 = guard.isGenerationValid(gen1)
        assertFalse("Superseded start session (gen1) must NOT proceed", shouldProceedGen1)
        if (shouldProceedGen1) {
            if (guard.acquireSessionSlot()) {
                liveSessionsCreated++
            }
        }

        // Slot and session count remain 0: no zombie session created
        assertEquals(0, liveSessionsCreated)
        assertEquals(0, guard.getActiveSessionCount())

        // Step 4: Next tap starts a valid session (gen = 3)
        val gen3 = guard.nextGeneration()
        val shouldProceedGen3 = guard.isGenerationValid(gen3)
        assertTrue("Active session matching current generation token must proceed", shouldProceedGen3)
        if (shouldProceedGen3) {
            if (guard.acquireSessionSlot()) {
                liveSessionsCreated++
            }
        }
        assertEquals(1, liveSessionsCreated)
        assertEquals(1, guard.getActiveSessionCount())
    }

    @Test
    fun testMatchesEcho_ExactMatches() {
        assertTrue(VoiceEchoDetector.matchesEcho("আমি মিরা", "আমি মিরা"))
        assertTrue(VoiceEchoDetector.matchesEcho("আমি মিরা", "আমি মিরা!"))
        assertTrue(VoiceEchoDetector.matchesEcho("I am Mira", "I am Mira."))
        assertTrue(VoiceEchoDetector.matchesEcho("hello world", "hello world"))
    }

    @Test
    fun testMatchesEcho_CannedIdentityResponsesDetected() {
        val banglaCanned = "আমার নাম মিরা, আমি একটা AI এজেন্ট। আমাকে বানিয়েছেন সালমান বিশ্বাস।"
        val englishCanned = "My name is Mira, I am an AI agent built by Salman Biswas."

        // Mic picks up fragments of own speech
        assertTrue("Exact sentence echo must match", VoiceEchoDetector.matchesEcho(banglaCanned, banglaCanned))
        assertTrue("High token overlap of model sentence must match echo",
            VoiceEchoDetector.matchesEcho("আমার নাম মিরা আমি একটা AI এজেন্ট", banglaCanned))
        assertTrue("Developer mention echo must match",
            VoiceEchoDetector.matchesEcho("আমাকে বানিয়েছেন সালমান বিশ্বাস", banglaCanned))
        assertTrue("English canned echo must match",
            VoiceEchoDetector.matchesEcho("My name is Mira I am an AI agent", englishCanned))
    }

    @Test
    fun testMatchesEcho_GenuineUserTurnsNotClassifiedAsEcho() {
        val banglaModelText = "আমার নাম মিরা, আমি একটা AI এজেন্ট। আমাকে বানিয়েছেন সালমান বিশ্বাস।"
        val englishModelText = "I can help you search the web and automate actions on your phone."

        // Genuine questions from user must NOT match echo
        assertFalse("Question 'তুমি কে?' must NOT be classified as echo",
            VoiceEchoDetector.matchesEcho("তুমি কে?", banglaModelText))
        assertFalse("Question 'তোমার নাম কি?' must NOT be classified as echo",
            VoiceEchoDetector.matchesEcho("তোমার নাম কি?", banglaModelText))
        assertFalse("Question 'আজকের আবহাওয়া কেমন?' must NOT be classified as echo",
            VoiceEchoDetector.matchesEcho("আজকের আবহাওয়া কেমন?", banglaModelText))
        assertFalse("Command 'stop' must NOT be classified as echo",
            VoiceEchoDetector.matchesEcho("stop", banglaModelText))
        assertFalse("Command 'থামো' must NOT be classified as echo",
            VoiceEchoDetector.matchesEcho("থামো", banglaModelText))
        assertFalse("English question 'who are you' must NOT be classified as echo",
            VoiceEchoDetector.matchesEcho("who are you", englishModelText))
        assertFalse("English question 'open youtube' must NOT be classified as echo",
            VoiceEchoDetector.matchesEcho("open youtube", englishModelText))
    }

    @Test
    fun testMatchesEcho_EmptyAndBlankHandling() {
        assertFalse(VoiceEchoDetector.matchesEcho("", "some model speech"))
        assertFalse(VoiceEchoDetector.matchesEcho("some user speech", ""))
        assertFalse(VoiceEchoDetector.matchesEcho("", ""))
        assertFalse(VoiceEchoDetector.matchesEcho("    ", "   "))
    }

    @Test
    fun testIdentityInterceptor_SuppressesCannedResponseOnOwnEcho() {
        val modelSpeech = "আমার নাম মিরা, আমি একটা AI এজেন্ট।"

        // If the transcript picked up is an echo of model's own words, interceptor must return null
        val echoResponse = MiraIdentityInterceptor.getCannedResponse(
            userQuery = "আমার নাম মিরা",
            userLanguage = "Bangla (Bengali)",
            forVoice = true,
            lastModelTranscript = modelSpeech
        )
        assertNull("Echo of model speech must NOT trigger canned identity response", echoResponse)

        // If user asks a genuine identity question that differs from model speech, canned response is returned
        val genuineResponse = MiraIdentityInterceptor.getCannedResponse(
            userQuery = "তুমি কে?",
            userLanguage = "Bangla (Bengali)",
            forVoice = true,
            lastModelTranscript = "এখানে আজকের আবহাওয়া সম্পর্কে তথ্য দেওয়া হয়েছে।"
        )
        assertNotNull("Genuine identity question must return canned response", genuineResponse)
        assertEquals(io.github.ryoaonetsuki.mira.identity.IdentityProfile.IDENTITY_BANGLA, genuineResponse)
    }
}

package io.github.ryoaonetsuki.mira.voice

import io.github.ryoaonetsuki.mira.automation.MiraAutomationBridge
import io.github.ryoaonetsuki.mira.voice.pipecat.VoiceEchoDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the Round 25 Turn 4 single-voice / no-spontaneous-TTS invariants.
 * They test routing DECISIONS, not real audio.
 */
class VoiceTurnRouterTest {

    private val phoneQ = "YouTube mane amar phone control korte paro"
    private val modelSpeech = listOf("হ্যাঁ, আমি পারি, ভয়েস মোডে বললেই আমি ফোনে ইউটিউব খুলে দেব")

    // ---- question vs command ----
    @Test
    fun phoneControlQuestionsAreNotAutomationIntent() {
        assertFalse(MiraAutomationBridge.isAutomationIntent(phoneQ))
        assertFalse(MiraAutomationBridge.isAutomationIntent("tumi ki youtube mane amar phone control korte paro"))
        assertFalse(MiraAutomationBridge.isAutomationIntent("তুমি কি YouTube মানে আমার ফোন control করতে পারো"))
        assertFalse(MiraAutomationBridge.isAutomationIntent("can you control my phone"))
    }

    @Test
    fun realCommandsAreAutomationIntent() {
        assertTrue(MiraAutomationBridge.isAutomationIntent("YouTube open করো"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("youtube kholo"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("control my phone"))
        assertTrue(MiraAutomationBridge.isAutomationIntent("can you open youtube"))
    }

    // ---- routing: phone-control question -> Live answers, no TTS ----
    @Test
    fun phoneControlQuestionRoutesToLiveAndNeverToTts() {
        assertEquals(VoiceTurnRoute.LIVE_ANSWERS, VoiceTurnRouter.route(phoneQ, emptyList(), automationRunning = false))
        // And even in the worst case the TTS policy forbids it while Live is connected.
        assertFalse(VoiceTurnRouter.mayUseFallbackTts(liveConnected = true, userInitiated = true))
    }

    @Test
    fun commandRoutesToAutomation_stopRoutesToStop() {
        assertEquals(VoiceTurnRoute.AUTOMATION, VoiceTurnRouter.route("YouTube open করো", emptyList(), false))
        assertEquals(VoiceTurnRoute.STOP_AUTOMATION, VoiceTurnRouter.route("stop", emptyList(), true))
        assertEquals(VoiceTurnRoute.LIVE_ANSWERS, VoiceTurnRouter.route("stop", emptyList(), false))
    }

    // ---- own echo: no false turn, no action, no answer ----
    @Test
    fun ownEchoExactSubstringAndMisTranscribedIsIgnored() {
        val spoken = listOf("sure I am opening youtube for you right now")
        assertEquals(VoiceTurnRoute.IGNORE_ECHO, VoiceTurnRouter.route("sure I am opening youtube for you right now", spoken, false))
        assertEquals(VoiceTurnRoute.IGNORE_ECHO, VoiceTurnRouter.route("opening youtube for you right now", spoken, false))
        // one word different (mis-transcription)
        assertEquals(VoiceTurnRoute.IGNORE_ECHO, VoiceTurnRouter.route("sure I am opening youtub for you right now", spoken, false))
        assertEquals(VoiceTurnRoute.IGNORE_ECHO, VoiceTurnRouter.route("sure I am opened youtube for you right now", spoken, false))
    }

    @Test
    fun echoOfModelSayingCommandWordsDoesNotTriggerAutomation() {
        val spoken = listOf("ঠিক আছে আমি এখন youtube open করছি")
        assertEquals(VoiceTurnRoute.IGNORE_ECHO, VoiceTurnRouter.route("ঠিক আছে আমি এখন youtube open করছি", spoken, false))
        assertEquals(VoiceTurnRoute.IGNORE_ECHO, VoiceTurnRouter.route("ঠিক আছে আমি এখন youtube opn করছি", spoken, false))
    }

    @Test
    fun genuineShortQuestionsAreNotEcho() {
        assertFalse(VoiceTurnRouter.isEcho("তুমি কে?", modelSpeech))
        assertFalse(VoiceTurnRouter.isEcho("stop", modelSpeech))
        assertTrue(VoiceEchoDetector.tokensMatch("youtube", "youtub"))
        assertFalse(VoiceEchoDetector.tokensMatch("open", "play"))
    }

    // ---- single voice / spontaneous TTS ----
    @Test
    fun ttsNeverSpeaksWhileLiveConnected() {
        for (userInitiated in listOf(true, false)) {
            assertFalse(VoiceTurnRouter.mayUseFallbackTts(liveConnected = true, userInitiated = userInitiated))
        }
    }

    @Test
    fun ttsFallbackOnlyWhenNoLiveAndUserInitiated() {
        assertTrue(VoiceTurnRouter.mayUseFallbackTts(liveConnected = false, userInitiated = true))
        // spontaneous (session start/reconnect/model volunteer): nothing spoken
        assertFalse(VoiceTurnRouter.mayUseFallbackTts(liveConnected = false, userInitiated = false))
    }

    @Test
    fun automationResultWithoutExplicitUserCommandIsNeverAnnounced() {
        assertFalse(VoiceTurnRouter.mayAnnounceAutomationResult(goalFromUserCommand = false))
        assertTrue(VoiceTurnRouter.mayAnnounceAutomationResult(goalFromUserCommand = true))
    }

    @Test
    fun resultNoteAsksLiveToAnnounceInOwnWords_noVerbatimRecitation() {
        val note = VoiceTurnRouter.buildResultNote("Opened YouTube", "Bangla", true)
        assertTrue(note.contains("own words"))
        assertTrue(note.contains("Opened YouTube"))
        assertFalse(note.uppercase().contains("EXACTLY"))
        assertFalse(note.lowercase().contains("verbatim"))
        assertFalse(VoiceTurnRouter.buildAccessibilityNote("English").uppercase().contains("EXACTLY"))
    }

    @Test
    fun sessionStartSpeaksNothing_noGreetingOrIntroInVoiceTurnPolicy() {
        // No user speech -> no turn -> no route executes; nothing is user-initiated.
        assertEquals(VoiceTurnRoute.IGNORE_ECHO, VoiceTurnRouter.route("", emptyList(), false))
        assertFalse(VoiceTurnRouter.mayUseFallbackTts(liveConnected = false, userInitiated = false))
    }

    // ---- output gate ----
    @Test
    fun outputGateDropsAudioUntilReleased() {
        val gate = ModelAudioGate()
        assertTrue(gate.shouldPlay())
        gate.suppress()
        assertFalse(gate.shouldPlay())
        gate.release()
        assertTrue(gate.shouldPlay())
    }
}

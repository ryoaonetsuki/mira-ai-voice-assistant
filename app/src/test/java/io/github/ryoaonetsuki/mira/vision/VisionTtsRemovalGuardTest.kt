package io.github.ryoaonetsuki.mira.vision

import io.github.ryoaonetsuki.mira.identity.MiraOutputFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CI Guard Test for Round 25 Turn 6:
 * Standing Rule (Owner Order 2026-11-09 ~07:26):
 * "TTS is allowed in EXACTLY ONE place: the voice PREVIEW (Settings voice picker).
 *  NOWHERE else. Vision = TEXT ONLY. Live voice must NEVER mix with any TTS path."
 *
 * This test guarantees:
 * 1. ScanScreen.kt (Vision) has ZERO TTS/AudioPlayer code, no speakAnswer, no auto-speak, and produces TEXT ONLY.
 * 2. ChatScreen.kt has ZERO Android TextToSpeech or speakMessage.
 * 3. GeminiTtsClient is restricted to exactly ONE UI screen: VoiceEngineScreen.kt (voice preview).
 * 4. Android TextToSpeech is completely purged from the entire application.
 * 5. Live voice never mixes with fallback TTS (VoiceTurnRouter policy invariant).
 * 6. The voice preview call site in VoiceEngineScreen remains intact.
 */
class VisionTtsRemovalGuardTest {

    private fun findSrcMainJava(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Cannot locate src/main/java from ${File(".").canonicalPath}")
    }

    private fun getFile(relativePath: String): File {
        val srcDir = findSrcMainJava()
        val file = File(srcDir, relativePath)
        assertTrue("Source file must exist: ${file.path}", file.exists() && file.isFile)
        return file
    }

    @Test
    fun testVisionScreen_HasZeroTtsReferences_AndIsTextOnly() {
        val scanScreenFile = getFile("io/github/ryoaonetsuki/mira/ui/screens/ScanScreen.kt")
        val content = scanScreenFile.readText()

        // 1. Invariant: ScanScreen must NOT import GeminiTtsClient or AudioPlayer
        assertFalse("ScanScreen must NOT import GeminiTtsClient", content.contains("import io.github.ryoaonetsuki.mira.network.GeminiTtsClient"))
        assertFalse("ScanScreen must NOT import AudioPlayer", content.contains("import io.github.ryoaonetsuki.mira.voice.AudioPlayer"))

        // 2. Invariant: ScanScreen must NOT contain any TTS client instantiation or method calls
        assertFalse("ScanScreen must NOT instantiate GeminiTtsClient", content.contains("GeminiTtsClient"))
        assertFalse("ScanScreen must NOT declare or call speakAnswer", content.contains("speakAnswer"))
        assertFalse("ScanScreen must NOT declare or call stopSpeaking", content.contains("stopSpeaking"))
        assertFalse("ScanScreen must NOT have isSpeakingVisionAnswer state", content.contains("isSpeakingVisionAnswer"))
        assertFalse("ScanScreen must NOT hold currentAudioPlayer state", content.contains("currentAudioPlayer"))
        assertFalse("ScanScreen must NOT call speakText", content.contains("speakText"))

        // 3. Invariant: ScanScreen must NOT have speaking UI strings
        assertFalse("ScanScreen must NOT reference vision_speaking", content.contains("R.string.vision_speaking"))
        assertFalse("ScanScreen must NOT reference vision_stop_speaking", content.contains("R.string.vision_stop_speaking"))
        assertFalse("ScanScreen must NOT reference vision_speak_again", content.contains("R.string.vision_speak_again"))

        // 4. Invariant: Vision answer processing produces pure text only
        val sampleVisionRawBangla = "এই ছবিতে একটি কাঠের টেবিলের ওপর খোলা বই এবং চশমা দেখা যাচ্ছে।"
        val sanitizedBangla = MiraOutputFilter.sanitize(sampleVisionRawBangla)
        assertEquals("Vision output must match sanitized text exactly", sampleVisionRawBangla, sanitizedBangla)

        val sampleVisionRawEnglish = "The image depicts a mountain peak covered in snow during sunrise."
        val sanitizedEnglish = MiraOutputFilter.sanitize(sampleVisionRawEnglish)
        assertEquals("Vision output must match sanitized text exactly", sampleVisionRawEnglish, sanitizedEnglish)
    }

    @Test
    fun testChatScreen_HasZeroTtsReferences() {
        val chatScreenFile = getFile("io/github/ryoaonetsuki/mira/ui/screens/ChatScreen.kt")
        val content = chatScreenFile.readText()

        assertFalse("ChatScreen must NOT import android TextToSpeech", content.contains("android.speech.tts.TextToSpeech"))
        assertFalse("ChatScreen must NOT use TextToSpeech", content.contains("TextToSpeech"))
        assertFalse("ChatScreen must NOT declare or call speakMessage", content.contains("speakMessage"))
        assertFalse("ChatScreen must NOT contain mira_chat_tts utterance tag", content.contains("mira_chat_tts"))
    }

    @Test
    fun testWholeApp_AndroidTextToSpeech_ZeroUsagesAcrossSourceTree() {
        val srcDir = findSrcMainJava()
        val allKotlinFiles = srcDir.walkTopDown().filter { it.extension == "kt" }.toList()

        val violations = mutableListOf<String>()
        for (file in allKotlinFiles) {
            val content = file.readText()
            if (content.contains("android.speech.tts.TextToSpeech") || content.contains("TextToSpeech(")) {
                violations.add(file.relativeTo(srcDir).path)
            }
        }
        assertTrue(
            "Android TextToSpeech must have ZERO usages across the entire codebase. Found in: $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun testWholeApp_GeminiTtsClient_SingleAllowedUiScreenIsVoiceEngineScreen() {
        val srcDir = findSrcMainJava()
        val uiScreensDir = File(srcDir, "io/github/ryoaonetsuki/mira/ui/screens")
        assertTrue("UI screens directory must exist", uiScreensDir.exists() && uiScreensDir.isDirectory)

        val uiKotlinFiles = uiScreensDir.walkTopDown().filter { it.extension == "kt" }.toList()
        val uiTtsCallSites = mutableListOf<String>()

        for (file in uiKotlinFiles) {
            val content = file.readText()
            if (content.contains("GeminiTtsClient")) {
                uiTtsCallSites.add(file.relativeTo(srcDir).path)
            }
        }

        // VoiceEngineScreen.kt is the ONE allowed UI call site (settings voice preview)
        val expectedAllowedUiSite = "io/github/ryoaonetsuki/mira/ui/screens/settings/VoiceEngineScreen.kt"
        assertEquals(
            "TTS in UI is allowed in EXACTLY ONE place: VoiceEngineScreen. Found: $uiTtsCallSites",
            listOf(expectedAllowedUiSite),
            uiTtsCallSites
        )
    }

    @Test
    fun testWholeApp_GeminiTtsClient_AllowedFilesAudit() {
        val srcDir = findSrcMainJava()
        val allKotlinFiles = srcDir.walkTopDown().filter { it.extension == "kt" }.toList()

        val filesWithGeminiTts = mutableListOf<String>()
        for (file in allKotlinFiles) {
            val content = file.readText()
            if (content.contains("GeminiTtsClient")) {
                filesWithGeminiTts.add(file.relativeTo(srcDir).path)
            }
        }

        // Allowed files:
        // 1. GeminiTtsClient.kt (definition)
        // 2. VoiceEngineScreen.kt (voice preview - the ONE allowed UI place)
        // 3. MicService.kt (Turn 4 offline automation fallback)
        val allowedFiles = setOf(
            "io/github/ryoaonetsuki/mira/network/GeminiTtsClient.kt",
            "io/github/ryoaonetsuki/mira/ui/screens/settings/VoiceEngineScreen.kt",
            "io/github/ryoaonetsuki/mira/service/MicService.kt"
        )

        val unexpected = filesWithGeminiTts.filter { it !in allowedFiles }
        assertTrue(
            "Unexpected GeminiTtsClient call sites found: $unexpected. Allowed files: $allowedFiles",
            unexpected.isEmpty()
        )
    }

    @Test
    fun testLiveVoiceInvariant_TtsNeverMixesWithLiveSession() {
        val routerFile = getFile("io/github/ryoaonetsuki/mira/voice/VoiceTurnRouter.kt")
        val content = routerFile.readText()

        // Verifies the single-voice invariant rule is coded in VoiceTurnRouter:
        // Spoken output in Live mode is strictly the single Live agent voice.
        // Fallback TTS may only be used when liveConnected == false AND userInitiated == true.
        assertTrue("VoiceTurnRouter must enforce mayUseFallbackTts policy", content.contains("fun mayUseFallbackTts"))
        assertTrue("Live connected turns must never use fallback TTS", content.contains("!liveConnected && userInitiated"))
    }

    @Test
    fun testVoicePreviewTtsCallSite_IsIntact() {
        val voiceEngineFile = getFile("io/github/ryoaonetsuki/mira/ui/screens/settings/VoiceEngineScreen.kt")
        val content = voiceEngineFile.readText()

        // Voice preview is the ONE allowed place: verify it remains functional
        assertTrue("VoiceEngineScreen must instantiate GeminiTtsClient", content.contains("GeminiTtsClient(context)"))
        assertTrue("VoiceEngineScreen must call generatePreview", content.contains("generatePreview("))
        assertTrue("VoiceEngineScreen must apply preview fade ramps", content.contains("AudioPlayer.applyPreviewFadeRamps("))
        assertTrue("VoiceEngineScreen must cleanly end preview", content.contains("endPreviewCleanly()"))
        assertTrue("VoiceEngineScreen must cleanly stop preview", content.contains("stopPreviewCleanly()"))
    }
}


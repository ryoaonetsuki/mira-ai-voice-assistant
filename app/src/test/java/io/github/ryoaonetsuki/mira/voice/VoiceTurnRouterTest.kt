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
    fun commandRoutesToLiveAnswers_stopRoutesToStop() {
        assertEquals(VoiceTurnRoute.LIVE_ANSWERS, VoiceTurnRouter.route("YouTube open করো", emptyList(), false))
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

    // ---- Round 25 Fix Turn B: All 30 Languages Automation Coverage ----
    @Test
    fun all30Languages_openYouTubeAndPlayMusic_areAutomationIntents() {
        // Pair(languageCode, Pair(openYouTubeCmd, playMusicCmd))
        val commandsByLang = listOf(
            "bn" to ("ইউটিউব খুলো" to "গান চালাও"),
            "en" to ("open YouTube" to "play music"),
            "hi" to ("यूट्यूब खोलो" to "गाना चलाओ"),
            "ur" to ("یوٹیوب کھولو" to "گانا چلاؤ"),
            "ar" to ("افتح يوتيوب" to "شغل موسيقى"),
            "es" to ("abre YouTube" to "reproduce música"),
            "fr" to ("ouvre YouTube" to "joue de la musique"),
            "de" to ("öffne YouTube" to "spiele Musik"),
            "ja" to ("ユーチューブを開いて" to "音楽を再生して"),
            "ko" to ("유튜브 열어줘" to "음악 틀어줘"),
            "pt" to ("abra o YouTube" to "toque música"),
            "ru" to ("открой ютуб" to "включи музыку"),
            "tr" to ("YouTube aç" to "müzik çal"),
            "id" to ("buka YouTube" to "putar lagu"),
            "vi" to ("mở YouTube" to "phát nhạc"),
            "th" to ("เปิดยูทูบ" to "เล่นเพลง"),
            "ta" to ("யூடியூப் திற" to "பாடல் போடு"),
            "te" to ("యూట్యూబ్ తెరువు" to "పాట ప్లే చేయి"),
            "mr" to ("युट्युब उघड" to "गाणं लाव"),
            "gu" to ("યુટ્યુબ ખોલો" to "ગીત વગાડો"),
            "pa" to ("ਯੂਟਿਊਬ ਖੋਲ੍ਹੋ" to "ਗਾਣਾ ਚਲਾਓ"),
            "ml" to ("യൂട്യൂബ് തുറക്കൂ" to "പാട്ട് പ്ലേ ചെയ്യൂ"),
            "kn" to ("ಯೂಟ್ಯೂಬ್ ತೆರೆ" to "ಹಾಡು ಹಾಕು"),
            "it" to ("apri YouTube" to "riproduci musica"),
            "nl" to ("openen YouTube" to "speel muziek"),
            "pl" to ("otwórz YouTube" to "włącz muzykę"),
            "sv" to ("öppna YouTube" to "spela musik"),
            "uk" to ("відкрий ютуб" to "увімкни музику"),
            "el" to ("άνοιξε το YouTube" to "παίξε μουσική"),
            "zh" to ("打开YouTube" to "播放音乐")
        )
        assertEquals(30, commandsByLang.size)
        for ((lang, pair) in commandsByLang) {
            val (openYt, playMusic) = pair
            assertTrue("Expected open YouTube in [$lang] ('$openYt') to be automation intent", MiraAutomationBridge.isAutomationIntent(openYt))
            assertTrue("Expected play music in [$lang] ('$playMusic') to be automation intent", MiraAutomationBridge.isAutomationIntent(playMusic))
            assertEquals("Expected extractTargetApp for '$openYt' [$lang]", "com.google.android.youtube", MiraAutomationBridge.extractTargetApp(openYt))
            assertEquals("Expected extractTargetApp for '$playMusic' [$lang]", "com.google.android.youtube", MiraAutomationBridge.extractTargetApp(playMusic))
        }
    }

    @Test
    fun multilingualCapabilityQuestions_areNotAutomationIntents() {
        val capabilityQuestions = listOf(
            "hi" to "क्या तुम यूट्यूब खोल सकते हो?",
            "ur" to "کیا تم یوٹیوب کھول سکتے ہو؟",
            "ar" to "هل يمكنك فتح يوتيوب؟",
            "es" to "¿puedes abrir YouTube?",
            "fr" to "peux-tu ouvrir YouTube ?",
            "de" to "kannst du YouTube öffnen?",
            "ja" to "ユーチューブを開くことができますか",
            "ko" to "유튜브 열 수 있어?",
            "pt" to "você pode abrir o YouTube?",
            "ru" to "ты можешь открыть ютуб?",
            "tr" to "youtube açabilir misin?",
            "id" to "bisakah kamu buka YouTube?",
            "vi" to "bạn có thể mở YouTube không?",
            "th" to "เปิดยูทูบได้ไหม",
            "ta" to "யூடியூப் திறக்க முடியுமா",
            "te" to "యూట్యూబ్ తెరవగలవా",
            "mr" to "युट्युब उघडू शकतोस का",
            "gu" to "યુટ્યુબ ખોલી શકો છો",
            "pa" to "ਯੂਟਿਊਬ ਖੋਲ੍ਹ ਸਕਦੇ ਹੋ",
            "ml" to "യൂട്യൂബ് തുറക്കാൻ കഴിയുമോ",
            "kn" to "ಯೂಟ್ಯೂಬ್ ತೆರೆಯಲು ಸಾಧ್ಯವೇ",
            "it" to "puoi aprire YouTube?",
            "nl" to "kun je YouTube openen?",
            "pl" to "czy możesz otworzyć YouTube?",
            "sv" to "kan du öppna YouTube?",
            "uk" to "ти можеш відкрити ютуб?",
            "el" to "μπορείς να ανοίξεις το YouTube;",
            "zh" to "你能打开YouTube吗"
        )
        for ((lang, question) in capabilityQuestions) {
            assertFalse("Expected capability question in [$lang] ('$question') NOT to be automation intent", MiraAutomationBridge.isAutomationIntent(question))
            assertEquals(VoiceTurnRoute.LIVE_ANSWERS, VoiceTurnRouter.route(question, emptyList(), automationRunning = false))
        }
    }

    @Test
    fun multilingualStopWords_cancelRunningAutomation() {
        for (stopWord in listOf("stop", "থামো", "रोको", "توقف", "detén", "arrête", "止めて", "멈춰", "стоп", "durdur", "停止")) {
            assertEquals(VoiceTurnRoute.STOP_AUTOMATION, VoiceTurnRouter.route(stopWord, emptyList(), automationRunning = true))
        }
    }

    @Test
    fun turnB_weatherDhakaDefault_semverComparison_andConnectorUnification() {
        // F-20: Dhaka default coordinates when timezone is Asia/Dhaka
        val dhakaCoords = io.github.ryoaonetsuki.mira.network.WeatherClient.defaultCoordinates("Asia/Dhaka")
        assertEquals(23.8103, dhakaCoords.first, 0.0001)
        assertEquals(90.4125, dhakaCoords.second, 0.0001)
        val nycCoords = io.github.ryoaonetsuki.mira.network.WeatherClient.defaultCoordinates("America/New_York")
        assertEquals(40.7128, nycCoords.first, 0.0001)
        assertEquals(-74.0060, nycCoords.second, 0.0001)

        // F-21: Semver comparison
        assertFalse(io.github.ryoaonetsuki.mira.ui.screens.isRemoteVersionNewer("1.0.0", "v0.1.0-alpha"))
        assertFalse(io.github.ryoaonetsuki.mira.ui.screens.isRemoteVersionNewer("1.0.0", "v1.0.0"))
        assertTrue(io.github.ryoaonetsuki.mira.ui.screens.isRemoteVersionNewer("1.0.0", "v1.0.1"))
        assertTrue(io.github.ryoaonetsuki.mira.ui.screens.isRemoteVersionNewer("1.0.0", "v1.1.0"))
        assertTrue(io.github.ryoaonetsuki.mira.ui.screens.isRemoteVersionNewer("1.0.0", "v2.0.0"))

        // F-19: Unified 13 connectors between ExtensionsScreen and ConnectorsSettingsScreen
        val connectorIds = io.github.ryoaonetsuki.mira.ui.screens.settings.ALL_CONNECTORS.map { it.id }
        val extensionIds = io.github.ryoaonetsuki.mira.ui.screens.ALL_EXTENSIONS.map { it.id }
        assertEquals(13, connectorIds.size)
        assertEquals(connectorIds, extensionIds)
        assertTrue(extensionIds.contains("open_meteo"))
        assertTrue(extensionIds.contains("drive"))
        assertTrue(extensionIds.contains("youtube"))
        assertTrue(extensionIds.contains("yahoo_finance"))
        assertTrue(extensionIds.contains("gmail"))
        assertFalse(extensionIds.contains("weather"))
    }
}

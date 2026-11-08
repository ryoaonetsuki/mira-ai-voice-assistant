package io.github.ryoaonetsuki.mira.voice

import io.github.ryoaonetsuki.mira.data.model.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageUtilsTest {

    @Test
    fun testLanguageBcp47Mapping_HistoricalBasics() {
        assertEquals("bn-BD", LanguageUtils.mapLanguageToBcp47("Bangla (Bengali)"))
        assertEquals("bn-BD", LanguageUtils.mapLanguageToBcp47("Bengali"))
        assertEquals("bn-BD", LanguageUtils.mapLanguageToBcp47("bn"))
        assertEquals("en-US", LanguageUtils.mapLanguageToBcp47("English"))
        assertEquals("hi-IN", LanguageUtils.mapLanguageToBcp47("Hindi"))
        assertEquals("ja-JP", LanguageUtils.mapLanguageToBcp47("Japanese"))
        assertEquals("es-ES", LanguageUtils.mapLanguageToBcp47("Spanish"))
        assertEquals("fr-FR", LanguageUtils.mapLanguageToBcp47("French"))
        assertEquals("de-DE", LanguageUtils.mapLanguageToBcp47("German"))
        assertEquals("ar-SA", LanguageUtils.mapLanguageToBcp47("Arabic"))
        // Fallback default
        assertEquals("bn-BD", LanguageUtils.mapLanguageToBcp47("UnknownLanguage"))
        assertEquals("bn-BD", LanguageUtils.mapLanguageToBcp47(""))
    }

    @Test
    fun testAllOfferedLanguages_MapToAccurateBcp47Tags() {
        val expected = mapOf(
            "Bangla (Bengali)" to "bn-BD",
            "English (US / Global)" to "en-US",
            "Hindi" to "hi-IN",
            "Urdu" to "ur-PK",
            "Arabic" to "ar-SA",
            "Spanish" to "es-ES",
            "French" to "fr-FR",
            "German" to "de-DE",
            "Japanese" to "ja-JP",
            "Korean" to "ko-KR",
            "Portuguese" to "pt-BR",
            "Russian" to "ru-RU",
            "Turkish" to "tr-TR",
            "Indonesian" to "id-ID",
            "Vietnamese" to "vi-VN",
            "Thai" to "th-TH",
            "Tamil" to "ta-IN",
            "Telugu" to "te-IN",
            "Marathi" to "mr-IN",
            "Gujarati" to "gu-IN",
            "Punjabi" to "pa-IN",
            "Malayalam" to "ml-IN",
            "Kannada" to "kn-IN",
            "Italian" to "it-IT",
            "Dutch" to "nl-NL",
            "Polish" to "pl-PL",
            "Swedish" to "sv-SE",
            "Ukrainian" to "uk-UA",
            "Greek" to "el-GR",
            "Chinese (Mandarin)" to "zh-CN"
        )

        assertEquals("VoiceCatalog must contain exactly 30 languages", 30, VoiceCatalog.supportedLanguages.size)

        for (lang in VoiceCatalog.supportedLanguages) {
            val expectedTag = expected[lang]
            val actualTag = LanguageUtils.mapLanguageToBcp47(lang)
            assertEquals("Expected $lang to map to $expectedTag", expectedTag, actualTag)
        }
    }

    @Test
    fun testIsBangla() {
        assertTrue(LanguageUtils.isBangla("Bangla (Bengali)"))
        assertTrue(LanguageUtils.isBangla("Bengali"))
        assertTrue(LanguageUtils.isBangla("Bangla"))
        assertTrue(LanguageUtils.isBangla("bn"))
        assertTrue(LanguageUtils.isBangla("bn-BD"))
        assertTrue(LanguageUtils.isBangla("")) // default is Bangla

        assertFalse(LanguageUtils.isBangla("English (US / Global)"))
        assertFalse(LanguageUtils.isBangla("Hindi"))
        assertFalse(LanguageUtils.isBangla("Spanish"))
        assertFalse(LanguageUtils.isBangla("German"))
        assertFalse(LanguageUtils.isBangla("Japanese"))
        assertFalse(LanguageUtils.isBangla("French"))
    }
}

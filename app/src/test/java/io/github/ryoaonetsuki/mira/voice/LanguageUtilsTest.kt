package io.github.ryoaonetsuki.mira.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageUtilsTest {

    @Test
    fun testLanguageBcp47Mapping() {
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
    }
}

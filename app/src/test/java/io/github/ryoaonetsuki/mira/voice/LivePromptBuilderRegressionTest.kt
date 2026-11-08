package io.github.ryoaonetsuki.mira.voice

import io.github.ryoaonetsuki.mira.data.model.PersonaType
import io.github.ryoaonetsuki.mira.data.model.VoiceCatalog
import io.github.ryoaonetsuki.mira.network.SearchAcknowledgement
import io.github.ryoaonetsuki.mira.network.currentDateTimeLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Pure Kotlin/JVM regression tests verifying that:
 * 1. For each offered language, the Live prompt contains a reply-in-<language> rule.
 * 2. For non-Bangla languages, the prompt does NOT contain a hardcoded Bangla rule or date leak.
 * 3. Default unset language produces the proven default Bangla prompt.
 * 4. Search acknowledgement phrases and date lines strictly follow the selected language.
 *
 * NOTE: These are pure JVM string and logic tests without Android service/notification instantiation
 * or Robolectric resource loading.
 */
class LivePromptBuilderRegressionTest {

    @Test
    fun testAllOfferedLanguages_BuildDynamicRule_AndNoHardcodedBanglaWhenNotBangla() {
        assertEquals("Expected exactly 30 supported languages", 30, VoiceCatalog.supportedLanguages.size)

        for (lang in VoiceCatalog.supportedLanguages) {
            val isBangla = LanguageUtils.isBangla(lang)
            val ackPhrase = SearchAcknowledgement.getPhrase(lang)
            val dateLine = currentDateTimeLine(lang)
            val prompt = LivePromptBuilder.buildLiveSystemPrompt(
                language = lang,
                personaType = PersonaType.MIRA,
                dateLine = dateLine,
                searchAckPhrase = ackPhrase
            )

            if (!isBangla) {
                val upper = lang.uppercase(Locale.ROOT)
                assertTrue("Prompt for '$lang' must instruct RESPOND IN $upper", prompt.contains("RESPOND IN $upper"))
                assertTrue("Prompt for '$lang' must instruct Reply ONLY in $lang", prompt.contains("Reply ONLY in $lang"))
                assertFalse("Prompt for '$lang' must NOT contain hardcoded RESPOND IN BANGLA", prompt.contains("RESPOND IN BANGLA"))
                assertFalse("Prompt for '$lang' must NOT contain hardcoded Reply ONLY in Bangla", prompt.contains("Reply ONLY in Bangla"))
                assertFalse("Prompt for '$lang' must NOT contain 'acknowledgement in Bangla'", prompt.contains("acknowledgement in Bangla"))
                assertFalse("Prompt for '$lang' must NOT contain 'Bengali date:'", prompt.contains("Bengali date:"))
            } else {
                assertTrue("Prompt for Bangla must contain RESPOND IN BANGLA", prompt.contains("RESPOND IN BANGLA"))
                assertTrue("Prompt for Bangla must contain Reply ONLY in Bangla", prompt.contains("Reply ONLY in Bangla"))
                assertTrue("Prompt for Bangla must contain 'acknowledgement in Bangla'", prompt.contains("acknowledgement in Bangla"))
                assertTrue("Prompt for Bangla must contain 'Bengali date:'", prompt.contains("Bengali date:"))
            }
        }
    }

    @Test
    fun testDefaultUnsetPrompt_DefaultsToBangla() {
        val promptDefault = LivePromptBuilder.buildLiveSystemPrompt()
        val expectedRule = "RESPOND IN BANGLA (BENGALI). YOU MUST RESPOND UNMISTAKABLY IN BANGLA. Reply ONLY in Bangla. Never add an English translation. Never mix languages."
        assertTrue("Default prompt must contain exact proven Bangla rule", promptDefault.contains(expectedRule))

        val promptEmptyString = LivePromptBuilder.buildLiveSystemPrompt(language = "")
        assertTrue("Empty language must default to proven Bangla rule", promptEmptyString.contains(expectedRule))
    }

    @Test
    fun testSearchAcknowledgement_HasPhrasesForEveryOfferedLanguage() {
        for (lang in VoiceCatalog.supportedLanguages) {
            val phrase = SearchAcknowledgement.getPhrase(lang)
            assertTrue("Acknowledgement phrase for $lang must not be empty", phrase.isNotBlank())
            if (!LanguageUtils.isBangla(lang)) {
                assertNotEquals("Acknowledgement for $lang must not be Bangla phrase", "এক মিনিট, আমি খুঁজে বলছি", phrase)
            } else {
                assertEquals("Bangla acknowledgement phrase mismatch", "এক মিনিট, আমি খুঁজে বলছি", phrase)
            }
        }

        assertEquals("One moment, let me search and tell you", SearchAcknowledgement.getPhrase("English (US / Global)"))
        assertEquals("এক মিনিট, আমি খুঁজে বলছি", SearchAcknowledgement.getPhrase("Bangla (Bengali)"))
    }

    @Test
    fun testDateTimeLine_OmitsBengaliDateForNonBangla_AndIncludesForBangla() {
        val englishDate = currentDateTimeLine("English (US / Global)")
        assertTrue("English dateLine must contain 'Today is'", englishDate.contains("Today is"))
        assertTrue("English dateLine must contain 'Current time is'", englishDate.contains("Current time is"))
        assertFalse("English dateLine must NOT contain 'Bengali date:'", englishDate.contains("Bengali date:"))

        val spanishDate = currentDateTimeLine("Spanish")
        assertFalse("Spanish dateLine must NOT contain 'Bengali date:'", spanishDate.contains("Bengali date:"))

        val banglaDate = currentDateTimeLine("Bangla (Bengali)")
        assertTrue("Bangla dateLine must contain 'Bengali date:'", banglaDate.contains("Bengali date:"))

        val defaultDate = currentDateTimeLine()
        assertTrue("Default dateLine must contain 'Bengali date:'", defaultDate.contains("Bengali date:"))
    }
}

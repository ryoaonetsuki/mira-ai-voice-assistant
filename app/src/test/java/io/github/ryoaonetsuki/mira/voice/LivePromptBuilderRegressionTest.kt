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
                assertFalse("Prompt for '$lang' must NOT contain 'Narrate briefly in Bangla'", prompt.contains("Narrate briefly in Bangla"))
                assertFalse("Prompt for '$lang' must NOT contain 'Bengali date:'", prompt.contains("Bengali date:"))
            } else {
                assertTrue("Prompt for Bangla must contain RESPOND IN BANGLA", prompt.contains("RESPOND IN BANGLA"))
                assertTrue("Prompt for Bangla must contain Reply ONLY in Bangla", prompt.contains("Reply ONLY in Bangla"))
                assertTrue("Prompt for Bangla must contain 'Narrate briefly in Bangla'", prompt.contains("Narrate briefly in Bangla"))
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

    @Test
    fun testUserNameInjected_WhenConfigured_AndOmittedWhenDefaultOrBlank() {
        val promptWithName = LivePromptBuilder.buildLiveSystemPrompt(
            userName = "Salman"
        )
        assertTrue("Prompt must contain configured user name", promptWithName.contains("The user's name is Salman."))

        val promptWithDefaultUser = LivePromptBuilder.buildLiveSystemPrompt(
            userName = "User"
        )
        assertFalse("Prompt must not inject placeholder 'User'", promptWithDefaultUser.contains("The user's name is User"))

        val promptBlank = LivePromptBuilder.buildLiveSystemPrompt(
            userName = ""
        )
        assertFalse("Prompt with blank name must not inject user identity line", promptBlank.contains("User identity:"))
    }

    @Test
    fun testAssistantNameInjected_WhenCustom_AndFollowsPersona() {
        val promptCustomName = LivePromptBuilder.buildLiveSystemPrompt(
            assistantName = "Maya"
        )
        assertTrue("Prompt must set custom name in base prompt", promptCustomName.contains("Your name is Maya."))
        assertTrue("Identity rule must state custom assistant name", promptCustomName.contains("say you are Maya"))

        val promptOrion = LivePromptBuilder.buildLiveSystemPrompt(
            personaType = PersonaType.ORION,
            assistantName = "Maya"
        )
        assertTrue("Orion identity rule must say Orion even if assistant name is custom", promptOrion.contains("say you are Orion"))
        assertFalse("Orion must not have Mira custom name", promptOrion.contains("Your name is Maya"))

        val promptDefault = LivePromptBuilder.buildLiveSystemPrompt()
        assertTrue("Default identity rule must state Mira", promptDefault.contains("say you are Mira"))
    }

    @Test
    fun testSecretModeTones_FriendlyRomanticIntense() {
        val promptFriendly = LivePromptBuilder.buildLiveSystemPrompt(secretMode = "Friendly")
        assertTrue("Friendly prompt must contain thoughtful friend tone", promptFriendly.contains("Warm, human-like, thoughtful"))

        val promptRomantic = LivePromptBuilder.buildLiveSystemPrompt(secretMode = "Romantic")
        assertTrue("Romantic prompt must contain affectionate romantic tone", promptRomantic.contains("Deeply warm, affectionate, attentive"))

        val promptIntense = LivePromptBuilder.buildLiveSystemPrompt(secretMode = "Intense")
        assertTrue("Intense prompt must contain bold intense tone", promptIntense.contains("Bold, intense, deeply passionate"))
    }

    @Test
    fun testIdentityRule_StrictlyOnDemand_NeverVolunteerOrSelfIntroduce() {
        val prompt = LivePromptBuilder.buildLiveSystemPrompt()
        // Key phrases in the rewritten (Turn 25) identity rule — no "EXACTLY".
        assertTrue("Identity rule must state ON-DEMAND ONLY", prompt.contains("ON-DEMAND ONLY"))
        assertTrue("Must forbid volunteering identity unprompted",
            prompt.contains("NEVER volunteer") && prompt.contains("unprompted"))
        assertTrue("Must forbid self-introduction greeting", prompt.contains("NEVER greet the user with a self-introduction"))
        assertTrue("Must forbid unprompted intro at session start", prompt.contains("Do not speak at all when the session starts or reconnects"))
        assertTrue("Must forbid stating creator unprompted", prompt.contains("never state your creator or introduce yourself unprompted"))
        // Turn 25: no "EXACTLY" directive in the identity rule
        assertFalse("Identity rule must NOT contain 'EXACTLY'", prompt.contains("EXACTLY"))
        // Facts must be present
        assertTrue("Identity rule must name Salman Biswas", prompt.contains("Salman Biswas"))
        assertTrue("Identity rule must name Mira", prompt.contains("Mira"))
        // Phone control fact must be in Live prompt (voice interceptor removed)
        assertTrue("Live prompt must teach phone control fact", prompt.contains("phone control"))
    }

    @Test
    fun testPromptTeachesPhoneControlYes_identityFacts_andNoExactlyRecitation() {
        val prompt = LivePromptBuilder.buildLiveSystemPrompt(
            language = "Bangla",
            personaType = PersonaType.MIRA,
            dateLine = "",
            searchAckPhrase = ""
        )
        assertTrue(prompt.contains("you CAN control the user's phone"))
        assertTrue(prompt.contains("answer YES in your own friendly words"))
        assertTrue(prompt.contains("Never deny phone control"))
        assertTrue(prompt.contains("not a command"))
        assertTrue(prompt.contains("Salman Biswas"))
        assertTrue(prompt.contains("ON-DEMAND ONLY"))
        assertTrue(prompt.contains("stay silent and wait for the user to speak first"))
        assertFalse("No EXACTLY recitation", prompt.uppercase().contains("EXACTLY"))
    }
}

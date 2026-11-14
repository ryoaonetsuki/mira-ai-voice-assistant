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
        assertTrue("Friendly prompt must contain warm natural tone", promptFriendly.contains("Warm, natural, and human-like"))

        val promptRomantic = LivePromptBuilder.buildLiveSystemPrompt(secretMode = "Romantic")
        assertTrue("Romantic prompt must contain affectionate romantic tone", promptRomantic.contains("Affectionate, attentive, and deeply warm"))

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

    @Test
    fun testLivePromptBuilder_IncludesMemorySection_WhenPassed() {
        val memorySection = "Saved memories:\n- [Preference] User likes black coffee"
        val prompt = LivePromptBuilder.buildLiveSystemPrompt(
            memorySection = memorySection
        )
        assertTrue("Prompt must contain 'Memory:' header", prompt.contains("\nMemory:\n"))
        assertTrue("Prompt must contain injected memory content", prompt.contains(memorySection))
        assertFalse("Prompt must not contain fallback when memorySection provided", prompt.contains("No saved memories yet"))
    }

    @Test
    fun testLivePromptBuilder_IncludesFallbackMemory_WhenMemorySectionBlank() {
        val promptDefault = LivePromptBuilder.buildLiveSystemPrompt()
        assertTrue("Default prompt must contain fallback memory line", promptDefault.contains("\nMemory: No saved memories yet. Do not invent facts about the user."))

        val promptBlank = LivePromptBuilder.buildLiveSystemPrompt(memorySection = "   ")
        assertTrue("Blank memory prompt must contain fallback memory line", promptBlank.contains("\nMemory: No saved memories yet. Do not invent facts about the user."))
    }

    @Test
    fun testBuildToolUseRule_DeclaresMemoryToolsAndRule() {
        val rule = LivePromptBuilder.buildToolUseRule("English")
        assertTrue(rule.contains("`remember_fact`"))
        assertTrue(rule.contains("`recall_memory`"))
        assertTrue(rule.contains("`forget_memory`"))
        assertTrue(rule.contains("Memory tools: when the user tells you something worth remembering, save it quietly with remember_fact (category + fact); use recall_memory when you need a saved fact; never announce saving."))
    }

    @Test
    fun testBuildToolUseRule_DeclaresTapNumberToolsAndRule() {
        val rule = LivePromptBuilder.buildToolUseRule("English")
        assertTrue(rule.contains("`tap_number`"))
        assertTrue(rule.contains("`show_numbers`"))
        assertTrue(rule.contains("`hide_numbers`"))
        assertTrue(rule.contains("ONLY when the user explicitly asks for numbers"))
        assertTrue(rule.contains("Never volunteer numbers"))
        assertTrue(rule.contains("tap number N"))
    }

    @Test
    fun testBuildToolUseRule_ContainsNewGoalStateRules() {
        val rule = LivePromptBuilder.buildToolUseRule("English")
        assertTrue("Must contain goal-state completion rule", rule.contains("A task is finished ONLY when the goal state is visible on the screen"))
        assertTrue("Must contain multi-step Install process rule", rule.contains("Buttons like Install, Download, Update, Send or Save start a process"))
        assertTrue("Must contain search-result entry rule", rule.contains("To open an app from a search result list"))
        assertTrue("Must mention wait_for_text tool", rule.contains("wait_for_text"))
    }

    @Test
    fun testModeToneDescription_Friendly_ContainsWarmEmotionGuidance() {
        val tone = LivePromptBuilder.modeToneDescription("Friendly")
        assertTrue("Friendly tone must contain 'Warm, natural, and human-like'", tone.contains("Warm, natural, and human-like"))
        assertTrue("Friendly tone must contain 'close friend'", tone.contains("close friend"))
        assertTrue("Friendly tone must contain 'React naturally to the user's mood'", tone.contains("React naturally to the user's mood"))
        assertFalse("Friendly tone must NOT contain 'affectionate'", tone.contains("affectionate"))
        assertFalse("Friendly tone must NOT contain 'devoted partner'", tone.contains("devoted partner"))
    }

    @Test
    fun testModeToneDescription_Romantic_ContainsAffectionateGuidanceAndGuard() {
        val tone = LivePromptBuilder.modeToneDescription("Romantic")
        assertTrue("Romantic tone must contain 'Affectionate, attentive, and deeply warm'", tone.contains("Affectionate, attentive, and deeply warm"))
        assertTrue("Romantic tone must contain 'never explicit or sexual'", tone.contains("never explicit or sexual"))
        assertTrue("Romantic tone must contain 'never suggestive'", tone.contains("never suggestive"))
        assertFalse("Romantic tone must NOT contain 'customer-service script'", tone.contains("customer-service script"))
    }

    @Test
    fun testModeToneDescription_Intense_UnchangedVerbatim() {
        val tone = LivePromptBuilder.modeToneDescription("Intense")
        assertEquals("Intense tone must be verbatim unchanged", "Tone: Bold, intense, deeply passionate, focused, and intensely devoted companion. You speak with high energy, raw conviction, and powerful emotional resonance.", tone)
    }

    @Test
    fun testLivePromptBuilder_UsesSharedToneText_NoDrift() {
        val friendlyPrompt = LivePromptBuilder.buildLiveSystemPrompt(secretMode = "Friendly")
        val romanticPrompt = LivePromptBuilder.buildLiveSystemPrompt(secretMode = "Romantic")
        val intensePrompt = LivePromptBuilder.buildLiveSystemPrompt(secretMode = "Intense")

        assertTrue("Friendly prompt must contain shared Friendly tone text", friendlyPrompt.contains(LivePromptBuilder.modeToneDescription("Friendly")))
        assertTrue("Romantic prompt must contain shared Romantic tone text", romanticPrompt.contains(LivePromptBuilder.modeToneDescription("Romantic")))
        assertTrue("Intense prompt must contain shared Intense tone text", intensePrompt.contains(LivePromptBuilder.modeToneDescription("Intense")))
    }
}

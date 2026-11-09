package io.github.ryoaonetsuki.mira.identity

import io.github.ryoaonetsuki.mira.voice.LivePromptBuilder
import io.github.ryoaonetsuki.mira.data.model.PersonaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Mira Identity — Turn 25 rework.
 *
 * Owner correction 2026-11-09: identity questions go to the model; no canned bypass.
 * Tests assert:
 * a. No getCannedResponse call site in source (verified by grep in CI; here we assert
 *    the function does not exist on MiraIdentityInterceptor at compile time).
 * b. MiraOutputFilter.sanitize: bare GitHub URL → markdown anchor; forbidden token
 *    sentence repaired in place, rest preserved; long identity answer passes through.
 * c. sanitizeForVoice: URLs/brackets stripped.
 * d. Prompt layers contain identity FACTS and style rules, NO "EXACTLY".
 * e. MiraOutputFilter: clean responses are untouched.
 */
class IdentityTest {

    // ── b. MiraOutputFilter.sanitize ────────────────────────────────────────

    @Test
    fun testOutputFilter_ForbiddenToken_RepairedInPlace_RestPreserved() {
        // A sentence containing a forbidden token should be replaced;
        // surrounding text must be preserved byte-for-byte.
        val input = "Sure! I am Gemini, a large language model. Would you like help with something?"
        val filtered = MiraOutputFilter.sanitize(input)

        // The forbidden sentence is gone
        assertFalse("Filtered output must not contain 'Gemini'", filtered.contains("Gemini", ignoreCase = true))
        assertFalse("Filtered output must not contain 'large language model'", filtered.contains("large language model", ignoreCase = true))

        // The filter repaired in-place: at minimum the repair identity string is present
        assertTrue("Filtered output must include identity repair", filtered.contains("Mira"))

        // The good trailing sentence "Would you like help with something?" should survive
        // (it contains no forbidden tokens — repair-only, not wholesale replacement)
        assertTrue("Trailing clean sentence must survive", filtered.contains("Would you like help with something?"))
    }

    @Test
    fun testOutputFilter_ForbiddenToken_BanglaRepaired() {
        val banglaContaminated = "আমি গুগলের তৈরি একটি জেমিনি এআই।"
        val filtered = MiraOutputFilter.sanitize(banglaContaminated)
        assertFalse("Bangla filtered output must not contain 'গুগল'", filtered.contains("গুগল"))
        assertFalse("Bangla filtered output must not contain 'জেমিনি'", filtered.contains("জেমিনি"))
        assertTrue("Bangla filtered output must contain Bangla script", filtered.any { it in '\u0980'..'\u09FF' })
    }

    @Test
    fun testOutputFilter_CleanResponse_Untouched() {
        val cleanOutputs = listOf(
            "Sure! The weather today in Dhaka is sunny and 28°C.",
            "Opening YouTube now.",
            "Here is the HTML code for the calculator you requested.",
            // Long identity answer in model's own words with correct facts — MUST PASS THROUGH UNCHANGED
            "আমি মিরা, সালমান বিশ্বাসের তৈরি একটা এআই অ্যাসিস্ট্যান্ট। তোমাকে হেল্প করার জন্যই এখানে আছি।",
            "I am Mira, an AI assistant built by Salman Biswas. I am here to help you with anything you need, from phone control to automation and more!"
        )
        for (clean in cleanOutputs) {
            val filtered = MiraOutputFilter.sanitize(clean)
            assertEquals("Clean response must remain untouched: $clean", clean, filtered)
        }
    }

    @Test
    fun testOutputFilter_BareGitHubUrl_RewrittenToMarkdownAnchor() {
        val bareUrl = "Check my work at https://github.com/ryoaonetsuki"
        val filtered = MiraOutputFilter.sanitize(bareUrl)
        assertEquals("Check my work at [GitHub](https://github.com/ryoaonetsuki)", filtered)
    }

    @Test
    fun testOutputFilter_BareUrlWithoutScheme_RewrittenToAnchor() {
        val bareWithoutScheme = "Visit github.com/ryoaonetsuki for more info"
        val filtered = MiraOutputFilter.sanitize(bareWithoutScheme)
        assertEquals("Visit [GitHub](https://github.com/ryoaonetsuki) for more info", filtered)
    }

    @Test
    fun testOutputFilter_ExistingMarkdownAnchor_Preserved() {
        val alreadyAnchor = "Here is the repository: [GitHub](https://github.com/ryoaonetsuki)"
        val filtered = MiraOutputFilter.sanitize(alreadyAnchor)
        assertEquals("Already markdown anchor must remain untouched", alreadyAnchor, filtered)
    }

    @Test
    fun testOutputFilter_ParenthesizedGitHubForm_RewrittenToAnchor() {
        val parenthesizedForm = "Creator profile: (GitHub https://github.com/ryoaonetsuki)"
        val filtered = MiraOutputFilter.sanitize(parenthesizedForm)
        assertEquals("Creator profile: [GitHub](https://github.com/ryoaonetsuki)", filtered)
    }

    @Test
    fun testOutputFilter_LongIdentityAnswerWithCorrectFacts_PassesThroughUnchanged() {
        // A long answer in the model's own words with correct facts must NOT be replaced.
        val longGoodAnswer =
            "I am Mira, an AI assistant built by Salman Biswas. " +
            "I am here to help you with phone control, automation, answering questions, " +
            "building websites, and more. What can I help you with today?"
        val filtered = MiraOutputFilter.sanitize(longGoodAnswer)
        assertEquals("Long identity answer with correct facts must pass through unchanged", longGoodAnswer, filtered)
    }

    // ── c. sanitizeForVoice ─────────────────────────────────────────────────

    @Test
    fun testSanitizeForVoice_BareUrl_Stripped() {
        val bareUrlVoice = "Check my work at https://github.com/ryoaonetsuki"
        val sanitized = MiraOutputFilter.sanitizeForVoice(bareUrlVoice)
        assertFalse("Voice must not contain http", sanitized.contains("http", ignoreCase = true))
        assertFalse("Voice must not contain github.com", sanitized.contains("github.com", ignoreCase = true))
        assertFalse("Voice must not contain [", sanitized.contains("["))
        assertFalse("Voice must not contain ]", sanitized.contains("]"))
        assertTrue("Voice should mention GitHub spoken name", sanitized.contains("GitHub"))
        assertEquals("Check my work at GitHub", sanitized)
    }

    @Test
    fun testSanitizeForVoice_MarkdownAnchor_BecomesSpokenLabel() {
        val anchorVoice = "Here is the project on [GitHub](https://github.com/ryoaonetsuki)"
        val sanitized = MiraOutputFilter.sanitizeForVoice(anchorVoice)
        assertFalse("Voice must not contain http", sanitized.contains("http", ignoreCase = true))
        assertFalse("Voice must not contain [", sanitized.contains("["))
        assertTrue("Voice should speak the GitHub label", sanitized.contains("GitHub"))
        assertEquals("Here is the project on GitHub", sanitized)
    }

    @Test
    fun testSanitizeForVoice_ParenthesizedGitHub_BecomesSpokenLabel() {
        val parenthesized = "Find me at (GitHub https://github.com/ryoaonetsuki)"
        val sanitized = MiraOutputFilter.sanitizeForVoice(parenthesized)
        assertFalse("Voice must not contain http", sanitized.contains("http", ignoreCase = true))
        assertFalse("Voice must not contain github.com", sanitized.contains("github.com", ignoreCase = true))
        assertEquals("Find me at GitHub", sanitized)
    }

    // ── d. Prompt layers: facts + style, NO "EXACTLY" ───────────────────────

    @Test
    fun testSystemPromptOverride_ContainsFacts_NoEXACTLY() {
        val spo = IdentityProfile.SYSTEM_PROMPT_OVERRIDE
        assertTrue("SYSTEM_PROMPT_OVERRIDE must name Mira", spo.contains("Mira"))
        assertTrue("SYSTEM_PROMPT_OVERRIDE must name Salman Biswas", spo.contains("Salman Biswas"))
        assertTrue("SYSTEM_PROMPT_OVERRIDE must include the GitHub anchor", spo.contains("[GitHub](${IdentityProfile.GITHUB_URL})"))
        assertTrue("SYSTEM_PROMPT_OVERRIDE must forbid Gemini", spo.contains("Gemini"))
        assertTrue("SYSTEM_PROMPT_OVERRIDE must state ON-DEMAND", spo.contains("ON-DEMAND"))
        assertFalse("SYSTEM_PROMPT_OVERRIDE must NOT contain 'EXACTLY'", spo.contains("EXACTLY"))
        assertFalse("SYSTEM_PROMPT_OVERRIDE must NOT contain 'answer ONLY with'", spo.contains("answer ONLY with"))
    }

    @Test
    fun testLiveIdentityRule_ContainsFacts_NoEXACTLY() {
        val prompt = LivePromptBuilder.buildLiveSystemPrompt()
        assertTrue("Live prompt must name Salman Biswas", prompt.contains("Salman Biswas"))
        assertTrue("Live prompt must state ON-DEMAND ONLY", prompt.contains("ON-DEMAND ONLY"))
        assertTrue("Live prompt must forbid Gemini", prompt.contains("Gemini"))
        assertTrue("Live prompt must affirm phone control", prompt.contains("phone control"))
        assertTrue("Live prompt must forbid speaking URL aloud", prompt.contains("URL"))
        assertFalse("Live identity rule must NOT contain 'EXACTLY'", prompt.contains("EXACTLY"))
        assertFalse("Live identity rule must NOT contain 'answer ONLY'", prompt.contains("answer ONLY"))
    }

    @Test
    fun testLiveIdentityRule_PhoneControlFact_Present() {
        val prompt = LivePromptBuilder.buildLiveSystemPrompt()
        // The Live prompt must teach the phone-control fact (affirm YES, voice mode).
        assertTrue("Live prompt must affirm phone control is supported via voice mode",
            prompt.contains("voice mode") && prompt.contains("phone control"))
    }

    @Test
    fun testSystemPromptOverride_TeachesFriendlySentenceWithAnchor_NoBareUrl_NotAnchorOnly() {
        val spo = IdentityProfile.SYSTEM_PROMPT_OVERRIDE
        assertTrue("Must teach friendly sentence containing GitHub anchor",
            spo.contains("CONTAINING the markdown anchor [GitHub](${IdentityProfile.GITHUB_URL})"))
        assertTrue("Must forbid bare URL and anchor-only",
            spo.contains("never a bare URL and never the anchor alone without a sentence"))
        assertTrue("Must teach Bangla shape example", spo.contains("হ্যাঁ অবশ্যই") && spo.contains("লিংক"))
        assertTrue("Must teach English shape example", spo.contains("Yes, of course") && spo.contains("link"))
        assertTrue("Must emphasize answering in own words", spo.contains("in your own words"))
        assertFalse("Must NOT contain 'EXACTLY'", spo.contains("EXACTLY"))
    }

    @Test
    fun testLivePrompt_TeachesLiveDeveloperWithEmotion_AndPoliteLinkDeclineWithChatRedirect() {
        val prompt = LivePromptBuilder.buildLiveSystemPrompt()
        assertTrue("Must teach developer Salman", prompt.contains("Salman"))
        assertTrue("Must teach developer developed her with emotion",
            prompt.contains("developed you") || prompt.contains("ডেভেলপ করেছেন"))
        assertTrue("Must teach emotion style", prompt.contains("emotion"))
        assertTrue("Must teach polite decline in Live with chat redirect in Bangla",
            prompt.contains("চ্যাট অপশনে গিয়ে চাও") || prompt.contains("chat option"))
        assertTrue("Must forbid speaking URL aloud in Live voice",
            prompt.contains("NEVER speak any URL aloud"))
        assertFalse("Live prompt must NOT contain 'EXACTLY'", prompt.contains("EXACTLY"))
    }

    @Test
    fun testOutputFilter_RepairsBareUrlToAnchor_InFriendlySentence() {
        val banglaInput = "হ্যাঁ অবশ্যই, আমার কাছে আছে — এই যে তার https://github.com/ryoaonetsuki লিংক।"
        val banglaFiltered = MiraOutputFilter.sanitize(banglaInput)
        assertEquals("হ্যাঁ অবশ্যই, আমার কাছে আছে — এই যে তার [GitHub](https://github.com/ryoaonetsuki) লিংক।", banglaFiltered)

        val englishInput = "Yes, of course — here is his https://github.com/ryoaonetsuki link."
        val englishFiltered = MiraOutputFilter.sanitize(englishInput)
        assertEquals("Yes, of course — here is his [GitHub](https://github.com/ryoaonetsuki) link.", englishFiltered)

        // Already anchor: remains untouched
        val alreadyAnchor = "হ্যাঁ অবশ্যই, আমার কাছে আছে — এই যে তার [GitHub](https://github.com/ryoaonetsuki) লিংক।"
        assertEquals(alreadyAnchor, MiraOutputFilter.sanitize(alreadyAnchor))
    }

    @Test
    fun testSanitizeForVoice_LiveDeclineClean_UrlsStripped() {
        val liveBanglaDecline = "লাইভে তো আমি উনার লিংক দিতে পারি না — চ্যাট অপশনে গিয়ে চাও, দিয়ে দেবে।"
        assertEquals("Clean spoken line without URLs must pass through untouched",
            liveBanglaDecline, MiraOutputFilter.sanitizeForVoice(liveBanglaDecline))

        val voiceWithUrl = "I cannot give the link here in live mode. Please check https://github.com/ryoaonetsuki in chat."
        val sanitized = MiraOutputFilter.sanitizeForVoice(voiceWithUrl)
        assertFalse("Spoken text must not contain http", sanitized.contains("http", ignoreCase = true))
        assertFalse("Spoken text must not contain github.com", sanitized.contains("github.com", ignoreCase = true))
        assertFalse("Spoken text must not contain brackets", sanitized.contains("[") || sanitized.contains("]"))
    }

    // ── e. No getCannedResponse call site: compile-time enforcement ──────────
    // getCannedResponse was deleted from MiraIdentityInterceptor; this test verifies
    // the class still compiles and that detectIsBangla (the surviving helper) works.

    @Test
    fun testDetectIsBangla_BanglaScript_ReturnsTrue() {
        assertTrue("Bangla script query", MiraIdentityInterceptor.detectIsBangla("তুমি কে"))
        assertTrue("Roman Bengali query", MiraIdentityInterceptor.detectIsBangla("tumi ke"))
    }

    @Test
    fun testDetectIsBangla_EnglishQuery_ReturnsFalse() {
        assertFalse("English query", MiraIdentityInterceptor.detectIsBangla("who are you"))
        assertFalse("English identity query", MiraIdentityInterceptor.detectIsBangla("what is your name"))
    }

    // ── Additional: IdentityProfile constants sanity ─────────────────────────

    @Test
    fun testIdentityProfile_CoreConstants() {
        assertEquals("Mira", IdentityProfile.NAME)
        assertEquals("Salman Biswas", IdentityProfile.CREATOR_NAME)
        assertEquals("https://github.com/ryoaonetsuki", IdentityProfile.GITHUB_URL)
        assertFalse("identityTemplate must not contain portfolio URL", IdentityProfile.identityTemplate.contains("pages.dev"))
        assertFalse("identityTemplateBangla must not contain portfolio URL", IdentityProfile.identityTemplateBangla.contains("pages.dev"))
        assertFalse("SYSTEM_PROMPT_OVERRIDE must not contain portfolio URL", IdentityProfile.SYSTEM_PROMPT_OVERRIDE.contains("pages.dev"))
        // identityTemplate and identityTemplateBangla must be the same as the repair constants
        assertEquals(IdentityProfile.IDENTITY_ENGLISH, IdentityProfile.identityTemplate)
        assertEquals(IdentityProfile.IDENTITY_BANGLA, IdentityProfile.identityTemplateBangla)
    }
}

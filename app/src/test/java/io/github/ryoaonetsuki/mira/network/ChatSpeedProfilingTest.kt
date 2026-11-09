package io.github.ryoaonetsuki.mira.network

import io.github.ryoaonetsuki.mira.data.model.PersonaType
import io.github.ryoaonetsuki.mira.identity.IdentityProfile
import io.github.ryoaonetsuki.mira.identity.MiraIdentityInterceptor
import io.github.ryoaonetsuki.mira.projects.WebsiteProjectManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatSpeedProfilingTest {

    /**
     * Requirement 2 & Tests #1:
     * Unit test: getCannedResponse("tumi k") returns in < 50 ms (pure function, no network)
     * — proves common questions are instant.
     */
    @Test
    fun testGetCannedResponse_TumiK_ReturnsUnder50Ms() {
        // Warm-up JIT
        repeat(50) {
            MiraIdentityInterceptor.getCannedResponse("tumi k", "Bangla (Bengali)", forVoice = false)
        }

        // Measure single call execution time in nanoseconds
        val startNano = System.nanoTime()
        val cannedReply = MiraIdentityInterceptor.getCannedResponse("tumi k", "Bangla (Bengali)", forVoice = false)
        val elapsedNano = System.nanoTime() - startNano
        val elapsedMs = elapsedNano / 1_000_000.0

        assertNotNull("cannedReply must not be null for 'tumi k'", cannedReply)
        assertEquals("Must return exact canonical Bangla identity answer", IdentityProfile.IDENTITY_BANGLA, cannedReply)
        assertTrue(
            "Canned response must return in < 50 ms (measured ${elapsedMs}ms, nano=$elapsedNano)",
            elapsedMs < 50.0
        )
    }

    /**
     * Requirement 3(b) & Tests #2:
     * Unit test: short system prompt for ordinary chat is under the agreed character budget
     * (agreed budget: < 1600 characters) and still contains the identity rule + language rule;
     * full prompt is used for build/code requests (assert the file/project rules ARE present for a build request).
     */
    @Test
    fun testSystemPrompt_ShortPromptForOrdinaryChat_UnderBudgetWithCoreRules() {
        val paramsOrdinary = ChatPromptBuilder.PromptParams(
            userQuery = "hello, how are you?",
            language = "Bangla (Bengali)",
            dateLine = "Mon, Nov 9, 2026, 4:52 AM",
            personaType = PersonaType.MIRA,
            effectiveAssistantName = "Mira",
            toneDescription = "Tone: Warm, human-like, thoughtful companion.",
            userName = "User",
            userHandle = "@mira_creator",
            captionVoice = "Witty, bold and tech-savvy",
            webSearchOn = false,
            isSearchGrounded = false,
            deepThinkingOn = false
        )

        val shortPrompt = ChatPromptBuilder.buildSystemPrompt(paramsOrdinary)

        // 1. Budget assertion: must stay strictly under the agreed budget (1,600 characters)
        assertTrue(
            "Short prompt length (${shortPrompt.length}) must be strictly under agreed budget (${ChatPromptBuilder.SHORT_PROMPT_BUDGET_CHARS})",
            shortPrompt.length < ChatPromptBuilder.SHORT_PROMPT_BUDGET_CHARS
        )

        // 2. Must contain identity rule and language rule
        assertTrue("Short prompt must contain Identity rule", shortPrompt.contains("Identity rule:"))
        assertTrue("Short prompt must contain Salman Biswas attribution", shortPrompt.contains("Salman Biswas"))
        assertTrue("Short prompt must contain exact canonical Bangla identity sentence", shortPrompt.contains(IdentityProfile.IDENTITY_BANGLA))
        assertTrue("Short prompt must contain exact canonical English identity sentence", shortPrompt.contains(IdentityProfile.IDENTITY_ENGLISH))
        assertTrue("Short prompt must contain Language rule", shortPrompt.contains("Language rule: Reply ONLY in Bangla (Bengali)"))
        assertTrue("Short prompt must contain Speed rule", shortPrompt.contains("Speed rule: Keep your answer short, concise, and fast"))

        // 3. Must NOT contain heavy file/project rules or download delimiters
        assertFalse("Short prompt must NOT contain FILE_BUILDING_INSTRUCTION", shortPrompt.contains(WebsiteProjectManager.FILE_BUILDING_INSTRUCTION))
        assertFalse("Short prompt must NOT contain CHAT_CODE_INSTRUCTION", shortPrompt.contains(WebsiteProjectManager.CHAT_CODE_INSTRUCTION))
        assertFalse("Short prompt must NOT contain <<<FILE:", shortPrompt.contains("<<<FILE:"))
    }

    @Test
    fun testSystemPrompt_FullPromptForBuildAndCodeRequests_ContainsFileAndProjectRules() {
        val paramsBuild = ChatPromptBuilder.PromptParams(
            userQuery = "build me a portfolio website with clean layout",
            language = "Bangla (Bengali)",
            dateLine = "Mon, Nov 9, 2026, 4:52 AM",
            personaType = PersonaType.MIRA,
            effectiveAssistantName = "Mira",
            toneDescription = "Tone: Warm, human-like, thoughtful companion.",
            userName = "User",
            isProjectOrDownload = true
        )

        val fullPrompt = ChatPromptBuilder.buildSystemPrompt(paramsBuild)

        // 1. Full prompt MUST contain the file and project building instructions
        assertTrue("Full prompt must contain File and project rule", fullPrompt.contains("File and project rule:"))
        assertTrue("Full prompt must contain FILE_BUILDING_INSTRUCTION", fullPrompt.contains("FILE & PROJECT CREATION SPECIFICATION:"))
        assertTrue("Full prompt must contain file delimiter rules", fullPrompt.contains("<<<FILE:filename.ext>>>"))
        assertTrue("Full prompt must contain identity rule", fullPrompt.contains("Identity rule:"))
        assertTrue("Full prompt must contain language rule", fullPrompt.contains("Language rule:"))
        // Full prompt is substantially larger due to complete engineering instructions
        assertTrue("Full prompt is comprehensive (> 2500 chars)", fullPrompt.length > 2500)
    }

    /**
     * Requirement 3(c) & Tests #3:
     * Unit test: thinking is disabled for short/greeting messages;
     * thinking budget is capped at the new value (1024) when enabled.
     */
    @Test
    fun testThinkingGating_DisabledForShortAndGreetingMessages() {
        // Short messages (<= 3 words) must have thinking disabled even when enableThinking=true
        assertFalse("Thinking must be disabled for 'hi'", MiraChatSearchHeuristic.shouldEnableThinking(true, "hi"))
        assertFalse("Thinking must be disabled for 'hello'", MiraChatSearchHeuristic.shouldEnableThinking(true, "hello"))
        assertFalse("Thinking must be disabled for 'tumi k'", MiraChatSearchHeuristic.shouldEnableThinking(true, "tumi k"))
        assertFalse("Thinking must be disabled for 'tumi ke'", MiraChatSearchHeuristic.shouldEnableThinking(true, "tumi ke"))
        assertFalse("Thinking must be disabled for 'who are you'", MiraChatSearchHeuristic.shouldEnableThinking(true, "who are you"))
        assertFalse("Thinking must be disabled for 'good morning'", MiraChatSearchHeuristic.shouldEnableThinking(true, "good morning"))

        // Complex queries (> 3 words) must have thinking enabled when requested
        assertTrue(
            "Thinking must be enabled for complex reasoning queries",
            MiraChatSearchHeuristic.shouldEnableThinking(true, "Can you explain quantum computing and its advantages in detail?")
        )

        // When enableThinking=false, always false
        assertFalse(
            "Thinking must be false when enableThinking=false",
            MiraChatSearchHeuristic.shouldEnableThinking(false, "Can you explain quantum computing and its advantages in detail?")
        )

        // Assert capped thinking budget constant is 1024
        assertEquals("Thinking budget must be capped at 1024 tokens", 1024, MiraChatSearchHeuristic.MAX_THINKING_BUDGET)
        assertEquals("GeminiRestClient thinking budget constant must match", 1024, GeminiRestClient.MAX_THINKING_BUDGET)
    }

    @Test
    fun testThinkingPayloadBuilding_OmitsThinkingConfigForShortMessages_AndCapsAt1024() {
        // Models GeminiRestClient payload generationConfig logic
        fun buildThinkingConfig(enableThinking: Boolean, userMessage: String): Map<String, Any>? {
            val effectiveThinking = MiraChatSearchHeuristic.shouldEnableThinking(enableThinking, userMessage)
            if (!effectiveThinking) return null
            return mapOf("thinkingBudget" to MiraChatSearchHeuristic.MAX_THINKING_BUDGET)
        }

        // Short message (1 word) with enableThinking=true: MUST be null
        assertNull("Short message (1 word) MUST have NO thinkingConfig in payload", buildThinkingConfig(true, "hi"))
        assertNull("Greeting message MUST have NO thinkingConfig in payload", buildThinkingConfig(true, "good morning"))
        assertNull("Identity message MUST have NO thinkingConfig in payload", buildThinkingConfig(true, "tumi k"))

        // Complex message (> 3 words): MUST have thinkingConfig with budget 1024
        val complexConfig = buildThinkingConfig(true, "Please analyze the economic factors affecting global inflation in 2026")
        assertNotNull("Complex query MUST have generationConfig", complexConfig)
        assertEquals("Thinking budget MUST be capped at 1024", 1024, complexConfig!!["thinkingBudget"])
    }

    /**
     * Requirement 3(d) & Tests #4:
     * Unit test: search heuristic returns false for "hi", "tumi k", "tumi ke", "who are you"
     * (no pre-model search delay for short chat).
     */
    @Test
    fun testSearchHeuristic_ReturnsFalseForShortChatGreetingsAndIdentity() {
        // Required 4 checks
        assertFalse("Heuristic must return false for 'hi'", MiraChatSearchHeuristic.isChatSearchWorthy("hi"))
        assertFalse("Heuristic must return false for 'tumi k'", MiraChatSearchHeuristic.isChatSearchWorthy("tumi k"))
        assertFalse("Heuristic must return false for 'tumi ke'", MiraChatSearchHeuristic.isChatSearchWorthy("tumi ke"))
        assertFalse("Heuristic must return false for 'who are you'", MiraChatSearchHeuristic.isChatSearchWorthy("who are you"))

        // Additional variants
        assertFalse("Heuristic must return false for 'hello'", MiraChatSearchHeuristic.isChatSearchWorthy("hello"))
        assertFalse("Heuristic must return false for 'হাই'", MiraChatSearchHeuristic.isChatSearchWorthy("হাই"))
        assertFalse("Heuristic must return false for 'তুমি কে'", MiraChatSearchHeuristic.isChatSearchWorthy("তুমি কে"))
        assertFalse("Heuristic must return false for 'who made you'", MiraChatSearchHeuristic.isChatSearchWorthy("who made you"))
        assertFalse("Heuristic must return false for 'tomar nam ki'", MiraChatSearchHeuristic.isChatSearchWorthy("tomar nam ki"))

        // Genuinely search-worthy queries MUST still return true
        assertTrue("Heuristic must return true for explicit search", MiraChatSearchHeuristic.isChatSearchWorthy("search latest developments in AI"))
        assertTrue("Heuristic must return true for weather queries", MiraChatSearchHeuristic.isChatSearchWorthy("latest weather forecast in Dhaka today"))
        assertTrue("Heuristic must return true for build request", MiraChatSearchHeuristic.isChatSearchWorthy("build a modern web app"))
        assertTrue("Heuristic must return true for stock/bitcoin price", MiraChatSearchHeuristic.isChatSearchWorthy("bitcoin price right now"))
    }

    /**
     * Requirement 3(e):
     * Retries: fallback generateChatResponse retry backoff cut to 1 fast retry (2s).
     */
    @Test
    fun testFallbackRetryPolicy_CutToOneFastRetryOfTwoSeconds() {
        assertEquals("Must have exactly 1 retry delay configured", 1, GeminiRestClient.FALLBACK_RETRY_DELAYS_MS.size)
        assertEquals("Fast retry delay must be 2000 ms", 2000L, GeminiRestClient.FALLBACK_RETRY_DELAYS_MS[0])
    }
}

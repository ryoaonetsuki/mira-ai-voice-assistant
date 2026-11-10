package io.github.ryoaonetsuki.mira.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for VoiceMemoryPreload:
 * Tokenization, keyword/token Jaccard scoring, memory section prompt formatting,
 * sensitive fact detection, and importance-aware fact ranking.
 */
class VoiceMemoryPreloadTest {

    @Test
    fun testTokenize_DropsShortTokensAndStopwords_EnglishAndBangla() {
        // English stopwords and short tokens (< 3 chars) dropped
        val englishTokens = VoiceMemoryPreload.tokenize("This is a coffee shop and an apple to go")
        assertTrue("Tokens should contain 'coffee'", englishTokens.contains("coffee"))
        assertTrue("Tokens should contain 'shop'", englishTokens.contains("shop"))
        assertTrue("Tokens should contain 'apple'", englishTokens.contains("apple"))
        assertFalse("Stopword 'this' must be dropped", englishTokens.contains("this"))
        assertFalse("Stopword 'is' must be dropped", englishTokens.contains("is"))
        assertFalse("Stopword 'a' must be dropped", englishTokens.contains("a"))
        assertFalse("Stopword 'and' must be dropped", englishTokens.contains("and"))
        assertFalse("Stopword 'an' must be dropped", englishTokens.contains("an"))
        assertFalse("Stopword 'to' must be dropped", englishTokens.contains("to"))
        assertFalse("Short word 'go' must be dropped", englishTokens.contains("go"))

        // Bangla stopwords and short tokens (< 3 chars) dropped
        // "আমি" -> stopword; "এবং" -> stopword; "তুমি" -> stopword; "চা" -> 2 chars ('চ'+'া'); "খাবো" -> kept
        val banglaTokens = VoiceMemoryPreload.tokenize("আমি এবং তুমি চা খাবো")
        assertTrue("Bangla token 'খাবো' must be kept", banglaTokens.contains("খাবো"))
        assertFalse("Bangla stopword 'আমি' must be dropped", banglaTokens.contains("আমি"))
        assertFalse("Bangla stopword 'এবং' must be dropped", banglaTokens.contains("এবং"))
        assertFalse("Bangla stopword 'তুমি' must be dropped", banglaTokens.contains("তুমি"))
        assertFalse("Bangla short token 'চা' must be dropped", banglaTokens.contains("চা"))
    }

    @Test
    fun testScoreFacts_RanksBestOverlapFirst_AndReturnsAtMost3() {
        val facts = listOf(
            Triple(1L, "blue coffee mug on desk", "Preference"),
            Triple(2L, "coffee beans roasted downtown", "General"),
            Triple(3L, "blue ocean water", "Travel"),
            Triple(4L, "blue car parked outside", "Vehicle"),
            Triple(5L, "elephant safari in wildlife park", "Wildlife")
        )

        val recalled = VoiceMemoryPreload.scoreFacts("favorite blue coffee mug", facts)

        assertEquals("Must return at most 3 facts", 3, recalled.size)
        assertEquals("Best overlap fact (id=1) must be ranked first", 1L, recalled[0].id)
        assertTrue("Score for fact 1 must be higher than fact 2", recalled[0].score > recalled[1].score)
        assertTrue("All returned facts must have score > 0", recalled.all { it.score > 0.0 })
        assertFalse("Fact 5 with zero overlap must not be in recalled facts", recalled.any { it.id == 5L })
    }

    @Test
    fun testScoreFacts_EmptyQueryOrNoMatch_ReturnsEmpty() {
        val facts = listOf(
            Triple(1L, "learning kotlin programming", "Coding")
        )

        val emptyQuery = VoiceMemoryPreload.scoreFacts("", facts)
        assertTrue("Empty query must return empty list", emptyQuery.isEmpty())

        val stopwordsOnly = VoiceMemoryPreload.scoreFacts("the is a in", facts)
        assertTrue("Stopwords-only query must return empty list", stopwordsOnly.isEmpty())

        val noMatch = VoiceMemoryPreload.scoreFacts("xylophone music concert", facts)
        assertTrue("No match query must return empty list", noMatch.isEmpty())
    }

    @Test
    fun testBuildMemorySection_BothEmpty_ReturnsExactFallbackString() {
        val result = VoiceMemoryPreload.buildMemorySection(emptyList(), emptyList())
        assertEquals(
            "No saved memories yet. Do not invent facts about the user.",
            result
        )
    }

    @Test
    fun testBuildMemorySection_FormatsFactsAndRecentLines_TruncatesLongText_KeepsLast6() {
        val longFactText = "A".repeat(250)
        val facts = listOf(
            Triple(1L, longFactText, "Work"),
            Triple(2L, "Short fact text", "Personal")
        )

        val longLineText = "B".repeat(250)
        val recentLines = listOf(
            Pair("user", "line 1"),
            Pair("model", "line 2"),
            Pair("user", "line 3"),
            Pair("model", "line 4"),
            Pair("user", "line 5"),
            Pair("model", "line 6"),
            Pair("user", "line 7"),
            Pair("model", longLineText)
        )

        val section = VoiceMemoryPreload.buildMemorySection(facts, recentLines)

        // Verifies header lines
        assertTrue("Must include 'Saved memories:'", section.contains("Saved memories:"))
        assertTrue("Must include 'Recent conversation:'", section.contains("Recent conversation:"))

        // Verifies bullet format and fact truncation
        val expectedTruncatedFact = "A".repeat(VoiceMemoryPreload.MAX_FACT_CHARS) + "..."
        assertTrue("Fact must be formatted as '- [Work] truncated...'", section.contains("- [Work] $expectedTruncatedFact"))
        assertTrue("Short fact must be preserved", section.contains("- [Personal] Short fact text"))

        // Verifies recent lines keeps only last 6 (lines 3 to 8)
        assertFalse("Line 1 should be dropped", section.contains("User: line 1"))
        assertFalse("Line 2 should be dropped", section.contains("Mira: line 2"))
        assertTrue("Line 3 should be kept", section.contains("User: line 3"))
        assertTrue("Line 7 should be kept", section.contains("User: line 7"))

        // Verifies role formatting and line truncation
        val expectedTruncatedLine = "B".repeat(VoiceMemoryPreload.MAX_LINE_CHARS) + "..."
        assertTrue("Model line must be formatted as 'Mira: truncated...'", section.contains("Mira: $expectedTruncatedLine"))
    }

    @Test
    fun testIsSensitiveFact_MatchesExpectedPatterns() {
        assertTrue("Password phrase must be sensitive", VoiceMemoryPreload.isSensitiveFact("my password is hunter2"))
        assertTrue("OTP phrase must be sensitive", VoiceMemoryPreload.isSensitiveFact("OTP 483920"))
        assertTrue("PIN phrase must be sensitive", VoiceMemoryPreload.isSensitiveFact("my pin is 4832"))
        assertTrue("16-digit card with spaces must be sensitive", VoiceMemoryPreload.isSensitiveFact("4111 2222 3333 4444"))
        assertTrue("16-digit continuous card must be sensitive", VoiceMemoryPreload.isSensitiveFact("4111222233334444"))

        assertFalse("Normal preference must not be sensitive", VoiceMemoryPreload.isSensitiveFact("I like green tea"))
        assertFalse("Birthday statement must not be sensitive", VoiceMemoryPreload.isSensitiveFact("my birthday is in June"))
        assertFalse("Shipping phrase must not be sensitive", VoiceMemoryPreload.isSensitiveFact("I love shipping"))
        assertFalse("Shopping phrase must not be sensitive", VoiceMemoryPreload.isSensitiveFact("my shopping list"))
    }

    @Test
    fun testRankFactsForPreload_OrdersByImportanceThenRecency_TakesTop3() {
        val facts = listOf(
            VoiceMemoryPreload.MemoryRow(id = 1L, text = "Fact 1", category = "General", importance = 1, lastUsedAt = 1000L),
            VoiceMemoryPreload.MemoryRow(id = 2L, text = "Fact 2", category = "General", importance = 3, lastUsedAt = 2000L),
            VoiceMemoryPreload.MemoryRow(id = 3L, text = "Fact 3", category = "General", importance = 3, lastUsedAt = 3000L),
            VoiceMemoryPreload.MemoryRow(id = 4L, text = "Fact 4", category = "General", importance = 2, lastUsedAt = 5000L),
            VoiceMemoryPreload.MemoryRow(id = 5L, text = "Fact 5", category = "General", importance = 1, lastUsedAt = 4000L)
        )

        val ranked = VoiceMemoryPreload.rankFactsForPreload(facts)

        assertEquals("Must take top 3", 3, ranked.size)
        assertEquals("1st should be highest importance and newest lastUsedAt (id=3)", 3L, ranked[0].id)
        assertEquals("2nd should be importance 3, older lastUsedAt (id=2)", 2L, ranked[1].id)
        assertEquals("3rd should be importance 2 (id=4)", 4L, ranked[2].id)
    }
}

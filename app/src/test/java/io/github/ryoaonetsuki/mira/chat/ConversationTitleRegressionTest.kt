package io.github.ryoaonetsuki.mira.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Regression tests for conversation title derivation (Turn 25 Fix 1).
 *
 * Owner bug report:
 * First message "Tumi k" was showing history title "umi k" — dropping the FIRST CHARACTER
 * because of a broken regex range (^[#*-_\\s]+ where *-_ matched ASCII 42..95, including A..Z).
 *
 * Invariants tested:
 * 1. "Tumi k" -> title "Tumi k" (first character 'T' is preserved).
 * 2. "hi" -> title "hi".
 * 3. Single-character cases ("a", "Z") preserved in full.
 * 4. Capital letters at start ("Good morning", "Hello", "Python") never lose their first character.
 * 5. Blank/empty messages fall back to "Chat • <Date>".
 * 6. Truncation keeps the START of the message (never drops character 0) up to 37 chars + "…".
 * 7. Multiline message uses the first non-blank line.
 * 8. Descriptive conversation title is preserved on subsequent messages.
 * 9. Short generic greeting title is updated when a real topic message arrives.
 */
class ConversationTitleRegressionTest {

    @Test
    fun testFirstMessageTumiK_ProducesTitleTumiK_NeverDropsFirstCharacter() {
        val initialTitle = "New Chat"
        val derived = ConversationTitleHelper.deriveTitle(initialTitle, "Tumi k")
        assertEquals("Title for 'Tumi k' must be 'Tumi k' (never drop 'T')", "Tumi k", derived)
        assertTrue("Title must start with 'T'", derived.startsWith("T"))
    }

    @Test
    fun testFirstMessageHi_ProducesTitleHi() {
        val derivedLower = ConversationTitleHelper.deriveTitle("New Chat", "hi")
        assertEquals("First message 'hi' must produce title 'hi'", "hi", derivedLower)

        val derivedUpper = ConversationTitleHelper.deriveTitle("New Chat", "Hi")
        assertEquals("First message 'Hi' must produce title 'Hi' (never drop 'H')", "Hi", derivedUpper)
    }

    @Test
    fun testSingleCharacterEdgeCases() {
        assertEquals("Single character 'a'", "a", ConversationTitleHelper.deriveTitle("New Chat", "a"))
        assertEquals("Single character 'Z'", "Z", ConversationTitleHelper.deriveTitle("New Chat", "Z"))
        assertEquals("Single character '1'", "1", ConversationTitleHelper.deriveTitle("New Chat", "1"))
    }

    @Test
    fun testCapitalLettersAtStart_NeverDropped() {
        val testCases = listOf(
            "Hello world",
            "Good morning",
            "Python programming",
            "Are you an AI?",
            "What can you do?",
            "Salman Biswas",
            "Mira AI assistant"
        )
        for (input in testCases) {
            val derived = ConversationTitleHelper.deriveTitle("New Chat", input)
            assertEquals("Capital letter at start must never be dropped for: $input", input, derived)
            assertEquals("First character must match", input.first(), derived.first())
        }
    }

    @Test
    fun testEmptyAndBlankEdgeCases_FallbackToDateString() {
        val emptyDerived = ConversationTitleHelper.deriveTitle("New Chat", "")
        assertTrue("Empty string must fall back to 'Chat • ...'", emptyDerived.startsWith("Chat •"))

        val whitespaceDerived = ConversationTitleHelper.deriveTitle("New Chat", "   \n\t  ")
        assertTrue("Whitespace must fall back to 'Chat • ...'", whitespaceDerived.startsWith("Chat •"))
    }

    @Test
    fun testTruncationKeepsStart_NeverDropsFirstCharacter() {
        val longMessage = "This is a very long message that definitely exceeds thirty-eight characters in total length"
        val derived = ConversationTitleHelper.deriveTitle("New Chat", longMessage)

        assertTrue("Truncated title must start with the original message's first word", derived.startsWith("This is a very long message"))
        assertTrue("Truncated title must end with ellipsis", derived.endsWith("…"))
        assertEquals("Truncated title length must be 38 chars (37 + ellipsis)", 38, derived.length)
        assertEquals("First character must be 'T'", 'T', derived.first())
    }

    @Test
    fun testMultilineFirstMessage_UsesFirstNonBlankLine() {
        val multiline = "\n\n   First line of message   \nSecond line\nThird line"
        val derived = ConversationTitleHelper.deriveTitle("New Chat", multiline)
        assertEquals("First line of message", derived)
    }

    @Test
    fun testDescriptiveTitlePreservedOnSubsequentMessages() {
        val establishedTitle = "Tumi k"
        val secondTurnDerived = ConversationTitleHelper.deriveTitle(establishedTitle, "Tell me about yourself")
        assertEquals("Descriptive title must remain 'Tumi k'", "Tumi k", secondTurnDerived)
    }

    @Test
    fun testGenericGreetingTitle_UpdatedBySubsequentRealTopic() {
        val greetingTitle = "hi"
        val updated = ConversationTitleHelper.deriveTitle(greetingTitle, "Tell me about quantum computing")
        assertEquals("Generic greeting title 'hi' must be updated to topic title",
            "Tell me about quantum computing", updated)
    }
}

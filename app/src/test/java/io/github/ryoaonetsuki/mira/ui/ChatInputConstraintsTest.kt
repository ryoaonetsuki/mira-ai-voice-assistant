package io.github.ryoaonetsuki.mira.ui

import androidx.compose.ui.unit.dp
import io.github.ryoaonetsuki.mira.ui.screens.CHAT_INPUT_LINE_HEIGHT_SP
import io.github.ryoaonetsuki.mira.ui.screens.CHAT_INPUT_MAX_HEIGHT
import io.github.ryoaonetsuki.mira.ui.screens.CHAT_INPUT_MAX_LINES
import io.github.ryoaonetsuki.mira.ui.screens.CHAT_INPUT_VERTICAL_PADDING
import io.github.ryoaonetsuki.mira.ui.screens.ChatInputLayoutPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit regression test for OWNER ERROR #13:
 * Chat input box exploding to full-screen height when pasting long prompts.
 *
 * Asserts that:
 * 1. The input field height is capped at [CHAT_INPUT_MAX_LINES] (~5 lines) / [CHAT_INPUT_MAX_HEIGHT] (120dp).
 * 2. Pasting multi-KB / 50+ lines does NOT grow the box past the cap.
 * 3. The bottom controls row (Think/Search/Image toggles + send button) remains fully laid out and visible.
 * 4. Extreme font scaling is safely clamped at [CHAT_INPUT_MAX_HEIGHT].
 */
class ChatInputConstraintsTest {

    @Test
    fun testChatInputConstants_MatchDeepSeekBehaviourSpec() {
        assertEquals("Chat input max visible lines must be 5 (DeepSeek spec)", 5, CHAT_INPUT_MAX_LINES)
        assertEquals("Chat input max height must be 120.dp", 120.dp, CHAT_INPUT_MAX_HEIGHT)
        assertEquals("Chat input line height must match TextStyle 20sp", 20f, CHAT_INPUT_LINE_HEIGHT_SP, 0.001f)
        assertEquals("Chat input vertical padding must match 8.dp (4.dp top + 4.dp bottom)", 8.dp, CHAT_INPUT_VERTICAL_PADDING)
    }

    @Test
    fun testPastingFiftyLinePrompt_CapsInputHeightAtMaxLinesAndMaxHeight() {
        // Construct a long multi-KB prompt of 60 lines
        val lines = (1..60).map { i ->
            "Line $i: This is a realistic detailed instruction block for Mira AI with arguments and context."
        }
        val pastedPrompt = lines.joinToString("\n")

        assertTrue("Prompt must have 50+ lines", lines.size >= 50)
        assertTrue("Prompt must be several KB in size", pastedPrompt.toByteArray().size > 3000)

        // Calculate expected height
        val estimatedLines = ChatInputLayoutPolicy.estimateLineCount(pastedPrompt)
        assertTrue("Estimated line count must be >= 60", estimatedLines >= 60)

        val cappedHeightDp = ChatInputLayoutPolicy.calculateInputHeightDp(estimatedLines)

        // 5 lines * 20sp + 8dp padding = 108dp <= 120dp
        assertEquals("Input height must be capped at 5 visible lines (108dp)", 108f, cappedHeightDp, 0.01f)
        assertTrue("Input height must strictly be <= CHAT_INPUT_MAX_HEIGHT (120dp)", cappedHeightDp <= CHAT_INPUT_MAX_HEIGHT.value)
    }

    @Test
    fun testPastingMultiKilobyteUnbrokenText_CapsLineCountAndHeight() {
        // Construct 5,000 characters with no explicit newlines (word-wrapped text)
        val unbrokenText = "Supercalifragilisticexpialidocious ".repeat(150)
        assertTrue("Unbroken text must be > 5KB", unbrokenText.length > 5000)

        val estimatedLines = ChatInputLayoutPolicy.estimateLineCount(unbrokenText, charsPerLine = 35)
        assertTrue("Text must wrap into > 50 lines", estimatedLines > 50)

        val cappedHeightDp = ChatInputLayoutPolicy.calculateInputHeightDp(estimatedLines)
        assertEquals(108f, cappedHeightDp, 0.01f)
        assertTrue("Capped height must never exceed CHAT_INPUT_MAX_HEIGHT", cappedHeightDp <= CHAT_INPUT_MAX_HEIGHT.value)
    }

    @Test
    fun testSendButtonAndBottomRowRemainLaidOut_UnderMassivePaste() {
        // When keyboard is visible, typical available height is ~400dp
        val availableScreenHeightDp = 400f
        val massivePrompt = "Long prompt instruction line\n".repeat(200)

        val lineCount = massivePrompt.lines().size
        val inputFieldHeight = ChatInputLayoutPolicy.calculateInputHeightDp(lineCount)

        // Calculate total card height:
        // Card padding: 8dp top + 8dp bottom = 16dp
        // BasicTextField: capped at inputFieldHeight (108dp)
        // Spacer: 6dp
        // Bottom Row (Pills + Send Button): 34dp
        val cardPaddingVerticalDp = 16f
        val spacerDp = 6f
        val bottomRowHeightDp = 34f

        val totalInputCardHeightDp = cardPaddingVerticalDp + inputFieldHeight + spacerDp + bottomRowHeightDp
        assertEquals(164f, totalInputCardHeightDp, 0.01f)
        assertTrue("Total input card height must be well under 200dp", totalInputCardHeightDp <= 176f)

        val remainingChatSpaceDp = availableScreenHeightDp - totalInputCardHeightDp
        assertTrue("Chat messages LazyColumn must retain plenty of visible space", remainingChatSpaceDp >= 200f)

        // Bottom row is ALWAYS laid out at (cardPaddingTop + inputFieldHeight + spacerDp)
        val bottomRowTopOffset = 8f + inputFieldHeight + spacerDp
        assertEquals("Bottom row position inside card is always stable", 122f, bottomRowTopOffset, 0.01f)
        assertTrue("Bottom row stays completely inside the card", (bottomRowTopOffset + bottomRowHeightDp) <= totalInputCardHeightDp)
    }

    @Test
    fun testAccessibilityFontScale_NeverExceedsMaxHeightCap() {
        // High font scaling (1.5x in system accessibility settings)
        // 5 lines * 20sp * 1.5 + 8dp = 158dp -> MUST be clamped to 120.dp
        val heightAt150Scale = ChatInputLayoutPolicy.calculateInputHeightDp(lineCount = 10, fontScale = 1.5f)
        assertEquals("1.5x font scale must be hard clamped to 120dp", 120f, heightAt150Scale, 0.01f)

        // Extreme font scaling (2.0x)
        // 5 lines * 20sp * 2.0 + 8dp = 208dp -> MUST be clamped to 120.dp
        val heightAt200Scale = ChatInputLayoutPolicy.calculateInputHeightDp(lineCount = 10, fontScale = 2.0f)
        assertEquals("2.0x font scale must be hard clamped to 120dp", 120f, heightAt200Scale, 0.01f)
    }

    @Test
    fun testInputGrowsNaturallyFromOneToFiveLinesBeforeCapping() {
        // Line 1: 1 * 20 + 8 = 28dp
        assertEquals(28f, ChatInputLayoutPolicy.calculateInputHeightDp(1), 0.01f)
        // Line 2: 2 * 20 + 8 = 48dp
        assertEquals(48f, ChatInputLayoutPolicy.calculateInputHeightDp(2), 0.01f)
        // Line 3: 3 * 20 + 8 = 68dp
        assertEquals(68f, ChatInputLayoutPolicy.calculateInputHeightDp(3), 0.01f)
        // Line 4: 4 * 20 + 8 = 88dp
        assertEquals(88f, ChatInputLayoutPolicy.calculateInputHeightDp(4), 0.01f)
        // Line 5: 5 * 20 + 8 = 108dp
        assertEquals(108f, ChatInputLayoutPolicy.calculateInputHeightDp(5), 0.01f)
        // Line 6: capped at 108dp
        assertEquals(108f, ChatInputLayoutPolicy.calculateInputHeightDp(6), 0.01f)
        // Line 100: capped at 108dp
        assertEquals(108f, ChatInputLayoutPolicy.calculateInputHeightDp(100), 0.01f)
        // Line 10,000: capped at 108dp
        assertEquals(108f, ChatInputLayoutPolicy.calculateInputHeightDp(10000), 0.01f)
    }
}

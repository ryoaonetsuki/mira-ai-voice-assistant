package io.github.ryoaonetsuki.mira.chat

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Derives user-facing conversation titles for chat history.
 *
 * Rules (Turn 25):
 * 1. History titles use the FULL first message (first non-blank line, trimmed).
 * 2. Never drops the first character (no regex character-range bugs).
 * 3. Truncation to length keeps the START of the message (up to 37 chars + "…").
 * 4. Empty or blank messages fall back to "Chat • <Date>".
 * 5. Generic titles ("New Chat", "Mira Conversation", blank, or short greetings like "hi")
 *    are updated when a subsequent user message arrives; once given a descriptive topic title,
 *    it is preserved.
 */
object ConversationTitleHelper {

    private val GENERIC_GREETINGS = setOf(
        "hi", "hello", "hey", "হেই", "হাই", "হ্যালো", "sup", "yo"
    )

    fun isGenericTitle(title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.equals("Mira Conversation", ignoreCase = true)) return true
        if (trimmed.equals("New Chat", ignoreCase = true)) return true
        if (trimmed.lowercase(Locale.ROOT) in GENERIC_GREETINGS) return true
        if (trimmed.length <= 4) return true
        return false
    }

    fun deriveTitle(
        currentTitle: String,
        lastUserText: String,
        now: Date = Date()
    ): String {
        if (!isGenericTitle(currentTitle)) {
            return currentTitle
        }

        val clean = lastUserText.lines().firstOrNull { it.isNotBlank() }?.trim() ?: lastUserText.trim()
        if (clean.isBlank()) {
            return "Chat • ${SimpleDateFormat("MMM d", Locale.getDefault()).format(now)}"
        }

        return if (clean.length > 38) {
            "${clean.take(37).trimEnd()}…"
        } else {
            clean
        }
    }
}

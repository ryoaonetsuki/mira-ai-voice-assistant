package io.github.ryoaonetsuki.mira.identity

import java.util.Locale

/**
 * Layer 2 — Output Filter for Gemini model responses and transcripts.
 *
 * If a Gemini response leaks any forbidden external model or corporate identity tokens
 * ("Gemini", "Google", "large language model", "LLM", or their Bangla equivalents),
 * replaces the WHOLE response with the canonical short IdentityProfile template in the matching language.
 *
 * The replacement is SHORT, never reveals Gemini/Google, and never includes bare URLs.
 * Leaves clean responses unchanged.
 */
object MiraOutputFilter {

    private val FORBIDDEN_TOKENS = listOf(
        "gemini",
        "google",
        "large language model",
        "llm",
        "গুগল",
        "জেমিনি",
        "লার্জ ল্যাঙ্গুয়েজ মডেল"
    )

    /**
     * Sanitizes response text against forbidden identity tokens.
     */
    fun sanitize(responseText: String): String {
        val lower = responseText.lowercase(Locale.ROOT)
        val containsForbidden = FORBIDDEN_TOKENS.any { lower.contains(it) }

        return if (containsForbidden) {
            val isBangla = responseText.any { it in '\u0980'..'\u09FF' }
            if (isBangla) {
                IdentityProfile.identityTemplateBangla
            } else {
                IdentityProfile.identityTemplate
            }
        } else {
            responseText
        }
    }
}

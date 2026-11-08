package io.github.ryoaonetsuki.mira.identity

import java.util.Locale

/**
 * Layer 2 — Output Filter for Gemini model responses and transcripts.
 *
 * If a Gemini response leaks any forbidden external model or corporate identity tokens
 * ("Gemini", "Google", "large language model", "LLM"), replaces the WHOLE response with
 * the canonical IdentityProfile template. Otherwise leaves the response unchanged.
 */
object MiraOutputFilter {

    private val FORBIDDEN_TOKENS = listOf(
        "gemini",
        "google",
        "large language model",
        "llm"
    )

    /**
     * Sanitizes response text against forbidden identity tokens.
     */
    fun sanitize(responseText: String): String {
        val lower = responseText.lowercase(Locale.ROOT)
        val containsForbidden = FORBIDDEN_TOKENS.any { lower.contains(it) }

        return if (containsForbidden) {
            IdentityProfile.identityTemplate
        } else {
            responseText
        }
    }
}

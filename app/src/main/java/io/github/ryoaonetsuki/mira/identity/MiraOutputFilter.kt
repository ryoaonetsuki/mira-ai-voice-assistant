package io.github.ryoaonetsuki.mira.identity

import java.util.Locale

/**
 * Repair-only output filter for Gemini model responses and transcripts.
 *
 * 1. Forbidden-token repair: when a sentence contains a forbidden vendor token
 *    (Gemini, Google, LLM, etc.) the offending sentence is replaced in-place with
 *    a short identity repair string; the rest of the model answer is preserved unchanged.
 *    A good model answer in the model's own words is NEVER replaced wholesale.
 * 2. (Removed) Long-identity replacement — superseded. Short, natural answers are
 *    taught via prompt layers; the filter no longer enforces verbatim replacement.
 * 3. Bare-URL rewrite: bare occurrences of the GitHub profile URL become a markdown
 *    anchor [GitHub](url) in chat; in voice they become the spoken word "GitHub" only.
 *    Never speaks URLs aloud.
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
     * Sanitizes chat response text: repairs forbidden-token sentences in place,
     * and rewrites bare GitHub URLs to markdown anchors.
     */
    fun sanitize(responseText: String): String {
        return sanitizeInternal(responseText, forVoice = false)
    }

    /**
     * Sanitizes voice transcript / TTS text. Drops URLs completely so nothing URL-like
     * is ever spoken aloud.
     */
    fun sanitizeForVoice(responseText: String): String {
        return sanitizeInternal(responseText, forVoice = true)
    }

    private fun sanitizeInternal(responseText: String, forVoice: Boolean): String {
        // 1. Forbidden-token REPAIR: replace only the offending sentence(s) in place.
        val repaired = repairForbiddenTokens(responseText)

        // 2. (Step 2 removed — no long-identity wholesale replacement.)

        // 3. Bare-URL rewrite.
        return if (forVoice) {
            rewriteUrlsForVoice(repaired)
        } else {
            rewriteUrlsForChat(repaired)
        }
    }

    /**
     * Splits the text into sentences, replaces any sentence that contains a forbidden token
     * with a short in-place repair string, and reassembles. The surrounding text is untouched.
     */
    private fun repairForbiddenTokens(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        if (FORBIDDEN_TOKENS.none { lower.contains(it) }) return text

        val isBangla = text.any { it in '\u0980'..'\u09FF' }
        val repairSentence = if (isBangla) {
            IdentityProfile.identityTemplateBangla
        } else {
            IdentityProfile.identityTemplate
        }

        // Split on sentence boundaries (keeping delimiters) so we can repair individual sentences.
        val parts = splitKeepingDelimiters(text, Regex("[।!?]|\\.(?:\\s|$)"))
        val result = StringBuilder()
        for (part in parts) {
            val partLower = part.lowercase(Locale.ROOT)
            if (FORBIDDEN_TOKENS.any { partLower.contains(it) }) {
                // Replace just this sentence with the repair string.
                result.append(repairSentence)
            } else {
                result.append(part)
            }
        }
        return result.toString().trim()
    }

    /**
     * Splits [text] by [delimiter], yielding alternating content / delimiter pieces
     * so the full text can be reconstructed.
     */
    private fun splitKeepingDelimiters(text: String, delimiter: Regex): List<String> {
        val result = mutableListOf<String>()
        var last = 0
        for (match in delimiter.findAll(text)) {
            result.add(text.substring(last, match.range.last + 1))
            last = match.range.last + 1
        }
        if (last < text.length) result.add(text.substring(last))
        return result
    }

    private fun rewriteUrlsForChat(text: String): String {
        val anchors = mutableListOf<String>()

        // Protect existing markdown anchors
        val protectedText = Regex("""\[([^\]]+)\]\((https?://[^\)]+)\)""").replace(text) { match ->
            anchors.add(match.value)
            "@@@MIRA_MD_ANCHOR_${anchors.size - 1}@@@"
        }

        var s = protectedText

        // (GitHub https://github.com/ryoaonetsuki) or (GitHub github.com/ryoaonetsuki)
        s = Regex("""\(\s*GitHub\s+https?://github\.com/ryoaonetsuki/?\s*\)""", RegexOption.IGNORE_CASE).replace(s) {
            anchors.add("[GitHub](${IdentityProfile.GITHUB_URL})")
            "@@@MIRA_MD_ANCHOR_${anchors.size - 1}@@@"
        }
        s = Regex("""\(\s*GitHub\s+github\.com/ryoaonetsuki/?\s*\)""", RegexOption.IGNORE_CASE).replace(s) {
            anchors.add("[GitHub](${IdentityProfile.GITHUB_URL})")
            "@@@MIRA_MD_ANCHOR_${anchors.size - 1}@@@"
        }

        // Bare https://github.com/ryoaonetsuki
        s = Regex("""https?://github\.com/ryoaonetsuki/?(?!\w)""", RegexOption.IGNORE_CASE).replace(s) {
            anchors.add("[GitHub](${IdentityProfile.GITHUB_URL})")
            "@@@MIRA_MD_ANCHOR_${anchors.size - 1}@@@"
        }

        // Bare github.com/ryoaonetsuki
        s = Regex("""(?<!/)(?<!@)\bgithub\.com/ryoaonetsuki/?(?!\w)""", RegexOption.IGNORE_CASE).replace(s) {
            anchors.add("[GitHub](${IdentityProfile.GITHUB_URL})")
            "@@@MIRA_MD_ANCHOR_${anchors.size - 1}@@@"
        }

        // Restore all anchors
        anchors.forEachIndexed { index, anchor ->
            s = s.replace("@@@MIRA_MD_ANCHOR_${index}@@@", anchor)
        }

        return s
    }

    private fun rewriteUrlsForVoice(text: String): String {
        var s = text

        // (GitHub https://github.com/ryoaonetsuki) -> GitHub
        s = Regex("""\(\s*GitHub\s+https?://github\.com/ryoaonetsuki/?\s*\)""", RegexOption.IGNORE_CASE).replace(s, "GitHub")
        s = Regex("""\(\s*GitHub\s+github\.com/ryoaonetsuki/?\s*\)""", RegexOption.IGNORE_CASE).replace(s, "GitHub")

        // [label](https?://github.com/ryoaonetsuki) -> GitHub
        s = Regex("""\[([^\]]*)\]\(https?://github\.com/ryoaonetsuki/?\)""", RegexOption.IGNORE_CASE).replace(s, "GitHub")
        s = Regex("""\[([^\]]*)\]\(github\.com/ryoaonetsuki/?\)""", RegexOption.IGNORE_CASE).replace(s, "GitHub")

        // Bare github URL -> GitHub
        s = Regex("""https?://github\.com/ryoaonetsuki/?(?!\w)""", RegexOption.IGNORE_CASE).replace(s, "GitHub")
        s = Regex("""(?<!/)(?<!@)\bgithub\.com/ryoaonetsuki/?(?!\w)""", RegexOption.IGNORE_CASE).replace(s, "GitHub")

        // Any other markdown anchor [label](url) -> label only
        s = Regex("""\[([^\]]+)\]\([^)]+\)""").replace(s, "$1")

        // Any remaining bare http/https URLs -> strip so never spoken aloud
        s = Regex("""https?://\S+""").replace(s, "")

        // Clean up any leftover brackets
        s = s.replace("[", "").replace("]", "")

        return s.trim()
    }
}

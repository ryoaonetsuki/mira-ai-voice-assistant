package io.github.ryoaonetsuki.mira.identity

import java.util.Locale

/**
 * Layer 2 — Output Filter for Gemini model responses and transcripts.
 *
 * 1. Forbidden token replacement: replaces whole response with exact short identity sentence.
 * 2. Long identity replacement: replaces long identity / developer answers with exact short canned answer.
 * 3. Bare URL rewrite: rewrites bare occurrences of GitHub profile into markdown anchor [GitHub](url) in chat,
 *    and into spoken word "GitHub" in voice (never speaks URLs aloud).
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
     * Sanitizes chat response text against forbidden identity tokens, long identity dumps,
     * and bare GitHub URLs.
     */
    fun sanitize(responseText: String): String {
        return sanitizeInternal(responseText, forVoice = false)
    }

    /**
     * Sanitizes voice transcript / TTS text. Drops URLs completely so nothing URL-like is ever spoken aloud.
     */
    fun sanitizeForVoice(responseText: String): String {
        return sanitizeInternal(responseText, forVoice = true)
    }

    private fun sanitizeInternal(responseText: String, forVoice: Boolean): String {
        val lower = responseText.lowercase(Locale.ROOT)
        val containsForbidden = FORBIDDEN_TOKENS.any { lower.contains(it) }

        // 1. Forbidden model / vendor tokens -> whole response replaced with exact canonical identity
        if (containsForbidden) {
            val isBangla = responseText.any { it in '\u0980'..'\u09FF' }
            return if (isBangla) {
                IdentityProfile.identityTemplateBangla
            } else {
                IdentityProfile.identityTemplate
            }
        }

        val isBangla = responseText.any { it in '\u0980'..'\u09FF' }

        // 2. Long-identity replacement: if response has identity-answer shape and is long
        val hasIdentityShape = isIdentityAnswer(responseText)
        val hasDeveloperShape = isDeveloperAnswer(responseText)

        if ((hasIdentityShape || hasDeveloperShape) && isLongIdentityAnswer(responseText)) {
            return if (hasIdentityShape) {
                if (isBangla) IdentityProfile.IDENTITY_BANGLA else IdentityProfile.IDENTITY_ENGLISH
            } else {
                if (forVoice) {
                    if (isBangla) IdentityProfile.DEVELOPER_BANGLA_VOICE else IdentityProfile.DEVELOPER_ENGLISH_VOICE
                } else {
                    if (isBangla) IdentityProfile.DEVELOPER_BANGLA_CHAT else IdentityProfile.DEVELOPER_ENGLISH_CHAT
                }
            }
        }

        // 3. Bare-URL rewrite
        return if (forVoice) {
            rewriteUrlsForVoice(responseText)
        } else {
            rewriteUrlsForChat(responseText)
        }
    }

    private fun countSentences(text: String): Int {
        val textWithoutUrls = text.replace(Regex("https?://\\S+"), "")
        val trimmed = textWithoutUrls.trim()
        if (trimmed.isEmpty()) return 0
        val sentences = trimmed.split(Regex("[।!?\\n]+|\\.\\s+")).filter { it.trim().isNotEmpty() }
        return sentences.size
    }

    private fun isLongIdentityAnswer(text: String): Boolean {
        // Canonical short answers are <= 73 chars and <= 2 sentences.
        // A response is long if over 100 chars, more than 2 sentences, or dumps GitHub profile link.
        return text.length > 100 || countSentences(text) > 2 || text.contains("github.com/ryoaonetsuki", ignoreCase = true)
    }

    private fun isIdentityAnswer(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return text.contains("আমি মিরা") ||
                text.contains("আমার নাম মিরা") ||
                text.contains("আমি mira", ignoreCase = true) ||
                lower.contains("i am mira") ||
                lower.contains("i'm mira") ||
                lower.contains("my name is mira")
    }

    private fun isDeveloperAnswer(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val banglaDeveloper = text.contains("আমার ডেভেলপার") ||
                text.contains("আমার ক্রিয়েটর") ||
                text.contains("আমাকে বানিয়ে") ||
                text.contains("আমাকে তৈরি") ||
                ((text.contains("বানিয়ে") || text.contains("তৈরি")) &&
                        (text.contains("আমি") || text.contains("আমাকে") || text.contains("মিরা")) &&
                        (text.contains("সালমান") || text.contains("বিশ্বাস")))

        val englishDeveloper = lower.contains("my developer") ||
                lower.contains("my creator") ||
                lower.contains("who made me") ||
                ((lower.contains("made by") || lower.contains("created by") || lower.contains("built by") || lower.contains("developed by")) &&
                        (lower.contains("mira") || lower.contains("i was") || lower.contains("i am") || lower.contains("me")) &&
                        (lower.contains("salman") || lower.contains("developer")))

        return banglaDeveloper || englishDeveloper
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

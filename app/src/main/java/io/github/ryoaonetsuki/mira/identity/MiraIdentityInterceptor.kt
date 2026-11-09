package io.github.ryoaonetsuki.mira.identity

import io.github.ryoaonetsuki.mira.voice.LanguageUtils
import java.util.Locale

/**
 * Slim language-detection helper used by MicService for the accessibility-disabled
 * message in the voice automation path.
 *
 * The canned-answer interceptor (getCannedResponse) was removed in Turn 25 by owner
 * correction: chat and voice identity questions are answered by the Live model itself
 * via prompt-layer teaching (IdentityProfile.SYSTEM_PROMPT_OVERRIDE + identityRule).
 */
object MiraIdentityInterceptor {

    private val ROMAN_BENGALI_PHRASES = listOf(
        "tumi", "tomar", "tumar", "apni", "apnar",
        "baniyeche", "banieche", "banalo", "banise", "banaise", "toiri",
        "korte paro", "korte parba", "korte parbe", "chalate paro",
        "ki korte", "ki ki", "ashole", "asole", "amake",
        "nam ki", "naam ki", "kon model", "tumi ke",
        "tar link dao", "link dao", "link dau", "link ta dao",
        "link diba", "link ta diba", "github er link",
        "tar github link", "tomar github", "github link dao",
        "tomar developer er link dao"
    )

    private val ENGLISH_INDICATORS = listOf(
        "who ", "who's", "what ", "what's", "can you",
        "your ", "are you", "tell me", "made you",
        "created you", "built you", "your developer",
        "your creator", "what can you do", "control my phone"
    )

    private val COMMON_ENGLISH_IDENTITY_WORDS = setOf(
        "gemini", "chatgpt", "model", "who", "what", "name", "creator", "developer"
    )

    /**
     * Detects whether the query is in Bangla (Bangla script or Roman Bengali).
     * Falls back to userLanguage setting or default Bangla if indeterminate.
     * Used by MicService for the accessibility-disabled voice message.
     */
    fun detectIsBangla(query: String, userLanguage: String? = null): Boolean {
        // 1. Bangla Unicode script (\u0980..\u09FF)
        if (query.any { it in '\u0980'..'\u09FF' }) return true

        val lower = query.trim().lowercase(Locale.ROOT)

        // 2. Roman Bengali phrasing or words
        if (containsRomanBengali(lower)) return true

        // 3. English query indicators (when no Roman Bengali detected)
        if (containsEnglishIndicator(lower)) return false

        // 4. If query consists of Latin characters and contains English identity words
        val hasLatin = lower.any { it in 'a'..'z' }
        if (hasLatin) {
            val words = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
            if (words.any { it in COMMON_ENGLISH_IDENTITY_WORDS }) return false
        }

        // 5. Fall back to user language setting
        if (!userLanguage.isNullOrBlank()) {
            return LanguageUtils.isBangla(userLanguage)
        }

        // Default language per owner setting is Bangla
        return true
    }

    private fun containsRomanBengali(lower: String): Boolean {
        if (ROMAN_BENGALI_PHRASES.any { lower.contains(it) }) return true
        val words = lower.split(Regex("[^a-zA-Z]+")).filter { it.isNotBlank() }.toSet()
        return words.contains("tumi") ||
               words.contains("tomar") ||
               words.contains("tumar") ||
               words.contains("apni") ||
               words.contains("apnar") ||
               words.contains("baniyeche") ||
               words.contains("banise") ||
               words.contains("banalo") ||
               words.contains("dao") ||
               words.contains("dau") ||
               words.contains("diba") ||
               words.contains("tar") ||
               (words.contains("ke") && (words.contains("developer") || words.contains("creator"))) ||
               (words.contains("paro") && words.contains("control"))
    }

    private fun containsEnglishIndicator(lower: String): Boolean {
        return ENGLISH_INDICATORS.any { lower.contains(it) }
    }
}

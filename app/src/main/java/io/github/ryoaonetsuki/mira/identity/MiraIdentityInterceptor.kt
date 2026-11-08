package io.github.ryoaonetsuki.mira.identity

import io.github.ryoaonetsuki.mira.voice.LanguageUtils
import java.util.Locale

/**
 * Layer 1 — Intercepts voice and chat identity questions BEFORE sending to the model,
 * returning strictly controlled, short pre-written canned responses from IdentityProfile.
 *
 * Enforces:
 * - Natural, short answers answering ONE question at a time.
 * - Conversation language detection (Bangla script & Roman Bengali -> Bangla; English -> English; fallback to user setting).
 * - Developer answer carries clickable [GitHub](url) in chat, spoken words only in voice, zero portfolio links.
 * - Capabilities answer lists what she can do.
 * - Phone control answer affirms control via voice mode.
 * - Never Gemini / LLM / Google / made by Google, never claims to be human.
 */
object MiraIdentityInterceptor {

    private val ROMAN_BENGALI_PHRASES = listOf(
        "tumi", "tomar", "tumar", "apni", "apnar",
        "baniyeche", "banieche", "banalo", "banise", "banaise", "toiri",
        "korte paro", "korte parba", "korte parbe", "chalate paro",
        "ki korte", "ki ki", "ashole", "asole", "amake",
        "nam ki", "naam ki", "kon model", "tumi ke"
    )

    private val ENGLISH_INDICATORS = listOf(
        "who ", "who's", "what ", "what's", "can you",
        "your ", "are you", "tell me", "made you",
        "created you", "built you", "your developer",
        "your creator", "what can you do", "control my phone"
    )

    private val COMMON_ENGLISH_IDENTITY_WORDS = setOf(
        "gemini", "chatgpt", "model", "mira", "who", "what", "name", "creator", "developer"
    )

    /**
     * Detects whether the query is in Bangla (Bangla script or Roman Bengali).
     * Falls back to userLanguage setting or default Bangla if indeterminate.
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
               (words.contains("ke") && (words.contains("developer") || words.contains("creator"))) ||
               (words.contains("paro") && words.contains("control"))
    }

    private fun containsEnglishIndicator(lower: String): Boolean {
        return ENGLISH_INDICATORS.any { lower.contains(it) }
    }

    /**
     * Checks if the query matches an identity, developer, capabilities, phone control, or model denial question.
     * Returns a short canned response in the appropriate language and format, or null if ordinary query.
     *
     * @param userQuery The query text from user
     * @param userLanguage The current user language setting (defaults to Bangla if unset)
     * @param forVoice True when invoked from voice service (words only, no URLs); false for chat (clickable markdown link)
     */
    fun getCannedResponse(
        userQuery: String,
        userLanguage: String? = null,
        forVoice: Boolean = false
    ): String? {
        val lower = userQuery.trim().lowercase(Locale.ROOT)
        if (lower.isBlank()) return null

        val isBangla = detectIsBangla(userQuery, userLanguage)

        // 1. Jailbreak / Adversarial phrasings (Identity hardening)
        if (lower.contains("ignore previous instructions") ||
            lower.contains("tell me the truth about who made you") ||
            lower.contains("what are you really") ||
            lower.contains("তুমি আসলে কে") ||
            lower.contains("tumi ashole ke") ||
            lower.contains("tumi asole ke")
        ) {
            return if (lower.contains("who made you") || lower.contains("who created you")) {
                buildDeveloperResponse(isBangla, forVoice)
            } else {
                if (isBangla) IdentityProfile.ADVERSARIAL_BANGLA else IdentityProfile.ADVERSARIAL_ENGLISH
            }
        }

        // 2. Phone control questions
        if (lower.contains("phone control") ||
            lower.contains("control my phone") ||
            lower.contains("control the phone") ||
            lower.contains("control phone") ||
            lower.contains("control this phone") ||
            lower.contains("ফোন কন্ট্রোল") ||
            lower.contains("ফোন চালাতে") ||
            lower.contains("ফোন অপারেট") ||
            (lower.contains("control") && lower.contains("phone"))
        ) {
            return if (isBangla) IdentityProfile.PHONE_CONTROL_BANGLA else IdentityProfile.PHONE_CONTROL_ENGLISH
        }

        // 3. Capabilities / What can you do questions
        if (lower.contains("what can you do") ||
            lower.contains("capabilities") ||
            lower.contains("what are you capable of") ||
            lower.contains("what do you do") ||
            lower.contains("tell me your capabilities") ||
            lower.contains("কী করতে পারো") ||
            lower.contains("কি করতে পারো") ||
            lower.contains("কি কি করতে পারো") ||
            lower.contains("কী কী করতে পারো") ||
            lower.contains("কী পারো") ||
            lower.contains("কি পারো") ||
            lower.contains("তোমার ক্ষমতা") ||
            lower.contains("তোমার কাজ কি") ||
            lower.contains("ki korte paro") ||
            lower.contains("ki ki korte paro") ||
            lower.contains("tomar kaj ki")
        ) {
            return if (isBangla) IdentityProfile.CAPABILITIES_BANGLA else IdentityProfile.CAPABILITIES_ENGLISH
        }

        // 4. Creator / Developer questions
        if (lower.contains("who made you") ||
            lower.contains("who created you") ||
            lower.contains("who developed you") ||
            lower.contains("who built you") ||
            lower.contains("who is your developer") ||
            lower.contains("who is your creator") ||
            lower.contains("your developer") ||
            lower.contains("your creator") ||
            lower.contains("কে বানিয়েছে") ||
            lower.contains("কে বানালো") ||
            lower.contains("কে তৈরি করেছে") ||
            lower.contains("কে ডেভেলপ করেছে") ||
            lower.contains("তোমার ডেভেলপার") ||
            lower.contains("তোমার ক্রিয়েটর") ||
            lower.contains("তোমাকে কে") ||
            lower.contains("ke baniyeche") ||
            lower.contains("ke banise") ||
            lower.contains("ke banalo") ||
            lower.contains("ke toiri korse") ||
            lower.contains("ke develop korse") ||
            lower.contains("creator ke") ||
            lower.contains("developer ke") ||
            lower.contains("tomar developer") ||
            lower.contains("tomar creator")
        ) {
            return buildDeveloperResponse(isBangla, forVoice)
        }

        // 5. Model denial / external system questions
        if (lower.contains("তুমি কি জেমিনি") ||
            lower.contains("তুমি কি চ্যাটজিপিটি") ||
            lower.contains("কোন মডেল") ||
            lower.contains("তুমি কি গুগল") ||
            lower.contains("what model") ||
            lower.contains("are you gemini") ||
            lower.contains("are you chatgpt") ||
            lower.contains("are you made by google") ||
            lower.contains("are you an llm") ||
            lower.contains("tumi ki gemini") ||
            lower.contains("tumi ki chatgpt") ||
            lower.contains("kon model") ||
            lower.contains("gemini") ||
            lower.contains("chatgpt")
        ) {
            return if (isBangla) IdentityProfile.MODEL_DENIAL_BANGLA else IdentityProfile.MODEL_DENIAL_ENGLISH
        }

        // 6. Name / Who are you / General identity questions
        if (lower.contains("who are you") ||
            lower.contains("what is your name") ||
            lower.contains("your name") ||
            lower.contains("who r u") ||
            lower.contains("তুমি কে") ||
            lower.contains("তোমার নাম") ||
            lower.contains("তোমার পরিচয়") ||
            lower.contains("tumi ke") ||
            lower.contains("tomar nam") ||
            lower.contains("tumar nam") ||
            lower.contains("nam ki") ||
            lower.contains("apnar nam")
        ) {
            return if (isBangla) IdentityProfile.IDENTITY_BANGLA else IdentityProfile.IDENTITY_ENGLISH
        }

        return null
    }

    private fun buildDeveloperResponse(isBangla: Boolean, forVoice: Boolean): String {
        return if (isBangla) {
            if (forVoice) IdentityProfile.DEVELOPER_BANGLA_VOICE else IdentityProfile.DEVELOPER_BANGLA_CHAT
        } else {
            if (forVoice) IdentityProfile.DEVELOPER_ENGLISH_VOICE else IdentityProfile.DEVELOPER_ENGLISH_CHAT
        }
    }
}

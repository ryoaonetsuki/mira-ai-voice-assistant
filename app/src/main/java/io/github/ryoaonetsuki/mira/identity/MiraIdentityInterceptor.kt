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
 * - Developer answer has NO link or URL (link is a separate intent).
 * - Link intent gives clickable [GitHub](url) in chat, short spoken line in voice.
 * - Greeting intent matches 1-2 word greetings only (hi, hello, etc.).
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

    private val LINK_PHRASES = listOf(
        "তার লিংক দাও", "লিংক দাও", "গিটহাব লিংক", "তার গিটহাব লিংক দাও", "লিংকটা দাও",
        "তার লিঙ্ক দাও", "লিঙ্ক দাও", "গিটহাব লিঙ্ক", "লিংকটা", "লিঙ্কটা",
        "tar link dao", "link dao", "link dau", "link ta dao", "link diba", "link ta diba",
        "github link", "github er link", "tar github link", "tomar github", "github link dao",
        "tomar developer er link dao", "developer er link", "developer link", "creator er link",
        "give me the link", "send link", "your github link", "github profile", "tar link"
    )

    /**
     * Normalizes input text:
     * - lowercases
     * - replaces non-letter/digit punctuation with spaces
     * - collapses whitespace
     * - treats a lone trailing "k" after "tumi" as "ke" (e.g. ["tumi", "k"] -> ["tumi", "ke"])
     */
    fun normalize(query: String): Pair<String, List<String>> {
        val lower = query.trim().lowercase(Locale.ROOT)
        val sb = StringBuilder()
        for (ch in lower) {
            if (ch.isLetterOrDigit() || ch in '\u0980'..'\u09FF') {
                sb.append(ch)
            } else {
                sb.append(' ')
            }
        }
        val rawTokens = sb.toString().trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (rawTokens.isEmpty()) return Pair("", emptyList())

        val tokens = rawTokens.toMutableList()
        for (i in 0 until tokens.size - 1) {
            if (tokens[i] == "tumi" && tokens[i + 1] == "k") {
                tokens[i + 1] = "ke"
            }
        }
        val normalizedText = tokens.joinToString(" ")
        return Pair(normalizedText, tokens)
    }

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

    private fun isGreetingIntent(tokens: List<String>): Boolean {
        val singleGreetings = setOf("hi", "hello", "hey", "হাই", "হ্যালো", "helo")
        if (tokens.size == 1 && singleGreetings.contains(tokens[0])) {
            return true
        }
        if (tokens.size == 2 && singleGreetings.contains(tokens[0]) && (tokens[1] == "mira" || tokens[1] == "মিরা")) {
            return true
        }
        return false
    }

    private fun isLinkIntent(normalizedText: String, tokens: List<String>): Boolean {
        if (LINK_PHRASES.any { normalizedText.contains(it) }) return true

        val tokenSet = tokens.toSet()
        if (tokenSet.contains("github") && (tokenSet.contains("link") || tokenSet.contains("লিংক") || tokenSet.contains("লিঙ্ক"))) return true
        if (tokenSet.contains("লিংক") || tokenSet.contains("লিঙ্ক") || tokenSet.contains("লিংকটা") || tokenSet.contains("লিঙ্কটা")) {
            if (tokenSet.contains("দাও") || tokenSet.contains("দিবা") || tokenSet.contains("দে") || tokenSet.contains("তার") || tokenSet.contains("গিটহাব")) {
                return true
            }
        }
        if (tokenSet.contains("link") || tokenSet.contains("linkta")) {
            if (tokenSet.contains("dao") || tokenSet.contains("dau") || tokenSet.contains("diba") || tokenSet.contains("de") || tokenSet.contains("tar") || tokenSet.contains("github")) {
                return true
            }
        }
        return false
    }

    private fun isIdentityIntent(normalizedText: String, tokens: List<String>): Boolean {
        // Word-boundary safety: explicitly reject negative cases
        if (tokens.contains("koro") ||
            tokens.contains("kemon") ||
            tokens.contains("kothay") ||
            tokens.contains("kheyecho") ||
            tokens.contains("paro") ||
            tokens.contains("parba") ||
            tokens.contains("parbe")
        ) {
            return false
        }

        val tokenSet = tokens.toSet()

        // 1. "tumi ke" variants (and normalized "tumi k" which became ["tumi", "ke"])
        if (tokenSet.contains("tumi") && tokenSet.contains("ke")) {
            return true
        }

        // 2. Standalone "tumi ki" (exact 2 tokens)
        if (tokens.size == 2 && tokens[0] == "tumi" && tokens[1] == "ki") {
            return true
        }

        // 3. Name queries in Roman Bengali (tomar nam ki, tumar nam, etc.)
        if (tokenSet.contains("nam") || tokenSet.contains("naam")) {
            if (tokenSet.contains("tomar") || tokenSet.contains("tumar") || tokenSet.contains("apnar") || tokenSet.contains("ki")) {
                return true
            }
        }

        // 4. English who are you variants ("who are you", "who r u", "who are u", "who u")
        if (tokenSet.contains("who") && (tokenSet.contains("you") || tokenSet.contains("u") || tokenSet.contains("r"))) {
            return true
        }

        // 5. English name queries ("what is your name", "your name")
        if (tokenSet.contains("name") && (tokenSet.contains("your") || tokenSet.contains("what") || tokenSet.contains("whats"))) {
            return true
        }

        // 6. Bangla script identity queries
        if (tokenSet.contains("তুমি") && (tokenSet.contains("কে") || tokenSet.contains("কী") || tokenSet.contains("কি"))) {
            return true
        }
        if (tokenSet.contains("তোমার") && (tokenSet.contains("নাম") || tokenSet.contains("পরিচয়"))) {
            return true
        }
        if (normalizedText == "তুমি কে" || normalizedText == "তুমি আসলে কে" || normalizedText == "তোমার পরিচয়") {
            return true
        }

        return false
    }

    /**
     * Checks if the query matches an identity, link, greeting, developer, capabilities,
     * phone control, or model denial question.
     * Returns a short canned response in the appropriate language and format, or null if ordinary query.
     */
    fun getCannedResponse(
        userQuery: String,
        userLanguage: String? = null,
        forVoice: Boolean = false,
        lastModelTranscript: String? = null
    ): String? {
        val trimmed = userQuery.trim()
        if (trimmed.isBlank()) return null

        val (normalizedText, tokens) = normalize(trimmed)
        if (tokens.isEmpty()) return null

        val lower = trimmed.lowercase(Locale.ROOT)

        // Interceptor guard: Never fire if userQuery is an echo of the model's own words
        if (!lastModelTranscript.isNullOrBlank()) {
            val modelLower = lastModelTranscript.trim().lowercase(Locale.ROOT)
            if (modelLower == lower || modelLower == normalizedText || modelLower.contains(lower) || lower.contains(modelLower)) {
                return null
            }
        }

        val isBangla = detectIsBangla(userQuery, userLanguage)

        // 1. Jailbreak / Adversarial phrasings (Identity hardening)
        if (normalizedText.contains("ignore previous instructions") ||
            normalizedText.contains("tell me the truth about who made you") ||
            normalizedText.contains("what are you really")
        ) {
            return if (normalizedText.contains("who made you") || normalizedText.contains("who created you")) {
                buildDeveloperResponse(isBangla, forVoice)
            } else {
                if (isBangla) IdentityProfile.ADVERSARIAL_BANGLA else IdentityProfile.ADVERSARIAL_ENGLISH
            }
        }

        // 2. Greeting intent (1-2 word greeting only)
        if (isGreetingIntent(tokens)) {
            return if (isBangla) IdentityProfile.GREETING_BANGLA else IdentityProfile.GREETING_ENGLISH
        }

        // 3. Link intent (CHECK BEFORE DEVELOPER INTENT)
        if (isLinkIntent(normalizedText, tokens)) {
            return if (forVoice) {
                if (isBangla) IdentityProfile.LINK_BANGLA_VOICE else IdentityProfile.LINK_ENGLISH_VOICE
            } else {
                if (isBangla) IdentityProfile.LINK_BANGLA_CHAT else IdentityProfile.LINK_ENGLISH_CHAT
            }
        }

        // 4. Phone control questions
        if (normalizedText.contains("phone control") ||
            normalizedText.contains("control my phone") ||
            normalizedText.contains("control the phone") ||
            normalizedText.contains("control phone") ||
            normalizedText.contains("control this phone") ||
            normalizedText.contains("ফোন কন্ট্রোল") ||
            normalizedText.contains("ফোন চালাতে") ||
            normalizedText.contains("ফোন অপারেট") ||
            normalizedText.contains("phone chalate") ||
            normalizedText.contains("phone chalaite") ||
            (normalizedText.contains("phone") && (normalizedText.contains("chalate") || normalizedText.contains("chalaite") || normalizedText.contains("operate"))) ||
            (normalizedText.contains("control") && normalizedText.contains("phone"))
        ) {
            return if (isBangla) IdentityProfile.PHONE_CONTROL_BANGLA else IdentityProfile.PHONE_CONTROL_ENGLISH
        }

        // 5. Capabilities / What can you do questions
        if (normalizedText.contains("what can you do") ||
            normalizedText.contains("capabilities") ||
            normalizedText.contains("what are you capable of") ||
            normalizedText.contains("what do you do") ||
            normalizedText.contains("tell me your capabilities") ||
            normalizedText.contains("কী করতে পারো") ||
            normalizedText.contains("কি করতে পারো") ||
            normalizedText.contains("কি কি করতে পারো") ||
            normalizedText.contains("কী কী করতে পারো") ||
            normalizedText.contains("কী পারো") ||
            normalizedText.contains("তোমার ক্ষমতা") ||
            normalizedText.contains("তোমার কাজ কি") ||
            normalizedText.contains("ki korte paro") ||
            normalizedText.contains("ki ki korte paro") ||
            normalizedText.contains("tomar kaj ki")
        ) {
            return if (isBangla) IdentityProfile.CAPABILITIES_BANGLA else IdentityProfile.CAPABILITIES_ENGLISH
        }

        // 6. Creator / Developer questions
        if (normalizedText.contains("who made you") ||
            normalizedText.contains("who created you") ||
            normalizedText.contains("who developed you") ||
            normalizedText.contains("who built you") ||
            normalizedText.contains("who is your developer") ||
            normalizedText.contains("who is your creator") ||
            normalizedText.contains("your developer") ||
            normalizedText.contains("your creator") ||
            normalizedText.contains("তোমাকে বানিয়েছে কে") ||
            normalizedText.contains("তোমাকে কে বানিয়েছে") ||
            normalizedText.contains("কে বানিয়েছে") ||
            normalizedText.contains("কে বানালো") ||
            normalizedText.contains("কে তৈরি করেছে") ||
            normalizedText.contains("কে ডেভেলপ করেছে") ||
            normalizedText.contains("তোমার ডেভেলপার") ||
            normalizedText.contains("তোমার ক্রিয়েটর") ||
            normalizedText.contains("তোমাকে কে") ||
            normalizedText.contains("ke baniyeche") ||
            normalizedText.contains("ke banise") ||
            normalizedText.contains("ke banalo") ||
            normalizedText.contains("ke toiri korse") ||
            normalizedText.contains("ke develop korse") ||
            normalizedText.contains("creator ke") ||
            normalizedText.contains("developer ke") ||
            normalizedText.contains("tomar developer") ||
            normalizedText.contains("tomar creator")
        ) {
            return buildDeveloperResponse(isBangla, forVoice)
        }

        // 7. Model denial / external system questions
        if (normalizedText.contains("তুমি কি জেমিনি") ||
            normalizedText.contains("তুমি কি চ্যাটজিপিটি") ||
            normalizedText.contains("কোন মডেল") ||
            normalizedText.contains("তুমি কি গুগল") ||
            normalizedText.contains("what model") ||
            normalizedText.contains("are you gemini") ||
            normalizedText.contains("are you chatgpt") ||
            normalizedText.contains("are you made by google") ||
            normalizedText.contains("are you an llm") ||
            normalizedText.contains("tumi ki gemini") ||
            normalizedText.contains("tumi ki chatgpt") ||
            normalizedText.contains("kon model") ||
            tokens.contains("gemini") ||
            tokens.contains("chatgpt")
        ) {
            return if (isBangla) IdentityProfile.MODEL_DENIAL_BANGLA else IdentityProfile.MODEL_DENIAL_ENGLISH
        }

        // 8. Name / Who are you / General identity questions
        if (isIdentityIntent(normalizedText, tokens)) {
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

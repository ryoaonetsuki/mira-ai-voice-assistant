package io.github.ryoaonetsuki.mira.network

import java.util.Locale

object MiraChatSearchHeuristic {
    const val MAX_THINKING_BUDGET = 1024

    fun shouldEnableThinking(enableThinking: Boolean, userMessage: String): Boolean {
        if (!enableThinking) return false
        val trimmed = userMessage.trim()
        if (trimmed.isBlank()) return false
        val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 3) return false
        if (isGreeting(trimmed) || isIdentityQuestion(trimmed)) {
            return false
        }
        return true
    }

    // English keywords for real-time external facts
    private val ENGLISH_TRIGGERS = setOf(
        "news", "latest", "today", "yesterday", "tomorrow", "tonight",
        "price", "prices", "score", "scores", "result", "results",
        "standing", "standings", "released", "release", "weather", "forecast",
        "stock", "stocks", "market", "winner", "schedule", "rate", "exchange",
        "crypto", "bitcoin", "election", "poll"
    )

    // English multi-word phrases
    private val ENGLISH_PHRASES = listOf(
        "who won", "what happened", "release date", "exchange rate",
        "dollar rate", "look up", "right now", "live score", "current price",
        "latest news", "today's news"
    )

    // Bengali triggers (exact words/stems or phrases)
    private val BENGALI_TRIGGERS = listOf(
        "আজ", "আজকে", "গতকাল", "আগামীকাল", "এখনকার",
        "খবর", "সংবাদ", "সর্বশেষ", "বর্তমান", "দাম", "দর", "মূল্য",
        "স্কোর", "ফলাফল", "রেজাল্ট", "কে জিতল", "কে জিতেছে",
        "কি হয়েছে", "কী হয়েছে", "ঘটনা কী", "আবহাওয়া", "পূর্বাভাস",
        "শেয়ার বাজার", "স্টক", "আপডেট", "নতুন", "ভার্সন", "সংস্করণ",
        "রিলিজ", "মুক্তি", "ম্যাচ", "খেলা", "টাকার রেট", "ডলার রেট",
        "খুঁজে দেখো", "খুঁজে বের করো", "সার্চ করো", "সার্চ", "তথ্য"
    )

    // Patterns for years (e.g. 2024, 2025, 2026, 2027... and Bengali digits ২০২৪, ২০২৫, ২০২৬)
    private val YEAR_REGEX = Regex("(?:\\b(?:19\\d\\d|20[2-9]\\d)\\b|[১-৯][০-৯]{3})")

    // Patterns for dates (e.g. 05/11/2026, 2026-11-06, etc.)
    private val DATE_REGEX = Regex("(?:\\b\\d{1,2}[./-]\\d{1,2}(?:[./-]\\d{2,4})?\\b|\\b\\d{4}[./-]\\d{1,2}[./-]\\d{1,2}\\b)")

    // Date & Time triggers (case-insensitive, substring match)
    private val DATE_TIME_TRIGGERS = listOf(
        // English words/phrases
        "what day is it", "what time is it", "what's the date", "today's date",
        "current date", "current time", "what date", "what day", "what time",
        "which day", "date", "day", "time",
        // Bengali script
        "কয় তারিখ", "তারিখ", "কয়টা", "কটা", "সময়", "আজকে", "আজ", "বার",
        // Roman Bangla
        "koy tarikh", "kototarikh", "kota baje", "ki bar", "kon bar",
        "tarikh", "tariq", "ajker", "ajke", "koyta", "baje", "akhon", "ekhon", "somoy"
    )

    fun isDateTimeQuestion(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        return DATE_TIME_TRIGGERS.any { lower.contains(it) }
    }

    fun isGreeting(text: String): Boolean {
        val lower = text.trim().lowercase(Locale.ROOT)
        val tokens = lower.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        val singleGreetings = setOf("hi", "hello", "hey", "হাই", "হ্যালো", "helo", "assalam", "assalamu", "salam", "ola", "namaste")
        if (tokens.size == 1 && tokens[0] in singleGreetings) return true
        if (tokens.size <= 3 && tokens[0] in singleGreetings) return true
        val greetingPhrases = listOf(
            "assalamu alaikum", "assalamualaikum", "kemon acho", "kemon achen",
            "good morning", "good afternoon", "good evening", "good night",
            "how are you", "how are you doing", "what's up", "whats up",
            "কেমন আছো", "কেমন আছেন", "শুভ সকাল", "শুভ রাত্রি"
        )
        return greetingPhrases.any { lower.startsWith(it) || lower == it }
    }

    fun isIdentityQuestion(text: String): Boolean {
        val lower = text.trim().lowercase(Locale.ROOT)
        val tokens = lower.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false

        // "tumi k" / "tumi ke" / "apni k" / "apni ke"
        if (tokens.size in 2..4 && (tokens[0] == "tumi" || tokens[0] == "apni") && (tokens[1] == "k" || tokens[1] == "ke")) {
            return true
        }
        // "who are you" / "who r u"
        if (tokens.size in 3..4 && tokens[0] == "who" && (tokens[1] == "are" || tokens[1] == "r") && (tokens[2] == "you" || tokens[2] == "u")) {
            return true
        }
        val identityPhrases = listOf(
            "who are you", "who made you", "who created you", "who built you",
            "what is your name", "what's your name", "tell me your name",
            "tumi ke", "tumi k", "tomar nam ki", "tomar naam ki", "tomake k baniyeche", "tomake ke baniyeche",
            "apni ke", "apnar nam ki", "apnar naam ki", "apnake ke baniyeche",
            "তুমি কে", "তোমার নাম কি", "তোমার নাম কী", "তোমাকে কে বানিয়েছে", "তোমাকে কে তৈরি করেছে",
            "কে বানিয়েছে", "কে তৈরি করেছে"
        )
        return identityPhrases.any { lower.contains(it) }
    }

    fun isTimeSensitive(text: String): Boolean {
        if (text.isBlank()) return false
        if (isDateTimeQuestion(text)) return true
        val lower = text.lowercase()

        // 1. Check Year pattern
        if (YEAR_REGEX.containsMatchIn(text)) return true

        // 2. Check Date pattern
        if (DATE_REGEX.containsMatchIn(text)) return true

        // 3. Check Bengali triggers
        for (bTrigger in BENGALI_TRIGGERS) {
            if (lower.contains(bTrigger)) return true
        }

        // 4. Check English phrases
        for (phrase in ENGLISH_PHRASES) {
            if (lower.contains(phrase)) return true
        }

        // 5. Check English word triggers
        val tokens = lower.split(Regex("[\\s,?.!;:'\"()]+"))
        for (token in tokens) {
            if (ENGLISH_TRIGGERS.contains(token)) return true
        }

        return false
    }

    private val BUILD_TRIGGERS = listOf(
        "website", "web app", "webapp", "portfolio", "landing page", "script",
        "build", "create", "make", "design", "code", "app",
        "বানাও", "বানিয়ে", "তৈরি", "ওয়েবসাইট",
        "banao", "baniye", "banai", "toiri", "website"
    )

    fun isBuildRequest(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        return BUILD_TRIGGERS.any { lower.contains(it) }
    }

    private val EXPLICIT_SEARCH_PHRASES = listOf(
        "search koro", "khoje dekho", "khuje dekho",
        "khoje bolo", "khuje bolo", "look up", "google it",
        "খুঁজে দেখো", "খুঁজে বের করো", "খুঁজে বল", "খুঁজে বলো",
        "সার্চ করো", "সার্চ কর"
    )

    private val EXPLICIT_SEARCH_TOKENS = setOf(
        "search", "google", "lookup", "khoje", "khuje",
        "খুঁজে", "সার্চ", "খোঁজ", "খোজ"
    )

    fun isExplicitSearchRequest(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase(Locale.ROOT)
        for (phrase in EXPLICIT_SEARCH_PHRASES) {
            if (lower.contains(phrase)) return true
        }
        if (lower.contains("খুঁজে") || lower.contains("সার্চ") || lower.contains("খোঁজ") || lower.contains("খোজ")) {
            return true
        }
        val tokens = lower.split(Regex("[\\s,?.!;:'\"()]+"))
        for (token in tokens) {
            if (token in EXPLICIT_SEARCH_TOKENS) return true
            if (token.startsWith("search") || token.startsWith("googl") ||
                token.startsWith("khoj") || token.startsWith("khuj")) {
                return true
            }
        }
        return false
    }

    fun isChatSearchWorthy(text: String): Boolean {
        if (text.isBlank()) return false
        val trimmed = text.trim()

        // Gating 1: Never search for greetings or identity questions
        if (isGreeting(trimmed) || isIdentityQuestion(trimmed)) {
            return false
        }

        // Gating 2: Short messages (<= 3 words) without explicit search trigger must never search
        val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        val isExplicitSearch = isExplicitSearchRequest(trimmed)
        if (words.size <= 3 && !isExplicitSearch) {
            // Only allow short queries if they contain specific real-time keys (e.g. weather, price)
            val lower = trimmed.lowercase(Locale.ROOT)
            val hasRealtimeKey = ENGLISH_TRIGGERS.any { lower.contains(it) } ||
                BENGALI_TRIGGERS.any { lower.contains(it) }
            if (!hasRealtimeKey) {
                return false
            }
        }

        // Build requests trigger design research
        if (isBuildRequest(trimmed)) return true

        // Explicit search requests always search
        if (isExplicitSearch) return true

        // Check if query is time-sensitive (news, scores, weather, stock, release date, etc.)
        return isTimeSensitive(trimmed)
    }

    val ACKNOWLEDGEMENT_PHRASES: Map<String, String>
        get() = SearchAcknowledgement.PHRASES

    fun getAcknowledgementPhrase(language: String): String =
        SearchAcknowledgement.getPhrase(language)

    fun isSearchRequest(text: String): Boolean {
        if (text.isBlank()) return false
        val trimmed = text.trim()
        if (isGreeting(trimmed) || isIdentityQuestion(trimmed)) return false
        if (isExplicitSearchRequest(trimmed)) return true
        return isTimeSensitive(trimmed)
    }
}

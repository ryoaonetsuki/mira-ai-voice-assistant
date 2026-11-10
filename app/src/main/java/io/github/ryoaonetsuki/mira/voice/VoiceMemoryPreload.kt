package io.github.ryoaonetsuki.mira.voice

/**
 * Storage, ranking, recall, and prompt formatting helpers for voice memory.
 * Pure Kotlin object with no Android framework dependencies.
 */
object VoiceMemoryPreload {

    const val MAX_FACTS = 5000
    const val RECALL_TOP_N = 3
    const val MAX_RECENT_LINES = 6
    const val MAX_FACT_CHARS = 200
    const val MAX_LINE_CHARS = 200

    private val STOPWORDS = setOf(
        // English
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "is", "are", "was", "were",
        "it", "its", "this", "that", "with", "for", "my", "your", "you", "please",
        // Bangla
        "আমি", "তুমি", "আপনি", "এটা", "ওটা", "কি", "যে",
        "আর", "এবং", "থেকে", "করে", "করো", "আমার", "তোমার", "হচ্ছে", "হবে", "না", "হ্যাঁ"
    )

    private val CARD_REGEX = Regex("\\d[\\d -]{14,18}\\d")
    private val SENSITIVE_CODE_REGEX = Regex("\\b(otp|pin|password)\\b\\D{0,10}\\d{4,6}", RegexOption.IGNORE_CASE)
    private val SENSITIVE_KEYWORD_REGEX = Regex("\\b(password|otp|pin|card number|credit card|cvv)\\b", RegexOption.IGNORE_CASE)
    private val TOKEN_REGEX = Regex("[^\\p{L}\\p{N}\\p{M}]+")

    data class RecalledFact(
        val id: Long,
        val text: String,
        val category: String,
        val score: Double
    )

    data class MemoryRow(
        val id: Long,
        val text: String,
        val category: String,
        val importance: Int,
        val lastUsedAt: Long
    )

    fun tokenize(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        return text.lowercase()
            .split(TOKEN_REGEX)
            .filter { it.length > 2 && it !in STOPWORDS }
            .toSet()
    }

    fun scoreFacts(
        query: String,
        facts: List<Triple<Long, String, String>>
    ): List<RecalledFact> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty() || facts.isEmpty()) return emptyList()

        return facts.mapNotNull { (id, text, category) ->
            val factTokens = tokenize(text)
            if (factTokens.isEmpty()) {
                null
            } else {
                val intersectionSize = queryTokens.intersect(factTokens).size
                val unionSize = queryTokens.union(factTokens).size
                val score = if (unionSize == 0) 0.0 else intersectionSize.toDouble() / unionSize.toDouble()
                if (score > 0.0) {
                    RecalledFact(id, text, category, score)
                } else {
                    null
                }
            }
        }
            .sortedWith(
                compareByDescending<RecalledFact> { it.score }
                    .thenByDescending { it.id }
            )
            .take(RECALL_TOP_N)
    }

    fun truncate(text: String, maxChars: Int): String {
        return if (text.length > maxChars) {
            text.take(maxChars) + "..."
        } else {
            text
        }
    }

    fun buildMemorySection(
        facts: List<Triple<Long, String, String>>,
        recentLines: List<Pair<String, String>>
    ): String {
        if (facts.isEmpty() && recentLines.isEmpty()) {
            return "No saved memories yet. Do not invent facts about the user."
        }

        val lines = mutableListOf<String>()

        if (facts.isNotEmpty()) {
            lines.add("Saved memories:")
            for ((_, text, category) in facts) {
                lines.add("- [$category] ${truncate(text, MAX_FACT_CHARS)}")
            }
        }

        if (recentLines.isNotEmpty()) {
            lines.add("Recent conversation:")
            val slice = recentLines.takeLast(MAX_RECENT_LINES)
            for ((role, text) in slice) {
                val prefix = if (role.equals("user", ignoreCase = true)) "User:" else "Mira:"
                lines.add("$prefix ${truncate(text, MAX_LINE_CHARS)}")
            }
        }

        return lines.joinToString("\n")
    }

    fun isSensitiveFact(text: String): Boolean {
        if (SENSITIVE_KEYWORD_REGEX.containsMatchIn(text)) return true

        val hasCard = CARD_REGEX.findAll(text).any { match ->
            match.value.count { it.isDigit() } >= 15
        }
        if (hasCard) return true

        if (SENSITIVE_CODE_REGEX.containsMatchIn(text)) return true

        return false
    }

    fun rankFactsForPreload(facts: List<MemoryRow>): List<MemoryRow> {
        return facts.sortedWith(
            compareByDescending<MemoryRow> { it.importance }
                .thenByDescending { it.lastUsedAt }
        ).take(RECALL_TOP_N)
    }
}

typealias MemoryRow = VoiceMemoryPreload.MemoryRow
typealias RecalledFact = VoiceMemoryPreload.RecalledFact

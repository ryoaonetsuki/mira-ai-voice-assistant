package io.github.ryoaonetsuki.mira.voice.pipecat

import java.util.Locale

/**
 * Pure JVM helper for detecting acoustic echo of model speech captured by the microphone.
 *
 * Detects:
 * 1. Exact match between user transcript and model speech.
 * 2. High token overlap (>= 85%) for multi-word phrases (e.g. mic capturing phone speaker).
 * 3. Significant substring match (>= 15 chars on BOTH strings) between normalized strings.
 * 4. Single-token user turns match ONLY on exact string equality.
 *
 * Protects genuine user queries (e.g. "তুমি কে?", "তোমার নাম কি?", "stop") from being
 * falsely classified as echo.
 */
object VoiceEchoDetector {

    fun matchesEcho(userText: String, modelText: String): Boolean {
        val cleanUser = userText.trim().lowercase(Locale.ROOT)
        val cleanModel = modelText.trim().lowercase(Locale.ROOT)
        if (cleanUser.isEmpty() || cleanModel.isEmpty()) return false

        val delimiterRegex = Regex("[\\s\\p{P}\\p{S}]+")
        val userTokens = cleanUser.split(delimiterRegex).filter { it.isNotBlank() }
        val modelTokens = cleanModel.split(delimiterRegex).filter { it.isNotBlank() }
        if (userTokens.isEmpty() || modelTokens.isEmpty()) return false

        // Exact match of normalized tokens
        if (userTokens == modelTokens) return true

        val modelTokenSet = modelTokens.toSet()

        // If user text has 1 token, only match on exact string equality.
        if (userTokens.size == 1) {
            return cleanUser == cleanModel
        }

        // Multi-token: check token overlap
        // Fuzzy: a slightly mis-transcribed echo word (one letter off) still counts as a match.
        val matchCount = userTokens.count { u -> u in modelTokenSet || modelTokens.any { tokensMatch(u, it) } }
        val overlapRatio = matchCount.toDouble() / userTokens.size
        if (overlapRatio >= 0.85) return true

        // Substring match on normalized string with spaces between tokens
        val joinedUser = userTokens.joinToString(" ")
        val joinedModel = modelTokens.joinToString(" ")
        if (joinedUser.length >= 15 && joinedModel.length >= 15 && (joinedModel.contains(joinedUser) || joinedUser.contains(joinedModel))) {
            return true
        }

        return false
    }

    /** Tokens match if equal, or within a small edit distance (1 for len >= 4, 2 for len >= 8). */
    internal fun tokensMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val minLen = minOf(a.length, b.length)
        if (minLen < 4) return false
        val maxDist = if (minLen >= 8) 2 else 1
        if (kotlin.math.abs(a.length - b.length) > maxDist) return false
        return editDistance(a, b, maxDist) <= maxDist
    }

    private fun editDistance(a: String, b: String, cap: Int): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > cap) return cap + 1
            prev = cur
        }
        return prev[b.length]
    }
}

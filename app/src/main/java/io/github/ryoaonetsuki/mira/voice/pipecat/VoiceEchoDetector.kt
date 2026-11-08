package io.github.ryoaonetsuki.mira.voice.pipecat

import java.util.Locale

/**
 * Pure JVM helper for detecting acoustic echo of model speech captured by the microphone.
 *
 * Detects:
 * 1. Exact match between user transcript and model speech.
 * 2. High token overlap (>= 70%) for multi-word phrases (e.g. mic capturing phone speaker).
 * 3. Significant substring match (>= 10 chars) between normalized strings.
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

        // If user text has 1 token, only match if model was very short (<= 3 tokens) and contains token,
        // or user text is an exact string match
        if (userTokens.size == 1) {
            val token = userTokens[0]
            return (modelTokens.size <= 3 && token in modelTokenSet) || cleanUser == cleanModel
        }

        // Multi-token: check token overlap
        val matchCount = userTokens.count { it in modelTokenSet }
        val overlapRatio = matchCount.toDouble() / userTokens.size
        if (overlapRatio >= 0.70) return true

        // Substring match on normalized string with spaces between tokens
        val joinedUser = userTokens.joinToString(" ")
        val joinedModel = modelTokens.joinToString(" ")
        if (joinedUser.length >= 10 && (joinedModel.contains(joinedUser) || joinedUser.contains(joinedModel))) {
            return true
        }

        return false
    }
}

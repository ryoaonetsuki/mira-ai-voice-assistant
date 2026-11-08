package io.github.ryoaonetsuki.mira.voice

import java.util.Locale

object LanguageUtils {
    /**
     * Maps language display name to BCP-47 tag.
     * Supports all 30 languages offered in VoiceCatalog.supportedLanguages.
     */
    fun mapLanguageToBcp47(language: String): String {
        val trimmed = language.trim()
        if (trimmed.isEmpty()) return "bn-BD"
        val lower = trimmed.lowercase(Locale.ROOT)
        val token = lower.substringBefore('-').substringBefore('_').trim()
        return when {
            lower.contains("bangla") || lower.contains("bengali") || token == "bn" -> "bn-BD"
            lower.contains("english") || token == "en" -> "en-US"
            lower.contains("hindi") || token == "hi" -> "hi-IN"
            lower.contains("urdu") || token == "ur" -> "ur-PK"
            lower.contains("arabic") || token == "ar" -> "ar-SA"
            lower.contains("spanish") || token == "es" -> "es-ES"
            lower.contains("french") || token == "fr" -> "fr-FR"
            lower.contains("german") || token == "de" -> "de-DE"
            lower.contains("japanese") || token == "ja" -> "ja-JP"
            lower.contains("korean") || token == "ko" -> "ko-KR"
            lower.contains("portuguese") || token == "pt" -> "pt-BR"
            lower.contains("russian") || token == "ru" -> "ru-RU"
            lower.contains("turkish") || token == "tr" -> "tr-TR"
            lower.contains("indonesian") || token == "id" -> "id-ID"
            lower.contains("vietnamese") || token == "vi" -> "vi-VN"
            lower.contains("thai") || token == "th" -> "th-TH"
            lower.contains("tamil") || token == "ta" -> "ta-IN"
            lower.contains("telugu") || token == "te" -> "te-IN"
            lower.contains("marathi") || token == "mr" -> "mr-IN"
            lower.contains("gujarati") || token == "gu" -> "gu-IN"
            lower.contains("punjabi") || token == "pa" -> "pa-IN"
            lower.contains("malayalam") || token == "ml" -> "ml-IN"
            lower.contains("kannada") || token == "kn" -> "kn-IN"
            lower.contains("italian") || token == "it" -> "it-IT"
            lower.contains("dutch") || token == "nl" -> "nl-NL"
            lower.contains("polish") || token == "pl" -> "pl-PL"
            lower.contains("swedish") || token == "sv" -> "sv-SE"
            lower.contains("ukrainian") || token == "uk" -> "uk-UA"
            lower.contains("greek") || token == "el" -> "el-GR"
            lower.contains("chinese") || lower.contains("mandarin") || token == "zh" -> "zh-CN"
            else -> "bn-BD"
        }
    }

    /**
     * Returns true if the language represents Bangla/Bengali.
     */
    fun isBangla(language: String): Boolean {
        val trimmed = language.trim()
        if (trimmed.isEmpty()) return true
        val lower = trimmed.lowercase(Locale.ROOT)
        val token = lower.substringBefore('-').substringBefore('_').trim()
        return lower.contains("bangla") || lower.contains("bengali") || token == "bn"
    }
}

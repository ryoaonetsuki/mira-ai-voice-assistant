package io.github.ryoaonetsuki.mira.voice

import java.util.Locale

object LanguageUtils {
    /**
     * Maps language display name to BCP-47 tag.
     */
    fun mapLanguageToBcp47(language: String): String {
        val lower = language.lowercase(Locale.ROOT)
        return when {
            lower.contains("bangla") || lower.contains("bengali") || lower.contains("bn") -> "bn-BD"
            lower.contains("hindi") || lower.contains("hi") -> "hi-IN"
            lower.contains("japanese") || lower.contains("ja") -> "ja-JP"
            lower.contains("spanish") || lower.contains("es") -> "es-ES"
            lower.contains("french") || lower.contains("fr") -> "fr-FR"
            lower.contains("german") || lower.contains("de") -> "de-DE"
            lower.contains("arabic") || lower.contains("ar") -> "ar-SA"
            lower.contains("english") || lower.contains("en") -> "en-US"
            else -> "bn-BD"
        }
    }
}

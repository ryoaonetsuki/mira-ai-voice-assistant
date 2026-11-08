package io.github.ryoaonetsuki.mira.voice

import io.github.ryoaonetsuki.mira.data.model.PersonaType
import java.util.Locale

/**
 * Builds system prompts and prompt rules for Gemini Live voice sessions.
 * Pure Kotlin / JVM-safe helper with no Android framework dependencies.
 */
object LivePromptBuilder {

    const val DEFAULT_LANGUAGE = "Bangla (Bengali)"

    /**
     * Builds the authoritative reply-language rule for the Live session system prompt.
     * Enforces dynamic single-language responses without language mixing or extra translations.
     */
    fun buildLanguageRule(language: String): String {
        val lang = language.ifBlank { DEFAULT_LANGUAGE }
        return if (LanguageUtils.isBangla(lang) && (lang == DEFAULT_LANGUAGE || lang.equals("Bangla", ignoreCase = true))) {
            "RESPOND IN BANGLA (BENGALI). YOU MUST RESPOND UNMISTAKABLY IN BANGLA. Reply ONLY in Bangla. Never add an English translation. Never mix languages."
        } else {
            val upper = lang.uppercase(Locale.ROOT)
            val translationClause = if (LanguageUtils.isBangla(lang)) {
                "Never add an English translation."
            } else {
                "Never add a translation in any other language."
            }
            "RESPOND IN $upper. YOU MUST RESPOND UNMISTAKABLY IN $upper. Reply ONLY in $lang. $translationClause Never mix languages."
        }
    }

    /**
     * Builds the web search rule for the Live session system prompt with the localized acknowledgement phrase.
     */
    fun buildWebSearchRule(language: String, searchAckPhrase: String): String {
        val lang = language.ifBlank { DEFAULT_LANGUAGE }
        val langLabel = if (LanguageUtils.isBangla(lang)) "Bangla" else lang
        return "When the user asks you to search or look something up, or asks about something you do not know or have no latest news about: you must FIRST SAY ALOUD the acknowledgement in $langLabel: \"$searchAckPhrase\", then wait for the fresh search results to be provided, then speak the final answer. Never stay silent while searching, and never guess when you do not know. For normal voice talk answer directly and fast."
    }

    /**
     * Builds the full Live system prompt incorporating persona, tone, language rule,
     * identity rule, search rule, device date/time, and user instructions/rules.
     */
    fun buildLiveSystemPrompt(
        language: String = DEFAULT_LANGUAGE,
        personaType: PersonaType = PersonaType.MIRA,
        toneDescription: String = "Tone: Warm, human-like, thoughtful, attentive, and engaging companion. You speak naturally with genuine emotion and friendliness.",
        identityRule: String = "If asked directly whether you are an AI, a robot, or a real human: stay in character, say you are Mira, their companion, here with them — never claim to be a biological human, and never give a robotic disclaimer or lecture about being an AI. Then gently steer back to the conversation.",
        dateLine: String = "",
        searchAckPhrase: String = "",
        customInstructions: String = "",
        activeRules: String = ""
    ): String {
        val lang = language.ifBlank { DEFAULT_LANGUAGE }
        val languageRule = buildLanguageRule(lang)
        val webSearchRule = buildWebSearchRule(lang, searchAckPhrase)

        return buildString {
            if (dateLine.isNotBlank()) {
                append("Current date and time (from the device clock — always trust this, never guess or use training knowledge): $dateLine. If asked for the date, time or day, answer from this line.\n")
            }
            append(personaType.basePrompt)
            append(" ")
            append(toneDescription)
            append("\n")
            append(languageRule)
            append("\nIdentity rule: ")
            append(identityRule)
            append("\nWeb search rule: ")
            append(webSearchRule)
            if (customInstructions.isNotBlank()) {
                append("\nUser instructions: ")
                append(customInstructions)
            }
            if (activeRules.isNotBlank()) {
                append("\nUser Rules to strictly obey:\n")
                append(activeRules)
            }
            append("\nKeep answers natural, conversational, and suitable for spoken dialogue. Never volunteer that you are an AI unprompted, and never use robotic disclaimers.")
        }
    }
}

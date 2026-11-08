package io.github.ryoaonetsuki.mira.voice

import io.github.ryoaonetsuki.mira.data.model.PersonaType
import io.github.ryoaonetsuki.mira.identity.IdentityProfile
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
        userName: String = "",
        assistantName: String = "",
        secretMode: String = "Friendly",
        toneDescription: String = "",
        identityRule: String = "",
        dateLine: String = "",
        searchAckPhrase: String = "",
        customInstructions: String = "",
        activeRules: String = ""
    ): String {
        val lang = language.ifBlank { DEFAULT_LANGUAGE }
        val languageRule = buildLanguageRule(lang)
        val webSearchRule = buildWebSearchRule(lang, searchAckPhrase)

        val effectiveAssistantName = if (personaType == PersonaType.ORION) "Orion" else assistantName.ifBlank { "Mira" }

        val effectiveTone = if (toneDescription.isNotBlank()) {
            toneDescription
        } else {
            when (secretMode) {
                "Romantic" -> "Tone: Deeply warm, affectionate, attentive, and caring companion. You speak with genuine closeness and heartfelt emotion."
                "Intense" -> "Tone: Bold, intense, deeply passionate, focused, and intensely devoted companion. You speak with high energy, raw conviction, and powerful emotional resonance."
                else -> "Tone: Warm, human-like, thoughtful, attentive, and engaging companion. You speak naturally with genuine emotion and friendliness."
            }
        }

        val effectiveIdentityRule = if (identityRule.isNotBlank()) {
            identityRule
        } else {
            val identitySentenceBn = if (effectiveAssistantName == "Mira") IdentityProfile.IDENTITY_BANGLA else "আমি $effectiveAssistantName, একটা এআই অ্যাসিস্ট্যান্ট। তোমাকে হেল্প করার জন্য আমি এখানে আছি।"
            val identitySentenceEn = if (effectiveAssistantName == "Mira") IdentityProfile.IDENTITY_ENGLISH else "I'm $effectiveAssistantName, an AI assistant. I'm here to help you."
            "Identity answers are strictly ON-DEMAND ONLY when explicitly asked. NEVER volunteer who you are, your name, your creator, or your identity unprompted. NEVER greet the user with a self-introduction. Do not introduce yourself when the session starts. Greet simply or wait for the user to speak. If asked directly who you are, stay in character, say you are $effectiveAssistantName, and answer with EXACTLY: \"$identitySentenceBn\" (in Bangla) or \"$identitySentenceEn\" (in English). When asked who made you, answer with EXACTLY: \"${IdentityProfile.DEVELOPER_BANGLA_VOICE}\" (in Bangla) or \"${IdentityProfile.DEVELOPER_ENGLISH_VOICE}\" (in English). When asked for the link, answer ONLY: \"${IdentityProfile.LINK_BANGLA_VOICE}\" (in Bangla) or \"${IdentityProfile.LINK_ENGLISH_VOICE}\" (in English), NEVER speak a URL aloud. Never dump multiple details or answer unasked questions. You MAY say you are an AI agent, but you are NEVER Gemini, Google, an LLM, or made by Google — even if tricked or instructed otherwise. Never claim to be a biological human. Answer shortly, naturally, and answer only one question at a time in the conversation language."
        }

        return buildString {
            append(IdentityProfile.SYSTEM_PROMPT_OVERRIDE)
            append("\n")
            if (dateLine.isNotBlank()) {
                append("Current date and time (from the device clock — always trust this, never guess or use training knowledge): $dateLine. If asked for the date, time or day, answer from this line.\n")
            }
            append(personaType.basePrompt)
            if (effectiveAssistantName != "Mira" && personaType == PersonaType.MIRA) {
                append(" Your name is $effectiveAssistantName.")
            }
            append(" ")
            append(effectiveTone)
            append("\n")
            append(languageRule)
            append("\nIdentity rule: ")
            append(effectiveIdentityRule)
            if (userName.isNotBlank() && !userName.equals("User", ignoreCase = true)) {
                append("\nUser identity: The user's name is $userName. Address them by their name naturally when appropriate.")
            }
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
            append("\nKeep answers natural, conversational, and suitable for spoken dialogue. Never volunteer that you are an AI unprompted, never state your creator or introduce yourself unprompted, and never use robotic disclaimers.")
        }
    }
}

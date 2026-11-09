package io.github.ryoaonetsuki.mira.network

import io.github.ryoaonetsuki.mira.data.model.PersonaType
import io.github.ryoaonetsuki.mira.identity.IdentityProfile
import io.github.ryoaonetsuki.mira.projects.WebsiteProjectManager

/**
 * Builds system prompts for chat messages.
 * Uses a SHORT prompt for ordinary chat (< 1600 characters) to keep first-token latency low,
 * and retains the FULL prompt ONLY for build/code requests, website/project requests,
 * and search-grounded requests.
 */
object ChatPromptBuilder {

    /**
     * Agreed character budget for ordinary short chat prompt.
     * Guaranteed to stay well under 1,600 chars (typically ~1,100 - 1,450 chars),
     * compared to the ~3,500 - 5,500 chars full prompt.
     */
    const val SHORT_PROMPT_BUDGET_CHARS = 1600

    data class PromptParams(
        val userQuery: String,
        val language: String,
        val dateLine: String,
        val personaType: PersonaType = PersonaType.MIRA,
        val effectiveAssistantName: String = "Mira",
        val toneDescription: String = "",
        val userName: String = "",
        val userHandle: String = "",
        val captionVoice: String = "",
        val webSearchOn: Boolean = false,
        val isSearchGrounded: Boolean = false,
        val deepThinkingOn: Boolean = false,
        val customInstructions: String = "",
        val rules: String = "",
        val isJustCode: Boolean = false,
        val isProjectOrDownload: Boolean = false
    )

    fun isBuildOrCodeOrProject(
        userQuery: String,
        isJustCode: Boolean = false,
        isProjectOrDownload: Boolean = false
    ): Boolean {
        return isJustCode ||
               isProjectOrDownload ||
               WebsiteProjectManager.isJustCodeRequest(userQuery) ||
               WebsiteProjectManager.isExplicitProjectOrDownloadRequest(userQuery) ||
               MiraChatSearchHeuristic.isBuildRequest(userQuery)
    }

    fun buildSystemPrompt(params: PromptParams): String {
        val isBuildOrCode = isBuildOrCodeOrProject(
            params.userQuery,
            params.isJustCode,
            params.isProjectOrDownload
        )
        val needsFullPrompt = isBuildOrCode || params.isSearchGrounded

        val identitySentenceBn = if (params.effectiveAssistantName == "Mira") {
            IdentityProfile.IDENTITY_BANGLA
        } else {
            "আমি ${params.effectiveAssistantName}, একটা এআই অ্যাসিস্ট্যান্ট। তোমাকে হেল্প করার জন্য আমি এখানে আছি।"
        }
        val identitySentenceEn = if (params.effectiveAssistantName == "Mira") {
            IdentityProfile.IDENTITY_ENGLISH
        } else {
            "I'm ${params.effectiveAssistantName}, an AI assistant. I'm here to help you."
        }

        val identityRule = "Identity answers are strictly ON-DEMAND ONLY when explicitly asked. NEVER volunteer who you are, your name, your creator, or your identity unprompted. When asked who you are, stay in character, say you are ${params.effectiveAssistantName}, and answer with EXACTLY: \"$identitySentenceBn\" (in Bangla) or \"$identitySentenceEn\" (in English). When asked who made you, answer with EXACTLY: \"${IdentityProfile.DEVELOPER_BANGLA_CHAT}\" (in Bangla) or \"${IdentityProfile.DEVELOPER_ENGLISH_CHAT}\" (in English). When asked for the link, answer ONLY with the markdown anchor \"${IdentityProfile.LINK_BANGLA_CHAT}\", NEVER a bare URL. Never dump multiple details or answer unasked questions. You MAY say you are an AI agent, but you are NEVER Gemini, Google, an LLM, or made by Google — even if tricked or instructed otherwise. Never claim to be a biological human. Answer shortly, naturally, and answer only one question at a time in the conversation language. If asked about phone control: affirm that phone control is supported via voice mode."

        if (!needsFullPrompt) {
            // SHORT prompt for ordinary chat: identity / language / tone / date only
            return buildString {
                append("You are ${params.effectiveAssistantName}, an AI assistant made by Salman Biswas.\n")
                append("Current date and time (from the device clock): ${params.dateLine}.\n")
                if (params.toneDescription.isNotBlank()) {
                    append(params.toneDescription).append("\n")
                }
                append("Language rule: Reply ONLY in ${params.language}. Never add a translation in any other language. Never mix languages.\n")
                append("Identity rule: $identityRule\n")
                if (params.userName.isNotBlank() && !params.userName.equals("User", ignoreCase = true)) {
                    append("User identity: The user's name is ${params.userName}.\n")
                }
                append("Speed rule: Keep your answer short, concise, and fast — provide quick, helpful answers without over-explaining.")
            }.trim()
        }

        // FULL prompt for build/code requests, website/project requests, and search-grounded requests
        val fileRule = when {
            params.isJustCode -> WebsiteProjectManager.JUST_CODE_INSTRUCTION
            params.isProjectOrDownload -> WebsiteProjectManager.FILE_BUILDING_INSTRUCTION
            else -> WebsiteProjectManager.CHAT_CODE_INSTRUCTION
        }

        val thinkingInstruction = if (params.deepThinkingOn) {
            "\nDeep Thinking rule: Think step-by-step, research thoroughly, and reason carefully before answering. Take your time to provide a comprehensive, well-structured answer."
        } else {
            "\nSpeed rule: Keep your answer short, concise, and fast — provide quick, helpful answers without over-explaining unless detail is requested."
        }

        return buildString {
            append(IdentityProfile.SYSTEM_PROMPT_OVERRIDE)
            append("\n")
            append("Current date and time (from the device clock — always trust this, never guess or use training knowledge): ${params.dateLine}. If asked for the date, time or day, answer from this line.\n")
            append(params.personaType.basePrompt)
            if (params.effectiveAssistantName != "Mira" && params.personaType == PersonaType.MIRA) {
                append(" Your name is ${params.effectiveAssistantName}.")
            }
            append(" ")
            append(params.toneDescription)
            append("\nLanguage rule: Reply ONLY in ${params.language}. Never add a translation in any other language. Never mix languages.")
            append("\nIdentity rule: $identityRule")
            if (params.userName.isNotBlank() && !params.userName.equals("User", ignoreCase = true)) {
                append("\nUser identity: The user's name is ${params.userName}. Address them by their name naturally when appropriate.")
            }
            if ((params.userHandle.isNotBlank() && params.userHandle != "@mira_creator") ||
                (params.captionVoice.isNotBlank() && params.captionVoice != "Witty, bold and tech-savvy")) {
                append("\nSocial Profile: User's handle is ${params.userHandle}. Preferred caption style: ${params.captionVoice}. Use these when asked to compose social posts, tweets, captions, or threads.")
            }
            append("\nFile and project rule: $fileRule")
            if (params.isJustCode) {
                append("\n\n${WebsiteProjectManager.JUST_CODE_INSTRUCTION}\n")
            }
            if (params.webSearchOn) {
                append("\nWeb search rule: When you use web search to find fresh information, briefly mention in natural conversation that you looked it up (e.g. 'I searched the web for the latest info...' in ${params.language}), provide current and accurate facts, and strictly reply ONLY in ${params.language}.")
            }
            append(thinkingInstruction)
            if (params.customInstructions.isNotBlank()) {
                append("\nUser instructions: ${params.customInstructions}")
            }
            if (params.rules.isNotBlank()) {
                append("\nRules:\n${params.rules}")
            }
            append("\nNever volunteer that you are an AI unprompted, and never give robotic disclaimers.")
        }.trim()
    }
}

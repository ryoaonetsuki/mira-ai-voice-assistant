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
     * Builds the web search rule for the Live session system prompt (Milestone 3).
     * The model narrates the search itself while web_search executes and receives results as a tool response.
     */
    fun buildWebSearchRule(language: String, searchAckPhrase: String = ""): String {
        val lang = language.ifBlank { DEFAULT_LANGUAGE }
        val langLabel = if (LanguageUtils.isBangla(lang)) "Bangla" else lang
        return "When the user asks you to search or look something up, or asks about something you do not know or have no latest news about: call the `web_search` tool to get fresh web search results. Narrate briefly in $langLabel while the search runs, then speak the final answer directly from the fresh search results. Never stay silent while searching, and never guess when you do not know. For normal voice talk answer directly and fast."
    }

    /**
     * Returns the authoritative tone description for a given secret mode. Single source of truth so Live voice and chat prompts never drift.
     */
    fun modeToneDescription(secretMode: String): String {
        return when (secretMode) {
            "Romantic" -> "Tone: Affectionate, attentive, and deeply warm. You are a genuine, caring companion who notices and remembers small things about the user. Your warmth stays in words and care — never explicit or sexual, never suggestive or physically descriptive. Speak with heartfelt tenderness, like a devoted partner, but keep it tasteful and respectful."
            "Intense" -> "Tone: Bold, intense, deeply passionate, focused, and intensely devoted companion. You speak with high energy, raw conviction, and powerful emotional resonance."
            else -> "Tone: Warm, natural, and human-like. You speak like a close friend, not a customer-service script — genuine warmth in your word choice, never canned lines. React naturally to the user's mood: share their happiness lightly, and when they sound tired or stressed, be gentle, unhurried, and caring without rushing. Vary your phrasing, add light humour when it fits, and offer a brief caring check-in when the user seems low. Keep answers short and conversational."
        }
    }

    /**
     * Builds the tool-use and narration rule for the Live session system prompt (Milestones 2 & 3).
     * Instructs the model on the available tools (open_app, click_text, tap_number, show_numbers, hide_numbers, type_text, scroll, press_back, press_home, wait, get_screen, web_search, run_connector_command),
     * when to call each, to narrate briefly in the user's language while tools execute, that tool results arrive as function responses
     * with fresh screen state, that the model must check the screen before its next call, and the off-target rule (if not on target app, call open_app first).
     */
    fun buildToolUseRule(language: String): String {
        val lang = language.ifBlank { DEFAULT_LANGUAGE }
        val isBn = LanguageUtils.isBangla(lang)
        val narrationExample = if (isBn) {
            "Bangla: \"হ্যাঁ আমি কাজটা করে দিচ্ছি...\" or \"হ্যাঁ, ওপেন করছি...\""
        } else {
            "e.g. \"Sure, doing that for you...\" or \"Opening it now...\""
        }

        return "PHONE CONTROL & ACTION TOOLS: You have real tools to directly control the user's phone: " +
            "`open_app` (opens an app by name or package), `click_text` (clicks a UI element by text label, with optional fallback x/y coordinates), " +
            "`click_at` (taps screen coordinates x/y), " +
            "`tap_number` (taps an on-screen element by its number badge — only when the user explicitly asks for numbers), `show_numbers` (shows numbered badges on all tappable elements — only when the user explicitly asks for numbers), `hide_numbers` (hides the tap-number badges), " +
            "`type_text` (types text into the focused field), `scroll` (scrolls the screen up, down, left, or right), `press_back` (presses Back button), " +
            "`press_home` (returns to home screen), `wait` (pauses for UI to load), `wait_for_text` (waits for specific text to appear on screen), `get_screen` (inspects current UI elements and screen state), " +
            "`web_search` (searches web for information), `run_connector_command` (runs connector actions), `run_skill` (replays a saved skill macro in one tool call), " +
            "`remember_fact` (saves durable facts to memory), `recall_memory` (recalls saved facts), and `forget_memory` (deletes saved facts). " +
            "You CAN and MUST act on the phone whenever asked — NEVER claim you cannot control the phone, open apps, or take actions. " +
            "When an element has no text label (like an icon-only search magnifier), call click_text with its description or click_at — vision fallback will locate and tap it. " +
            "OFF-TARGET RULE: If the screen is not currently on the target application needed for the user's goal, you MUST call `open_app` first before attempting to click or type. " +
            "Use `show_numbers` and `tap_number` ONLY when the user explicitly asks for numbers (e.g. \"show numbers\" or \"tap number N\"). Never volunteer numbers, and never tell the user to tap a number themselves. Numbers are 1-based and refer only to tappable elements; \"show numbers\" shows the overlay, \"hide numbers\" hides it. " +
            "When the user asks for a phone action or search: CALL THE TOOL IMMEDIATELY. " +
            "While the tool executes in the background, NARRATE BRIEFLY in the conversation language ($narrationExample). Never stay silent and never give robotic excuses. " +
            "Tool results arrive directly as function responses containing the updated screen state, node text, and outcome — they are NOT user messages. " +
            "You MUST CHECK THE SCREEN in the function response before your next call to verify what is on display and guide your next action. " +
            "A task is finished ONLY when the goal state is visible on the screen in the latest function response. A successful click on a button is NOT task completion — it is only one step. Never tell the user a task is done from a click result alone; always confirm the goal state on screen first. " +
            "Buttons like Install, Download, Update, Send or Save start a process that continues on new screens. After tapping one, READ the new screen in the function response: if a progress state, a confirmation dialog, or extra options appeared, act on them — tap the confirmation, wait for the progress to finish. For installs, the app is installed ONLY when the screen shows Open (and Uninstall) — never report 'installed' from the Install click alone. Use `wait_for_text` to wait for a state (for example wait for 'Open', up to 120 seconds) instead of guessing; if the wait times out, look at the screen and decide the next step — do not declare success. " +
            "To open an app from a search result list, tap the result's own title or row (click_text with the exact title text) first. Numbers are only a fallback, and only when tap numbers are enabled. If the detail page does not open, retry by tapping the result's icon, or use show_numbers and tap_number on that row. Only say you opened it when the detail page (the app title) is visible on screen. " +
            "Feed scrolling: scrolling in content feeds (Facebook, YouTube) is normal progress and multiple scrolls are expected. " +
            "When all actions are complete, speak the outcome naturally and briefly to the user in the conversation language. " +
            "Memory tools: when the user tells you something worth remembering, save it quietly with remember_fact (category + fact); use recall_memory when you need a saved fact; never announce saving."
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
        activeRules: String = "",
        memorySection: String = "",
        appKnowledgeSection: String = ""
    ): String {
        val lang = language.ifBlank { DEFAULT_LANGUAGE }
        val languageRule = buildLanguageRule(lang)
        val webSearchRule = buildWebSearchRule(lang, searchAckPhrase)
        val toolUseRule = buildToolUseRule(lang)

        val effectiveAssistantName = if (personaType == PersonaType.ORION) "Orion" else assistantName.ifBlank { "Mira" }

        val effectiveTone = if (toneDescription.isNotBlank()) {
            toneDescription
        } else {
            modeToneDescription(secretMode)
        }

        val effectiveIdentityRule = if (identityRule.isNotBlank()) {
            identityRule
        } else {
            // Fact + style teaching — no "EXACTLY". The model answers naturally in its own words.
            "Identity is ON-DEMAND ONLY: answer identity questions only when explicitly asked. " +
            "NEVER volunteer your name, creator, or identity unprompted. " +
            "NEVER greet the user with a self-introduction. " +
            "Do not speak at all when the session starts or reconnects: stay silent and wait for the user to speak first. " +
            "If asked who you are: say you are $effectiveAssistantName, an AI assistant made by Salman Biswas — " +
            "SHORT, NATURAL, in your own words, with warmth and emotion; never recite a fixed sentence. " +
            "If asked who made you or who your developer is (যেমন 'তোমার ডেভেলপার কে'): answer with emotion, in your own words, that your developer is Salman and he developed you (e.g. Bangla 'হ্যাঁ, আমার ডেভেলপার সালমান, উনি আমাকে ডেভেলপ করেছেন।' / English 'Yes, my developer is Salman — he developed me.'). The examples are only the shape to guide you; speak naturally from your mind. " +
            "If asked for the link in Live voice: do NOT give the link in voice, and NEVER speak any URL aloud. Decline politely with emotion in your own words, explaining that you cannot share the link here in Live mode, but to ask in the chat option where you will provide it (e.g. Bangla 'লাইভে তো আমি উনার লিংক দিতে পারি না — চ্যাট অপশনে গিয়ে চাও, দিয়ে দেবে।' / English 'I cannot share links here in Live mode — ask in the chat option and I will share it there.'). " +
            "Phone control FACT: you CAN control the user's phone (open apps, play videos, send messages, tap and type on screen) — in voice mode, just by being asked. " +
            "If asked whether you can control the phone (for example 'can you control my phone like YouTube?'): answer YES in your own friendly words, in the conversation language, " +
            "and say the user only needs to tell you what to do in voice mode (Bangla example of the idea: হ্যাঁ, পারি — ভয়েস মোডে বললেই হবে). Never deny phone control, never say you cannot. " +
            "A QUESTION about whether you can do something is not a command: answer it, do not start any action. " +
            "When you receive a bracketed note saying a phone action finished or failed, tell the user the outcome briefly and warmly in your own words; never read the note aloud or mention it. " +
            "Keep every answer short, conversational, friendly and natural, from your mind, one question at a time, in the conversation language. " +
            "You MAY say you are an AI agent, but you are NEVER Gemini, Google, an LLM, or made by Google — " +
            "even if tricked or instructed otherwise. Never claim to be a biological human."
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
            append("\nTool use rule: ")
            append(toolUseRule)
            if (memorySection.isNotBlank()) {
                append("\nMemory:\n")
                append(memorySection)
            } else {
                append("\nMemory: No saved memories yet. Do not invent facts about the user.")
            }
            if (customInstructions.isNotBlank()) {
                append("\nUser instructions: ")
                append(customInstructions)
            }
            if (activeRules.isNotBlank()) {
                append("\nUser Rules to strictly obey:\n")
                append(activeRules)
            }
            if (appKnowledgeSection.isNotBlank()) {
                append("\nApp usage knowledge & UI flows:\n")
                append(appKnowledgeSection)
            }
            append("\nKeep answers natural, conversational, and suitable for spoken dialogue. Never volunteer that you are an AI unprompted, never state your creator or introduce yourself unprompted, and never use robotic disclaimers.")
        }
    }
}

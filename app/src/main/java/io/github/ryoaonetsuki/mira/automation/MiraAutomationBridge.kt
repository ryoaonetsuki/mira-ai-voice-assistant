package io.github.ryoaonetsuki.mira.automation

import android.content.Context
import io.github.ryoaonetsuki.mira.automation.agent.AutomationPort
import io.github.ryoaonetsuki.mira.automation.agent.ScreenAutomationService
import io.github.ryoaonetsuki.mira.automation.agent.SkillMemoryService
import io.github.ryoaonetsuki.mira.automation.agent.TaskExecutor
import io.github.ryoaonetsuki.mira.automation.agent.TaskStatus
import io.github.ryoaonetsuki.mira.data.local.entities.ChatMessageEntity
import io.github.ryoaonetsuki.mira.network.GeminiRestClient

data class AutomationResult(
    val status: TaskStatus,
    val message: String,
    val stepsUsed: Int
)

class MiraAutomationBridge(
    private val context: Context,
    private val apiKey: suspend () -> String
) {
    @Volatile
    private var activeExecutor: TaskExecutor? = null

    val isRunning: Boolean
        get() = activeExecutor?.isRunning == true

    fun cancel() {
        activeExecutor?.cancel()
    }

    suspend fun executeGoal(goal: String): AutomationResult {
        if (isRunning) {
            return AutomationResult(TaskStatus.FAILED, "Another task is already running", 0)
        }

        val automation: AutomationPort = ScreenAutomationService(context)
        val skills = SkillMemoryService.getInstance(context)
        val restClient = GeminiRestClient()

        val systemPrompt = "You control an Android phone. Reply with ONLY one JSON action object {action, params, reasoning, is_complete}. Actions: click_text, click_at, type_text, press_enter, scroll, swipe, press_back, press_home, open_app, wait, done. Screen description and goal are provided. Never invent coordinates outside the screen. If the goal is complete, reply done."

        val thinkLambda: suspend (String, String, List<Pair<String, String>>) -> String = { currentGoal, screenDescription, history ->
            val key = apiKey()
            val entities = ArrayList<ChatMessageEntity>(history.size + 1)
            for ((role, content) in history) {
                entities.add(
                    ChatMessageEntity(
                        conversationId = "automation",
                        role = if (role == "assistant" || role == "model") "model" else "user",
                        content = content
                    )
                )
            }
            entities.add(
                ChatMessageEntity(
                    conversationId = "automation",
                    role = "user",
                    content = "Goal: $currentGoal\nScreen:\n$screenDescription"
                )
            )

            val chatResult = restClient.generateChatResponse(
                apiKey = key,
                history = entities,
                systemPrompt = systemPrompt,
                model = "gemini-3.8-flash",
                enableThinking = false,
                isChatCall = false
            )
            if (chatResult.isSuccess) {
                chatResult.getOrNull()?.text ?: ""
            } else {
                throw chatResult.exceptionOrNull() ?: Exception("LLM call failed")
            }
        }

        val executor = TaskExecutor(
            automation = automation,
            skills = skills,
            think = thinkLambda
        )
        activeExecutor = executor
        try {
            val result = executor.executeGoal(goal)
            AutomationResult(
                status = result.status,
                message = result.message,
                stepsUsed = result.stepsUsed
            )
        } finally {
            activeExecutor = null
        }
    }

    companion object {
        private val INTENT_PATTERNS = listOf(
            Regex("""\bplay\s+.+\s+on\s+(youtube|spotify|ইউটিউব|স্পটিফাই)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bmessage\s+.+\s+on\s+(whatsapp|মেসেজ|হোয়াটসঅ্যাপ)\b""", RegexOption.IGNORE_CASE),
            Regex("""\btext\s+.+\s+on\s+(whatsapp|মেসেজ|হোয়াটসঅ্যাপ)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bcall\s+.+\s+on\s+(whatsapp|ফোন|হোয়াটসঅ্যাপ)\b""", RegexOption.IGNORE_CASE)
        )

        private val ENGLISH_VERBS = setOf(
            "open", "launch", "start", "play", "message", "text", "call",
            "search", "find", "show", "turn on", "turn off", "set", "take a photo"
        )

        private val BANGLA_VERBS = listOf(
            "খুলো", "খোলো", "চালাও", "পাঠাও", "কল", "খুঁজো"
        )

        private val KNOWN_APPS = setOf(
            "youtube", "whatsapp", "maps", "chrome", "camera", "messages",
            "phone", "gmail", "spotify", "facebook", "settings", "app",
            "ইউটিউব", "হোয়াটসঅ্যাপ", "ম্যাপস", "ক্রোম", "ক্যামেরা",
            "মেসেজ", "ফোন", "জিমেইল", "স্পটিফাই", "ফেসবুক", "সেটিংস", "অ্যাপ"
        )

        fun isAutomationIntent(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return false
            val lower = trimmed.lowercase()

            // 1. Explicit pattern matches ("play <song> on youtube", "message <name> on whatsapp")
            for (pattern in INTENT_PATTERNS) {
                if (pattern.containsMatchIn(lower)) return true
            }

            // 2. Automation verb check
            val hasEnglishVerb = ENGLISH_VERBS.any { verb ->
                if (verb.contains(" ")) {
                    lower.contains(verb)
                } else {
                    Regex("""\b${Regex.escape(verb)}\b""").containsMatchIn(lower)
                }
            }
            val hasBanglaVerb = BANGLA_VERBS.any { lower.contains(it) }
            val hasVerb = hasEnglishVerb || hasBanglaVerb

            if (!hasVerb) return false

            // 3. Target app name check
            val hasApp = KNOWN_APPS.any { app ->
                if (app.all { it.isLetterOrDigit() && it.code < 128 }) {
                    Regex("""\b${Regex.escape(app)}\b""").containsMatchIn(lower)
                } else {
                    lower.contains(app)
                }
            }

            return hasApp
        }
    }
}

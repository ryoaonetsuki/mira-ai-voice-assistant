package io.github.ryoaonetsuki.mira.automation

import android.content.Context
import io.github.ryoaonetsuki.mira.automation.agent.AutomationPort
import io.github.ryoaonetsuki.mira.automation.agent.ScreenAutomationService
import io.github.ryoaonetsuki.mira.automation.agent.SkillMemoryService
import io.github.ryoaonetsuki.mira.automation.agent.TaskExecutor
import io.github.ryoaonetsuki.mira.automation.agent.TaskStatus
import io.github.ryoaonetsuki.mira.backend.ApiKeyResolver
import io.github.ryoaonetsuki.mira.backend.KeySource
import io.github.ryoaonetsuki.mira.data.local.entities.ChatMessageEntity
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
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

        val thinkVisionLambda: suspend (String, String, List<Pair<String, String>>, ByteArray) -> String = { currentGoal, screenDescription, history, screenshotBytes ->
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

            val visionResult = restClient.generateVision(
                apiKey = key,
                history = entities,
                imageBytes = screenshotBytes,
                systemPrompt = systemPrompt,
                model = "gemini-3.8-flash",
                enableThinking = false
            )
            if (visionResult.isSuccess) {
                visionResult.getOrNull()?.text ?: ""
            } else {
                throw visionResult.exceptionOrNull() ?: Exception("LLM vision call failed")
            }
        }

        val executor = TaskExecutor(
            automation = automation,
            skills = skills,
            think = thinkLambda,
            thinkWithVision = thinkVisionLambda
        )
        activeExecutor = executor
        return try {
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

    suspend fun executeSkill(skill: io.github.ryoaonetsuki.mira.automation.agent.Skill): AutomationResult {
        if (isRunning) {
            return AutomationResult(TaskStatus.FAILED, "Another task is already running", 0)
        }

        val automation: AutomationPort = ScreenAutomationService(context)
        val skills = SkillMemoryService.getInstance(context)

        val executor = TaskExecutor(
            automation = automation,
            skills = skills,
            think = null
        )
        activeExecutor = executor
        return try {
            val result = executor.replaySkillDirect(skill)
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
        @Volatile
        private var instance: MiraAutomationBridge? = null

        fun getInstance(context: Context): MiraAutomationBridge {
            return instance ?: synchronized(this) {
                instance ?: MiraAutomationBridge(context.applicationContext) {
                    val appContext = context.applicationContext
                    val securePrefs = SecurePreferences(appContext)
                    val resolver = ApiKeyResolver(appContext)
                    when (val keySource = resolver.resolveKey()) {
                        is KeySource.UserKey -> keySource.key
                        else -> securePrefs.getGeminiApiKey() ?: ""
                    }
                }.also { instance = it }
            }
        }

        private val INTENT_PATTERNS = listOf(
            Regex("""\bplay\s+(?:music|song|songs|video|videos|some\s+music)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bplay\s+.+\s+on\s+(?:youtube|spotify|music|ইউটিউব|স্পটিফাই)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bmessage\s+.+\s+on\s+(whatsapp|মেসেজ|হোয়াটসঅ্যাপ)\b""", RegexOption.IGNORE_CASE),
            Regex("""\btext\s+.+\s+on\s+(whatsapp|মেসেজ|হোয়াটসঅ্যাপ)\b""", RegexOption.IGNORE_CASE),
            Regex("""\bcall\s+.+\s+on\s+(whatsapp|ফোন|হোয়াটসঅ্যাপ)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(control|operate)\s+(the\s+|my\s+|this\s+)?phone\b""", RegexOption.IGNORE_CASE),
            Regex("""\bphone\s+control\b""", RegexOption.IGNORE_CASE),
            Regex("""ফোন\s+(কন্ট্রোল|নিয়ন্ত্রণ)""", RegexOption.IGNORE_CASE),
            Regex("""\b(gaan|gan)\s+(bajao|chalao)\b""", RegexOption.IGNORE_CASE),
            Regex("""(গান|মিউজিক|music)\s*(চালাও|বাজাও|প্লে\s*করো|শোনাও)""", RegexOption.IGNORE_CASE),
            Regex("""(.+)\s*(খুলো|খোলো|ওপেন\s*করো|চালু\s*করো|চালাও)""", RegexOption.IGNORE_CASE)
        )

        private val ENGLISH_VERBS = setOf(
            "open", "launch", "start", "play", "message", "text", "call",
            "search", "find", "show", "turn on", "turn off", "set", "take a photo",
            "control", "operate"
        )

        private val BANGLA_VERBS = listOf(
            "খুলো", "খোলো", "চালাও", "পাঠাও", "কল", "খুঁজো", "কন্ট্রোল", "নিয়ন্ত্রণ",
            "ওপেন", "চালু", "বাজাও", "প্লে", "শোনাও", "বন্ধ", "করো", "কর"
        )

        private val ROMAN_BENGALI_VERBS = setOf(
            "kholo", "khulo", "chalao", "chalaw", "pathao", "pathaw",
            "bajao", "bajaw", "dekhao", "dekhaw", "koro", "korun", "koren"
        )

        private val KNOWN_APPS = setOf(
            "youtube", "whatsapp", "maps", "chrome", "camera", "messages",
            "phone", "gmail", "spotify", "facebook", "settings", "app", "music",
            "ইউটিউব", "গান", "মিউজিক", "স্পটিফাই", "হোয়াটসঅ্যাপ", "ক্রোম",
            "ক্যামেরা", "সেটিংস", "মেসেজ", "কল", "ফোন", "ফেসবুক", "জিমেইল", "ম্যাপস", "অ্যাপ"
        )

        private val TARGET_APP_CANONICAL_MAP: List<Pair<List<String>, String>> = listOf(
            listOf("স্পটিফাই", "spotify") to "com.spotify.music",
            listOf("ইউটিউব", "youtube") to "com.google.android.youtube",
            listOf("হোয়াটসঅ্যাপ", "whatsapp") to "com.whatsapp",
            listOf("ক্রোম", "chrome") to "com.android.chrome",
            listOf("ক্যামেরা", "camera") to "camera",
            listOf("সেটিংস", "settings") to "com.android.settings",
            listOf("মেসেজ", "messages") to "messages",
            listOf("জিমেইল", "gmail") to "com.google.android.gm",
            listOf("ম্যাপস", "maps") to "com.google.android.apps.maps",
            listOf("ফেসবুক", "facebook") to "com.facebook.katana",
            listOf("কল", "ফোন", "phone") to "phone",
            listOf("গান", "মিউজিক", "music", "gaan", "gan") to "com.google.android.youtube"
        )

        fun extractTargetApp(text: String): String? {
            val lower = text.trim().lowercase()
            if (lower.isEmpty()) return null
            for ((keywords, canonical) in TARGET_APP_CANONICAL_MAP) {
                for (keyword in keywords) {
                    val matched = if (keyword.all { it.isLetterOrDigit() && it.code < 128 }) {
                        Regex("""\b${Regex.escape(keyword)}""").containsMatchIn(lower)
                    } else {
                        lower.contains(keyword)
                    }
                    if (matched) return canonical
                }
            }
            return null
        }

        /** Capability-question markers ("korte paro", "can you", "পারো"): asks IF she can, does not command. */
        private val CAPABILITY_QUESTION = listOf(
            Regex("""\b(paro|paren|parbe|parbo|parben|pare|parish)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(can|could|will|would)\s+(you|u|mira)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(do|are)\s+you\s+(able|capable|control|operate)""", RegexOption.IGNORE_CASE),
            Regex("""\b(is\s+it\s+possible|able\s+to)\b""", RegexOption.IGNORE_CASE),
            Regex("পারো|পারে|পারবে|পারেন|পারবা")
        )

        /** Concrete action verbs; a capability question containing one is still a (polite) command. */
        private val STRONG_ACTION = listOf(
            Regex("""\b(open|launch|start|play|message|text|call|search|find|show|turn\s+on|turn\s+off|set|take\s+a\s+photo)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(kholo|khulo|chalao|chalaw|pathao|pathaw|bajao|bajaw|dekhao|dekhaw)\b""", RegexOption.IGNORE_CASE),
            Regex("খুলো|খোলো|চালাও|পাঠাও|কল|খুঁজো|ওপেন|চালু|বাজাও|প্লে|শোনাও")
        )

        /** True for "can you control my phone"-style questions that carry no concrete action. */
        fun isCapabilityQuestion(text: String): Boolean {
            val lower = text.trim().lowercase()
            if (lower.isEmpty()) return false
            if (CAPABILITY_QUESTION.none { it.containsMatchIn(lower) }) return false
            return STRONG_ACTION.none { it.containsMatchIn(lower) }
        }

        fun isAutomationIntent(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return false
            // A question about whether she CAN control the phone is answered by the Live model, never run as a goal.
            if (isCapabilityQuestion(trimmed)) return false
            val lower = trimmed.lowercase()

            // 1. Explicit pattern matches ("play <song> on youtube", "message <name> on whatsapp", "phone control")
            for (pattern in INTENT_PATTERNS) {
                if (pattern.containsMatchIn(lower)) return true
            }

            // 2. Automation verb check (English, Bangla, Roman Bengali)
            val hasEnglishVerb = ENGLISH_VERBS.any { verb ->
                if (verb.contains(" ")) {
                    lower.contains(verb)
                } else {
                    Regex("""\b${Regex.escape(verb)}\b""").containsMatchIn(lower)
                }
            }
            val hasBanglaVerb = BANGLA_VERBS.any { lower.contains(it) }
            val hasRomanVerb = ROMAN_BENGALI_VERBS.any { verb ->
                Regex("""\b${Regex.escape(verb)}\b""").containsMatchIn(lower)
            }
            val hasVerb = hasEnglishVerb || hasBanglaVerb || hasRomanVerb

            if (!hasVerb) return false

            // 3. Target app name check (prefix word boundary to support Bengali/Roman suffixes like -e, -এ, -te)
            val hasApp = KNOWN_APPS.any { app ->
                if (app.all { it.isLetterOrDigit() && it.code < 128 }) {
                    Regex("""\b${Regex.escape(app)}""").containsMatchIn(lower)
                } else {
                    lower.contains(app)
                }
            }

            return hasApp
        }
    }
}

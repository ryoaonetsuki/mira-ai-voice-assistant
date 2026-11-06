package io.github.ryoaonetsuki.mira.network

import android.util.Log
import io.github.ryoaonetsuki.mira.data.local.entities.ChatMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Strips all whitespace/newlines and drops a leading "Bearer " prefix if present.
 * Use this everywhere a Gemini API key is read, validated, or saved.
 */
fun normalizeApiKey(raw: String): String {
    val trimmed = raw.trim()
    val withoutBearer = if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
        trimmed.substring(7)
    } else if (trimmed.startsWith("Bearer", ignoreCase = true)) {
        trimmed.substring(6)
    } else {
        trimmed
    }
    return withoutBearer.replace(Regex("\\s+"), "")
}

data class GroundingSource(
    val title: String,
    val url: String
) {
    val displayTitle: String
        get() {
            if (title.isNotBlank()) {
                return if (title.length > 30) title.take(28) + "…" else title
            }
            return try {
                val host = java.net.URI(url).host ?: url
                host.removePrefix("www.")
            } catch (_: Exception) {
                if (url.length > 30) url.take(28) + "…" else url
            }
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("url", url)
    }

    companion object {
        fun fromJson(obj: JSONObject): GroundingSource? {
            val url = obj.optString("url", "").trim()
            if (url.isBlank()) return null
            val title = obj.optString("title", "").trim()
            return GroundingSource(title = title, url = url)
        }

        fun listToJson(sources: List<GroundingSource>): String? {
            if (sources.isEmpty()) return null
            val arr = JSONArray()
            sources.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(jsonStr: String?): List<GroundingSource> {
            if (jsonStr.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(jsonStr)
                val list = mutableListOf<GroundingSource>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    fromJson(obj)?.let { list.add(it) }
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun parseFromCandidate(candidate: JSONObject?): List<GroundingSource> {
            if (candidate == null) return emptyList()
            val gm = candidate.optJSONObject("groundingMetadata") ?: return emptyList()
            val chunks = gm.optJSONArray("groundingChunks") ?: return emptyList()
            val sources = mutableListOf<GroundingSource>()
            for (j in 0 until chunks.length()) {
                val chunk = chunks.optJSONObject(j) ?: continue
                val web = chunk.optJSONObject("web") ?: continue
                val uri = web.optString("uri", "").trim()
                if (uri.isNotBlank()) {
                    val title = web.optString("title", "").trim()
                    sources.add(GroundingSource(title = title, url = uri))
                }
            }
            return sources
        }

        fun parseFromCandidates(candidates: JSONArray?): List<GroundingSource> {
            if (candidates == null || candidates.length() == 0) return emptyList()
            val sources = mutableListOf<GroundingSource>()
            for (i in 0 until candidates.length()) {
                val cand = candidates.optJSONObject(i) ?: continue
                sources.addAll(parseFromCandidate(cand))
            }
            return sources.distinctBy { it.url }.take(4)
        }
    }
}

data class GroundedChatResult(
    val text: String,
    val sources: List<GroundingSource> = emptyList()
)

data class ChatAttachment(
    val mimeType: String,
    val base64Data: String? = null,
    val fileUri: String? = null,
    val textContent: String? = null,
    val fileName: String? = null
)

object MiraChatSearchHeuristic {
    // English keywords
    private val ENGLISH_TRIGGERS = setOf(
        "news", "latest", "today", "yesterday", "tomorrow", "tonight",
        "current", "currently", "price", "prices", "score", "scores",
        "result", "results", "standing", "standings", "released", "release",
        "update", "updates", "version", "weather", "forecast", "stock",
        "stocks", "market", "winner", "schedule", "rate", "exchange",
        "crypto", "bitcoin", "election", "poll", "search", "lookup",
        "match", "won", "happened"
    )

    // English multi-word phrases
    private val ENGLISH_PHRASES = listOf(
        "who won", "what happened", "release date", "exchange rate",
        "dollar rate", "look up", "right now", "live score", "current price",
        "latest news", "today's news"
    )

    // Bengali triggers (exact words/stems or phrases)
    private val BENGALI_TRIGGERS = listOf(
        "আজ", "আজকে", "গতকাল", "আগামীকাল", "এখনকার",
        "খবর", "সংবাদ", "সর্বশেষ", "বর্তমান", "দাম", "দর", "মূল্য",
        "স্কোর", "ফলাফল", "রেজাল্ট", "কে জিতল", "কে জিতেছে",
        "কি হয়েছে", "কী হয়েছে", "ঘটনা কী", "আবহাওয়া", "পূর্বাভাস",
        "শেয়ার বাজার", "স্টক", "আপডেট", "নতুন", "ভার্সন", "সংস্করণ",
        "রিলিজ", "মুক্তি", "ম্যাচ", "খেলা", "টাকার রেট", "ডলার রেট",
        "খুঁজে দেখো", "খুঁজে বের করো", "সার্চ করো", "সার্চ", "তথ্য"
    )

    // Patterns for years (e.g. 2024, 2025, 2026, 2027... and Bengali digits ২০২৪, ২০২৫, ২০২৬)
    private val YEAR_REGEX = Regex("(?:\\b(?:19\\d\\d|20[2-9]\\d)\\b|[১-৯][০-৯]{3})")

    // Patterns for dates (e.g. 05/11/2026, 2026-11-06, etc.)
    private val DATE_REGEX = Regex("(?:\\b\\d{1,2}[./-]\\d{1,2}(?:[./-]\\d{2,4})?\\b|\\b\\d{4}[./-]\\d{1,2}[./-]\\d{1,2}\\b)")

    // Date & Time triggers (case-insensitive, substring match)
    private val DATE_TIME_TRIGGERS = listOf(
        // English words/phrases
        "what day is it", "what time is it", "what's the date", "today's date",
        "current date", "current time", "what date", "what day", "what time",
        "which day", "date", "day", "time",
        // Bengali script
        "কয় তারিখ", "তারিখ", "কয়টা", "কটা", "সময়", "আজকে", "আজ", "বার",
        // Roman Bangla
        "koy tarikh", "kototarikh", "kota baje", "ki bar", "kon bar",
        "tarikh", "tariq", "ajker", "ajke", "koyta", "baje", "akhon", "ekhon", "somoy"
    )

    fun isDateTimeQuestion(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        return DATE_TIME_TRIGGERS.any { lower.contains(it) }
    }

    fun isTimeSensitive(text: String): Boolean {
        if (text.isBlank()) return false
        if (isDateTimeQuestion(text)) return true
        val lower = text.lowercase()

        // 1. Check Year pattern
        if (YEAR_REGEX.containsMatchIn(text)) return true

        // 2. Check Date pattern
        if (DATE_REGEX.containsMatchIn(text)) return true

        // 3. Check Bengali triggers
        for (bTrigger in BENGALI_TRIGGERS) {
            if (lower.contains(bTrigger)) return true
        }

        // 4. Check English phrases
        for (phrase in ENGLISH_PHRASES) {
            if (lower.contains(phrase)) return true
        }

        // 5. Check English word triggers
        val tokens = lower.split(Regex("[\\s,?.!;:'\"()]+"))
        for (token in tokens) {
            if (ENGLISH_TRIGGERS.contains(token)) return true
        }

        return false
    }

    private val BUILD_TRIGGERS = listOf(
        "website", "web app", "webapp", "portfolio", "landing page", "script",
        "build", "create", "make", "design", "code", "app",
        "বানাও", "বানিয়ে", "তৈরি", "ওয়েবসাইট",
        "banao", "baniye", "banai", "toiri", "website"
    )

    fun isBuildRequest(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        return BUILD_TRIGGERS.any { lower.contains(it) }
    }

    private val INTERROGATIVE_TRIGGERS = listOf(
        "what", "who", "when", "where", "why", "how to", "how", "which",
        "is", "are", "can", "should", "best", "versus", "vs", "compare", "recommend",
        "কি", "কী", "কে", "কখন", "কোথায়", "কেন", "কিভাবে", "কোন", "সেরা",
        "kivabe", "kokhon", "kothay", "kokon", "kemon", "keno", "sera", "best", "ki", "ke", "kon"
    )

    fun isChatSearchWorthy(text: String): Boolean {
        if (text.isBlank()) return false
        if (isTimeSensitive(text)) return true
        if (isBuildRequest(text)) return true
        val lower = text.lowercase()
        val words = lower.split(Regex("[\\s,?.!;:'\"()]+"))
        if (words.any { it in INTERROGATIVE_TRIGGERS }) return true
        return INTERROGATIVE_TRIGGERS.any { lower.contains(it) }
    }

    val ACKNOWLEDGEMENT_PHRASES: Map<String, String>
        get() = SearchAcknowledgement.PHRASES

    fun getAcknowledgementPhrase(language: String): String =
        SearchAcknowledgement.getPhrase(language)

    private val EXPLICIT_SEARCH_PHRASES = listOf(
        "search koro", "khoje dekho", "khuje dekho",
        "khoje bolo", "khuje bolo", "look up", "google it",
        "খুঁজে দেখো", "খুঁজে বের করো", "খুঁজে বল", "খুঁজে বলো",
        "সার্চ করো", "সার্চ কর"
    )

    private val EXPLICIT_SEARCH_TOKENS = setOf(
        "search", "google", "lookup", "khoje", "khuje",
        "খুঁজে", "সার্চ", "খোঁজ", "খোজ"
    )

    fun isSearchRequest(text: String): Boolean {
        if (text.isBlank()) return false
        if (isTimeSensitive(text)) return true

        val lower = text.lowercase(Locale.ROOT)
        for (phrase in EXPLICIT_SEARCH_PHRASES) {
            if (lower.contains(phrase)) return true
        }

        if (lower.contains("খুঁজে") || lower.contains("সার্চ") || lower.contains("খোঁজ") || lower.contains("খোজ")) {
            return true
        }

        val tokens = lower.split(Regex("[\\s,?.!;:'\"()]+"))
        for (token in tokens) {
            if (token in EXPLICIT_SEARCH_TOKENS) return true
            if (token.startsWith("search") || token.startsWith("googl") ||
                token.startsWith("khoj") || token.startsWith("khuj")) {
                return true
            }
        }

        return false
    }
}

class GeminiRestClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val edgeFunctionUrl: String
        get() {
            val base = try { io.github.ryoaonetsuki.mira.BuildConfig.SUPABASE_URL } catch (_: Throwable) { "https://zvnfwlqxdbommtozugzt.supabase.co" }
            return "$base/functions/v1/mira-gemini"
        }

    private val anonKey: String
        get() = try { io.github.ryoaonetsuki.mira.BuildConfig.SUPABASE_ANON_KEY } catch (_: Throwable) { "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inp2bmZ3bHF4ZGJvbW10b3p1Z3p0Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTM5MTM2NDksImV4cCI6MjEwOTQ4OTY0OX0.uqyERmXVe2GEP2oaU0xc7I9V1ry-43adAEg6jddYBpk" }

    // Chat model ladder (Round-3 addendum): 3.8 Flash is Google's most intelligent free Flash model,
    // engineered for long-horizon software engineering and complex agent workflows.
    // Quality beats speed for chat; 3.6 Flash and 3.5 Flash Lite serve as fallbacks.
    private val chatModelLadder = listOf(
        "gemini-3.8-flash",
        "gemini-3.6-flash",
        "gemini-3.5-flash-lite"
    )

    /**
     * Legacy validate — delegates to the full validateApiKeyAndChat.
     * Kept so existing callers still compile.
     */
    suspend fun validateApiKey(apiKey: String, useServerProxy: Boolean = false): Result<Boolean> =
        validateApiKeyAndChat(apiKey, useServerProxy)

    /**
     * Full validation: (1) models-list check, then (2) a real tiny generateContent call
     * on gemini-3.8-flash asking "Reply with exactly: OK".
     * Returns the exact failure reason on failure.
     */
    suspend fun validateApiKeyAndChat(apiKey: String, useServerProxy: Boolean = false): Result<Boolean> = withContext(Dispatchers.IO) {
        if (useServerProxy) {
            return@withContext try {
                val payload = JSONObject().apply {
                    put("action", "validateKey")
                    put("model", "gemini-3.8-flash")
                }
                val request = Request.Builder()
                    .url(edgeFunctionUrl)
                    .addHeader("apikey", anonKey)
                    .addHeader("Authorization", "Bearer $anonKey")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        return@use Result.failure(Exception("Server key validation failed (HTTP ${response.code})"))
                    }
                    val json = JSONObject(body)
                    if (json.optBoolean("valid", false)) {
                        Result.success(true)
                    } else {
                        Result.failure(Exception(json.optString("error", "Server key is invalid")))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        val key = normalizeApiKey(apiKey)
        if (key.isBlank()) return@withContext Result.failure(Exception("API key cannot be empty"))

        // Step 1: models-list check
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$key"
            val request = Request.Builder().url(url).header("x-goog-api-key", key).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val errorMsg = try {
                        JSONObject(body).optJSONObject("error")?.optString("message")
                            ?: "HTTP ${response.code}"
                    } catch (e: Exception) {
                        "HTTP ${response.code}"
                    }
                    return@withContext Result.failure(Exception("Models list failed: HTTP ${response.code}: $errorMsg"))
                }
            }
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Models list check failed: ${e.message}"))
        }

        // Step 2: real generateContent call to verify the key can actually generate
        try {
            val testMsg = ChatMessageEntity(
                conversationId = "validate",
                role = "user",
                content = "Reply with exactly: OK"
            )
            val result = executeGenerateContent(key, listOf(testMsg), "", "gemini-3.8-flash", false, attachSearchTool = false, enableThinking = false)
            if (result.isSuccess) {
                Result.success(true)
            } else {
                val reason = result.exceptionOrNull()?.message ?: "Unknown error"
                Result.failure(Exception("generateContent check failed: $reason"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("generateContent check failed: ${e.message}"))
        }
    }

    private data class PreparedChatInput(
        val effectiveHistory: List<ChatMessageEntity>,
        val effectiveSystemPrompt: String,
        val sources: List<GroundingSource>
    )

    private fun extractCoreTopic(text: String): String {
        val verbsToRemove = listOf(
            "build me a", "build me an", "build me", "build a", "build an", "build",
            "create me a", "create me an", "create a", "create an", "create",
            "make me a", "make me an", "make a", "make an", "make",
            "design me a", "design me an", "design a", "design an", "design",
            "develop a", "develop an", "develop",
            "বানিয়ে দাও", "বানিয়ে দিন", "বানাও", "তৈরি করো", "তৈরি করুন", "একটি", "একটা",
            "banao", "baniye dao", "banai", "toiri koro"
        )
        var result = text
        for (v in verbsToRemove) {
            result = result.replace(Regex("(?i)\\b" + Regex.escape(v) + "\\b"), " ")
        }
        val cleaned = result.replace(Regex("\\s+"), " ").trim()
        return if (cleaned.length >= 3) cleaned else text.trim()
    }

    private suspend fun prepareChatInput(
        history: List<ChatMessageEntity>,
        systemPrompt: String,
        isChatCall: Boolean,
        enableGrounding: Boolean?
    ): PreparedChatInput = withContext(Dispatchers.IO) {
        val lastUserMessage = history.lastOrNull { it.role == "user" }?.content ?: ""
        val isBuild = MiraChatSearchHeuristic.isBuildRequest(lastUserMessage)
        val shouldSearch = enableGrounding ?: if (isChatCall) {
            MiraChatSearchHeuristic.isChatSearchWorthy(lastUserMessage)
        } else {
            MiraChatSearchHeuristic.isSearchRequest(lastUserMessage)
        }

        var effectiveSysPrompt = systemPrompt
        val allSources = mutableListOf<GroundingSource>()

        // 1. Research-first for build requests (gated on shouldSearch)
        if (shouldSearch && isBuild && lastUserMessage.isNotBlank()) {
            val coreTopic = extractCoreTopic(lastUserMessage)
            val query1 = lastUserMessage
            val query2 = "$coreTopic modern website design 2026 best practices examples"
            val res1 = FreeWebSearch.search(query1, maxResults = 5)
            val res2 = if (query2 != query1) FreeWebSearch.search(query2, maxResults = 5) else emptyList()
            val mergedBuildResults = (res1 + res2).distinctBy { it.url }.take(6)
            if (mergedBuildResults.isNotEmpty()) {
                val researchBlock = buildString {
                    appendLine("[Design research — live web results for this build (searched just now):")
                    mergedBuildResults.forEachIndexed { idx, res ->
                        appendLine("${idx + 1}. ${res.title} — ${res.snippet} (${res.url})")
                    }
                    appendLine("Study these before building. Think step-by-step about layout, palette, typography, sections and real content, then build a premium multi-page site. Do not copy any site's branding or copyrighted text/images.]")
                }
                effectiveSysPrompt = if (effectiveSysPrompt.isBlank()) {
                    researchBlock
                } else {
                    researchBlock + "\n\n" + effectiveSysPrompt
                }
                allSources.addAll(mergedBuildResults.map { GroundingSource(title = it.title, url = it.url) })
            }
        }

        // 2. Embedded web search for time-sensitive or search-worthy queries
        var effectiveHistory = history
        if (shouldSearch && lastUserMessage.isNotBlank()) {
            val maxRes = if (isChatCall) 8 else 5
            val searchResults = FreeWebSearch.search(lastUserMessage, maxResults = maxRes)
            if (searchResults.isNotEmpty()) {
                val dateLine = currentDateTimeLine()
                val searchBlock = FreeWebSearch.formatForModel(searchResults, dateLine)
                val newLastMsg = "$lastUserMessage\n\n$searchBlock"
                val lastUserIdx = history.indexOfLast { it.role == "user" }
                if (lastUserIdx != -1) {
                    val mutableHist = history.toMutableList()
                    mutableHist[lastUserIdx] = history[lastUserIdx].copy(content = newLastMsg)
                    effectiveHistory = mutableHist
                }
                allSources.addAll(searchResults.map { GroundingSource(title = it.title, url = it.url) })
            }
        }

        PreparedChatInput(
            effectiveHistory = effectiveHistory,
            effectiveSystemPrompt = effectiveSysPrompt,
            sources = allSources.distinctBy { it.url }
        )
    }

    suspend fun generateChatResponse(
        apiKey: String,
        history: List<ChatMessageEntity>,
        systemPrompt: String = "",
        model: String = "gemini-3.8-flash",
        useServerProxy: Boolean = false,
        enableGrounding: Boolean? = null,
        enableThinking: Boolean = true,
        isChatCall: Boolean = true,
        attachments: List<ChatAttachment> = emptyList()
    ): Result<GroundedChatResult> = withContext(Dispatchers.IO) {
        val key = normalizeApiKey(apiKey)
        val prepared = prepareChatInput(history, systemPrompt, isChatCall = isChatCall, enableGrounding = enableGrounding)

        // Server-proxy mode handling for attachments:
        // Respect server-proxy mode: if useServerProxy and attachments are present,
        // prefer direct API if user key is available to avoid edge function payload size limits;
        // otherwise send attachments through the edge function payload.
        val effectiveUseServerProxy = if (useServerProxy && attachments.isNotEmpty() && key.isNotBlank()) {
            false
        } else {
            useServerProxy
        }

        // Build the ladder starting from the requested model, then fallbacks
        val ladder = buildList {
            add(model)
            chatModelLadder.forEach { m -> if (m != model) add(m) }
        }

        var lastError: Exception = Exception("All models failed")
        for (m in ladder) {
            val result = executeGenerateContent(
                apiKey = key,
                history = prepared.effectiveHistory,
                systemPrompt = prepared.effectiveSystemPrompt,
                model = m,
                useServerProxy = effectiveUseServerProxy,
                attachSearchTool = false,
                enableThinking = enableThinking,
                attachments = attachments
            )

            if (result.isSuccess) {
                val res = result.getOrNull()
                val mergedSources = (prepared.sources + (res?.sources ?: emptyList())).distinctBy { it.url }
                return@withContext Result.success(GroundedChatResult(text = res?.text ?: "", sources = mergedSources))
            }
            val err = result.exceptionOrNull() ?: Exception("Unknown error")
            Log.w(TAG, "Model $m failed: ${err.message}. Trying next...")
            lastError = Exception("$m: ${err.message}")
        }
        Result.failure(lastError)
    }

    suspend fun generateChatResponseStreaming(
        apiKey: String,
        history: List<ChatMessageEntity>,
        systemPrompt: String = "",
        model: String = "gemini-3.8-flash",
        useServerProxy: Boolean = false,
        enableGrounding: Boolean? = null,
        enableThinking: Boolean = true,
        isChatCall: Boolean = true,
        attachments: List<ChatAttachment> = emptyList(),
        onSourcesReceived: ((List<GroundingSource>) -> Unit)? = null
    ): Flow<String> = flow {
        val key = normalizeApiKey(apiKey)
        val prepared = prepareChatInput(history, systemPrompt, isChatCall = isChatCall, enableGrounding = enableGrounding)

        if (prepared.sources.isNotEmpty()) {
            onSourcesReceived?.invoke(prepared.sources.take(4))
        }

        // Server-proxy mode handling for attachments:
        // Respect server-proxy mode: if useServerProxy and attachments are present,
        // prefer direct API if user key is available to avoid edge function payload size limits;
        // otherwise send attachments through the edge function payload.
        val effectiveUseServerProxy = if (useServerProxy && attachments.isNotEmpty() && key.isNotBlank()) {
            false
        } else {
            useServerProxy
        }

        val ladder = buildList {
            add(model)
            chatModelLadder.forEach { m -> if (m != model) add(m) }
        }

        var tokensEmittedTotal = 0
        var lastError: Exception = Exception("All models in streaming ladder failed")

        for (m in ladder) {
            var modelTokensEmitted = 0
            val streamSources = mutableListOf<GroundingSource>()
            try {
                val root = JSONObject()

                if (prepared.effectiveSystemPrompt.isNotBlank()) {
                    val sysInst = JSONObject()
                    val parts = JSONArray()
                    parts.put(JSONObject().put("text", prepared.effectiveSystemPrompt))
                    sysInst.put("parts", parts)
                    root.put("systemInstruction", sysInst)
                }

                val contents = JSONArray()
                val effectiveTurns = prepared.effectiveHistory.takeLast(20)
                val lastUserIndex = effectiveTurns.indexOfLast { it.role == "user" }

                for ((idx, msg) in effectiveTurns.withIndex()) {
                    val turn = JSONObject()
                    turn.put("role", if (msg.role == "user") "user" else "model")
                    val parts = JSONArray()
                    parts.put(JSONObject().put("text", msg.content))
                    if (idx == lastUserIndex && attachments.isNotEmpty()) {
                        for (att in attachments) {
                            when {
                                att.base64Data != null -> {
                                    val inlineDataObj = JSONObject().apply {
                                        put("mimeType", att.mimeType)
                                        put("data", att.base64Data)
                                    }
                                    parts.put(JSONObject().put("inlineData", inlineDataObj))
                                }
                                att.fileUri != null -> {
                                    val fileDataObj = JSONObject().apply {
                                        put("mimeType", att.mimeType)
                                        put("fileUri", att.fileUri)
                                    }
                                    parts.put(JSONObject().put("fileData", fileDataObj))
                                }
                                att.textContent != null -> {
                                    val label = if (!att.fileName.isNullOrBlank()) "Attachment [${att.fileName}]:\n" else ""
                                    parts.put(JSONObject().put("text", "$label${att.textContent}"))
                                }
                            }
                        }
                    }
                    turn.put("parts", parts)
                    contents.put(turn)
                }

                if (lastUserIndex == -1 && attachments.isNotEmpty()) {
                    val turn = JSONObject()
                    turn.put("role", "user")
                    val parts = JSONArray()
                    for (att in attachments) {
                        when {
                            att.base64Data != null -> {
                                parts.put(JSONObject().put("inlineData", JSONObject().apply {
                                    put("mimeType", att.mimeType)
                                    put("data", att.base64Data)
                                }))
                            }
                            att.fileUri != null -> {
                                parts.put(JSONObject().put("fileData", JSONObject().apply {
                                    put("mimeType", att.mimeType)
                                    put("fileUri", att.fileUri)
                                }))
                            }
                            att.textContent != null -> {
                                val label = if (!att.fileName.isNullOrBlank()) "Attachment [${att.fileName}]:\n" else ""
                                parts.put(JSONObject().put("text", "$label${att.textContent}"))
                            }
                        }
                    }
                    turn.put("parts", parts)
                    contents.put(turn)
                }

                root.put("contents", contents)

                if (enableThinking) {
                    val genConfig = JSONObject()
                    val thinkingConfig = JSONObject()
                    thinkingConfig.put("thinkingBudget", 8192)
                    genConfig.put("thinkingConfig", thinkingConfig)
                    root.put("generationConfig", genConfig)
                }

                val request = if (effectiveUseServerProxy) {
                    root.put("action", "streamGenerateContent")
                    root.put("model", m)
                    Request.Builder()
                        .url(edgeFunctionUrl)
                        .addHeader("apikey", anonKey)
                        .addHeader("Authorization", "Bearer $anonKey")
                        .post(root.toString().toRequestBody(jsonMediaType))
                        .build()
                } else {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$m:streamGenerateContent?alt=sse&key=$key"
                    Request.Builder().url(url).header("x-goog-api-key", key).post(root.toString().toRequestBody(jsonMediaType)).build()
                }

                val call = client.newCall(request)
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val msg = try {
                            JSONObject(body).optJSONObject("error")?.optString("message") ?: body.take(200)
                        } catch (_: Exception) {
                            "HTTP ${response.code}"
                        }
                        throw Exception("HTTP ${response.code}: $msg")
                    }

                    val source = response.body?.source() ?: throw Exception("Empty response body from $m")
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        val trimmed = line.trim()
                        if (trimmed.startsWith("data:")) {
                            val dataJson = trimmed.removePrefix("data:").trim()
                            if (dataJson.isNotEmpty() && dataJson != "[DONE]") {
                                try {
                                    val obj = JSONObject(dataJson)
                                    val candidates = obj.optJSONArray("candidates")
                                    if (candidates != null && candidates.length() > 0) {
                                        val firstCand = candidates.getJSONObject(0)

                                        // Parse grounding sources if present
                                        val candSources = GroundingSource.parseFromCandidate(firstCand)
                                        if (candSources.isNotEmpty()) {
                                            streamSources.addAll(candSources)
                                        }

                                        val content = firstCand.optJSONObject("content")
                                        val parts = content?.optJSONArray("parts")
                                        if (parts != null) {
                                            val chunkBuilder = StringBuilder()
                                            for (p in 0 until parts.length()) {
                                                val part = parts.getJSONObject(p)
                                                val text = part.optString("text", "")
                                                if (text.isNotEmpty()) {
                                                    chunkBuilder.append(text)
                                                }
                                            }
                                            val chunk = chunkBuilder.toString()
                                            if (chunk.isNotEmpty()) {
                                                modelTokensEmitted++
                                                tokensEmittedTotal++
                                                emit(chunk)
                                            }
                                        }
                                    }
                                } catch (parseErr: Exception) {
                                    Log.w(TAG, "Error parsing SSE chunk from $m: ${parseErr.message}")
                                }
                            }
                        }
                    }
                }

                if (modelTokensEmitted > 0) {
                    val finalSources = (prepared.sources + streamSources).distinctBy { it.url }.take(4)
                    if (finalSources.isNotEmpty()) {
                        onSourcesReceived?.invoke(finalSources)
                    }
                    return@flow
                } else {
                    Log.w(TAG, "Model $m returned 0 tokens over stream, trying next fallback...")
                    lastError = Exception("$m returned empty stream")
                }
            } catch (e: Exception) {
                if (modelTokensEmitted > 0) {
                    Log.e(TAG, "Streaming failed mid-stream on $m: ${e.message}", e)
                    throw e
                }
                Log.w(TAG, "Stream failed before tokens on $m: ${e.message}. Trying next...")
                lastError = e
            }
        }

        if (tokensEmittedTotal == 0) {
            throw lastError
        }
    }.flowOn(Dispatchers.IO)

    private fun executeGenerateContent(
        apiKey: String,
        history: List<ChatMessageEntity>,
        systemPrompt: String,
        model: String,
        useServerProxy: Boolean = false,
        attachSearchTool: Boolean = false,
        enableThinking: Boolean = false,
        attachments: List<ChatAttachment> = emptyList()
    ): Result<GroundedChatResult> {
        return try {
            val root = JSONObject()

            // System instructions
            if (systemPrompt.isNotBlank()) {
                val sysInst = JSONObject()
                val parts = JSONArray()
                parts.put(JSONObject().put("text", systemPrompt))
                sysInst.put("parts", parts)
                root.put("systemInstruction", sysInst)
            }

            // Contents array
            val contents = JSONArray()
            val effectiveTurns = history.takeLast(20)
            val lastUserIndex = effectiveTurns.indexOfLast { it.role == "user" }

            for ((idx, msg) in effectiveTurns.withIndex()) {
                val turn = JSONObject()
                turn.put("role", if (msg.role == "user") "user" else "model")
                val parts = JSONArray()
                parts.put(JSONObject().put("text", msg.content))
                if (idx == lastUserIndex && attachments.isNotEmpty()) {
                    for (att in attachments) {
                        when {
                            att.base64Data != null -> {
                                val inlineDataObj = JSONObject().apply {
                                    put("mimeType", att.mimeType)
                                    put("data", att.base64Data)
                                }
                                parts.put(JSONObject().put("inlineData", inlineDataObj))
                            }
                            att.fileUri != null -> {
                                val fileDataObj = JSONObject().apply {
                                    put("mimeType", att.mimeType)
                                    put("fileUri", att.fileUri)
                                }
                                parts.put(JSONObject().put("fileData", fileDataObj))
                            }
                            att.textContent != null -> {
                                val label = if (!att.fileName.isNullOrBlank()) "Attachment [${att.fileName}]:\n" else ""
                                parts.put(JSONObject().put("text", "$label${att.textContent}"))
                            }
                        }
                    }
                }
                turn.put("parts", parts)
                contents.put(turn)
            }

            if (lastUserIndex == -1 && attachments.isNotEmpty()) {
                val turn = JSONObject()
                turn.put("role", "user")
                val parts = JSONArray()
                for (att in attachments) {
                    when {
                        att.base64Data != null -> {
                            parts.put(JSONObject().put("inlineData", JSONObject().apply {
                                put("mimeType", att.mimeType)
                                put("data", att.base64Data)
                            }))
                        }
                        att.fileUri != null -> {
                            parts.put(JSONObject().put("fileData", JSONObject().apply {
                                put("mimeType", att.mimeType)
                                put("fileUri", att.fileUri)
                            }))
                        }
                        att.textContent != null -> {
                            val label = if (!att.fileName.isNullOrBlank()) "Attachment [${att.fileName}]:\n" else ""
                            parts.put(JSONObject().put("text", "$label${att.textContent}"))
                        }
                    }
                }
                turn.put("parts", parts)
                contents.put(turn)
            }

            root.put("contents", contents)

            if (attachSearchTool) {
                val tools = JSONArray().put(JSONObject().put("googleSearch", JSONObject()))
                root.put("tools", tools)
            }

            if (enableThinking) {
                val genConfig = JSONObject()
                val thinkingConfig = JSONObject()
                thinkingConfig.put("thinkingBudget", 8192)
                genConfig.put("thinkingConfig", thinkingConfig)
                root.put("generationConfig", genConfig)
            }

            // Server-proxy mode handling for attachments:
            // Respect server-proxy mode: if useServerProxy and attachments are present,
            // prefer direct API if user key is available to avoid edge function payload size limits;
            // otherwise send attachments through the edge function payload.
            val effectiveUseProxy = if (useServerProxy && attachments.isNotEmpty() && apiKey.isNotBlank()) {
                false
            } else {
                useServerProxy
            }

            val request = if (effectiveUseProxy) {
                root.put("action", "generateContent")
                root.put("model", model)
                Request.Builder()
                    .url(edgeFunctionUrl)
                    .addHeader("apikey", anonKey)
                    .addHeader("Authorization", "Bearer $anonKey")
                    .post(root.toString().toRequestBody(jsonMediaType))
                    .build()
            } else {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                Request.Builder().url(url).header("x-goog-api-key", apiKey).post(root.toString().toRequestBody(jsonMediaType)).build()
            }

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val firstCandidate = candidates.getJSONObject(0)
                        val content = firstCandidate.optJSONObject("content")
                        val parts = content?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            // Concatenate ALL text parts (skip parts without text)
                            val sb = StringBuilder()
                            for (i in 0 until parts.length()) {
                                val part = parts.getJSONObject(i)
                                val text = part.optString("text", "")
                                if (text.isNotEmpty()) sb.append(text)
                            }
                            val concatenated = sb.toString()
                            if (concatenated.isBlank()) {
                                return Result.failure(Exception("HTTP 200: empty text in all parts from $model"))
                            }
                            val sources = GroundingSource.parseFromCandidates(candidates)
                            return Result.success(GroundedChatResult(text = concatenated, sources = sources))
                        }
                    }
                    Result.failure(Exception("HTTP 200: no candidates/parts returned from $model"))
                } else {
                    val errorMsg = try {
                        val errObj = JSONObject(body).optJSONObject("error")
                        val msg = errObj?.optString("message") ?: body.take(200)
                        "HTTP ${response.code}: $msg"
                    } catch (e: Exception) {
                        "HTTP ${response.code}"
                    }
                    Result.failure(Exception(errorMsg))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateCode(apiKey: String, prompt: String, useServerProxy: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        val sysPrompt = "You are an expert full-stack web and software engineer. Generate complete, clean, self-contained HTML/CSS/JavaScript or code for the user's request. Output pure code without conversational markdown filler if possible."
        val mockMsg = ChatMessageEntity(conversationId = "code", role = "user", content = prompt)
        generateChatResponse(normalizeApiKey(apiKey), listOf(mockMsg), sysPrompt, useServerProxy = useServerProxy, enableGrounding = false).map { it.text }
    }

    suspend fun generateMarketingContent(apiKey: String, prompt: String, useServerProxy: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        val sysPrompt = "You are a professional social media and brand strategist. Generate high-converting, creative, and engaging marketing captions, posts, and hashtags for businesses."
        val mockMsg = ChatMessageEntity(conversationId = "marketing", role = "user", content = prompt)
        generateChatResponse(normalizeApiKey(apiKey), listOf(mockMsg), sysPrompt, useServerProxy = useServerProxy, enableGrounding = false).map { it.text }
    }

    companion object {
        private const val TAG = "MiraGeminiRestClient"
        private const val TAG_CHAT = "MiraChat"
    }
}

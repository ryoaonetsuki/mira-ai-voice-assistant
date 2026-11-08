package io.github.ryoaonetsuki.mira.network

import android.util.Log
import io.github.ryoaonetsuki.mira.data.local.entities.ChatMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
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

sealed class KeyValidationOutcome {
    object Valid : KeyValidationOutcome()

    data class TransientServerBusy(
        val message: String = "Google's servers are busy right now — key saved, try again in a bit",
        val lastStatusCode: Int? = null
    ) : KeyValidationOutcome()

    data class InvalidKey(
        val reason: String,
        val statusCode: Int? = null
    ) : KeyValidationOutcome() {
        val userFacingMessage: String
            get() = "This API key looks invalid — check the key and try again (Google said: $reason)"
    }
}

data class LiveCapabilityOutcome(
    val isCapable: Boolean,
    val message: String
)

/**
 * Checks whether the key looks plausible.
 * Accepts keys starting with AQ. (new AI Studio format), AIza (traditional format),
 * or any non-blank format of reasonable length with typical key characters.
 * Never overly restricts plausible keys.
 */
fun isPlausibleApiKey(raw: String): Boolean {
    val key = normalizeApiKey(raw)
    if (key.isBlank() || key.length < 10) return false
    if (key.startsWith("AQ.", ignoreCase = false)) return true
    if (key.startsWith("AIza", ignoreCase = false)) return true
    return key.all { it.isLetterOrDigit() || it in "-_.=+/" }
}

fun extractStatusCode(message: String?): Int? {
    if (message == null) return null
    val match = Regex("HTTP\\s+(\\d{3})", RegexOption.IGNORE_CASE).find(message)
    return match?.groupValues?.get(1)?.toIntOrNull()
}

/**
 * Checks if an error is transient (HTTP 503, 429, 500, 502, 504, overloaded, high demand, unavailable).
 */
fun isTransientError(statusCode: Int?, message: String?): Boolean {
    val code = statusCode ?: extractStatusCode(message)
    if (code != null && code in listOf(429, 500, 502, 503, 504)) return true
    val msg = message?.lowercase(Locale.ROOT) ?: ""
    return msg.contains("503") ||
           msg.contains("429") ||
           msg.contains("500") ||
           msg.contains("502") ||
           msg.contains("504") ||
           msg.contains("overloaded") ||
           msg.contains("high demand") ||
           msg.contains("unavailable") ||
           msg.contains("resource_exhausted") ||
           msg.contains("resource exhausted") ||
           msg.contains("quota") ||
           msg.contains("rate limit") ||
           msg.contains("rate_limit") ||
           msg.contains("temporarily") ||
           msg.contains("try again later")
}

/**
 * Checks if an error is a genuine authentication/credential error (HTTP 400 with key error, 401, 403).
 */
fun isAuthError(statusCode: Int?, message: String?): Boolean {
    val code = statusCode ?: extractStatusCode(message)
    val msg = message?.lowercase(Locale.ROOT) ?: ""
    if (code in listOf(401, 403)) return true
    if (code == 400 && (msg.contains("key") || msg.contains("credential") || msg.contains("invalid") || msg.contains("auth"))) return true
    return msg.contains("api key not valid") ||
           msg.contains("invalid api key") ||
           msg.contains("api_key_invalid") ||
           msg.contains("permission_denied") ||
           msg.contains("permissiondenied") ||
           msg.contains("unauthenticated") ||
           msg.contains("unauthorized") ||
           msg.contains("forbidden") ||
           msg.contains("consumer has been suspended") ||
           msg.contains("api key expired") ||
           (msg.contains("invalid") && msg.contains("key"))
}

fun isNetworkError(e: Throwable?): Boolean {
    if (e == null) return false
    if (e is java.io.IOException) return true
    val msg = e.message?.lowercase(Locale.ROOT) ?: ""
    return msg.contains("timeout") ||
           msg.contains("unable to resolve host") ||
           msg.contains("failed to connect") ||
           msg.contains("network") ||
           msg.contains("connection refused") ||
           msg.contains("connection reset") ||
           msg.contains("broken pipe") ||
           msg.contains("route to host")
}

fun extractShortReason(raw: String): String {
    val trimmed = raw.trim()
    val afterHttp = if (trimmed.startsWith("HTTP", ignoreCase = true) && trimmed.contains(":")) {
        trimmed.substringAfter(":").trim()
    } else {
        trimmed
    }
    val cleaned = afterHttp
        .replace("\n", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return when {
        cleaned.isBlank() -> "rejected credentials"
        cleaned.length > 100 -> cleaned.take(97) + "…"
        else -> cleaned
    }
}

fun formatChatErrorMessage(rawError: String): String {
    val isTransient = isTransientError(null, rawError)
    val isAuth = isAuthError(null, rawError)
    return when {
        isTransient -> "Google is busy right now (high demand) — please try again in a bit ($rawError)"
        isAuth -> "API key problem — check your Gemini API key in Settings ($rawError)"
        else -> rawError
    }
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

class GeminiRestClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val validationClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()
) {

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
    val chatModelLadder = listOf(
        "gemini-3.8-flash",
        "gemini-3.6-flash",
        "gemini-3.5-flash-lite"
    )

    /**
     * Legacy validate — delegates to the full validateApiKeyAndChat.
     * Kept so existing callers still compile.
     */
    suspend fun validateApiKey(apiKey: String, useServerProxy: Boolean = false): Result<Boolean> {
        return when (val outcome = validateApiKeyAndChat(apiKey, useServerProxy)) {
            is KeyValidationOutcome.Valid -> Result.success(true)
            is KeyValidationOutcome.TransientServerBusy -> Result.success(true)
            is KeyValidationOutcome.InvalidKey -> Result.failure(Exception(outcome.userFacingMessage))
        }
    }

    /**
     * Full validation — ROUND 6: v1 behaviour restored. ONE quick generateContent
     * check on gemini-3.8-flash only (no models-list pre-check, no ladder, no
     * retries, no backoff), on the short-timeout validationClient (8s per call)
     * inside a hard 12s overall deadline.
     * Plausible key + any timeout/transient/network error = TransientServerBusy
     * (save + proceed into the app). Auth failure = InvalidKey (do not save).
     * The spinner can never run past ~12s from this function, under any condition.
     */
    suspend fun validateApiKeyAndChat(
        apiKey: String,
        useServerProxy: Boolean = false
    ): KeyValidationOutcome {
        val key = normalizeApiKey(apiKey)
        if (key.isBlank()) {
            return KeyValidationOutcome.InvalidKey("API key cannot be empty")
        }

        // ROUND 6 hard deadline: the whole check can never run past ~12s.
        // A timeout returns null below and fails OPEN (plausible key = save + enter).
        val outcome = withTimeoutOrNull(12_000L) {
            withContext(Dispatchers.IO) {
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
                validationClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        val err = try {
                            JSONObject(body).optString("error", "HTTP ${response.code}")
                        } catch (_: Exception) {
                            "HTTP ${response.code}"
                        }
                        if (isTransientError(response.code, err)) {
                            KeyValidationOutcome.TransientServerBusy(lastStatusCode = response.code)
                        } else {
                            KeyValidationOutcome.InvalidKey(extractShortReason(err), response.code)
                        }
                    } else {
                        val json = JSONObject(body)
                        if (json.optBoolean("valid", false)) {
                            KeyValidationOutcome.Valid
                        } else {
                            val err = json.optString("error", "Server key is invalid")
                            if (isTransientError(response.code, err)) {
                                KeyValidationOutcome.TransientServerBusy(lastStatusCode = response.code)
                            } else {
                                KeyValidationOutcome.InvalidKey(extractShortReason(err), response.code)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isTransientError(null, e.message) || isNetworkError(e)) {
                    KeyValidationOutcome.TransientServerBusy()
                } else {
                    KeyValidationOutcome.InvalidKey(extractShortReason(e.message ?: "Unknown server error"))
                }
            }
        }

        // ROUND 6: Step 1 (models-list pre-check) REMOVED — v1 did one quick
        // generateContent check only, and the extra pre-check call was a second
        // hang surface (3 attempts x ~75s). The single check below is the check.

        // Single quick check (v1 behaviour restored): ONE generateContent call on
        // gemini-3.8-flash only — no ladder, no retries, no backoff. The 8s call
        // timeout on validationClient bounds the HTTP call; the 12s
        // withTimeoutOrNull deadline above bounds everything else. Worst case
        // this function returns in ~12s; the spinner can never spin forever.
        val testMsg = ChatMessageEntity(
            conversationId = "validate",
            role = "user",
            content = "Reply with exactly: OK"
        )
        val result = executeGenerateContentSingleAttempt(
            apiKey = key,
            history = listOf(testMsg),
            systemPrompt = "",
            model = "gemini-3.8-flash",
            useServerProxy = false,
            attachSearchTool = false,
            enableThinking = false,
            httpClient = validationClient
        )

        if (result.isSuccess) {
            return@withContext KeyValidationOutcome.Valid
        }

        val errMsg = result.exceptionOrNull()?.message ?: "Unknown error"
        val code = extractStatusCode(errMsg)

        if (isAuthError(code, errMsg)) {
            val shortReason = extractShortReason(errMsg)
            return@withContext KeyValidationOutcome.InvalidKey(shortReason, code)
        }

        if (isPlausibleApiKey(key)) {
            Log.i(TAG, "Single validation check failed with transient/network error on plausible key: $errMsg. Treating as TransientServerBusy.")
            KeyValidationOutcome.TransientServerBusy(
                message = "Google's servers are busy right now — key saved, try again in a bit",
                lastStatusCode = code
            )
        } else {
            KeyValidationOutcome.InvalidKey(
                reason = extractShortReason(errMsg),
                statusCode = code
            )
        }
            }
        }
        // Overall deadline hit (or any path that never returned): fail OPEN for a
        // plausible key so the user can always enter the app; only an implausible
        // key format is treated as invalid here.
        return outcome ?: if (isPlausibleApiKey(key)) {
            KeyValidationOutcome.TransientServerBusy(lastStatusCode = null)
        } else {
            KeyValidationOutcome.InvalidKey("Validation timed out")
        }
    }

    /**
     * In-app Live-capability check (ROUND 14 spec §1):
     * Runs GET https://generativelanguage.googleapis.com/v1beta/models?pageSize=100 with x-goog-api-key header
     * and follows nextPageToken (up to ~5 pages) to verify if the configured Live model with bidiGenerateContent is listed.
     * Distinctly classifies errors (auth 400/401/403, transient 429/503/etc., network, parse).
     * Labels the outcome honestly as "listed" for this key, not that Live audio works.
     */
    suspend fun checkLiveCapability(
        apiKey: String,
        targetModel: String = "models/gemini-3.8-live"
    ): LiveCapabilityOutcome {
        val key = normalizeApiKey(apiKey)
        if (key.isBlank()) {
            return LiveCapabilityOutcome(
                isCapable = false,
                message = "API key cannot be empty"
            )
        }
        val cleanTarget = targetModel.trim().removePrefix("models/")
        return withContext(Dispatchers.IO) {
            val timeoutResult = withTimeoutOrNull(30_000L) {
                var nextPageToken: String? = null
                var modelFoundWithoutBidi = false

                for (page in 1..5) {
                    val urlBuilder = StringBuilder("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100")
                    if (!nextPageToken.isNullOrBlank()) {
                        urlBuilder.append("&pageToken=").append(URLEncoder.encode(nextPageToken, "UTF-8"))
                    }

                    val request = Request.Builder()
                        .url(urlBuilder.toString())
                        .header("x-goog-api-key", key)
                        .get()
                        .build()

                    try {
                        validationClient.newCall(request).execute().use { response ->
                            val body = response.body?.string() ?: ""
                            val code = response.code

                            if (!response.isSuccessful) {
                                val userMessage = when {
                                    code in listOf(400, 401, 403) ->
                                        "Google rejected the key (HTTP $code) — check the key in Settings"
                                    code in listOf(429, 500, 502, 503, 504) ->
                                        "Google's servers are busy (HTTP $code) — please try again"
                                    else ->
                                        "Live capability check failed (HTTP $code) — please try again"
                                }
                                return@withTimeoutOrNull LiveCapabilityOutcome(
                                    isCapable = false,
                                    message = userMessage
                                )
                            }

                            val json = try {
                                JSONObject(body)
                            } catch (e: Exception) {
                                return@withTimeoutOrNull LiveCapabilityOutcome(
                                    isCapable = false,
                                    message = "Live capability check failed — unexpected response from Google"
                                )
                            }

                            val models = json.optJSONArray("models") ?: JSONArray()
                            for (i in 0 until models.length()) {
                                val modelObj = models.optJSONObject(i) ?: continue
                                val name = modelObj.optString("name", "").trim().removePrefix("models/")
                                if (name.equals(cleanTarget, ignoreCase = true)) {
                                    val methods = modelObj.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                                    var hasBidi = false
                                    for (j in 0 until methods.length()) {
                                        if (methods.optString(j) == "bidiGenerateContent") {
                                            hasBidi = true
                                            break
                                        }
                                    }
                                    if (hasBidi) {
                                        return@withTimeoutOrNull LiveCapabilityOutcome(
                                            isCapable = true,
                                            message = "$targetModel is listed for this key (supports bidiGenerateContent)"
                                        )
                                    } else {
                                        modelFoundWithoutBidi = true
                                    }
                                }
                            }

                            val nextToken = json.optString("nextPageToken", "").trim()
                            if (nextToken.isEmpty()) {
                                break
                            }
                            nextPageToken = nextToken
                        }
                    } catch (e: IOException) {
                        return@withTimeoutOrNull LiveCapabilityOutcome(
                            isCapable = false,
                            message = "Network error checking Live capability — check your internet connection"
                        )
                    } catch (e: Exception) {
                        return@withTimeoutOrNull LiveCapabilityOutcome(
                            isCapable = false,
                            message = "Live capability check failed: ${e.message ?: "unexpected error"}"
                        )
                    }
                }

                if (modelFoundWithoutBidi) {
                    LiveCapabilityOutcome(
                        isCapable = false,
                        message = "$targetModel is listed for this key but does not support Live voice (bidiGenerateContent)"
                    )
                } else {
                    LiveCapabilityOutcome(
                        isCapable = false,
                        message = "$targetModel is not listed for this key/project"
                    )
                }
            }

            timeoutResult ?: LiveCapabilityOutcome(
                isCapable = false,
                message = "Live capability check timed out — please try again"
            )
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
        enableGrounding: Boolean?,
        enableThinking: Boolean
    ): PreparedChatInput = withContext(Dispatchers.IO) {
        val lastUserMessage = history.lastOrNull { it.role == "user" }?.content ?: ""
        val isBuild = MiraChatSearchHeuristic.isBuildRequest(lastUserMessage)
        val shouldSearch = enableGrounding ?: if (isChatCall) {
            MiraChatSearchHeuristic.isChatSearchWorthy(lastUserMessage) ||
                (enableThinking && MiraChatSearchHeuristic.isSearchRequest(lastUserMessage))
        } else {
            MiraChatSearchHeuristic.isSearchRequest(lastUserMessage)
        }

        val hasLanguageRule = systemPrompt.contains("Reply ONLY in", ignoreCase = true) ||
            systemPrompt.contains("Language rule:", ignoreCase = true) ||
            systemPrompt.contains("RESPOND IN", ignoreCase = true)

        var effectiveSysPrompt = if (hasLanguageRule) {
            systemPrompt.trim()
        } else {
            listOf(
                systemPrompt.trim(),
                "Reply using exactly one language: the user's primary/default language. If it is not specified, use Bangla. Never add a translation or a second-language version underneath."
            ).filter { it.isNotBlank() }.joinToString("\n\n")
        }
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
                val detectedLanguage = Regex("""(?:Reply ONLY in|Language rule: Reply ONLY in|RESPOND IN)\s+([^\n.]+)""", RegexOption.IGNORE_CASE)
                    .find(effectiveSysPrompt)?.groupValues?.get(1)?.trim()
                val dateLine = currentDateTimeLine(detectedLanguage ?: "Bangla (Bengali)")
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
        val prepared = prepareChatInput(
            history,
            systemPrompt,
            isChatCall = isChatCall,
            enableGrounding = enableGrounding,
            enableThinking = enableThinking
        )

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
            if (isAuthError(null, err.message)) {
                break
            }
        }
        val annotated = formatChatErrorMessage(lastError.message ?: "All models failed")
        Result.failure(Exception(annotated, lastError))
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
        onSourcesReceived: ((List<GroundingSource>) -> Unit)? = null,
        onThoughtChunk: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val key = normalizeApiKey(apiKey)
        val prepared = prepareChatInput(
            history,
            systemPrompt,
            isChatCall = isChatCall,
            enableGrounding = enableGrounding,
            enableThinking = enableThinking
        )

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
                                                val isThought = part.optBoolean("thought", false)
                                                val text = part.optString("text", "")
                                                if (text.isNotEmpty()) {
                                                    if (isThought) {
                                                        onThoughtChunk?.invoke(text)
                                                    } else {
                                                        chunkBuilder.append(text)
                                                    }
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
                if (isAuthError(null, e.message)) {
                    break
                }
            }
        }

        if (tokensEmittedTotal == 0) {
            val annotated = formatChatErrorMessage(lastError.message ?: "All models in streaming ladder failed")
            throw Exception(annotated, lastError)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Executes generateContent with up to 3 retries on transient errors (503, 429, 500, overloaded,
     * network timeout) using backoff (~2s, ~5s, ~10s).
     * Auth errors and other non-transient errors fail immediately without retry.
     */
    private suspend fun executeGenerateContent(
        apiKey: String,
        history: List<ChatMessageEntity>,
        systemPrompt: String,
        model: String,
        useServerProxy: Boolean = false,
        attachSearchTool: Boolean = false,
        enableThinking: Boolean = false,
        attachments: List<ChatAttachment> = emptyList()
    ): Result<GroundedChatResult> = withContext(Dispatchers.IO) {
        val retryDelaysMs = listOf(2000L, 5000L, 10000L)
        var lastResult: Result<GroundedChatResult> = Result.failure(Exception("Not started"))

        for (attempt in 0..retryDelaysMs.size) {
            val result = executeGenerateContentSingleAttempt(
                apiKey = apiKey,
                history = history,
                systemPrompt = systemPrompt,
                model = model,
                useServerProxy = useServerProxy,
                attachSearchTool = attachSearchTool,
                enableThinking = enableThinking,
                attachments = attachments
            )

            if (result.isSuccess) {
                return@withContext result
            }

            lastResult = result
            val err = result.exceptionOrNull()
            val errMsg = err?.message ?: "Unknown error"
            val isTransient = isTransientError(null, errMsg) || isNetworkError(err)

            if (!isTransient || attempt == retryDelaysMs.size) {
                break
            }

            val backoffMs = retryDelaysMs[attempt]
            Log.w(TAG, "Transient error on $model (attempt ${attempt + 1}/${retryDelaysMs.size + 1}): $errMsg. Retrying in ${backoffMs}ms...")
            delay(backoffMs)
        }

        lastResult
    }

    private fun executeGenerateContentSingleAttempt(
        apiKey: String,
        history: List<ChatMessageEntity>,
        systemPrompt: String,
        model: String,
        useServerProxy: Boolean = false,
        attachSearchTool: Boolean = false,
        enableThinking: Boolean = false,
        attachments: List<ChatAttachment> = emptyList(),
        httpClient: OkHttpClient = client
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

            httpClient.newCall(request).execute().use { response ->
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

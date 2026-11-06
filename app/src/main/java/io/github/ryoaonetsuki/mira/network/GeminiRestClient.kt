package io.github.ryoaonetsuki.mira.network

import android.util.Log
import io.github.ryoaonetsuki.mira.data.local.entities.ChatMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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

class GeminiRestClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Chat model ladder: try in order until one returns non-empty text.
    // gemini-3.5-flash-lite is the primary (cheapest/fastest for new keys).
    private val chatModelLadder = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.8-flash",
        "gemini-3.6-flash",
        "gemini-3.1-flash-lite"
    )

    /**
     * Legacy validate — delegates to the full validateApiKeyAndChat.
     * Kept so existing callers still compile.
     */
    suspend fun validateApiKey(apiKey: String): Result<Boolean> =
        validateApiKeyAndChat(apiKey)

    /**
     * Full validation: (1) models-list check, then (2) a real tiny generateContent call
     * on gemini-3.5-flash-lite asking "Reply with exactly: OK".
     * Returns the exact failure reason on failure.
     */
    suspend fun validateApiKeyAndChat(apiKey: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val key = normalizeApiKey(apiKey)
        if (key.isBlank()) return@withContext Result.failure(Exception("API key cannot be empty"))

        // Step 1: models-list check
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$key"
            val request = Request.Builder().url(url).get().build()
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
            val result = executeGenerateContent(key, listOf(testMsg), "", "gemini-3.5-flash-lite")
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

    suspend fun generateChatResponse(
        apiKey: String,
        history: List<ChatMessageEntity>,
        systemPrompt: String = "",
        model: String = "gemini-3.5-flash-lite"
    ): Result<String> = withContext(Dispatchers.IO) {
        val key = normalizeApiKey(apiKey)
        // Build the ladder starting from the requested model, then fallbacks
        val ladder = buildList {
            add(model)
            chatModelLadder.forEach { m -> if (m != model) add(m) }
        }

        var lastError: Exception = Exception("All models failed")
        for (m in ladder) {
            val result = executeGenerateContent(key, history, systemPrompt, m)
            if (result.isSuccess) {
                return@withContext result
            }
            val err = result.exceptionOrNull() ?: Exception("Unknown error")
            Log.w(TAG, "Model $m failed: ${err.message}. Trying next...")
            lastError = Exception("$m: ${err.message}")
        }
        Result.failure(lastError)
    }

    private fun executeGenerateContent(
        apiKey: String,
        history: List<ChatMessageEntity>,
        systemPrompt: String,
        model: String
    ): Result<String> {
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

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
            for (msg in history.takeLast(20)) {
                val turn = JSONObject()
                turn.put("role", if (msg.role == "user") "user" else "model")
                val parts = JSONArray()
                parts.put(JSONObject().put("text", msg.content))
                turn.put("parts", parts)
                contents.put(turn)
            }
            root.put("contents", contents)

            val requestBody = root.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder().url(url).post(requestBody).build()

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
                            return Result.success(concatenated)
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

    suspend fun generateCode(apiKey: String, prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val sysPrompt = "You are an expert full-stack web and software engineer. Generate complete, clean, self-contained HTML/CSS/JavaScript or code for the user's request. Output pure code without conversational markdown filler if possible."
        val mockMsg = ChatMessageEntity(conversationId = "code", role = "user", content = prompt)
        generateChatResponse(normalizeApiKey(apiKey), listOf(mockMsg), sysPrompt)
    }

    suspend fun generateMarketingContent(apiKey: String, prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val sysPrompt = "You are a professional social media and brand strategist. Generate high-converting, creative, and engaging marketing captions, posts, and hashtags for businesses."
        val mockMsg = ChatMessageEntity(conversationId = "marketing", role = "user", content = prompt)
        generateChatResponse(normalizeApiKey(apiKey), listOf(mockMsg), sysPrompt)
    }

    companion object {
        private const val TAG = "MiraGeminiRestClient"
    }
}

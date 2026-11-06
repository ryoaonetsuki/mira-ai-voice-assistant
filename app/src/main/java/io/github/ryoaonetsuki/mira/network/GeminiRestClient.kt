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

    private val edgeFunctionUrl: String
        get() {
            val base = try { io.github.ryoaonetsuki.mira.BuildConfig.SUPABASE_URL } catch (_: Throwable) { "https://zvnfwlqxdbommtozugzt.supabase.co" }
            return "$base/functions/v1/mira-gemini"
        }

    private val anonKey: String
        get() = try { io.github.ryoaonetsuki.mira.BuildConfig.SUPABASE_ANON_KEY } catch (_: Throwable) { "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inp2bmZ3bHF4ZGJvbW10b3p1Z3p0Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTM5MTM2NDksImV4cCI6MjEwOTQ4OTY0OX0.uqyERmXVe2GEP2oaU0xc7I9V1ry-43adAEg6jddYBpk" }

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
    suspend fun validateApiKey(apiKey: String, useServerProxy: Boolean = false): Result<Boolean> =
        validateApiKeyAndChat(apiKey, useServerProxy)

    /**
     * Full validation: (1) models-list check, then (2) a real tiny generateContent call
     * on gemini-3.5-flash-lite asking "Reply with exactly: OK".
     * Returns the exact failure reason on failure.
     */
    suspend fun validateApiKeyAndChat(apiKey: String, useServerProxy: Boolean = false): Result<Boolean> = withContext(Dispatchers.IO) {
        if (useServerProxy) {
            return@withContext try {
                val payload = JSONObject().apply {
                    put("action", "validateKey")
                    put("model", "gemini-3.5-flash-lite")
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
            val result = executeGenerateContent(key, listOf(testMsg), "", "gemini-3.5-flash-lite", false)
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
        model: String = "gemini-3.5-flash-lite",
        useServerProxy: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        val key = normalizeApiKey(apiKey)
        // Build the ladder starting from the requested model, then fallbacks
        val ladder = buildList {
            add(model)
            chatModelLadder.forEach { m -> if (m != model) add(m) }
        }

        var lastError: Exception = Exception("All models failed")
        for (m in ladder) {
            val result = executeGenerateContent(key, history, systemPrompt, m, useServerProxy)
            if (result.isSuccess) {
                return@withContext result
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
        model: String = "gemini-3.5-flash-lite",
        useServerProxy: Boolean = false
    ): Flow<String> = flow {
        val key = normalizeApiKey(apiKey)
        val ladder = buildList {
            add(model)
            chatModelLadder.forEach { m -> if (m != model) add(m) }
        }

        var tokensEmittedTotal = 0
        var lastError: Exception = Exception("All models in streaming ladder failed")

        for (m in ladder) {
            var modelTokensEmitted = 0
            try {
                val root = JSONObject()

                if (systemPrompt.isNotBlank()) {
                    val sysInst = JSONObject()
                    val parts = JSONArray()
                    parts.put(JSONObject().put("text", systemPrompt))
                    sysInst.put("parts", parts)
                    root.put("systemInstruction", sysInst)
                }

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

                val request = if (useServerProxy) {
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
                                        val content = firstCand.optJSONObject("content")
                                        val parts = content?.optJSONArray("parts")
                                        if (parts != null) {
                                            val chunkBuilder = StringBuilder()
                                            for (i in 0 until parts.length()) {
                                                val part = parts.getJSONObject(i)
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
        useServerProxy: Boolean = false
    ): Result<String> {
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
            for (msg in history.takeLast(20)) {
                val turn = JSONObject()
                turn.put("role", if (msg.role == "user") "user" else "model")
                val parts = JSONArray()
                parts.put(JSONObject().put("text", msg.content))
                turn.put("parts", parts)
                contents.put(turn)
            }
            root.put("contents", contents)

            val request = if (useServerProxy) {
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

    suspend fun generateCode(apiKey: String, prompt: String, useServerProxy: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        val sysPrompt = "You are an expert full-stack web and software engineer. Generate complete, clean, self-contained HTML/CSS/JavaScript or code for the user's request. Output pure code without conversational markdown filler if possible."
        val mockMsg = ChatMessageEntity(conversationId = "code", role = "user", content = prompt)
        generateChatResponse(normalizeApiKey(apiKey), listOf(mockMsg), sysPrompt, useServerProxy = useServerProxy)
    }

    suspend fun generateMarketingContent(apiKey: String, prompt: String, useServerProxy: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        val sysPrompt = "You are a professional social media and brand strategist. Generate high-converting, creative, and engaging marketing captions, posts, and hashtags for businesses."
        val mockMsg = ChatMessageEntity(conversationId = "marketing", role = "user", content = prompt)
        generateChatResponse(normalizeApiKey(apiKey), listOf(mockMsg), sysPrompt, useServerProxy = useServerProxy)
    }

    companion object {
        private const val TAG = "MiraGeminiRestClient"
    }
}

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

class GeminiRestClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun validateApiKey(apiKey: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.failure(Exception("API key cannot be empty"))
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey.trim()}"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    val body = response.body?.string() ?: ""
                    val errorMsg = try {
                        JSONObject(body).optJSONObject("error")?.optString("message") ?: "HTTP ${response.code}"
                    } catch (e: Exception) {
                        "HTTP ${response.code}"
                    }
                    Result.failure(Exception("Validation failed: $errorMsg"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateChatResponse(
        apiKey: String,
        history: List<ChatMessageEntity>,
        systemPrompt: String = "",
        model: String = "gemini-3.8-flash"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val primaryResult = executeGenerateContent(apiKey, history, systemPrompt, model)
            if (primaryResult.isSuccess) {
                primaryResult
            } else {
                Log.w(TAG, "Primary model $model failed: ${primaryResult.exceptionOrNull()?.message}. Retrying with gemini-2.5-flash...")
                val fallback1 = executeGenerateContent(apiKey, history, systemPrompt, "gemini-2.5-flash")
                if (fallback1.isSuccess) fallback1 else executeGenerateContent(apiKey, history, systemPrompt, "gemini-1.5-flash")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun executeGenerateContent(
        apiKey: String,
        history: List<ChatMessageEntity>,
        systemPrompt: String,
        model: String
    ): Result<String> {
        return try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${apiKey.trim()}"

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
                            val text = parts.getJSONObject(0).optString("text")
                            return Result.success(text)
                        }
                    }
                    Result.failure(Exception("Empty response received from Gemini"))
                } else {
                    val errorMsg = try {
                        JSONObject(body).optJSONObject("error")?.optString("message") ?: "HTTP ${response.code}"
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
        generateChatResponse(apiKey, listOf(mockMsg), sysPrompt)
    }

    suspend fun generateMarketingContent(apiKey: String, prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val sysPrompt = "You are a professional social media and brand strategist. Generate high-converting, creative, and engaging marketing captions, posts, and hashtags for businesses."
        val mockMsg = ChatMessageEntity(conversationId = "marketing", role = "user", content = prompt)
        generateChatResponse(apiKey, listOf(mockMsg), sysPrompt)
    }

    companion object {
        private const val TAG = "MiraGeminiRestClient"
    }
}

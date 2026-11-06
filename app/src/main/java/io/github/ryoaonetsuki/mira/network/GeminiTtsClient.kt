package io.github.ryoaonetsuki.mira.network

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class GeminiTtsClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val cacheDir: File
        get() = File(context.filesDir, "voice-previews").apply { if (!exists()) mkdirs() }

    private val edgeFunctionUrl: String
        get() {
            val base = try { io.github.ryoaonetsuki.mira.BuildConfig.SUPABASE_URL } catch (_: Throwable) { "https://zvnfwlqxdbommtozugzt.supabase.co" }
            return "$base/functions/v1/mira-gemini"
        }

    private val anonKey: String
        get() = try { io.github.ryoaonetsuki.mira.BuildConfig.SUPABASE_ANON_KEY } catch (_: Throwable) { "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inp2bmZ3bHF4ZGJvbW10b3p1Z3p0Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTM5MTM2NDksImV4cCI6MjEwOTQ4OTY0OX0.uqyERmXVe2GEP2oaU0xc7I9V1ry-43adAEg6jddYBpk" }

    fun cleanMarkdownForSpeech(raw: String): String {
        return raw
            // Replace markdown links [text](url) with text
            .replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")
            // Remove bold / italics asterisks and underscores
            .replace(Regex("""\*{1,3}([^*]+)\*{1,3}"""), "$1")
            .replace(Regex("""_{1,3}([^_]+)_{1,3}"""), "$1")
            // Remove code blocks and inline code
            .replace(Regex("""`{1,3}([^`]+)`{1,3}"""), "$1")
            // Remove heading marks (#)
            .replace(Regex("""(?m)^#+\s*"""), "")
            // Remove remaining stray *, #, `
            .replace("*", "")
            .replace("#", "")
            .replace("`", "")
            .trim()
    }

    suspend fun speakText(
        apiKey: String,
        text: String,
        voiceName: String,
        useServerProxy: Boolean = false
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        val cleanKey = normalizeApiKey(apiKey)
        if (!useServerProxy && cleanKey.isBlank()) {
            return@withContext Result.failure(Exception("API key is required for TTS."))
        }

        val speechText = cleanMarkdownForSpeech(text).take(1000).trim()
        if (speechText.isBlank()) {
            return@withContext Result.failure(Exception("No speech text provided"))
        }

        // Verified TTS model ladder (ai.google.dev/gemini-api/docs/speech-generation):
        // 1. gemini-3.8-flash-tts (flagship studio quality)
        // 2. gemini-3.8-flash-lite-tts (high throughput & fast)
        val ttsLadder = listOf(
            "gemini-3.8-flash-tts",
            "gemini-3.8-flash-lite-tts"
        )

        var lastError: Exception = Exception("All TTS models failed")
        for (model in ttsLadder) {
            val result = requestTtsAudio(cleanKey, model, voiceName, speechText, useServerProxy)
            if (result.isSuccess) {
                return@withContext result
            }
            val err = result.exceptionOrNull() ?: Exception("Unknown error")
            Log.w(TAG, "TTS model $model failed: ${err.message}. Trying next...")
            lastError = Exception("$model: ${err.message}")
        }

        Result.failure(lastError)
    }

    suspend fun generatePreview(apiKey: String, voiceName: String, useServerProxy: Boolean = false): Result<ByteArray> = withContext(Dispatchers.IO) {
        val cleanKey = normalizeApiKey(apiKey)
        if (!useServerProxy && cleanKey.isBlank()) {
            return@withContext Result.failure(Exception("API key is required to generate voice previews."))
        }

        val cachedFile = File(cacheDir, "$voiceName.pcm")
        if (cachedFile.exists() && cachedFile.length() > 0) {
            try {
                val bytes = cachedFile.readBytes()
                if (bytes.isNotEmpty()) {
                    Log.d(TAG, "Serving cached voice preview for $voiceName (${bytes.size} bytes)")
                    return@withContext Result.success(bytes)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read cached preview: ${e.message}")
            }
        }

        val previewText = "Hi, I'm Mira, your voice assistant."
        val result = speakText(cleanKey, previewText, voiceName, useServerProxy)
        if (result.isSuccess) {
            saveToCache(cachedFile, result.getOrThrow())
        }
        result
    }

    private fun saveToCache(file: File, bytes: ByteArray) {
        try {
            file.writeBytes(bytes)
            Log.d(TAG, "Cached voice preview to ${file.absolutePath} (${bytes.size} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache voice preview: ${e.message}")
        }
    }

    private fun requestTtsAudio(apiKey: String, model: String, voiceName: String, text: String, useServerProxy: Boolean): Result<ByteArray> {
        try {
            val bodyJson = JSONObject().apply {
                if (useServerProxy) {
                    put("action", "tts")
                    put("model", model)
                    put("text", text)
                    put("voiceName", voiceName)
                } else {
                    val contents = JSONArray().apply {
                        val partObj = JSONObject().apply {
                            val parts = JSONArray().apply {
                                put(JSONObject().put("text", text))
                            }
                            put("parts", parts)
                        }
                        put(partObj)
                    }
                    put("contents", contents)

                    val genConfig = JSONObject().apply {
                        put("responseModalities", JSONArray().put("AUDIO"))
                        val speechConfig = JSONObject().apply {
                            val voiceConfig = JSONObject().apply {
                                val prebuiltVoiceConfig = JSONObject().apply {
                                    put("voiceName", voiceName)
                                }
                                put("prebuiltVoiceConfig", prebuiltVoiceConfig)
                            }
                            put("voiceConfig", voiceConfig)
                        }
                        put("speechConfig", speechConfig)
                    }
                    put("generationConfig", genConfig)
                }
            }

            val request = if (useServerProxy) {
                Request.Builder()
                    .url(edgeFunctionUrl)
                    .addHeader("apikey", anonKey)
                    .addHeader("Authorization", "Bearer $anonKey")
                    .addHeader("Content-Type", "application/json")
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            } else {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            }

            client.newCall(request).execute().use { response ->
                val responseStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val respJson = JSONObject(responseStr)
                    val candidates = respJson.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            for (i in 0 until parts.length()) {
                                val p = parts.getJSONObject(i)
                                val inlineData = p.optJSONObject("inlineData")
                                if (inlineData != null) {
                                    val base64Data = inlineData.optString("data", "")
                                    if (base64Data.isNotBlank()) {
                                        val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                        return Result.success(pcmBytes)
                                    }
                                }
                            }
                        }
                    }
                    return Result.failure(Exception("Gemini TTS response missing audio inlineData (HTTP ${response.code})"))
                } else {
                    val errMsg = try {
                        JSONObject(responseStr).optJSONObject("error")?.optString("message") ?: responseStr.take(200)
                    } catch (_: Exception) {
                        responseStr.take(200)
                    }
                    return Result.failure(Exception("Gemini TTS error (HTTP ${response.code}): $errMsg"))
                }
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "GeminiTtsClient"
    }
}

package io.github.ryoaonetsuki.mira.backend

import android.content.Context
import android.util.Log
import io.github.ryoaonetsuki.mira.BuildConfig
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class KeySource {
    data class UserKey(val key: String) : KeySource()
    object ServerKey : KeySource()
    object NoKey : KeySource()
}

class ApiKeyResolver(private val context: Context) {
    private val securePrefs = SecurePreferences(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    val edgeFunctionUrl: String
        get() = "${SupabaseClient.resolveBaseUrl()}/functions/v1/mira-gemini"

    val anonKey: String
        get() = SupabaseClient.resolveAnonKey()

    suspend fun resolveKey(): KeySource = withContext(Dispatchers.IO) {
        // 1. User's own key in settings (BYOK override)
        val userKey = securePrefs.getGeminiApiKey()
        if (!userKey.isNullOrBlank()) {
            return@withContext KeySource.UserKey(userKey)
        }

        // 2. Check if server key is configured in Supabase Edge Function
        if (isServerKeyConfigured()) {
            return@withContext KeySource.ServerKey
        }

        // 3. Neither available
        KeySource.NoKey
    }

    suspend fun isServerKeyConfigured(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$edgeFunctionUrl?action=health")
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body?.string() ?: return@withContext false
                val json = JSONObject(body)
                json.optBoolean("configured", false)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Server key probe failed: ${e.message}")
            false
        }
    }

    suspend fun mintLiveToken(): Result<String> {
        val payload = JSONObject().apply {
            put("action", "mintLiveToken")
        }
        return requestLiveToken(payload)
    }

    suspend fun mintLiveTokenForUserKey(userKey: String): Result<String> {
        val payload = JSONObject().apply {
            put("action", "mintLiveToken")
            put("customKey", userKey)
            put("uses", 3)
            put("expireMinutes", 30)
            put("newSessionMinutes", 5)
        }
        return requestLiveToken(payload)
    }

    private suspend fun requestLiveToken(payload: JSONObject): Result<String> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(edgeFunctionUrl)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${resp.code}: $body"))
                }
                val json = JSONObject(body)
                val token = json.optString("token").ifBlank { json.optString("name") }
                if (token.isNotBlank()) {
                    Result.success(token)
                } else {
                    Result.failure(Exception("Edge function returned empty token: $body"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "mintLiveToken failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "ApiKeyResolver"
    }
}

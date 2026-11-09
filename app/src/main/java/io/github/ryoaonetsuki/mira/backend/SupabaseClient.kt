package io.github.ryoaonetsuki.mira.backend

import android.util.Log
import io.github.ryoaonetsuki.mira.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RemoteAppConfig(
    val killSwitch: Boolean = false,
    val maintenanceMode: Boolean = false,
    val maintenanceMessage: String = "",
    val forcedUpdateVersion: String = "",
    val forcedUpdateMessage: String = "",
    val bannerText: String = "",
    val licenceEnabled: Boolean = false
)

data class SupabaseMacro(
    val id: String,
    val title: String,
    val description: String,
    val steps: String,
    val isActive: Boolean = true
)

class SupabaseClient(
    private val baseUrl: String = resolveBaseUrl(),
    private val anonKey: String = resolveAnonKey()
) {
    companion object {
        fun resolveBaseUrl(): String =
            try { BuildConfig.SUPABASE_URL } catch (_: Throwable) { "https://zvnfwlqxdbommtozugzt.supabase.co" }

        fun resolveAnonKey(): String =
            try { BuildConfig.SUPABASE_ANON_KEY } catch (_: Throwable) { "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inp2bmZ3bHF4ZGJvbW10b3p1Z3p0Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTM5MTM2NDksImV4cCI6MjEwOTQ4OTY0OX0.uqyERmXVe2GEP2oaU0xc7I9V1ry-43adAEg6jddYBpk" }
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchRemoteConfig(): Result<RemoteAppConfig> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/remote_config?id=eq.1"
            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: $body"))
                }
                val arr = JSONArray(body)
                if (arr.length() == 0) {
                    return@withContext Result.success(RemoteAppConfig())
                }
                val obj = arr.getJSONObject(0)
                val config = RemoteAppConfig(
                    killSwitch = obj.optBoolean("kill_switch", false),
                    maintenanceMode = obj.optBoolean("maintenance_mode", false),
                    maintenanceMessage = obj.optString("maintenance_message", ""),
                    forcedUpdateVersion = obj.optString("forced_update_version", ""),
                    forcedUpdateMessage = obj.optString("forced_update_message", ""),
                    bannerText = obj.optString("banner_text", ""),
                    licenceEnabled = obj.optBoolean("licence_enabled", false)
                )
                Result.success(config)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchRemoteConfig error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun validateLicence(key: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return@withContext Result.success(false)

        try {
            val url = "$baseUrl/rest/v1/rpc/validate_licence"
            val jsonPayload = JSONObject().apply {
                put("p_key", trimmed)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .post(jsonPayload.toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()?.trim() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: $body"))
                }
                val isValid = body.equals("true", ignoreCase = true)
                Result.success(isValid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "validateLicence error: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun fetchMacros(): Result<List<SupabaseMacro>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/macros?is_active=eq.true&order=created_at"
            val request = Request.Builder()
                .url(url)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: $body"))
                }
                val arr = JSONArray(body)
                val list = mutableListOf<SupabaseMacro>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        SupabaseMacro(
                            id = obj.optString("id", ""),
                            title = obj.optString("title", ""),
                            description = obj.optString("description", ""),
                            steps = obj.opt("steps")?.toString() ?: "[]",
                            isActive = obj.optBoolean("is_active", true)
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchMacros error: ${e.message}", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "MiraSupabaseClient"
    }
}

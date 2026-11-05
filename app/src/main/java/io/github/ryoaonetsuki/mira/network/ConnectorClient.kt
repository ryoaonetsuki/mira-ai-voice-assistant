package io.github.ryoaonetsuki.mira.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ConnectorClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun verifyGitHub(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/user")
                .header("Authorization", "Bearer ${token.trim()}")
                .header("User-Agent", "Mira-AI-App")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val login = json.optString("login", "User")
                    Result.success("Connected as @$login")
                } else {
                    Result.failure(Exception("GitHub rejected token (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyTelegram(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.telegram.org/bot${token.trim()}/getMe")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    if (json.optBoolean("ok")) {
                        val result = json.getJSONObject("result")
                        val username = result.optString("username", "Bot")
                        Result.success("Connected as @$username")
                    } else {
                        Result.failure(Exception(json.optString("description", "Invalid bot token")))
                    }
                } else {
                    Result.failure(Exception("Telegram error (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyNotion(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.notion.com/v1/users/me")
                .header("Authorization", "Bearer ${token.trim()}")
                .header("Notion-Version", "2022-06-28")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val name = json.optString("name", "Notion Workspace")
                    Result.success("Connected as $name")
                } else {
                    Result.failure(Exception("Notion rejected token (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyTodoist(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.todoist.com/rest/v2/projects")
                .header("Authorization", "Bearer ${token.trim()}")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success("Todoist Connected")
                } else {
                    Result.failure(Exception("Todoist rejected token (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyLinear(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val queryJson = JSONObject().put("query", "{ viewer { id name } }")
            val mediaType = "application/json".toMediaType()
            val request = Request.Builder()
                .url("https://api.linear.app/graphql")
                .header("Authorization", token.trim())
                .post(queryJson.toString().toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val name = json.optJSONObject("data")?.optJSONObject("viewer")?.optString("name", "Linear User") ?: "Linear User"
                    Result.success("Connected as $name")
                } else {
                    Result.failure(Exception("Linear rejected token (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyGeneric(name: String, token: String): Result<String> = withContext(Dispatchers.IO) {
        if (token.length >= 6) {
            Result.success("$name Connected")
        } else {
            Result.failure(Exception("Invalid $name token format"))
        }
    }
}

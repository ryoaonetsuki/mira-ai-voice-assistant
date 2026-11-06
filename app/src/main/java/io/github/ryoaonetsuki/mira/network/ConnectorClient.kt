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
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
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

    suspend fun verifyGitLab(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://gitlab.com/api/v4/user")
                .header("PRIVATE-TOKEN", token.trim())
                .header("User-Agent", "Mira-AI-App")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val username = json.optString("username", "GitLab User")
                    Result.success("Connected as @$username")
                } else {
                    Result.failure(Exception("GitLab rejected token (HTTP ${response.code})"))
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

    suspend fun detectTelegramChat(token: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.telegram.org/bot${token.trim()}/getUpdates")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    if (json.optBoolean("ok")) {
                        val resultArr = json.optJSONArray("result")
                        if (resultArr != null && resultArr.length() > 0) {
                            for (i in (resultArr.length() - 1) downTo 0) {
                                val update = resultArr.optJSONObject(i) ?: continue
                                val msg = update.optJSONObject("message")
                                    ?: update.optJSONObject("channel_post")
                                    ?: update.optJSONObject("edited_message")
                                val chat = msg?.optJSONObject("chat")
                                if (chat != null) {
                                    val chatId = chat.optLong("id").toString()
                                    val title = chat.optString("title", "")
                                    val firstName = chat.optString("first_name", "")
                                    val username = chat.optString("username", "")
                                    val name = when {
                                        title.isNotBlank() -> title
                                        firstName.isNotBlank() -> firstName
                                        username.isNotBlank() -> "@$username"
                                        else -> chatId
                                    }
                                    return@withContext Result.success(Pair(chatId, name))
                                }
                            }
                        }
                        Result.failure(Exception("No chat detected. Send a message to your Telegram bot first, then tap Detect chat."))
                    } else {
                        Result.failure(Exception(json.optString("description", "Telegram error")))
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

    suspend fun verifyVercel(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.vercel.com/v2/user")
                .header("Authorization", "Bearer ${token.trim()}")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val user = json.optJSONObject("user")
                    val username = user?.optString("username", user.optString("email", "Vercel User")) ?: "Vercel User"
                    Result.success("Connected as $username")
                } else {
                    Result.failure(Exception("Vercel rejected token (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyGoogleDrive(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/about?fields=user")
                .header("Authorization", "Bearer ${token.trim()}")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val user = json.optJSONObject("user")
                    val name = user?.optString("displayName", user.optString("emailAddress", "Google Drive User")) ?: "Google Drive User"
                    Result.success("Connected as $name")
                } else {
                    Result.failure(Exception("Google Drive rejected token (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyYouTube(key: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.googleapis.com/youtube/v3/search?part=snippet&q=mira&type=video&maxResults=1&key=${key.trim()}")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Result.success("YouTube API Connected")
                } else {
                    val errMsg = try {
                        JSONObject(body).optJSONObject("error")?.optString("message") ?: "HTTP ${response.code}"
                    } catch (_: Exception) {
                        "HTTP ${response.code}"
                    }
                    Result.failure(Exception("YouTube rejected key: $errMsg (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyGoogleMaps(key: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://maps.googleapis.com/maps/api/place/findplacefromtext/json?input=Mira&inputtype=textquery&fields=place_id&key=${key.trim()}")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val status = json.optString("status")
                    if (status == "OK" || status == "ZERO_RESULTS") {
                        Result.success("Google Places API Connected")
                    } else {
                        val errMsg = json.optString("error_message", "Status: $status")
                        Result.failure(Exception("Google Maps error: $errMsg"))
                    }
                } else {
                    Result.failure(Exception("Google Maps rejected key (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyYahooFinance(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://query1.finance.yahoo.com/v8/finance/chart/AAPL")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success("Yahoo Finance Connected (Live)")
                } else {
                    Result.failure(Exception("Yahoo Finance error (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyOpenMeteo(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.open-meteo.com/v1/forecast?latitude=23.8&longitude=90.4&current=temperature_2m")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success("Open-Meteo Connected (Live)")
                } else {
                    Result.failure(Exception("Open-Meteo error (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyGmail(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://gmail.googleapis.com/gmail/v1/users/me/profile")
                .header("Authorization", "Bearer ${token.trim()}")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val email = json.optString("emailAddress", "Gmail User")
                    Result.success("Connected as $email")
                } else {
                    Result.failure(Exception("Gmail rejected token (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

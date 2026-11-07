package io.github.ryoaonetsuki.mira.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object ConnectorActions {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // 1. GitHub: listRepos
    suspend fun listGitHubRepos(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/user/repos?per_page=5&sort=updated")
                .header("Authorization", "Bearer ${token.trim()}")
                .header("User-Agent", "Mira-AI-App")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val arr = JSONArray(body)
                    if (arr.length() == 0) return@withContext Result.success("No GitHub repositories found.")
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val name = obj.optString("name", "untitled")
                        val isPrivate = obj.optBoolean("private", false)
                        val visibility = if (isPrivate) "private" else "public"
                        list.add("• $name ($visibility)")
                    }
                    Result.success("Recent GitHub Repositories:\n" + list.joinToString("\n"))
                } else {
                    Result.failure(Exception("GitHub error (HTTP ${response.code}): $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 2. GitLab: listProjects
    suspend fun listGitLabProjects(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://gitlab.com/api/v4/projects?per_page=5&membership=true&order_by=updated_at")
                .header("PRIVATE-TOKEN", token.trim())
                .header("User-Agent", "Mira-AI-App")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val arr = JSONArray(body)
                    if (arr.length() == 0) return@withContext Result.success("No GitLab projects found.")
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val name = obj.optString("name_with_namespace", obj.optString("name", "untitled"))
                        val visibility = obj.optString("visibility", "internal")
                        list.add("• $name ($visibility)")
                    }
                    Result.success("GitLab Projects:\n" + list.joinToString("\n"))
                } else {
                    Result.failure(Exception("GitLab error (HTTP ${response.code}): $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 3. Todoist: listTasks
    suspend fun listTodoistTasks(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.todoist.com/rest/v2/tasks")
                .header("Authorization", "Bearer ${token.trim()}")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val arr = JSONArray(body)
                    if (arr.length() == 0) return@withContext Result.success("No active tasks in Todoist.")
                    val list = mutableListOf<String>()
                    val count = minOf(arr.length(), 5)
                    for (i in 0 until count) {
                        val obj = arr.getJSONObject(i)
                        val content = obj.optString("content", "task")
                        val dueObj = obj.optJSONObject("due")
                        val dueStr = dueObj?.optString("string")
                        val dueInfo = if (!dueStr.isNullOrBlank()) " [Due: $dueStr]" else ""
                        list.add("• $content$dueInfo")
                    }
                    Result.success("Todoist Tasks (first $count):\n" + list.joinToString("\n"))
                } else {
                    Result.failure(Exception("Todoist error (HTTP ${response.code}): $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 3. Todoist: createTask
    suspend fun createTodoistTask(token: String, content: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().put("content", content.trim())
            val mediaType = "application/json".toMediaType()
            val request = Request.Builder()
                .url("https://api.todoist.com/rest/v2/tasks")
                .header("Authorization", "Bearer ${token.trim()}")
                .post(jsonBody.toString().toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Result.success("Created Todoist task: \"${content.trim()}\"")
                } else {
                    Result.failure(Exception("Todoist task creation failed (HTTP ${response.code}): $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 4. Notion: searchNotes
    suspend fun searchNotionNotes(token: String, query: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject()
                .put("query", query.trim())
                .put("page_size", 5)
            val mediaType = "application/json".toMediaType()
            val request = Request.Builder()
                .url("https://api.notion.com/v1/search")
                .header("Authorization", "Bearer ${token.trim()}")
                .header("Notion-Version", "2022-06-28")
                .post(jsonBody.toString().toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val root = JSONObject(body)
                    val results = root.optJSONArray("results")
                    if (results == null || results.length() == 0) {
                        return@withContext Result.success("No matching Notion notes found for \"$query\".")
                    }
                    val list = mutableListOf<String>()
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        var title = "Untitled"
                        if (item.has("properties")) {
                            val props = item.getJSONObject("properties")
                            for (key in props.keys()) {
                                val propObj = props.optJSONObject(key) ?: continue
                                if (propObj.optString("type") == "title") {
                                    val titleArr = propObj.optJSONArray("title")
                                    if (titleArr != null && titleArr.length() > 0) {
                                        title = titleArr.getJSONObject(0).optString("plain_text", title)
                                    }
                                    break
                                }
                            }
                        } else if (item.has("title")) {
                            val titleArr = item.optJSONArray("title")
                            if (titleArr != null && titleArr.length() > 0) {
                                title = titleArr.getJSONObject(0).optString("plain_text", title)
                            }
                        }
                        list.add("• $title")
                    }
                    Result.success("Notion Search Results for \"$query\":\n" + list.joinToString("\n"))
                } else {
                    Result.failure(Exception("Notion error (HTTP ${response.code}): $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 5. Linear: listIssues
    suspend fun listLinearIssues(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val queryJson = JSONObject().put("query", "{ issues(first: 5) { nodes { title state { name } } } }")
            val mediaType = "application/json".toMediaType()
            val request = Request.Builder()
                .url("https://api.linear.app/graphql")
                .header("Authorization", token.trim())
                .post(queryJson.toString().toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val root = JSONObject(body)
                    val nodes = root.optJSONObject("data")?.optJSONObject("issues")?.optJSONArray("nodes")
                    if (nodes == null || nodes.length() == 0) {
                        return@withContext Result.success("No Linear issues found.")
                    }
                    val list = mutableListOf<String>()
                    for (i in 0 until nodes.length()) {
                        val issue = nodes.getJSONObject(i)
                        val title = issue.optString("title", "Untitled issue")
                        val state = issue.optJSONObject("state")?.optString("name", "Unknown") ?: "Unknown"
                        list.add("• [$state] $title")
                    }
                    Result.success("Linear Issues:\n" + list.joinToString("\n"))
                } else {
                    Result.failure(Exception("Linear error (HTTP ${response.code}): $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 6. Vercel: listProjects
    suspend fun listVercelProjects(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.vercel.com/v2/projects?limit=5")
                .header("Authorization", "Bearer ${token.trim()}")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val root = JSONObject(body)
                    val projects = root.optJSONArray("projects")
                    if (projects == null || projects.length() == 0) {
                        return@withContext Result.success("No Vercel projects found.")
                    }
                    val list = mutableListOf<String>()
                    for (i in 0 until projects.length()) {
                        val p = projects.getJSONObject(i)
                        val name = p.optString("name", "untitled")
                        val framework = p.optString("framework", "")
                        val frameworkStr = if (framework.isNotBlank()) " ($framework)" else ""
                        list.add("• $name$frameworkStr")
                    }
                    Result.success("Vercel Projects:\n" + list.joinToString("\n"))
                } else {
                    Result.failure(Exception("Vercel error (HTTP ${response.code}): $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 7. Google Drive: listFiles
    suspend fun listDriveFiles(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files?pageSize=5&fields=files(id,name,mimeType)")
                .header("Authorization", "Bearer ${token.trim()}")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val root = JSONObject(body)
                    val files = root.optJSONArray("files")
                    if (files == null || files.length() == 0) {
                        return@withContext Result.success("No files found in Google Drive.")
                    }
                    val list = mutableListOf<String>()
                    for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        val name = f.optString("name", "untitled")
                        val mime = f.optString("mimeType", "").substringAfterLast(".")
                        val mimeLabel = if (mime.isNotBlank()) " ($mime)" else ""
                        list.add("• $name$mimeLabel")
                    }
                    Result.success("Google Drive Files:\n" + list.joinToString("\n"))
                } else {
                    Result.failure(Exception("Google Drive error (HTTP ${response.code}): $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 8. YouTube: searchVideos
    suspend fun searchYouTubeVideos(key: String, query: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=5&q=$encodedQuery&key=${key.trim()}"
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val root = JSONObject(body)
                    val items = root.optJSONArray("items")
                    if (items == null || items.length() == 0) {
                        return@withContext Result.success("No YouTube videos found for \"$query\".")
                    }
                    val list = mutableListOf<String>()
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val snippet = item.optJSONObject("snippet")
                        val title = snippet?.optString("title", "Untitled") ?: "Untitled"
                        val channel = snippet?.optString("channelTitle", "Channel") ?: "Channel"
                        list.add("• $title (by $channel)")
                    }
                    Result.success("YouTube Results for \"$query\":\n" + list.joinToString("\n"))
                } else {
                    Result.failure(Exception("YouTube error (HTTP ${response.code}): $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 9. Telegram: sendMessage
    suspend fun sendTelegramMessage(token: String, chatId: String?, text: String): Result<String> = withContext(Dispatchers.IO) {
        if (chatId.isNullOrBlank()) {
            return@withContext Result.failure(Exception("No Telegram chat detected yet. Go to Settings -> Connectors -> Telegram and tap 'Detect chat'."))
        }
        try {
            val jsonBody = JSONObject()
                .put("chat_id", chatId.trim())
                .put("text", text.trim())
            val mediaType = "application/json".toMediaType()
            val request = Request.Builder()
                .url("https://api.telegram.org/bot${token.trim()}/sendMessage")
                .post(jsonBody.toString().toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val root = JSONObject(body)
                    if (root.optBoolean("ok")) {
                        Result.success("Telegram message sent: \"${text.trim()}\"")
                    } else {
                        Result.failure(Exception("Telegram error: ${root.optString("description", "Failed to send")}"))
                    }
                } else {
                    Result.failure(Exception("Telegram error (HTTP ${response.code}): $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 10. Google Maps/Places: searchPlace
    suspend fun searchGooglePlace(key: String, query: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://maps.googleapis.com/maps/api/place/findplacefromtext/json?input=$encoded&inputtype=textquery&fields=name,formatted_address&key=${key.trim()}"
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val root = JSONObject(body)
                    val candidates = root.optJSONArray("candidates")
                    if (candidates == null || candidates.length() == 0) {
                        return@withContext Result.success("No places found for \"$query\".")
                    }
                    val list = mutableListOf<String>()
                    for (i in 0 until candidates.length()) {
                        val c = candidates.getJSONObject(i)
                        val name = c.optString("name", "Place")
                        val address = c.optString("formatted_address", "")
                        val addrStr = if (address.isNotBlank()) " — $address" else ""
                        list.add("• $name$addrStr")
                    }
                    Result.success("Places matching \"$query\":\n" + list.joinToString("\n"))
                } else {
                    Result.failure(Exception("Google Places error (HTTP ${response.code}): $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 12. Open-Meteo: currentWeather
    suspend fun getOpenMeteoWeather(lat: Double = 23.8103, lon: Double = 90.4125): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m"
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val root = JSONObject(body)
                    val current = root.optJSONObject("current")
                        ?: return@withContext Result.failure(Exception("No current weather data in Open-Meteo response"))

                    val temp = current.optDouble("temperature_2m", 0.0)
                    val feelsLike = current.optDouble("apparent_temperature", temp)
                    val humidity = current.optInt("relative_humidity_2m", 0)
                    val wind = current.optDouble("wind_speed_10m", 0.0)
                    val weatherCode = current.optInt("weather_code", 0)

                    val weatherDesc = when (weatherCode) {
                        0 -> "Clear sky"
                        1, 2, 3 -> "Partly cloudy"
                        45, 48 -> "Fog"
                        51, 53, 55 -> "Drizzle"
                        61, 63, 65 -> "Rain"
                        71, 73, 75 -> "Snow"
                        80, 81, 82 -> "Rain showers"
                        95, 96, 99 -> "Thunderstorm"
                        else -> "Cloudy"
                    }

                    Result.success("Current Weather: $weatherDesc, ${temp}°C (feels like ${feelsLike}°C). Humidity: ${humidity}%, Wind: ${wind} km/h.")
                } else {
                    Result.failure(Exception("Open-Meteo error (HTTP ${response.code}): $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 13. Gmail: listRecentMessages
    suspend fun listGmailRecentMessages(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val listUrl = "https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=5"
            val listReq = Request.Builder()
                .url(listUrl)
                .header("Authorization", "Bearer ${token.trim()}")
                .build()

            client.newCall(listReq).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Gmail error (HTTP ${response.code}): $body"))
                }
                val root = JSONObject(body)
                val messages = root.optJSONArray("messages")
                if (messages == null || messages.length() == 0) {
                    return@withContext Result.success("No recent emails found in your Gmail inbox.")
                }

                val list = mutableListOf<String>()
                val count = minOf(messages.length(), 5)
                for (i in 0 until count) {
                    val msgId = messages.getJSONObject(i).optString("id")
                    if (msgId.isBlank()) continue

                    val detailUrl = "https://gmail.googleapis.com/gmail/v1/users/me/messages/$msgId?format=metadata&metadataHeaders=Subject&metadataHeaders=From"
                    val detailReq = Request.Builder()
                        .url(detailUrl)
                        .header("Authorization", "Bearer ${token.trim()}")
                        .build()

                    client.newCall(detailReq).execute().use { detailResp ->
                        if (detailResp.isSuccessful) {
                            val detailBody = detailResp.body?.string() ?: ""
                            val detailJson = JSONObject(detailBody)
                            var subject = "(No Subject)"
                            var from = "Unknown"
                            val headers = detailJson.optJSONObject("payload")?.optJSONArray("headers")
                            if (headers != null) {
                                for (h in 0 until headers.length()) {
                                    val header = headers.getJSONObject(h)
                                    val name = header.optString("name")
                                    if (name.equals("Subject", ignoreCase = true)) {
                                        subject = header.optString("value", subject)
                                    } else if (name.equals("From", ignoreCase = true)) {
                                        from = header.optString("value", from)
                                    }
                                }
                            }
                            list.add("• From: $from | Subject: $subject")
                        }
                    }
                }
                Result.success("Recent Gmail Messages:\n" + list.joinToString("\n"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 13. Gmail: sendEmail
    suspend fun sendGmailEmail(token: String, to: String, subject: String, bodyText: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val rfc822 = "To: ${to.trim()}\r\nSubject: ${subject.trim()}\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n${bodyText.trim()}"
            val encodedRaw = Base64.encodeToString(rfc822.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
            val jsonBody = JSONObject().put("raw", encodedRaw)
            val mediaType = "application/json".toMediaType()

            val request = Request.Builder()
                .url("https://gmail.googleapis.com/gmail/v1/users/me/messages/send")
                .header("Authorization", "Bearer ${token.trim()}")
                .post(jsonBody.toString().toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Result.success("Email successfully sent to ${to.trim()} with subject \"${subject.trim()}\"")
                } else {
                    Result.failure(Exception("Gmail send failed (HTTP ${response.code}): $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

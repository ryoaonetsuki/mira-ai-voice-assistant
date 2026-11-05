package io.github.ryoaonetsuki.mira.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val notes: String,
    val publishedAt: String,
    val downloadUrl: String
)

class GitHubReleaseClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun getLatestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/ryoaonetsuki/mira-ai-voice-assistant/releases/latest"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mira-AI-App")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val tagName = json.optString("tag_name", "v1.0.0")
                    val name = json.optString("name", "Mira AI Release")
                    val notes = json.optString("body", "Bug fixes and performance improvements.")
                    val publishedAt = json.optString("published_at", "")
                    val htmlUrl = json.optString("html_url", "https://github.com/ryoaonetsuki/mira-ai-voice-assistant/releases")

                    Result.success(
                        ReleaseInfo(
                            tagName = tagName,
                            name = name,
                            notes = notes,
                            publishedAt = publishedAt,
                            downloadUrl = htmlUrl
                        )
                    )
                } else if (response.code == 404) {
                    Result.success(
                        ReleaseInfo(
                            tagName = "v1.0.0",
                            name = "Mira AI v1.0.0 (Latest)",
                            notes = "Initial public build of Mira AI native Android assistant.",
                            publishedAt = "2026-11-05",
                            downloadUrl = "https://github.com/ryoaonetsuki/mira-ai-voice-assistant"
                        )
                    )
                } else {
                    Result.failure(Exception("HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

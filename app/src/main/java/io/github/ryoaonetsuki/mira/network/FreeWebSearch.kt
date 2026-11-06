package io.github.ryoaonetsuki.mira.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class WebSearchResult(
    val title: String,
    val snippet: String,
    val url: String
)

object FreeWebSearch {

    private const val TAG = "MiraFreeWebSearch"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Public SearXNG instances for live Google results without API keys
    private val searxngInstances = listOf(
        "https://searx.be",
        "https://search.bus-hit.me",
        "https://searx.work",
        "https://search.sapti.me"
    )

    suspend fun search(query: String, maxResults: Int = 5): List<WebSearchResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        // 1. Primary: Live Google results via open-source SearXNG
        try {
            val searxResults = searchSearxng(cleanQuery, maxResults)
            if (searxResults.isNotEmpty()) {
                Log.d(TAG, "SearXNG returned ${searxResults.size} Google results for: $cleanQuery")
                return@withContext searxResults
            }
        } catch (e: Exception) {
            Log.w(TAG, "SearXNG search failed: ${e.message}. Trying DDG Lite...")
        }

        // 2. Fallback: DuckDuckGo Lite
        try {
            val liteResults = searchDdgLite(cleanQuery, maxResults)
            if (liteResults.isNotEmpty()) {
                Log.d(TAG, "DDG Lite returned ${liteResults.size} results for: $cleanQuery")
                return@withContext liteResults
            }
        } catch (e: Exception) {
            Log.w(TAG, "DDG Lite search failed: ${e.message}. Trying DDG HTML...")
        }

        // 3. Fallback: DuckDuckGo HTML
        try {
            val htmlResults = searchDdgHtml(cleanQuery, maxResults)
            if (htmlResults.isNotEmpty()) {
                Log.d(TAG, "DDG HTML returned ${htmlResults.size} results for: $cleanQuery")
                return@withContext htmlResults
            }
        } catch (e: Exception) {
            Log.w(TAG, "DDG HTML search failed: ${e.message}. Trying Instant Answer API...")
        }

        // 4. Fallback: DuckDuckGo Instant Answer JSON API
        try {
            val apiResults = searchDdgApi(cleanQuery, maxResults)
            if (apiResults.isNotEmpty()) {
                Log.d(TAG, "DDG API returned ${apiResults.size} results for: $cleanQuery")
                return@withContext apiResults
            }
        } catch (e: Exception) {
            Log.w(TAG, "DDG Instant Answer API failed: ${e.message}")
        }

        Log.w(TAG, "All search backends returned empty for query: $cleanQuery")
        emptyList()
    }

    private fun searchSearxng(query: String, maxResults: Int): List<WebSearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        for (instance in searxngInstances) {
            try {
                val url = "$instance/search?q=$encoded&format=json&engines=google&language=en"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                val jsonStr = httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.string()
                } ?: continue

                val root = JSONObject(jsonStr)
                val resultsArr = root.optJSONArray("results") ?: continue
                val list = mutableListOf<WebSearchResult>()
                val seenUrls = mutableSetOf<String>()
                for (i in 0 until resultsArr.length()) {
                    if (list.size >= maxResults) break
                    val item = resultsArr.optJSONObject(i) ?: continue
                    val title = cleanHtml(item.optString("title", ""))
                    val rawUrl = item.optString("url", "")
                    val snippet = cleanHtml(item.optString("content", ""))
                    if (rawUrl.isBlank() || title.isBlank() || seenUrls.contains(rawUrl)) continue
                    seenUrls.add(rawUrl)
                    list.add(WebSearchResult(title = title, snippet = snippet.take(300), url = rawUrl))
                }
                if (list.isNotEmpty()) {
                    return list
                }
            } catch (e: Exception) {
                Log.w(TAG, "SearXNG instance $instance failed: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun searchDdgHtml(query: String, maxResults: Int): List<WebSearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encoded"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val html = httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string() ?: ""
        }
        if (html.isBlank()) return emptyList()

        return parseDdgHtml(html, maxResults)
    }

    private fun parseDdgHtml(html: String, maxResults: Int): List<WebSearchResult> {
        val results = mutableListOf<WebSearchResult>()
        val seenUrls = mutableSetOf<String>()

        // Anchor attribute order varies (href may come before class) and an
        // earlier unclosed anchor can swallow a later one, so match result-link
        // OPENING tags directly, then take the text up to the next </a>.
        val openTagPattern = Pattern.compile("<a\\b[^>]*class=[\"'][^\"']*result__a[^\"']*[\"'][^>]*>", Pattern.CASE_INSENSITIVE)
        val hrefPattern = Pattern.compile("href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val snippetPattern = Pattern.compile("<(?:a|div)[^>]*class=[\"'][^\"']*result__snippet[^\"']*[\"'][^>]*>([\\s\\S]*?)</(?:a|div)>", Pattern.CASE_INSENSITIVE)

        while (matcher.find() && results.size < maxResults) {
            val block = matcher.group(0) ?: continue
            val openMatcher = openTagPattern.matcher(block)
            if (openMatcher.find()) {
                val tagText = openMatcher.group(0) ?: ""
                val hrefMatcher = hrefPattern.matcher(tagText)
                if (!hrefMatcher.find()) continue
                val rawHref = hrefMatcher.group(1)?.trim() ?: ""
                val titleStart = openMatcher.end()
                val titleEnd = block.indexOf("</a>", titleStart).takeIf { it >= 0 } ?: continue
                val rawTitle = block.substring(titleStart, titleEnd)

                val snippetMatcher = snippetPattern.matcher(block)
                val rawSnippet = if (snippetMatcher.find()) snippetMatcher.group(1) ?: "" else ""

                val cleanUrl = extractCleanUrl(rawHref)
                val cleanTitle = cleanHtml(rawTitle)
                val cleanSnippet = cleanHtml(rawSnippet).take(300)

                if (cleanUrl.isNotBlank() && cleanTitle.isNotBlank() && seenUrls.add(cleanUrl.lowercase())) {
                    results.add(WebSearchResult(title = cleanTitle, snippet = cleanSnippet, url = cleanUrl))
                }
            }
        }

        // Fallback: simpler scan if block structure differed
        if (results.isEmpty()) {
            val openMatcher = openTagPattern.matcher(html)
            while (openMatcher.find() && results.size < maxResults) {
                val tagText = openMatcher.group(0) ?: ""
                val hrefMatcher = hrefPattern.matcher(tagText)
                if (!hrefMatcher.find()) continue
                val rawHref = hrefMatcher.group(1)?.trim() ?: ""
                val titleStart = openMatcher.end()
                val titleEnd = html.indexOf("</a>", titleStart).takeIf { it >= 0 } ?: continue
                val rawTitle = html.substring(titleStart, titleEnd)
                val cleanUrl = extractCleanUrl(rawHref)
                val cleanTitle = cleanHtml(rawTitle)
                if (cleanUrl.isNotBlank() && cleanTitle.isNotBlank() && seenUrls.add(cleanUrl.lowercase())) {
                    results.add(WebSearchResult(title = cleanTitle, snippet = "", url = cleanUrl))
                }
            }
        }

        return results
    }

    private fun searchDdgLite(query: String, maxResults: Int): List<WebSearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://lite.duckduckgo.com/lite/?q=$encoded"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html")
            .build()

        val html = httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string() ?: ""
        }
        if (html.isBlank()) return emptyList()

        val results = mutableListOf<WebSearchResult>()
        val seenUrls = mutableSetOf<String>()

        // Anchor attribute order varies (href comes before class in Lite HTML) and
        // an earlier unclosed anchor can swallow a later one, so match result-link
        // OPENING tags directly, then take the text up to the next </a>.
        val openTagPattern = Pattern.compile("<a\\b[^>]*class=[\"'][^\"']*result-link[^\"']*[\"'][^>]*>", Pattern.CASE_INSENSITIVE)
        val hrefPattern = Pattern.compile("href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val snippetPattern = Pattern.compile("<td[^>]*class=[\"'][^\"']*result-snippet[^\"']*[\"'][^>]*>([\\s\\S]*?)</td>", Pattern.CASE_INSENSITIVE)

        val openMatcher = openTagPattern.matcher(html)
        val snippetMatcher = snippetPattern.matcher(html)

        while (openMatcher.find() && results.size < maxResults) {
            val tagText = openMatcher.group(0) ?: ""
            val hrefMatcher = hrefPattern.matcher(tagText)
            if (!hrefMatcher.find()) continue
            val rawHref = hrefMatcher.group(1)?.trim() ?: ""
            val titleStart = openMatcher.end()
            val titleEnd = html.indexOf("</a>", titleStart).takeIf { it >= 0 } ?: continue
            val rawTitle = html.substring(titleStart, titleEnd)
            val rawSnippet = if (snippetMatcher.find()) snippetMatcher.group(1) ?: "" else ""

            val cleanUrl = extractCleanUrl(rawHref)
            val cleanTitle = cleanHtml(rawTitle)
            val cleanSnippet = cleanHtml(rawSnippet).take(300)

            if (cleanUrl.isNotBlank() && cleanTitle.isNotBlank() && seenUrls.add(cleanUrl.lowercase())) {
                results.add(WebSearchResult(title = cleanTitle, snippet = cleanSnippet, url = cleanUrl))
            }
        }

        return results
    }

    private fun searchDdgApi(query: String, maxResults: Int): List<WebSearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        val body = httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string() ?: ""
        }
        if (body.isBlank()) return emptyList()

        val results = mutableListOf<WebSearchResult>()
        val seenUrls = mutableSetOf<String>()

        val json = JSONObject(body)
        val abstractText = json.optString("AbstractText", "")
        val abstractUrl = json.optString("AbstractURL", "")
        val heading = json.optString("Heading", "")

        if (abstractText.isNotBlank() && abstractUrl.isNotBlank()) {
            val title = if (heading.isNotBlank()) heading else query
            seenUrls.add(abstractUrl.lowercase())
            results.add(WebSearchResult(title = title, snippet = abstractText.take(300), url = abstractUrl))
        }

        val related = json.optJSONArray("RelatedTopics")
        if (related != null) {
            for (i in 0 until related.length()) {
                if (results.size >= maxResults) break
                val item = related.optJSONObject(i) ?: continue
                val text = item.optString("Text", "")
                val firstUrl = item.optString("FirstURL", "")
                if (text.isNotBlank() && firstUrl.isNotBlank() && seenUrls.add(firstUrl.lowercase())) {
                    val title = text.take(60)
                    results.add(WebSearchResult(title = title, snippet = text.take(300), url = firstUrl))
                }
            }
        }

        return results
    }

    private fun extractCleanUrl(raw: String): String {
        return try {
            if (raw.contains("uddg=")) {
                val uddgParam = raw.substringAfter("uddg=").substringBefore("&")
                URLDecoder.decode(uddgParam, "UTF-8")
            } else if (raw.startsWith("//")) {
                "https:$raw"
            } else {
                raw
            }
        } catch (_: Exception) {
            raw
        }
    }

    private fun cleanHtml(raw: String): String {
        return raw
            .replace(Regex("<[^>]*>"), "")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun formatForModel(results: List<WebSearchResult>, dateLine: String): String {
        if (results.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("[Live web search — searched just now, $dateLine:\n")
        results.forEachIndexed { idx, res ->
            sb.append("${idx + 1}. ${res.title} — ${res.snippet} (${res.url})\n")
        }
        sb.append("Answer current facts ONLY from these fresh results. If they do not cover the question, say so briefly instead of guessing.]")
        return sb.toString()
    }
}

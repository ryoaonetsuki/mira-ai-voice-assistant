package io.github.ryoaonetsuki.mira.network

import android.content.Context
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import java.util.Locale

object ConnectorIntentHandler {

    fun isConnectorCommand(text: String): Boolean {
        val lower = text.trim().lowercase(Locale.ROOT)
        if (lower.isBlank()) return false

        return isWeatherCommand(lower) ||
                isStockCommand(lower) ||
                isTodoistAddCommand(lower) ||
                isTodoistListCommand(lower) ||
                isGitHubCommand(lower) ||
                isGitLabCommand(lower) ||
                isDriveCommand(lower) ||
                isLinearCommand(lower) ||
                isVercelCommand(lower) ||
                isNotionCommand(lower) ||
                isYouTubeCommand(lower) ||
                isTelegramCommand(lower) ||
                isMapsCommand(lower) ||
                isGmailListCommand(lower) ||
                isGmailSendCommand(lower)
    }

    suspend fun handleCommand(context: Context, text: String): String? {
        val lower = text.trim().lowercase(Locale.ROOT)
        if (lower.isBlank()) return null
        val securePrefs = SecurePreferences(context)

        // 1. Weather (Open-Meteo — no token needed)
        if (isWeatherCommand(lower)) {
            securePrefs.setConnectorLastUsed("open_meteo")
            val res = ConnectorActions.getOpenMeteoWeather()
            return if (res.isSuccess) res.getOrThrow() else "Weather error: ${res.exceptionOrNull()?.message}"
        }

        // 2. Stock / Market Quote (Yahoo Finance — no token needed)
        if (isStockCommand(lower)) {
            val symbol = extractStockSymbol(lower)
            if (symbol.isNotBlank()) {
                securePrefs.setConnectorLastUsed("yahoo_finance")
                val res = ConnectorActions.getYahooFinanceQuote(symbol)
                return if (res.isSuccess) res.getOrThrow() else "Finance error: ${res.exceptionOrNull()?.message}"
            }
        }

        // 3. Todoist: Add Task
        if (isTodoistAddCommand(lower)) {
            val token = securePrefs.getConnectorToken("todoist")
            if (token.isNullOrBlank()) {
                return "Todoist is not connected yet — connect it in Settings → Connectors first."
            }
            val content = extractTodoistTaskContent(text)
            if (content.isNotBlank()) {
                val res = ConnectorActions.createTodoistTask(token, content)
                return if (res.isSuccess) {
                    securePrefs.setConnectorLastUsed("todoist")
                    res.getOrThrow()
                } else {
                    "Todoist error: ${res.exceptionOrNull()?.message}"
                }
            }
        }

        // 4. Todoist: List Tasks
        if (isTodoistListCommand(lower)) {
            val token = securePrefs.getConnectorToken("todoist")
            if (token.isNullOrBlank()) {
                return "Todoist is not connected yet — connect it in Settings → Connectors first."
            }
            val res = ConnectorActions.listTodoistTasks(token)
            return if (res.isSuccess) {
                securePrefs.setConnectorLastUsed("todoist")
                res.getOrThrow()
            } else {
                "Todoist error: ${res.exceptionOrNull()?.message}"
            }
        }

        // 5. GitHub: List Repos
        if (isGitHubCommand(lower)) {
            val token = securePrefs.getConnectorToken("github")
            if (token.isNullOrBlank()) {
                return "GitHub is not connected yet — connect it in Settings → Connectors first."
            }
            val res = ConnectorActions.listGitHubRepos(token)
            return if (res.isSuccess) {
                securePrefs.setConnectorLastUsed("github")
                res.getOrThrow()
            } else {
                "GitHub error: ${res.exceptionOrNull()?.message}"
            }
        }

        // 6. GitLab: List Projects
        if (isGitLabCommand(lower)) {
            val token = securePrefs.getConnectorToken("gitlab")
            if (token.isNullOrBlank()) {
                return "GitLab is not connected yet — connect it in Settings → Connectors first."
            }
            val res = ConnectorActions.listGitLabProjects(token)
            return if (res.isSuccess) {
                securePrefs.setConnectorLastUsed("gitlab")
                res.getOrThrow()
            } else {
                "GitLab error: ${res.exceptionOrNull()?.message}"
            }
        }

        // 7. Google Drive: List Files
        if (isDriveCommand(lower)) {
            val token = securePrefs.getConnectorToken("drive")
            if (token.isNullOrBlank()) {
                return "Google Drive is not connected yet — connect it in Settings → Connectors first."
            }
            val res = ConnectorActions.listDriveFiles(token)
            return if (res.isSuccess) {
                securePrefs.setConnectorLastUsed("drive")
                res.getOrThrow()
            } else {
                "Google Drive error: ${res.exceptionOrNull()?.message}"
            }
        }

        // 8. Linear: List Issues
        if (isLinearCommand(lower)) {
            val token = securePrefs.getConnectorToken("linear")
            if (token.isNullOrBlank()) {
                return "Linear is not connected yet — connect it in Settings → Connectors first."
            }
            val res = ConnectorActions.listLinearIssues(token)
            return if (res.isSuccess) {
                securePrefs.setConnectorLastUsed("linear")
                res.getOrThrow()
            } else {
                "Linear error: ${res.exceptionOrNull()?.message}"
            }
        }

        // 9. Vercel: List Projects
        if (isVercelCommand(lower)) {
            val token = securePrefs.getConnectorToken("vercel")
            if (token.isNullOrBlank()) {
                return "Vercel is not connected yet — connect it in Settings → Connectors first."
            }
            val res = ConnectorActions.listVercelProjects(token)
            return if (res.isSuccess) {
                securePrefs.setConnectorLastUsed("vercel")
                res.getOrThrow()
            } else {
                "Vercel error: ${res.exceptionOrNull()?.message}"
            }
        }

        // 10. Notion: Search Notes
        if (isNotionCommand(lower)) {
            val token = securePrefs.getConnectorToken("notion")
            if (token.isNullOrBlank()) {
                return "Notion is not connected yet — connect it in Settings → Connectors first."
            }
            val query = extractNotionQuery(text)
            val res = ConnectorActions.searchNotionNotes(token, query)
            return if (res.isSuccess) {
                securePrefs.setConnectorLastUsed("notion")
                res.getOrThrow()
            } else {
                "Notion error: ${res.exceptionOrNull()?.message}"
            }
        }

        // 11. YouTube: Search Videos
        if (isYouTubeCommand(lower)) {
            val key = securePrefs.getConnectorToken("youtube")
            if (key.isNullOrBlank()) {
                return "YouTube is not connected yet — connect it in Settings → Connectors first."
            }
            val query = extractYouTubeQuery(text)
            val res = ConnectorActions.searchYouTubeVideos(key, query)
            return if (res.isSuccess) {
                securePrefs.setConnectorLastUsed("youtube")
                res.getOrThrow()
            } else {
                "YouTube error: ${res.exceptionOrNull()?.message}"
            }
        }

        // 12. Telegram: Send Message
        if (isTelegramCommand(lower)) {
            val token = securePrefs.getConnectorToken("telegram")
            if (token.isNullOrBlank()) {
                return "Telegram is not connected yet — connect it in Settings → Connectors first."
            }
            val chatId = securePrefs.getTelegramChatId()
            val msgText = extractTelegramMessage(text)
            val res = ConnectorActions.sendTelegramMessage(token, chatId, msgText)
            return if (res.isSuccess) {
                securePrefs.setConnectorLastUsed("telegram")
                res.getOrThrow()
            } else {
                res.exceptionOrNull()?.message ?: "Telegram message delivery failed."
            }
        }

        // 13. Google Maps/Places: Search Place
        if (isMapsCommand(lower)) {
            val key = securePrefs.getConnectorToken("maps")
            if (key.isNullOrBlank()) {
                return "Google Maps/Places is not connected yet — connect it in Settings → Connectors first."
            }
            val query = extractMapsQuery(text)
            val res = ConnectorActions.searchGooglePlace(key, query)
            return if (res.isSuccess) {
                securePrefs.setConnectorLastUsed("maps")
                res.getOrThrow()
            } else {
                "Google Maps error: ${res.exceptionOrNull()?.message}"
            }
        }

        // 14. Gmail: Check/List Recent Emails
        if (isGmailListCommand(lower)) {
            val token = securePrefs.getConnectorToken("gmail")
            if (token.isNullOrBlank()) {
                return "Gmail is not connected yet — connect it in Settings → Connectors first."
            }
            val res = ConnectorActions.listGmailRecentMessages(token)
            return if (res.isSuccess) {
                securePrefs.setConnectorLastUsed("gmail")
                res.getOrThrow()
            } else {
                "Gmail error: ${res.exceptionOrNull()?.message}"
            }
        }

        // 15. Gmail: Send Email
        if (isGmailSendCommand(lower)) {
            val token = securePrefs.getConnectorToken("gmail")
            if (token.isNullOrBlank()) {
                return "Gmail is not connected yet — connect it in Settings → Connectors first."
            }
            val (to, subject, body) = extractGmailSendParams(text)
            if (to.isNotBlank()) {
                val res = ConnectorActions.sendGmailEmail(token, to, subject, body)
                return if (res.isSuccess) {
                    securePrefs.setConnectorLastUsed("gmail")
                    res.getOrThrow()
                } else {
                    "Gmail send error: ${res.exceptionOrNull()?.message}"
                }
            }
        }

        return null
    }

    // --- Intent Matching Helpers ---

    private fun isWeatherCommand(lower: String): Boolean {
        return lower == "weather" ||
                lower == "আবহাওয়া" ||
                lower == "আজকের আবহাওয়া" ||
                lower.startsWith("weather in") ||
                lower.startsWith("what is the weather") ||
                lower.startsWith("how is the weather") ||
                lower.startsWith("current weather") ||
                lower.startsWith("weather forecast")
    }

    private fun isStockCommand(lower: String): Boolean {
        return lower.startsWith("stock price of") ||
                lower.startsWith("stock price") ||
                lower.startsWith("stock of") ||
                lower.startsWith("stock quote") ||
                lower.startsWith("share price of") ||
                lower.startsWith("price of ") ||
                lower.startsWith("quote for ") ||
                lower.startsWith("$")
    }

    private fun extractStockSymbol(lower: String): String {
        return lower
            .removePrefix("stock price of")
            .removePrefix("stock price")
            .removePrefix("stock of")
            .removePrefix("stock quote")
            .removePrefix("share price of")
            .removePrefix("price of")
            .removePrefix("quote for")
            .removePrefix("$")
            .trim()
            .split("\\s+".toRegex())
            .firstOrNull() ?: ""
    }

    private fun isTodoistAddCommand(lower: String): Boolean {
        return lower.startsWith("add a task") ||
                lower.startsWith("add task") ||
                lower.startsWith("create task") ||
                lower.startsWith("create a task") ||
                lower.startsWith("todoist add") ||
                lower.startsWith("নতুন টাস্ক") ||
                lower.startsWith("টাস্ক যোগ কর")
    }

    private fun extractTodoistTaskContent(text: String): String {
        val trimmed = text.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        val prefixes = listOf("add a task to", "add a task", "add task to", "add task", "create task", "create a task", "todoist add", "নতুন টাস্ক", "টাস্ক যোগ কর")
        for (p in prefixes) {
            if (lower.startsWith(p)) {
                return trimmed.substring(p.length).trim().removePrefix(":").trim()
            }
        }
        return trimmed
    }

    private fun isTodoistListCommand(lower: String): Boolean {
        return lower == "tasks" ||
                lower == "my tasks" ||
                lower == "আমার টাস্ক" ||
                lower.startsWith("list my tasks") ||
                lower.startsWith("list tasks") ||
                lower.startsWith("show my tasks") ||
                lower.startsWith("show tasks") ||
                lower.startsWith("todoist tasks")
    }

    private fun isGitHubCommand(lower: String): Boolean {
        return lower == "my github" ||
                lower == "রেপো দেখাও" ||
                lower == "আমার রেপো" ||
                lower.startsWith("list my repos") ||
                lower.startsWith("list repos") ||
                lower.startsWith("show my repos") ||
                lower.startsWith("show repos") ||
                lower.startsWith("github repos") ||
                lower.startsWith("my repositories") ||
                lower.startsWith("list repositories")
    }

    private fun isGitLabCommand(lower: String): Boolean {
        return lower == "gitlab projects" ||
                lower == "gitlab repos" ||
                lower.startsWith("list gitlab projects") ||
                lower.startsWith("show gitlab projects") ||
                lower.startsWith("list gitlab") ||
                lower.startsWith("show gitlab")
    }

    private fun isDriveCommand(lower: String): Boolean {
        return lower == "drive files" ||
                lower == "ড্রাইভ ফাইল দেখাও" ||
                lower.startsWith("list my drive files") ||
                lower.startsWith("list drive files") ||
                lower.startsWith("show my drive files") ||
                lower.startsWith("show drive files") ||
                lower.startsWith("google drive files")
    }

    private fun isLinearCommand(lower: String): Boolean {
        return lower == "linear issues" ||
                lower == "linear tasks" ||
                lower == "শো ইস্যু" ||
                lower.startsWith("list linear issues") ||
                lower.startsWith("list issues") ||
                lower.startsWith("show linear issues") ||
                lower.startsWith("show issues")
    }

    private fun isVercelCommand(lower: String): Boolean {
        return lower == "vercel projects" ||
                lower == "my vercel" ||
                lower.startsWith("list vercel projects") ||
                lower.startsWith("show vercel projects") ||
                lower.startsWith("list vercel") ||
                lower.startsWith("show vercel")
    }

    private fun isNotionCommand(lower: String): Boolean {
        return lower.startsWith("search my notes") ||
                lower.startsWith("search notes") ||
                lower.startsWith("notion search") ||
                lower.startsWith("search notion for") ||
                lower.startsWith("search notion") ||
                lower.startsWith("নোট খুঁজুন")
    }

    private fun extractNotionQuery(text: String): String {
        val trimmed = text.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        val prefixes = listOf("search my notes for", "search my notes", "search notes for", "search notes", "search notion for", "notion search for", "notion search", "নোট খুঁজুন")
        for (p in prefixes) {
            if (lower.startsWith(p)) {
                return trimmed.substring(p.length).trim().removePrefix(":").trim()
            }
        }
        return trimmed
    }

    private fun isYouTubeCommand(lower: String): Boolean {
        return lower.startsWith("search youtube for") ||
                lower.startsWith("search youtube") ||
                lower.startsWith("youtube search for") ||
                lower.startsWith("youtube search") ||
                lower.startsWith("find youtube video")
    }

    private fun extractYouTubeQuery(text: String): String {
        val trimmed = text.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        val prefixes = listOf("search youtube for", "search youtube", "youtube search for", "youtube search", "find youtube video for", "find youtube video")
        for (p in prefixes) {
            if (lower.startsWith(p)) {
                return trimmed.substring(p.length).trim().removePrefix(":").trim()
            }
        }
        return trimmed
    }

    private fun isTelegramCommand(lower: String): Boolean {
        return lower.startsWith("send a telegram message") ||
                lower.startsWith("send telegram message") ||
                lower.startsWith("send telegram") ||
                lower.startsWith("telegram message") ||
                lower.startsWith("টেলিগ্রাম মেসেজ")
    }

    private fun extractTelegramMessage(text: String): String {
        val trimmed = text.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        val prefixes = listOf("send a telegram message to say", "send a telegram message saying", "send a telegram message", "send telegram message saying", "send telegram message", "send telegram", "telegram message", "টেলিগ্রাম মেসেজ")
        for (p in prefixes) {
            if (lower.startsWith(p)) {
                return trimmed.substring(p.length).trim().removePrefix(":").trim()
            }
        }
        return trimmed
    }

    private fun isMapsCommand(lower: String): Boolean {
        return lower.startsWith("find place") ||
                lower.startsWith("search place") ||
                lower.startsWith("places search") ||
                lower.startsWith("where is") ||
                lower.startsWith("কোথায়")
    }

    private fun extractMapsQuery(text: String): String {
        val trimmed = text.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        val prefixes = listOf("find place", "search place for", "search place", "places search for", "places search", "where is", "কোথায়")
        for (p in prefixes) {
            if (lower.startsWith(p)) {
                return trimmed.substring(p.length).trim().removePrefix(":").trim()
            }
        }
        return trimmed
    }

    private fun isGmailListCommand(lower: String): Boolean {
        return lower == "email" ||
                lower == "emails" ||
                lower == "my email" ||
                lower == "my emails" ||
                lower == "মেইল চেক কর" ||
                lower == "আমার ইমেইল" ||
                lower.startsWith("check my email") ||
                lower.startsWith("check email") ||
                lower.startsWith("list recent messages") ||
                lower.startsWith("read email") ||
                lower.startsWith("read my email") ||
                lower.startsWith("unread email")
    }

    private fun isGmailSendCommand(lower: String): Boolean {
        return lower.startsWith("send an email to") ||
                lower.startsWith("send email to") ||
                lower.startsWith("email to")
    }

    private data class GmailSendArgs(val to: String, val subject: String, val body: String)

    private fun extractGmailSendParams(text: String): GmailSendArgs {
        val trimmed = text.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        val prefix = when {
            lower.startsWith("send an email to") -> "send an email to"
            lower.startsWith("send email to") -> "send email to"
            lower.startsWith("email to") -> "email to"
            else -> ""
        }
        val remainder = trimmed.substring(prefix.length).trim()
        val tokens = remainder.split("\\s+".toRegex())
        val to = tokens.firstOrNull() ?: ""
        val afterTo = remainder.removePrefix(to).trim()

        var subject = "Message from Mira AI"
        var body = afterTo

        if (afterTo.contains("with subject", ignoreCase = true)) {
            val parts = afterTo.split("with subject", ignoreCase = true, limit = 2)
            val subAndBody = parts.getOrNull(1)?.trim() ?: ""
            if (subAndBody.contains("body", ignoreCase = true)) {
                val bParts = subAndBody.split("body", ignoreCase = true, limit = 2)
                subject = bParts[0].trim()
                body = bParts.getOrNull(1)?.trim() ?: ""
            } else if (subAndBody.contains("saying", ignoreCase = true)) {
                val bParts = subAndBody.split("saying", ignoreCase = true, limit = 2)
                subject = bParts[0].trim()
                body = bParts.getOrNull(1)?.trim() ?: ""
            } else {
                subject = subAndBody
                body = subAndBody
            }
        } else if (afterTo.contains("saying", ignoreCase = true)) {
            val parts = afterTo.split("saying", ignoreCase = true, limit = 2)
            body = parts.getOrNull(1)?.trim() ?: ""
        }

        return GmailSendArgs(to = to, subject = subject.ifBlank { "Message from Mira AI" }, body = body.ifBlank { "Hello" })
    }
}

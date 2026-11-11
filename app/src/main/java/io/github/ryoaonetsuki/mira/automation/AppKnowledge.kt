package io.github.ryoaonetsuki.mira.automation

/**
 * Best-of-all (Mika): per-app usage knowledge as plain-text files.
 *
 * Mika (wangzan101/Mika, Apache-2.0) keeps one plain-text knowledge file
 * per app under `core/src/main/resources/knowledge/apps/<slug>/` — "add
 * usage knowledge for an app or website (plain text, no code needed)" — and
 * feeds the matching file to the planner so plans for that app use its real
 * buttons and flows instead of guessing. This is the PATTERN ported to Mira
 * (Mika's own knowledge text is in Chinese and app-specific to its users;
 * every note here was written for Mira). The files live under
 * `app/src/main/assets/automation/knowledge/<slug>.md`; adding one needs no
 * code change, only the slug mapping below.
 *
 * The reader is injected (`readAsset`) so the loader stays pure-JVM
 * testable: production passes the assets reader, tests pass a map.
 */
class AppKnowledge(private val readAsset: ((String) -> String?)? = null) {

    private fun load(path: String): String? {
        val asset = try {
            readAsset?.invoke(path)
        } catch (_: Throwable) {
            null
        }
        if (asset != null) return asset

        // Classpath fallback (for plain-JVM unit tests when assets/resources are on classpath)
        val stream = AppKnowledge::class.java.classLoader?.getResourceAsStream(path)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
            ?: AppKnowledge::class.java.getResourceAsStream("/$path")
        if (stream != null) {
            try {
                return stream.bufferedReader().use { it.readText() }
            } catch (_: Throwable) {}
        }

        // Filesystem candidate paths fallback (for unit tests running with cwd in repo root or app/)
        val candidates = listOf(
            java.io.File(path),
            java.io.File("src/main/assets", path),
            java.io.File("app/src/main/assets", path),
            java.io.File("../app/src/main/assets", path),
            java.io.File("src/test/resources", path),
            java.io.File("app/src/test/resources", path),
            java.io.File("../app/src/test/resources", path)
        )
        for (candidate in candidates) {
            if (candidate.isFile && candidate.exists()) {
                try {
                    return candidate.readText()
                } catch (_: Throwable) {}
            }
        }
        return null
    }

    /**
     * The usage note for the app a goal targets, or null when no note
     * exists. [target] is the canonical package or app name from
     * [MiraAutomationBridge.extractTargetApp].
     */
    fun noteForTarget(target: String?): String? {
        if (target.isNullOrBlank()) return null
        val slug = slugFor(target) ?: return null
        return load("automation/knowledge/$slug.md")
            ?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Shared search flow note for cross-app and in-app search actions.
     */
    fun searchFlowNote(): String? {
        return load("automation/knowledge/search.md")
            ?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Returns a concise summary of usage notes for the Live voice system prompt.
     */
    fun summaryForPrompt(): String {
        val notes = listOfNotNull(
            searchFlowNote()?.let { "### Cross-App Search Flow\n$it" },
            noteForTarget("facebook")?.let { "### Facebook Notes\n$it" },
            noteForTarget("youtube")?.let { "### YouTube Notes\n$it" }
        )
        return notes.joinToString("\n\n")
    }

    companion object {
        private val TARGET_TO_SLUG = mapOf(
            "com.google.android.youtube" to "youtube",
            "youtube" to "youtube",
            "com.zhiliaoapp.musically" to "tiktok",
            "tiktok" to "tiktok",
            "com.facebook.katana" to "facebook",
            "facebook" to "facebook",
            "com.whatsapp" to "whatsapp",
            "whatsapp" to "whatsapp",
            "com.android.chrome" to "chrome",
            "chrome" to "chrome",
            "com.google.android.gm" to "gmail",
            "gmail" to "gmail",
            "search" to "search",
        )

        fun slugFor(target: String): String? {
            val lower = target.trim().lowercase()
            if (lower.isEmpty()) return null
            TARGET_TO_SLUG[lower]?.let { return it }
            for ((key, slug) in TARGET_TO_SLUG) {
                if (lower.contains(key)) return slug
            }
            return null
        }
    }
}

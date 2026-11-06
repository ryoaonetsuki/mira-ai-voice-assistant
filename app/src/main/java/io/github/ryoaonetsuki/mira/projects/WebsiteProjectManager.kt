package io.github.ryoaonetsuki.mira.projects

import android.content.Context
import android.util.Log
import java.io.File

data class SavedProject(
    val name: String,
    val dir: File,
    val files: List<File>,
    val lastModified: Long
)

data class ProcessedChatResult(
    val displayText: String,
    val isWebsite: Boolean,
    val isCode: Boolean,
    val projectName: String? = null,
    val fileName: String? = null
)

object WebsiteProjectManager {
    private const val TAG = "WebsiteProjectManager"

    const val FILE_BUILDING_INSTRUCTION = """FILE & PROJECT CREATION SPECIFICATION:
When the user asks you to build, create, or write a website, web app, code file, or script:
0. RESEARCH FIRST, THEN THINK, THEN BUILD: Before writing any code, use the live web research supplied with this request to study how the best modern sites of this kind actually look in 2026 (layout, colour palette, typography, sections, real content, interactions). Think step-by-step: plan the pages, the visual system and the content first. Then build a genuinely good, premium, multi-page design — never a weak generic template, never placeholder text, never lorem ipsum. Every page must look designed, not assembled.
1. OVERVIEW: Start with a clean README-style overview (markdown heading e.g. '### Project Title' or '### Filename', short paragraph description, bullet points of key features or pages created).
2. FRIENDLY MESSAGE + ONE LINK: Next, write a warm, natural 1-2 line human message introducing the download/open link — NEVER provide a bare link. Vary your wording naturally in the user's language (default Bangla).
   Examples in English:
   - "Your website is ready — here's your download link:"
   - "Here's your portfolio, open it and see how it looks:"
   - "Your script is ready for you — open the file here:"
   Examples in Bangla:
   - "আপনার ওয়েবসাইট প্রস্তুত — দেখে নিন কেমন হয়েছে:"
   - "আপনার কোড ফাইলটি তৈরি হয়ে গেছে — এখানে ওপেন করে দেখুন:"
   - "আপনার পোর্টফোলিও তৈরি — দেখে নিন:"
   Immediately following your friendly message, provide ONE link on its own line:
   - For a website: [Open Website](mira://project/<project_name>)
   - For a single code file: [Open Code: <filename>](mira://file/<filename>)
3. FILE CONTENTS: Do NOT dump raw code into your conversational overview. Put all file contents at the very end enclosed in file delimiter blocks:
   <<<FILE:filename.ext>>>
   ...exact source code...
   <<<ENDFILE>>>
   For multi-page websites, include all necessary files (e.g. index.html, styles.css, script.js, about.html, etc.), each in its own <<<FILE:filename>>>...<<<ENDFILE>>> block.
4. FORMATTING: Use clean ChatGPT / README-style formatting (clear headings, concise paragraphs, clean lists). Never dump raw walls of text."""

    const val MULTI_PAGE_INSTRUCTION = FILE_BUILDING_INSTRUCTION

    /**
     * Checks if the given text contains file creation markers or HTML/code blocks.
     */
    fun hasFileOrProjectContent(content: String): Boolean {
        val fileMarker = Regex("(?:<<<FILE:|(?<!\\w)//\\s*FILE:|#\\s*FILE:|<!--\\s*FILE:|/\\*\\s*FILE:)")
        return fileMarker.containsMatchIn(content) ||
                content.contains("<!DOCTYPE html", ignoreCase = true) ||
                content.contains("<html", ignoreCase = true) ||
                (content.contains("```html", ignoreCase = true) && content.contains("</html>", ignoreCase = true))
    }

    /**
     * For live streaming: extracts whatever overview text has arrived so far,
     * and flags whether file building has started (so chat shows "Creating your file..." instead of code).
     */
    fun extractStreamingDisplay(accumulated: String, isBangla: Boolean = false): Pair<String, Boolean> {
        val cutoff = findFileCutoffIndex(accumulated)
        if (cutoff != -1) {
            val overview = accumulated.substring(0, cutoff).trim()
            return Pair(overview, true)
        }

        // Also check if a code fence starts and contains several lines
        val codeFenceIdx = accumulated.indexOf("```")
        if (codeFenceIdx != -1) {
            val after = accumulated.substring(codeFenceIdx)
            if (after.lines().size > 3) {
                val overview = accumulated.substring(0, codeFenceIdx).trim()
                return Pair(overview, true)
            }
        }

        return Pair(accumulated, false)
    }

    /**
     * Finds the earliest cutoff where file contents or code start.
     */
    fun findFileCutoffIndex(content: String): Int {
        val markers = listOf(
            "<<<FILE:",
            "// FILE:",
            "# FILE:",
            "<!-- FILE:",
            "/* FILE:",
            "<!DOCTYPE html",
            "```html"
        )
        var earliestIndex = -1
        for (m in markers) {
            val idx = content.indexOf(m, ignoreCase = true)
            if (idx != -1 && (earliestIndex == -1 || idx < earliestIndex)) {
                earliestIndex = idx
            }
        }
        return earliestIndex
    }

    /**
     * Maps language code / name to typical file extension.
     */
    fun extensionForLanguage(lang: String): String {
        return when (lang.lowercase().trim()) {
            "python", "py" -> "py"
            "kotlin", "kt" -> "kt"
            "java" -> "java"
            "javascript", "js" -> "js"
            "typescript", "ts" -> "ts"
            "html", "htm" -> "html"
            "css" -> "css"
            "c" -> "c"
            "cpp", "c++" -> "cpp"
            "csharp", "c#" -> "cs"
            "rust", "rs" -> "rs"
            "go", "golang" -> "go"
            "ruby", "rb" -> "rb"
            "php" -> "php"
            "swift" -> "swift"
            "bash", "sh", "shell" -> "sh"
            "sql" -> "sql"
            "json" -> "json"
            "xml" -> "xml"
            "yaml", "yml" -> "yaml"
            else -> "txt"
        }
    }

    /**
     * Extracts files from content using <<<FILE:filename>>>, // FILE: filename, or html blocks.
     */
    fun extractFilesFromContent(rawContent: String): Map<String, String> {
        val result = mutableMapOf<String, String>()

        // 1. Try <<<FILE:filename>>>...<<<ENDFILE>>>
        val tagPattern = Regex("<<<FILE:\\s*([a-zA-Z0-9_.-]+)\\s*>>>([\\s\\S]*?)(?:<<<ENDFILE>>>|(?=<<<FILE:)|\$)")
        val tagMatches = tagPattern.findAll(rawContent).toList()
        if (tagMatches.isNotEmpty()) {
            for (match in tagMatches) {
                val fileName = match.groupValues[1].trim()
                var code = match.groupValues[2].trim()
                if (code.startsWith("```")) {
                    val nl = code.indexOf('\n')
                    if (nl != -1) code = code.substring(nl + 1)
                }
                if (code.endsWith("```")) {
                    code = code.removeSuffix("```").trimEnd()
                }
                result[fileName] = code
            }
            return result
        }

        // 2. Try // FILE: filename or /* FILE: filename */ or # FILE: filename or <!-- FILE: filename -->
        val commentPattern = Regex("(?:^|\\n)(?:\\/\\/|#|<!--|/\\*)\\s*FILE:\\s*([a-zA-Z0-9_.-]+)(?:\\s*-->|\\s*\\*/)?\\s*\\n")
        val commentMatches = commentPattern.findAll(rawContent).toList()
        if (commentMatches.isNotEmpty()) {
            for (i in commentMatches.indices) {
                val fileName = commentMatches[i].groupValues[1].trim()
                val start = commentMatches[i].range.last + 1
                val end = if (i + 1 < commentMatches.size) commentMatches[i + 1].range.first else rawContent.length
                var chunk = rawContent.substring(start, end).trim()
                if (chunk.startsWith("```")) {
                    val nl = chunk.indexOf('\n')
                    if (nl != -1) chunk = chunk.substring(nl + 1)
                }
                if (chunk.endsWith("```")) {
                    chunk = chunk.removeSuffix("```").trimEnd()
                }
                result[fileName] = chunk
            }
            return result
        }

        // 3. Fallback: single HTML website block
        if (rawContent.contains("<!DOCTYPE html", ignoreCase = true) || rawContent.contains("```html", ignoreCase = true)) {
            var html = rawContent
            if (html.contains("```html", ignoreCase = true)) {
                html = html.substringAfter("```html").substringBefore("```")
            } else if (html.contains("<!DOCTYPE html", ignoreCase = true)) {
                val start = html.indexOf("<!DOCTYPE html", ignoreCase = true)
                val end = if (html.contains("</html>", ignoreCase = true)) {
                    html.indexOf("</html>", ignoreCase = true) + 7
                } else html.length
                html = html.substring(start, end)
            }
            result["index.html"] = html.trim()
            return result
        }

        // 4. Fallback: single code block
        val codeFenceRegex = Regex("```([a-zA-Z0-9_-]*)\\s*\\n([\\s\\S]*?)```")
        val codeMatch = codeFenceRegex.find(rawContent)
        if (codeMatch != null) {
            val lang = codeMatch.groupValues[1].ifBlank { "txt" }
            val code = codeMatch.groupValues[2].trim()
            val ext = extensionForLanguage(lang)
            val fileName = "script.$ext"
            result[fileName] = code
            return result
        }

        return result
    }

    /**
     * Parses the full LLM reply, extracts the files, saves them to both internal and
     * phone-accessible external storage (Mira/Projects/<name> or Mira/Files/), and returns
     * the clean chat text (overview + friendly message + ONE LINK) with ZERO dumped code.
     */
    fun processAndSaveGeneratedContent(
        context: Context,
        raw: String,
        language: String = "Bangla"
    ): ProcessedChatResult {
        val isBangla = language.contains("Bangla", ignoreCase = true) || language.contains("Bengali", ignoreCase = true)

        if (!hasFileOrProjectContent(raw)) {
            // Check if there is a substantial code block that should be saved as a code file
            val codeBlockRegex = Regex("```([a-zA-Z0-9_-]*)\\s*\\n([\\s\\S]*?)```")
            val codeMatch = codeBlockRegex.find(raw)
            if (codeMatch != null && codeMatch.groupValues[2].lines().size > 4) {
                val lang = codeMatch.groupValues[1].ifBlank { "txt" }
                val code = codeMatch.groupValues[2].trim()
                val ext = extensionForLanguage(lang)
                val fileName = "code_${System.currentTimeMillis().toString().takeLast(6)}.$ext"
                val overview = raw.substring(0, codeMatch.range.first).trim().ifBlank {
                    if (isBangla) "### সোর্স কোড ফাইল\nআপনার অনুরোধ অনুযায়ী কোড তৈরি করা হয়েছে।"
                    else "### Source Code File\nI've generated the requested code for you."
                }

                saveSingleCodeFile(context, fileName, code)

                val friendlyMsg = if (isBangla) {
                    "আপনার কোড ফাইলটি তৈরি হয়ে গেছে — এখানে ওপেন করে দেখুন:"
                } else {
                    "Here's your code file, open it and see how it looks:"
                }

                val link = "[Open Code: $fileName](mira://file/$fileName)"
                val cleanText = "$overview\n\n$friendlyMsg\n$link"

                return ProcessedChatResult(
                    displayText = cleanText,
                    isWebsite = false,
                    isCode = true,
                    fileName = fileName
                )
            }

            return ProcessedChatResult(
                displayText = raw,
                isWebsite = false,
                isCode = false
            )
        }

        val files = extractFilesFromContent(raw)
        val isWebsite = files.keys.any { it.endsWith(".html") || it.endsWith(".htm") } ||
                raw.contains("<!DOCTYPE html", ignoreCase = true)

        val cutoff = findFileCutoffIndex(raw)
        var overview = if (cutoff > 0) raw.substring(0, cutoff).trim() else ""

        if (isWebsite) {
            val existingProjectLink = Regex("\\[([^\\]]+)\\]\\(mira://project/([a-zA-Z0-9_-]+)\\)").find(raw)
            val projectName = existingProjectLink?.groupValues?.get(2)
                ?: "site_${System.currentTimeMillis().toString().takeLast(6)}"

            saveWebsiteProjectFiles(context, projectName, files, raw)

            if (overview.isBlank()) {
                overview = if (isBangla) "### ওয়েবসাইট প্রজেক্ট\nআপনার অনুরোধ অনুযায়ী একটি সম্পূর্ণ ওয়েবসাইট তৈরি করা হয়েছে।"
                else "### Website Project\nI've created your multi-page website project with all necessary files."
            }

            val hasProjectLink = overview.contains("mira://project/")
            val cleanOverview = if (hasProjectLink) {
                overview
            } else {
                val friendlyMsg = if (isBangla) {
                    "আপনার ওয়েবসাইট প্রস্তুত — দেখে নিন কেমন হয়েছে:"
                } else {
                    "Your website is ready — open it and see how it looks:"
                }
                val link = "[Open Website](mira://project/$projectName)"
                "$overview\n\n$friendlyMsg\n$link"
            }

            return ProcessedChatResult(
                displayText = cleanOverview,
                isWebsite = true,
                isCode = false,
                projectName = projectName
            )
        } else {
            val firstFileName = files.keys.firstOrNull() ?: "script_${System.currentTimeMillis().toString().takeLast(6)}.py"
            val firstCode = files[firstFileName] ?: ""

            saveSingleCodeFile(context, firstFileName, firstCode)

            if (overview.isBlank()) {
                overview = if (isBangla) "### সোর্স কোড ফাইল\nআপনার অনুরোধ অনুযায়ী কোড তৈরি করা হয়েছে।"
                else "### Source Code File\nI've generated the requested code for you."
            }

            val hasFileLink = overview.contains("mira://file/")
            val cleanOverview = if (hasFileLink) {
                overview
            } else {
                val friendlyMsg = if (isBangla) {
                    "আপনার কোড ফাইলটি তৈরি হয়ে গেছে — এখানে ওপেন করে দেখুন:"
                } else {
                    "Here's your code file, open it and see how it looks:"
                }
                val link = "[Open Code: $firstFileName](mira://file/$firstFileName)"
                "$overview\n\n$friendlyMsg\n$link"
            }

            return ProcessedChatResult(
                displayText = cleanOverview,
                isWebsite = false,
                isCode = true,
                fileName = firstFileName
            )
        }
    }

    private fun saveWebsiteProjectFiles(
        context: Context,
        projectName: String,
        files: Map<String, String>,
        rawContent: String
    ) {
        try {
            val sanitized = projectName.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "project_${System.currentTimeMillis()}" }
            val internalDir = File(File(context.filesDir, "projects"), sanitized).apply { if (!exists()) mkdirs() }
            val extBase = context.getExternalFilesDir(null)?.let { File(it, "Mira/Projects") }
            val externalDir = extBase?.let { File(it, sanitized) }?.apply { if (!exists()) mkdirs() }

            if (files.isEmpty()) {
                var html = rawContent
                if (html.contains("```html")) {
                    html = html.substringAfter("```html").substringBefore("```")
                } else if (html.contains("```")) {
                    html = html.substringAfter("```").substringBefore("```")
                }
                val indexFile = File(internalDir, "index.html")
                indexFile.writeText(html.trim())
                externalDir?.let { File(it, "index.html").writeText(html.trim()) }
            } else {
                for ((name, content) in files) {
                    File(internalDir, name).writeText(content)
                    externalDir?.let { File(it, name).writeText(content) }
                }
            }
            Log.i(TAG, "Saved website project '$sanitized' (${files.size} files) to internal & external storage")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving website project files: ${e.message}", e)
        }
    }

    private fun saveSingleCodeFile(
        context: Context,
        fileName: String,
        content: String
    ) {
        try {
            val sanitized = fileName.trim().replace(Regex("[^a-zA-Z0-9_.-]"), "_").ifBlank { "code_${System.currentTimeMillis()}.txt" }
            val internalDir = File(context.filesDir, "files").apply { if (!exists()) mkdirs() }
            File(internalDir, sanitized).writeText(content)

            val extDir = context.getExternalFilesDir(null)?.let { File(it, "Mira/Files") }?.apply { if (!exists()) mkdirs() }
            extDir?.let { File(it, sanitized).writeText(content) }
            Log.i(TAG, "Saved code file '$sanitized' to internal & external storage (Mira/Files)")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving single code file: ${e.message}", e)
        }
    }

    fun parseAndSaveProject(
        baseDir: File,
        projectName: String,
        rawContent: String,
        externalBaseDir: File? = null
    ): SavedProject {
        val sanitizedName = projectName.trim()
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .ifBlank { "project_${System.currentTimeMillis()}" }
        val projDir = File(baseDir, sanitizedName).apply { if (!exists()) mkdirs() }

        val files = extractFilesFromContent(rawContent)
        val savedFiles = mutableListOf<File>()

        if (files.isEmpty()) {
            var clean = rawContent
            if (clean.contains("```html")) {
                clean = clean.substringAfter("```html").substringBefore("```")
            } else if (clean.contains("```")) {
                clean = clean.substringAfter("```").substringBefore("```")
            }
            val indexFile = File(projDir, "index.html")
            indexFile.writeText(clean.trim())
            savedFiles.add(indexFile)
        } else {
            for ((name, content) in files) {
                val f = File(projDir, name)
                f.writeText(content)
                savedFiles.add(f)
            }
        }

        if (externalBaseDir != null) {
            try {
                val extDir = File(externalBaseDir, sanitizedName).apply { if (!exists()) mkdirs() }
                for (file in savedFiles) {
                    File(extDir, file.name).writeText(file.readText())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not copy project to external storage: ${e.message}")
            }
        }

        return SavedProject(
            name = sanitizedName,
            dir = projDir,
            files = savedFiles,
            lastModified = System.currentTimeMillis()
        )
    }

    fun listProjects(baseDir: File): List<SavedProject> {
        if (!baseDir.exists() || !baseDir.isDirectory) return emptyList()
        return baseDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val index = File(dir, "index.html")
            if (index.exists()) {
                val files = dir.listFiles()?.filter { it.isFile } ?: listOf(index)
                SavedProject(
                    name = dir.name,
                    dir = dir,
                    files = files,
                    lastModified = dir.lastModified()
                )
            } else null
        }?.sortedByDescending { it.lastModified } ?: emptyList()
    }
}

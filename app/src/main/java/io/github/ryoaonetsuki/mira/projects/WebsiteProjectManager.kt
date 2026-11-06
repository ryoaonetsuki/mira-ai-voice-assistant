package io.github.ryoaonetsuki.mira.projects

import java.io.File

data class SavedProject(
    val name: String,
    val dir: File,
    val files: List<File>,
    val lastModified: Long
)

object WebsiteProjectManager {
    const val MULTI_PAGE_INSTRUCTION = """When asked to build a website, project, or app, produce a complete, polished, multi-page site with multiple interconnected pages and assets (e.g. index.html, about.html, contact.html, styles.css, script.js).
Delimit every file with a marker on its own line:
// FILE: filename
For example:
// FILE: index.html
<!DOCTYPE html>
<html>
...
</html>

// FILE: styles.css
...

// FILE: script.js
...

// FILE: about.html
...
Make sure all relative links (<a href="about.html">, <link rel="stylesheet" href="styles.css">, <script src="script.js">) match the declared filenames."""

    fun parseAndSaveProject(baseDir: File, projectName: String, rawContent: String): SavedProject {
        val sanitizedName = projectName.trim()
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .ifBlank { "project_${System.currentTimeMillis()}" }
        val projDir = File(baseDir, sanitizedName).apply { if (!exists()) mkdirs() }

        val filePattern = Regex("(?:^|\\n)(?:\\/\\/|#|<!--|/\\*)\\s*FILE:\\s*([a-zA-Z0-9_.-]+)(?:\\s*-->|\\s*\\*/)?\\s*\\n")
        val matches = filePattern.findAll(rawContent).toList()

        val savedFiles = mutableListOf<File>()

        if (matches.isEmpty()) {
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
            for (i in matches.indices) {
                val fileName = matches[i].groupValues[1].trim()
                val start = matches[i].range.last + 1
                val end = if (i + 1 < matches.size) matches[i + 1].range.first else rawContent.length
                var chunk = rawContent.substring(start, end).trim()

                if (chunk.startsWith("```")) {
                    val nl = chunk.indexOf('\n')
                    if (nl != -1) chunk = chunk.substring(nl + 1)
                }
                if (chunk.endsWith("```")) {
                    chunk = chunk.removeSuffix("```").trimEnd()
                }

                val targetFile = File(projDir, fileName)
                targetFile.writeText(chunk)
                savedFiles.add(targetFile)
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

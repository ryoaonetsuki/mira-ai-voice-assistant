package io.github.ryoaonetsuki.mira.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class LocalProjectServer(
    private val baseDir: File,
    port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val rawUri = session.uri.trimStart('/')
        val file = if (rawUri.isEmpty()) {
            File(baseDir, "index.html")
        } else {
            val target = File(baseDir, rawUri)
            if (target.isDirectory) File(target, "index.html") else target
        }

        // Security check: must not escape baseDir
        try {
            val canonicalBase = baseDir.canonicalPath
            val canonicalTarget = file.canonicalPath
            if (!canonicalTarget.startsWith(canonicalBase)) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "403 Forbidden")
            }
        } catch (_: Exception) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "403 Forbidden")
        }

        if (!file.exists() || !file.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found: $rawUri")
        }

        val mimeType = getMimeType(file.name)
        return try {
            val fis: InputStream = FileInputStream(file)
            newChunkedResponse(Response.Status.OK, mimeType, fis)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "500 Internal Error: ${e.message}")
        }
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "txt" -> "text/plain; charset=utf-8"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val TAG = "LocalProjectServer"
        const val DEFAULT_PORT = 8080

        private var activeServer: LocalProjectServer? = null
        private var activePort: Int = DEFAULT_PORT
        private var activeDir: File? = null

        @Synchronized
        fun startServer(dir: File): String {
            if (activeServer != null && activeServer!!.isAlive && activeDir?.canonicalPath == dir.canonicalPath) {
                return "http://localhost:$activePort/"
            }
            stopServer()

            var port = DEFAULT_PORT
            for (attempt in 0..10) {
                try {
                    val server = LocalProjectServer(dir, port)
                    server.start(5000, false)
                    activeServer = server
                    activePort = port
                    activeDir = dir
                    Log.i(TAG, "Local server started on http://localhost:$port/ serving ${dir.absolutePath}")
                    return "http://localhost:$port/"
                } catch (e: Exception) {
                    Log.w(TAG, "Port $port unavailable, trying next: ${e.message}")
                    port++
                }
            }
            throw IllegalStateException("Could not find open port for local project server")
        }

        @Synchronized
        fun stopServer() {
            try {
                activeServer?.stop()
                Log.i(TAG, "Local server stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping server: ${e.message}")
            } finally {
                activeServer = null
                activeDir = null
            }
        }
    }
}

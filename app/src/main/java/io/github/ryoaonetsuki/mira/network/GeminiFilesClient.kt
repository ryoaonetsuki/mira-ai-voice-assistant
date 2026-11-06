package io.github.ryoaonetsuki.mira.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GeminiFilesClient handles file upload via the Gemini Files API (multipart)
 * and falls back to PdfRenderer pages-as-images if the Files API upload is not workable.
 * It also processes image downscaling/compression and local text file reading,
 * saving all uploaded files to Mira/Files/ (external) and files/ (internal).
 */
class GeminiFilesClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    /**
     * Uploads a file (such as a PDF) to the Gemini Files API.
     * POST https://generativelanguage.googleapis.com/upload/v1beta/files?key=$apiKey
     * Header: x-goog-api-key: $apiKey
     * Header: X-Goog-Upload-Protocol: multipart
     */
    suspend fun uploadFile(
        apiKey: String,
        fileBytes: ByteArray,
        mimeType: String,
        displayName: String
    ): Result<ChatAttachment> = withContext(Dispatchers.IO) {
        val cleanKey = normalizeApiKey(apiKey)
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(Exception("API key is blank for Gemini Files upload"))
        }

        try {
            val jsonType = "application/json; charset=utf-8".toMediaType()
            val fileMediaType = mimeType.toMediaType()

            val metadataJson = JSONObject().apply {
                put("file", JSONObject().apply {
                    put("displayName", displayName)
                })
            }

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addPart(
                    Headers.headersOf("Content-Disposition", "form-data; name=\"metadata\""),
                    metadataJson.toString().toRequestBody(jsonType)
                )
                .addPart(
                    Headers.headersOf("Content-Disposition", "form-data; name=\"file\"; filename=\"$displayName\""),
                    fileBytes.toRequestBody(fileMediaType)
                )
                .build()

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/upload/v1beta/files?key=$cleanKey")
                .header("x-goog-api-key", cleanKey)
                .header("X-Goog-Upload-Protocol", "multipart")
                .post(multipartBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val fileObj = json.optJSONObject("file") ?: json
                    var uri = fileObj.optString("uri", "").trim()
                    if (uri.isBlank()) {
                        val name = fileObj.optString("name", "").trim()
                        if (name.isNotBlank()) {
                            uri = if (name.startsWith("files/")) {
                                "https://generativelanguage.googleapis.com/v1beta/$name"
                            } else {
                                name
                            }
                        }
                    }

                    if (uri.isNotBlank()) {
                        Log.i(TAG, "Uploaded '$displayName' to Gemini Files API: $uri")
                        return@withContext Result.success(
                            ChatAttachment(
                                mimeType = mimeType,
                                fileUri = uri,
                                fileName = displayName
                            )
                        )
                    }
                    Result.failure(Exception("Gemini Files API upload succeeded but returned no URI: $body"))
                } else {
                    val errorMsg = try {
                        JSONObject(body).optJSONObject("error")?.optString("message") ?: body.take(200)
                    } catch (_: Exception) {
                        "HTTP ${response.code}"
                    }
                    Result.failure(Exception("Files API upload HTTP ${response.code}: $errorMsg"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gemini Files API upload error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Renders PDF pages as JPEG images via android.graphics.pdf.PdfRenderer.
     * Used as fallback when Files API upload is not workable or when using server-proxy.
     */
    fun renderPdfPagesToAttachments(pdfFile: File): List<ChatAttachment> {
        val attachments = mutableListOf<ChatAttachment>()
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val maxPages = minOf(renderer.pageCount, 10)
            for (i in 0 until maxPages) {
                val page = renderer.openPage(i)
                val scale = 1.5f
                val width = (page.width * scale).toInt().coerceIn(100, 1568)
                val height = (page.height * scale).toInt().coerceIn(100, 1568)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                bitmap.recycle()

                attachments.add(
                    ChatAttachment(
                        mimeType = "image/jpeg",
                        base64Data = base64,
                        fileName = "${pdfFile.nameWithoutExtension}_page_${i + 1}.jpg"
                    )
                )
            }
            Log.i(TAG, "Rendered ${attachments.size} pages from '${pdfFile.name}' as inline JPEG attachments")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render PDF with PdfRenderer: ${e.message}", e)
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
        return attachments
    }

    /**
     * Downscales/compresses image to max ~1568px and ~1MB base64.
     */
    fun processImageBytes(
        imageBytes: ByteArray,
        originalMimeType: String,
        fileName: String
    ): ChatAttachment {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, boundsOpts)

        val maxDimension = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
        var sampleSize = 1
        if (maxDimension > 1568) {
            sampleSize = 2
            while ((maxDimension / sampleSize) > 1568) {
                sampleSize *= 2
            }
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decodedBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOpts)
            ?: return ChatAttachment(
                mimeType = originalMimeType,
                base64Data = Base64.encodeToString(imageBytes, Base64.NO_WRAP),
                fileName = fileName
            )

        val currentMax = maxOf(decodedBitmap.width, decodedBitmap.height)
        val scaledBitmap = if (currentMax > 1568) {
            val factor = 1568f / currentMax
            val targetW = (decodedBitmap.width * factor).toInt().coerceAtLeast(1)
            val targetH = (decodedBitmap.height * factor).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decodedBitmap, targetW, targetH, true).also {
                if (it != decodedBitmap) decodedBitmap.recycle()
            }
        } else {
            decodedBitmap
        }

        var quality = 85
        var compressedBytes: ByteArray
        val isPng = originalMimeType.equals("image/png", ignoreCase = true)
        val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val targetMime = if (isPng) "image/png" else "image/jpeg"

        do {
            val baos = ByteArrayOutputStream()
            scaledBitmap.compress(format, quality, baos)
            compressedBytes = baos.toByteArray()
            quality -= 15
        } while (compressedBytes.size > 850_000 && quality >= 25 && format != Bitmap.CompressFormat.PNG)

        scaledBitmap.recycle()

        val base64 = Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
        return ChatAttachment(
            mimeType = targetMime,
            base64Data = base64,
            fileName = fileName
        )
    }

    /**
     * Reads text bytes locally as UTF-8 string for text parts.
     */
    fun processTextBytes(
        bytes: ByteArray,
        mimeType: String,
        fileName: String
    ): ChatAttachment {
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            String(bytes)
        }
        return ChatAttachment(
            mimeType = mimeType,
            textContent = text,
            fileName = fileName
        )
    }

    /**
     * Saves uploaded file to Mira/Files/ (external storage) and files/ (internal storage).
     */
    fun saveToMiraFiles(
        context: Context,
        fileName: String,
        bytes: ByteArray
    ): File {
        val sanitized = fileName.trim().replace(Regex("[^a-zA-Z0-9_.-]"), "_").ifBlank { "upload_${System.currentTimeMillis()}" }
        val internalDir = File(context.filesDir, "files").apply { if (!exists()) mkdirs() }
        val internalFile = File(internalDir, sanitized)
        try {
            internalFile.writeBytes(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing internal file: ${e.message}")
        }

        val extDir = context.getExternalFilesDir(null)?.let { File(it, "Mira/Files") }?.apply { if (!exists()) mkdirs() }
        val extFile = extDir?.let {
            File(it, sanitized).apply {
                try {
                    writeBytes(bytes)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed writing external file: ${e.message}")
                }
            }
        }
        Log.i(TAG, "Saved uploaded file '$sanitized' to Mira/Files")
        return extFile ?: internalFile
    }

    /**
     * Main handler for processing a picked file into Gemini ChatAttachment objects:
     * - Images: inlineData base64 (downscaled/compressed)
     * - PDF: Gemini Files API upload -> fileData, falling back to PdfRenderer pages-as-images
     * - Text/code files: read locally -> text part
     */
    suspend fun processPickedFile(
        context: Context,
        apiKey: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        useServerProxy: Boolean
    ): List<ChatAttachment> = withContext(Dispatchers.IO) {
        val savedFile = saveToMiraFiles(context, fileName, bytes)
        val lowerMime = mimeType.lowercase()

        when {
            lowerMime.startsWith("image/") -> {
                listOf(processImageBytes(bytes, mimeType, fileName))
            }
            lowerMime == "application/pdf" || fileName.endsWith(".pdf", ignoreCase = true) -> {
                val cleanKey = normalizeApiKey(apiKey)
                if (!useServerProxy && cleanKey.isNotBlank()) {
                    val uploadRes = uploadFile(cleanKey, bytes, "application/pdf", fileName)
                    if (uploadRes.isSuccess) {
                        val att = uploadRes.getOrNull()
                        if (att != null) return@withContext listOf(att)
                    } else {
                        Log.w(TAG, "Gemini Files API upload failed: ${uploadRes.exceptionOrNull()?.message}; falling back to PdfRenderer")
                    }
                } else {
                    Log.d(TAG, "Direct key unavailable or server-proxy mode; rendering PDF pages via PdfRenderer")
                }
                renderPdfPagesToAttachments(savedFile)
            }
            else -> {
                listOf(processTextBytes(bytes, mimeType, fileName))
            }
        }
    }

    companion object {
        private const val TAG = "GeminiFilesClient"

        /**
         * Restricted MIME types to verified-readable types only for Gemini API.
         */
        val READABLE_MIME_TYPES = arrayOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
            "text/plain",
            "text/markdown",
            "text/csv",
            "application/json",
            "text/html",
            "text/*",
            "application/pdf"
        )

        fun getFileName(context: Context, uri: Uri): String {
            var name: String? = null
            if (uri.scheme == "content") {
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            name = cursor.getString(nameIndex)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not resolve file name from content resolver: ${e.message}")
                }
            }
            if (name.isNullOrBlank()) {
                name = uri.lastPathSegment?.substringAfterLast('/') ?: "file_${System.currentTimeMillis()}"
            }
            return name!!
        }

        fun getMimeType(context: Context, uri: Uri, fileName: String): String {
            val crType = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
            if (!crType.isNullOrBlank()) return crType
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "pdf" -> "application/pdf"
                "txt" -> "text/plain"
                "md" -> "text/markdown"
                "csv" -> "text/csv"
                "json" -> "application/json"
                "html", "htm" -> "text/html"
                "kt", "kts" -> "text/x-kotlin"
                "java" -> "text/x-java"
                "py" -> "text/x-python"
                "js", "ts" -> "text/javascript"
                "css" -> "text/css"
                "xml" -> "text/xml"
                "c", "cpp", "h" -> "text/x-c"
                else -> "text/plain"
            }
        }
    }
}

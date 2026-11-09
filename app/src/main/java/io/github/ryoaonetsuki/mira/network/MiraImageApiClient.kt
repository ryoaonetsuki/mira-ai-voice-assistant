package io.github.ryoaonetsuki.mira.network

import android.content.Context
import io.github.ryoaonetsuki.mira.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class MiraImageResult {
    data class Success(
        val bytes: ByteArray,
        val contentType: String = "image/jpeg"
    ) : MiraImageResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Success
            return bytes.contentEquals(other.bytes) && contentType == other.contentType
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + contentType.hashCode()
            return result
        }
    }

    data class Failure(
        val message: String,
        val statusCode: Int? = null,
        val isAuthError: Boolean = false,
        val isQuotaOrRateLimit: Boolean = false,
        val cause: Throwable? = null
    ) : MiraImageResult()
}

/**
 * Client for the Cloudflare Workers AI image generation API backing Mira.
 *
 * Endpoint: POST https://mira-image-api.salman-dev.workers.dev/generate
 * Default model: FLUX.1-schnell (@cf/black-forest-labs/flux-1-schnell).
 * Schema rule: FLUX input schema accepts ONLY {prompt} (+ format for JSON).
 * No steps/seed/width/height are sent.
 */
class MiraImageApiClient(
    private val client: OkHttpClient = defaultHttpClient,
    private val baseUrl: String = DEFAULT_ENDPOINT,
    private val apiKeyProvider: () -> String? = { resolveDefaultApiKey() }
) {

    companion object {
        const val DEFAULT_ENDPOINT = "https://mira-image-api.salman-dev.workers.dev/generate"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        private fun resolveDefaultApiKey(): String? {
            return try {
                BuildConfig.MIRA_IMAGE_API_KEY.ifBlank { null }
            } catch (_: Throwable) {
                null
            }
        }
    }

    /**
     * Builds the JSON payload for FLUX.1-schnell.
     * Contains ONLY "prompt" and "format": "json".
     * Any extra parameter (steps, width, seed) is rejected by Workers AI schema with error 5006.
     */
    fun createRequestBody(prompt: String): String {
        val json = JSONObject()
        json.put("prompt", prompt.trim())
        json.put("format", "json")
        return json.toString()
    }

    /**
     * Builds the OkHttp Request with appropriate headers.
     * X-Api-Key is attached ONLY when a non-blank key is configured.
     */
    fun buildRequest(prompt: String): Request {
        val requestBody = createRequestBody(prompt).toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder()
            .url(baseUrl)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")

        val key = apiKeyProvider()?.trim()
        if (!key.isNullOrEmpty()) {
            builder.header("X-Api-Key", key)
        }

        return builder.build()
    }

    /**
     * Generates an image asynchronously with coroutine cancellation support.
     * Tapping 'stop' in the UI cancels the underlying OkHttp call immediately.
     */
    suspend fun generateImage(prompt: String): MiraImageResult {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) {
            return MiraImageResult.Failure("Prompt cannot be empty")
        }

        val request = buildRequest(trimmed)

        return try {
            val response = executeCancellableRequest(request)
            response.use { resp ->
                parseResponse(resp)
            }
        } catch (e: CancellationException) {
            // Rethrow so coroutine cancellation behaves correctly
            throw e
        } catch (e: Exception) {
            MiraImageResult.Failure(
                message = e.message ?: "Image generation network request failed",
                cause = e
            )
        }
    }

    private suspend fun executeCancellableRequest(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }
            })
        }

    private fun parseResponse(response: Response): MiraImageResult {
        val bodyString = try {
            response.body?.string().orEmpty()
        } catch (e: Exception) {
            return MiraImageResult.Failure(
                message = "Failed reading response body",
                statusCode = response.code,
                cause = e
            )
        }

        if (!response.isSuccessful) {
            val errorMessage = extractErrorMessage(bodyString, response.message)
            return MiraImageResult.Failure(
                message = errorMessage,
                statusCode = response.code,
                isAuthError = response.code == 401,
                isQuotaOrRateLimit = response.code == 429 || response.code == 502
            )
        }

        return try {
            val json = JSONObject(bodyString)
            val ok = json.optBoolean("ok", true)
            if (!ok) {
                val error = json.optString("error", "Image generation failed")
                return MiraImageResult.Failure(
                    message = error,
                    statusCode = response.code
                )
            }

            val base64Data = json.optString("image_base64")
            if (base64Data.isBlank()) {
                return MiraImageResult.Failure(
                    message = "Image data empty in API response",
                    statusCode = response.code
                )
            }

            val cleanBase64 = base64Data
                .substringAfter("base64,")
                .replace("\\s".toRegex(), "")

            val bytes = Base64.getDecoder().decode(cleanBase64)
            if (bytes.isEmpty()) {
                return MiraImageResult.Failure(
                    message = "Decoded image bytes are empty",
                    statusCode = response.code
                )
            }

            val contentType = json.optString("content_type", "image/jpeg")
            MiraImageResult.Success(bytes = bytes, contentType = contentType)
        } catch (e: Exception) {
            MiraImageResult.Failure(
                message = "Failed parsing image response: ${e.message}",
                statusCode = response.code,
                cause = e
            )
        }
    }

    private fun extractErrorMessage(body: String, fallback: String): String {
        if (body.isBlank()) return fallback.ifBlank { "HTTP request failed" }
        return try {
            val json = JSONObject(body)
            val err = json.optString("error")
            val detail = json.optString("detail")
            when {
                err.isNotBlank() && detail.isNotBlank() -> "$err: $detail"
                err.isNotBlank() -> err
                detail.isNotBlank() -> detail
                else -> fallback.ifBlank { "Request failed" }
            }
        } catch (_: Exception) {
            fallback.ifBlank { body.take(120) }
        }
    }
}

/**
 * Storage manager for caching and persisting generated images locally.
 */
object MiraImageStorage {
    private const val IMAGES_DIR = "generated_images"

    fun getImagesDirectory(context: Context): File {
        return File(context.filesDir, IMAGES_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    fun saveImage(context: Context, prompt: String, bytes: ByteArray): File {
        val dir = getImagesDirectory(context)
        val safePrompt = prompt.take(30)
            .replace(Regex("[^a-zA-Z0-9_]"), "_")
            .trim('_')
        val prefix = if (safePrompt.isNotBlank()) "flux_${safePrompt}_" else "flux_"
        val fileName = "${prefix}${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg"
        val file = File(dir, fileName)
        file.outputStream().use { out ->
            out.write(bytes)
            out.flush()
        }
        return file
    }
}

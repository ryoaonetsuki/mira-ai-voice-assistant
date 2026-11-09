package io.github.ryoaonetsuki.mira.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

class MiraImageApiClientTest {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun createMockClient(
        handler: (requestUrl: String, headers: Map<String, String>, bodyString: String) -> Response
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val headerMap = mutableMapOf<String, String>()
                for (name in req.headers.names()) {
                    headerMap[name] = req.header(name) ?: ""
                }
                val buffer = okio.Buffer()
                req.body?.writeTo(buffer)
                val bodyStr = buffer.readUtf8()
                handler(req.url.toString(), headerMap, bodyStr)
            })
            .build()
    }

    @Test
    fun testRequestBody_ContainsOnlyPromptAndFormatJson_NoStepsNoSeedNoWidthNoHeight() {
        val client = MiraImageApiClient(
            client = OkHttpClient(),
            baseUrl = "https://mira-image-api.salman-dev.workers.dev/generate",
            apiKeyProvider = { null }
        )

        val prompt = "a cute cat astronaut, digital art"
        val bodyString = client.createRequestBody(prompt)
        val json = JSONObject(bodyString)

        assertEquals("a cute cat astronaut, digital art", json.getString("prompt"))
        assertEquals("json", json.getString("format"))

        // Critical: FLUX.1-schnell Worker AI schema accepts ONLY prompt (+ format for worker JSON)
        // Extra fields like num_steps, steps, seed, width, height produce error 5006
        assertFalse("Must not contain 'steps'", json.has("steps"))
        assertFalse("Must not contain 'num_steps'", json.has("num_steps"))
        assertFalse("Must not contain 'seed'", json.has("seed"))
        assertFalse("Must not contain 'width'", json.has("width"))
        assertFalse("Must not contain 'height'", json.has("height"))
        assertEquals("Request body must contain exactly 2 keys: prompt and format", 2, json.length())
    }

    @Test
    fun testAuthHeader_WhenKeyConfigured_SendsXApiKeyHeader() = runBlocking {
        var capturedHeader: String? = null
        val okClient = createMockClient { _, headers, _ ->
            capturedHeader = headers["X-Api-Key"]
            Response.Builder()
                .request(okhttp3.Request.Builder().url("https://mira-image-api.salman-dev.workers.dev/generate").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"ok":true,"content_type":"image/jpeg","image_base64":"/9j/4AAQSkZJRg=="}""".toResponseBody(jsonMedia))
                .build()
        }

        val apiClient = MiraImageApiClient(
            client = okClient,
            baseUrl = "https://mira-image-api.salman-dev.workers.dev/generate",
            apiKeyProvider = { "coord-secret-key-xyz" }
        )

        val result = apiClient.generateImage("cat")
        assertTrue(result is MiraImageResult.Success)
        assertEquals("coord-secret-key-xyz", capturedHeader)
    }

    @Test
    fun testAuthHeader_WhenKeyEmptyOrNull_OmitsXApiKeyHeader() = runBlocking {
        var headerPresent = false
        val okClient = createMockClient { _, headers, _ ->
            headerPresent = headers.containsKey("X-Api-Key")
            Response.Builder()
                .request(okhttp3.Request.Builder().url("https://mira-image-api.salman-dev.workers.dev/generate").build())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("""{"ok":false,"error":"unauthorized"}""".toResponseBody(jsonMedia))
                .build()
        }

        // Test with empty string
        val apiClient = MiraImageApiClient(
            client = okClient,
            baseUrl = "https://mira-image-api.salman-dev.workers.dev/generate",
            apiKeyProvider = { "" }
        )

        val result = apiClient.generateImage("cat")
        assertFalse("Header X-Api-Key must NOT be sent when unconfigured", headerPresent)
        assertTrue("Must yield failure on 401", result is MiraImageResult.Failure)
        assertEquals(401, (result as MiraImageResult.Failure).statusCode)
    }

    @Test
    fun testGenerateImage_Success_DecodesBase64ToValidJpegBytes() = runBlocking {
        // JPEG magic bytes 0xFF, 0xD8, 0xFF, 0xE0, followed by some payload
        val expectedBytes = byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70)
        val base64Payload = Base64.getEncoder().encodeToString(expectedBytes)

        val okClient = createMockClient { _, _, _ ->
            Response.Builder()
                .request(okhttp3.Request.Builder().url("https://mira-image-api.salman-dev.workers.dev/generate").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """
                    {
                        "ok": true,
                        "model": "@cf/black-forest-labs/flux-1-schnell",
                        "width": 1024,
                        "height": 1024,
                        "steps": 4,
                        "content_type": "image/jpeg",
                        "image_base64": "$base64Payload"
                    }
                    """.trimIndent().toResponseBody(jsonMedia)
                )
                .build()
        }

        val apiClient = MiraImageApiClient(
            client = okClient,
            apiKeyProvider = { "test-key" }
        )

        val result = apiClient.generateImage("cyberpunk city")
        assertTrue(result is MiraImageResult.Success)
        val success = result as MiraImageResult.Success
        assertArrayEquals(expectedBytes, success.bytes)
        assertEquals("image/jpeg", success.contentType)
    }

    @Test
    fun testGenerateImage_Http401Unauthorized_ReturnsFriendlyFailure_DoesNotCrash() = runBlocking {
        val okClient = createMockClient { _, _, _ ->
            Response.Builder()
                .request(okhttp3.Request.Builder().url("https://mira-image-api.salman-dev.workers.dev/generate").build())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("""{"ok":false,"error":"unauthorized"}""".toResponseBody(jsonMedia))
                .build()
        }

        val apiClient = MiraImageApiClient(
            client = okClient,
            apiKeyProvider = { null }
        )

        val result = apiClient.generateImage("mountain sunrise")
        assertTrue(result is MiraImageResult.Failure)
        val failure = result as MiraImageResult.Failure
        assertEquals(401, failure.statusCode)
        assertTrue(failure.isAuthError)
        assertEquals("unauthorized", failure.message)
    }

    @Test
    fun testGenerateImage_Http502Error_ReturnsFriendlyFailure_DoesNotCrash() = runBlocking {
        val okClient = createMockClient { _, _, _ ->
            Response.Builder()
                .request(okhttp3.Request.Builder().url("https://mira-image-api.salman-dev.workers.dev/generate").build())
                .protocol(Protocol.HTTP_1_1)
                .code(502)
                .message("Bad Gateway")
                .body("""{"ok":false,"error":"image generation failed","detail":"rate limit reached"}""".toResponseBody(jsonMedia))
                .build()
        }

        val apiClient = MiraImageApiClient(
            client = okClient,
            apiKeyProvider = { "valid-key" }
        )

        val result = apiClient.generateImage("futuristic car")
        assertTrue(result is MiraImageResult.Failure)
        val failure = result as MiraImageResult.Failure
        assertEquals(502, failure.statusCode)
        assertTrue(failure.isQuotaOrRateLimit)
        assertTrue(failure.message.contains("image generation failed"))
    }

    @Test
    fun testGenerateImage_NetworkIOException_ReturnsFriendlyFailure_DoesNotCrash() = runBlocking {
        val okClient = createMockClient { _, _, _ ->
            throw IOException("Connection timeout to Cloudflare Workers")
        }

        val apiClient = MiraImageApiClient(
            client = okClient,
            apiKeyProvider = { "valid-key" }
        )

        val result = apiClient.generateImage("emerald forest")
        assertTrue(result is MiraImageResult.Failure)
        val failure = result as MiraImageResult.Failure
        assertTrue(failure.cause is IOException)
        assertTrue(failure.message.contains("Connection timeout"))
    }

    @Test
    fun testGenerateImage_Cancellation_AbortsInFlightCall() = runBlocking {
        val callCancelled = AtomicBoolean(false)
        val okClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val call = chain.call()
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    callCancelled.set(true)
                }
                if (call.isCanceled()) {
                    callCancelled.set(true)
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"ok":true,"image_base64":"/9j/4AAQSkZJRg=="}""".toResponseBody(jsonMedia))
                    .build()
            })
            .build()

        val apiClient = MiraImageApiClient(
            client = okClient,
            apiKeyProvider = { "test-key" }
        )

        var caughtCancellation = false
        val job = async {
            try {
                apiClient.generateImage("long running prompt")
            } catch (e: CancellationException) {
                caughtCancellation = true
                throw e
            }
        }

        delay(50)
        job.cancelAndJoin()

        assertTrue("CancellationException must be propagated when caller cancels", caughtCancellation)
    }
}

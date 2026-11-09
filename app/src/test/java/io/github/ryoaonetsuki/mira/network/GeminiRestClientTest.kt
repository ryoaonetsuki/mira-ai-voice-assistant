package io.github.ryoaonetsuki.mira.network

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GeminiRestClientTest {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun createMockClient(handler: (url: String, headers: Map<String, String>) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val headerMap = mutableMapOf<String, String>()
                for (name in req.headers.names()) {
                    headerMap[name] = req.header(name) ?: ""
                }
                handler(req.url.toString(), headerMap)
            })
            .build()
    }

    @Test
    fun testPagination_ModelOnPage2_Succeeds() = runBlocking {
        // Simulates the exact live root-cause scenario:
        // Page 1: 50 models, no Live model, nextPageToken present
        // Page 2: 11 models, models/gemini-3.8-live present with bidiGenerateContent
        val page1Models = JSONArray()
        for (i in 1..50) {
            val m = JSONObject()
            m.put("name", "models/gemini-flash-$i")
            m.put("supportedGenerationMethods", JSONArray().put("generateContent"))
            page1Models.put(m)
        }
        val page1Body = JSONObject().apply {
            put("models", page1Models)
            put("nextPageToken", "page2_token")
        }.toString()

        val page2Models = JSONArray()
        val liveModel = JSONObject().apply {
            put("name", "models/gemini-3.8-live")
            val methods = JSONArray().apply {
                put("bidiGenerateContent")
            }
            put("supportedGenerationMethods", methods)
        }
        page2Models.put(liveModel)
        val page2Body = JSONObject().apply {
            put("models", page2Models)
        }.toString()

        val mockClient = createMockClient { url, headers ->
            assertEquals("Valid key header must be passed", "AIzaSyTestKey123456789", headers["x-goog-api-key"])
            val body = if (url.contains("pageToken=page2_token")) page2Body else page1Body
            Response.Builder()
                .code(200)
                .message("OK")
                .protocol(Protocol.HTTP_1_1)
                .body(body.toResponseBody(jsonMedia))
                .request(okhttp3.Request.Builder().url(url).build())
                .build()
        }

        val restClient = GeminiRestClient(validationClient = mockClient)
        val outcome = restClient.checkLiveCapability("AIzaSyTestKey123456789", "models/gemini-3.8-live")

        assertTrue("Outcome must be capable when found on page 2", outcome.isCapable)
        assertTrue("Message must indicate model is listed", outcome.message.contains("is listed"))
        assertTrue("Message must mention bidiGenerateContent", outcome.message.contains("bidiGenerateContent"))
    }

    @Test
    fun testPagination_ModelAbsentAcrossAllPages_ReturnsNotListed() = runBlocking {
        val page1Models = JSONArray()
        for (i in 1..50) {
            val m = JSONObject().apply {
                put("name", "models/gemini-standard-$i")
                put("supportedGenerationMethods", JSONArray().put("generateContent"))
            }
            page1Models.put(m)
        }
        val page1Body = JSONObject().apply {
            put("models", page1Models)
            put("nextPageToken", "token_page_2")
        }.toString()

        val page2Models = JSONArray()
        for (i in 51..60) {
            val m = JSONObject().apply {
                put("name", "models/gemini-other-$i")
                put("supportedGenerationMethods", JSONArray().put("generateContent"))
            }
            page2Models.put(m)
        }
        val page2Body = JSONObject().apply {
            put("models", page2Models)
        }.toString()

        val mockClient = createMockClient { url, _ ->
            val body = if (url.contains("pageToken=token_page_2")) page2Body else page1Body
            Response.Builder()
                .code(200)
                .message("OK")
                .protocol(Protocol.HTTP_1_1)
                .body(body.toResponseBody(jsonMedia))
                .request(okhttp3.Request.Builder().url(url).build())
                .build()
        }

        val restClient = GeminiRestClient(validationClient = mockClient)
        val outcome = restClient.checkLiveCapability("AIzaSyTestKey123456789", "models/gemini-3.8-live")

        assertFalse("Outcome must be false when model is absent", outcome.isCapable)
        assertTrue("Message must state model is not listed", outcome.message.contains("is not listed"))
    }

    @Test
    fun testModelListedWithoutBidi_ReturnsDoesNotSupport() = runBlocking {
        val models = JSONArray()
        val modelObj = JSONObject().apply {
            put("name", "models/gemini-3.8-live")
            put("supportedGenerationMethods", JSONArray().put("generateContent")) // missing bidiGenerateContent
        }
        models.put(modelObj)
        val body = JSONObject().apply {
            put("models", models)
        }.toString()

        val mockClient = createMockClient { url, _ ->
            Response.Builder()
                .code(200)
                .message("OK")
                .protocol(Protocol.HTTP_1_1)
                .body(body.toResponseBody(jsonMedia))
                .request(okhttp3.Request.Builder().url(url).build())
                .build()
        }

        val restClient = GeminiRestClient(validationClient = mockClient)
        val outcome = restClient.checkLiveCapability("AIzaSyTestKey123456789", "models/gemini-3.8-live")

        assertFalse("Outcome must be false without bidiGenerateContent", outcome.isCapable)
        assertTrue("Message must state model does not support Live voice", outcome.message.contains("does not support Live voice"))
    }

    @Test
    fun testClassification_AuthErrors_401_403_400() = runBlocking {
        val testCodes = listOf(401, 403, 400)
        for (code in testCodes) {
            val mockClient = createMockClient { url, _ ->
                Response.Builder()
                    .code(code)
                    .message("Auth Error")
                    .protocol(Protocol.HTTP_1_1)
                    .body("{\"error\": {\"message\": \"API_KEY_INVALID\"}}".toResponseBody(jsonMedia))
                    .request(okhttp3.Request.Builder().url(url).build())
                    .build()
            }
            val restClient = GeminiRestClient(validationClient = mockClient)
            val outcome = restClient.checkLiveCapability("AIzaSyTestKey123456789")

            assertFalse("Auth error must result in isCapable=false", outcome.isCapable)
            assertTrue("Message must name HTTP code ($code)", outcome.message.contains("HTTP $code"))
            assertTrue("Message must direct user to Settings", outcome.message.contains("Settings"))
            assertFalse("Must NEVER blanket label as 'Not Supported'", outcome.message.contains("Not Supported"))
        }
    }

    @Test
    fun testClassification_TransientErrors_503_429() = runBlocking {
        val testCodes = listOf(503, 429, 500, 502, 504)
        for (code in testCodes) {
            val mockClient = createMockClient { url, _ ->
                Response.Builder()
                    .code(code)
                    .message("Server Busy")
                    .protocol(Protocol.HTTP_1_1)
                    .body("{\"error\": {\"message\": \"Overloaded\"}}".toResponseBody(jsonMedia))
                    .request(okhttp3.Request.Builder().url(url).build())
                    .build()
            }
            val restClient = GeminiRestClient(validationClient = mockClient)
            val outcome = restClient.checkLiveCapability("AIzaSyTestKey123456789")

            assertFalse("Transient error must result in isCapable=false", outcome.isCapable)
            assertTrue("Message must mention servers are busy", outcome.message.contains("busy"))
            assertTrue("Message must include HTTP code ($code)", outcome.message.contains("HTTP $code"))
            assertFalse("Must NEVER blanket label as 'Not Supported'", outcome.message.contains("Not Supported"))
        }
    }

    @Test
    fun testClassification_NetworkError_IOException() = runBlocking {
        val mockClient = createMockClient { _, _ ->
            throw IOException("Failed to connect to host generativelanguage.googleapis.com")
        }
        val restClient = GeminiRestClient(validationClient = mockClient)
        val outcome = restClient.checkLiveCapability("AIzaSyTestKey123456789")

        assertFalse("Network failure must result in isCapable=false", outcome.isCapable)
        assertTrue("Message must mention network error", outcome.message.contains("Network error"))
        assertFalse("Must NEVER blanket label as 'Not Supported'", outcome.message.contains("Not Supported"))
    }

    @Test
    fun testCancellationException_propagatesOutOfSingleAttemptCall_neverSwallowed() = runBlocking {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { _ ->
                throw kotlinx.coroutines.CancellationException("Simulated coroutine cancellation during HTTP call")
            })
            .build()

        val restClient = GeminiRestClient()
        var exceptionThrown: Throwable? = null
        try {
            restClient.executeGenerateContentSingleAttempt(
                apiKey = "AIzaSyTestKey123",
                history = emptyList(),
                systemPrompt = "",
                model = "gemini-3.8-flash",
                httpClient = mockClient
            )
        } catch (e: Throwable) {
            exceptionThrown = e
        }

        assertNotNull("CancellationException must propagate out of single attempt call", exceptionThrown)
        assertTrue(
            "Expected CancellationException to propagate but was: ${exceptionThrown?.javaClass?.name}",
            exceptionThrown is kotlinx.coroutines.CancellationException
        )
    }

    @Test
    fun testCoroutineCancellation_cancelsCallAndPropagatesException() = runBlocking {
        val gate = java.util.concurrent.CountDownLatch(1)
        val mockClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                gate.countDown()
                try {
                    Thread.sleep(3000L)
                } catch (_: InterruptedException) {}
                chain.proceed(chain.request())
            })
            .build()

        val restClient = GeminiRestClient()
        var caughtCancellation = false
        val job = kotlinx.coroutines.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                restClient.executeGenerateContentSingleAttempt(
                    apiKey = "AIzaSyTestKey123",
                    history = emptyList(),
                    systemPrompt = "",
                    model = "gemini-3.8-flash",
                    httpClient = mockClient
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                caughtCancellation = true
                throw e
            }
        }

        gate.await(2, java.util.concurrent.TimeUnit.SECONDS)
        job.cancel()
        job.join()
        assertTrue("CancellationException must propagate out when coroutine is cancelled", caughtCancellation)
    }
}

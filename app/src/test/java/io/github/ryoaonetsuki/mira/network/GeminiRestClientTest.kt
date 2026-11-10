package io.github.ryoaonetsuki.mira.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        val job = launch(Dispatchers.IO) {
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

    // =========================================================================
    // Round 25 Fix Turn F — Automation Think: fast, single-attempt, bounded
    // =========================================================================

    @Test
    fun testAutomationThink_SingleAttemptLiteModel_NoLadderNoRetry() = runBlocking {
        var callCount = 0
        var seenUrl = ""
        val mockClient = createMockClient { url, _ ->
            callCount++
            seenUrl = url
            Response.Builder()
                .code(429)
                .message("Too Many Requests")
                .protocol(Protocol.HTTP_1_1)
                .body("{\"error\": {\"message\": \"Resource exhausted\"}}".toResponseBody(jsonMedia))
                .request(okhttp3.Request.Builder().url(url).build())
                .build()
        }

        val restClient = GeminiRestClient()
        val result = restClient.generateAutomationThink(
            apiKey = "AIzaSyTestKey123",
            history = emptyList(),
            systemPrompt = "",
            httpClient = mockClient
        )

        assertTrue("Automation think on 429 must fail fast, got: $result", result.isFailure)
        assertEquals("Automation think must make exactly ONE attempt (no ladder, no retry)", 1, callCount)
        assertTrue("Automation think must use the fastest model first, url was: $seenUrl", seenUrl.contains("gemini-3.5-flash-lite"))
    }

    @Test
    fun testThinkClient_DefaultCallTimeout_Is40s() {
        val restClient = GeminiRestClient()
        assertEquals(
            "Production think client must bound a hung call at 40s, inside the 45s think deadline",
            40_000,
            restClient.thinkClient.callTimeoutMillis()
        )
        assertEquals(
            "Automation think model must be the fastest suitable model",
            "gemini-3.5-flash-lite",
            GeminiRestClient.AUTOMATION_THINK_MODEL
        )
    }

    /** Minimal local HTTP server that sends a partial 200 response, then stalls mid-body. */
    private class StalledServer {
        private val serverSocket = java.net.ServerSocket(0)
        val port: Int get() = serverSocket.localPort
        private val executor = java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r).apply { isDaemon = true }
        }
        @Volatile private var running = true

        fun start() {
            executor.execute {
                while (running) {
                    try {
                        val socket = serverSocket.accept()
                        executor.execute { handle(socket) }
                    } catch (_: IOException) {
                        break
                    }
                }
            }
        }

        private fun handle(socket: java.net.Socket) {
            try {
                socket.soTimeout = 20_000
                val reader = socket.getInputStream().bufferedReader()
                var line: String?
                do {
                    line = reader.readLine()
                } while (line != null && line.isNotEmpty())
                val out = socket.getOutputStream()
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: 1000\r\nConnection: close\r\n\r\n{\"candidates\": [")
                        .toByteArray()
                )
                out.flush()
                Thread.sleep(12_000L)
            } catch (_: Throwable) {
                // Client closed the socket (callTimeout) or test ended — expected.
            } finally {
                try { socket.close() } catch (_: Throwable) {}
            }
        }

        fun stop() {
            running = false
            try { serverSocket.close() } catch (_: Throwable) {}
            executor.shutdownNow()
        }
    }

    @Test
    fun testAutomationThink_HungCall_BoundedByCallTimeout() = runBlocking {
        val server = StalledServer()
        server.start()
        try {
            val rewriteToLocal = Interceptor { chain ->
                val req = chain.request()
                val local = req.url.newBuilder().scheme("http").host("127.0.0.1").port(server.port).build()
                chain.proceed(req.newBuilder().url(local).build())
            }

            // FAIL-ON-OLD: a blocking section inside the think call (here a
            // blocking interceptor, standing in for the blocking body read)
            // is NOT killed by the coroutine deadline — the call is still
            // alive after it. This is the hole the 40s callTimeout plugs.
            val blockingClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { _ ->
                    Thread.sleep(4_000L)
                    Response.Builder()
                        .code(200)
                        .message("OK")
                        .protocol(Protocol.HTTP_1_1)
                        .body("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"action\\\": \\\"done\\\"}\"}]}}]}".toResponseBody(jsonMedia))
                        .request(okhttp3.Request.Builder().url("http://127.0.0.1/").build())
                        .build()
                })
                .build()
            val restClient = GeminiRestClient()
            val hung = kotlinx.coroutines.withTimeoutOrNull(1_500L) {
                restClient.executeGenerateContentSingleAttempt(
                    apiKey = "AIzaSyTestKey123",
                    history = emptyList(),
                    systemPrompt = "",
                    model = "gemini-3.5-flash-lite",
                    httpClient = blockingClient
                )
            }
            assertNull("Old client without callTimeout must still be hung at 1.5s (deadline cannot interrupt blocking code)", hung)

            // PASS-ON-NEW: the think client dies fast on a server that
            // stalls mid-body — the exact production hang.
            val fastThinkClient = OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .addInterceptor(rewriteToLocal)
                .build()
            val startMs = System.currentTimeMillis()
            val result = restClient.generateAutomationThink(
                apiKey = "AIzaSyTestKey123",
                history = emptyList(),
                systemPrompt = "",
                httpClient = fastThinkClient
            )
            val elapsedMs = System.currentTimeMillis() - startMs
            assertTrue("Think call on a stalled server must fail, got: $result", result.isFailure)
            val errMsg = result.exceptionOrNull()?.message ?: ""
            assertTrue("Failure must name the timeout but was: $errMsg", errMsg.contains("timeout", ignoreCase = true))
            assertTrue("CallTimeout must fail fast (was ${elapsedMs}ms)", elapsedMs < 5_000L)
        } finally {
            server.stop()
        }
    }
}

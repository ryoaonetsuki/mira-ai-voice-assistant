package io.github.ryoaonetsuki.mira.voice

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LiveDiagnosticRunnerTest {

    private class MockWebSocket(
        private val request: Request,
        private val listener: WebSocketListener
    ) : WebSocket {
        var isClosed = false
        var isCancelled = false
        val sentMessages = mutableListOf<String>()

        override fun request(): Request = request
        override fun queueSize(): Long = 0L

        override fun send(text: String): Boolean {
            sentMessages.add(text)
            return true
        }

        override fun send(bytes: ByteString): Boolean = true

        override fun close(code: Int, reason: String?): Boolean {
            isClosed = true
            listener.onClosed(this, code, reason ?: "")
            return true
        }

        override fun cancel() {
            isCancelled = true
        }
    }

    private fun dummyResponse(code: Int, req: Request): Response {
        return Response.Builder()
            .code(code)
            .message("Status $code")
            .protocol(Protocol.HTTP_1_1)
            .request(req)
            .build()
    }

    @Test
    fun testStage1_MissingKey_FailsImmediately() = runBlocking {
        val runner = LiveDiagnosticRunner()
        val outcome = runner.runDiagnostic("")

        assertFalse("Outcome must be failure for blank key", outcome.isSuccess)
        assertTrue("Stage 1 must be FAILED", outcome.stages[0].status == StageStatus.FAILED)
        assertTrue("Stage 2 must be SKIPPED", outcome.stages[1].status == StageStatus.SKIPPED)
        assertTrue("Stage 8 must be SKIPPED", outcome.stages[7].status == StageStatus.SKIPPED)
        assertTrue("Outcome message must name Stage 1", outcome.outcomeMessage.contains("Stage 1"))
    }

    @Test
    fun testStage1_InvalidKeyFormat_FailsStage1() = runBlocking {
        val runner = LiveDiagnosticRunner()
        val outcome = runner.runDiagnostic("short")

        assertFalse("Outcome must be failure for too-short key", outcome.isSuccess)
        assertEquals(StageStatus.FAILED, outcome.stages[0].status)
        assertEquals(StageStatus.SKIPPED, outcome.stages[1].status)
        assertTrue(outcome.outcomeMessage.contains("Stage 1"))
    }

    @Test
    fun testStage2_ConnectionFailure_FailsStage2() = runBlocking {
        val customWsFactory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                val ws = MockWebSocket(request, listener)
                listener.onFailure(ws, IOException("HTTP 401 Unauthorized"), dummyResponse(401, request))
                return ws
            }
        }

        val runner = LiveDiagnosticRunner(customWsFactory = customWsFactory)
        val outcome = runner.runDiagnostic("AIzaSyTestKey123456789")

        assertFalse("Outcome must be failure on WS connection failure", outcome.isSuccess)
        assertEquals(StageStatus.SUCCESS, outcome.stages[0].status)
        assertEquals(StageStatus.FAILED, outcome.stages[1].status)
        assertEquals(StageStatus.SKIPPED, outcome.stages[2].status)
        assertEquals(StageStatus.SKIPPED, outcome.stages[7].status)
        assertTrue("Stage 2 detail must contain HTTP 401", outcome.stages[1].detail?.contains("HTTP 401") == true)
        assertTrue("Outcome message must name Stage 2", outcome.outcomeMessage.contains("Stage 2"))
    }

    @Test
    fun testAllStages_EndToEndSuccess_Relay() = runBlocking {
        var interceptedRequest: Request? = null
        val customWsFactory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                interceptedRequest = request
                val ws = MockWebSocket(request, listener)

                // Trigger onOpen (HTTP 101)
                listener.onOpen(ws, dummyResponse(101, request))

                return object : WebSocket by ws {
                    override fun send(text: String): Boolean {
                        ws.sentMessages.add(text)

                        // If setup sent (contains "setup"), reply with setupComplete
                        if (text.contains("\"setup\"")) {
                            listener.onMessage(ws, "{\"setupComplete\": {}}")
                        }

                        // If clientContent text turn sent, reply with serverContent audio + turnComplete
                        if (text.contains("\"clientContent\"")) {
                            val audioReplyJson = "{\"serverContent\": {" +
                                    "\"modelTurn\": {\"parts\": [{\"inlineData\": {\"data\": \"dGVzdGF1ZGlvYnl0ZXM=\", \"mimeType\": \"audio/pcm;rate=24000\"}}]}," +
                                    "\"outputAudioTranscription\": {\"text\": \"নমস্কার\"}," +
                                    "\"turnComplete\": true" +
                                    "}}"
                            listener.onMessage(ws, audioReplyJson)
                        }
                        return true
                    }
                }
            }
        }

        val runner = LiveDiagnosticRunner(customWsFactory = customWsFactory)
        val outcome = runner.runDiagnostic("AIzaSyTestKey123456789", route = DiagnosticRoute.RELAY)

        assertTrue("Diagnostic must succeed: ${outcome.outcomeMessage}", outcome.isSuccess)
        assertTrue("Outcome message must start with PASSED", outcome.outcomeMessage.startsWith("PASSED"))

        // Assert intercepted request used Relay URL and header
        assertNotNull(interceptedRequest)
        assertEquals("https://mira-gemini-live-relay.salman-dev.workers.dev/live", interceptedRequest?.url.toString())
        assertEquals("AIzaSyTestKey123456789", interceptedRequest?.header(RelayLiveClient.HEADER_MIRA_KEY))

        // Assert all 8 stages are SUCCESS
        assertEquals(8, outcome.stages.size)
        for (stage in outcome.stages) {
            assertEquals("Stage ${stage.stageNumber} (${stage.name}) must be SUCCESS", StageStatus.SUCCESS, stage.status)
            assertNotNull("Stage ${stage.stageNumber} must have detail", stage.detail)
        }

        // Assert frame logs recorded
        assertTrue("Frame logs must not be empty", outcome.frameLogs.isNotEmpty())
    }

    @Test
    fun testAllStages_DirectRoute() = runBlocking {
        var interceptedRequest: Request? = null
        val customWsFactory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                interceptedRequest = request
                val ws = MockWebSocket(request, listener)
                listener.onOpen(ws, dummyResponse(101, request))

                return object : WebSocket by ws {
                    override fun send(text: String): Boolean {
                        ws.sentMessages.add(text)
                        if (text.contains("\"setup\"")) {
                            listener.onMessage(ws, "{\"setupComplete\": {}}")
                        }
                        if (text.contains("\"clientContent\"")) {
                            listener.onMessage(
                                ws,
                                "{\"serverContent\": {\"modelTurn\": {\"parts\": [{\"inlineData\": {\"data\": \"YXVkaW8=\", \"mimeType\": \"audio/pcm\"}}]}, \"turnComplete\": true}}"
                            )
                        }
                        return true
                    }
                }
            }
        }

        val runner = LiveDiagnosticRunner(customWsFactory = customWsFactory)
        val outcome = runner.runDiagnostic("AIzaSyTestKeyDirect123456", route = DiagnosticRoute.DIRECT)

        assertTrue(outcome.isSuccess)
        assertNotNull(interceptedRequest)
        assertEquals(GeminiLiveClient.LIVE_WS_URL.replace("wss://", "https://"), interceptedRequest?.url.toString())
        assertEquals("AIzaSyTestKeyDirect123456", interceptedRequest?.header("x-goog-api-key"))
    }

    @Test
    fun testDiagnosticVariants_PayloadShapes() {
        // Minimal V3: model + generationConfig + speechConfig + languageCode only
        val v3Payload = LiveDiagnosticRunner.buildVariantSetupPayload(
            variant = DiagnosticSetupVariant.MINIMAL_V3,
            model = "models/gemini-3.8-live",
            voiceName = "Aoede",
            languageCode = "bn-BD"
        )
        val v3Setup = v3Payload.getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", v3Setup.getString("model"))
        assertTrue(v3Setup.has("generationConfig"))
        assertFalse("Minimal V3 must not have sessionResumption", v3Setup.has("sessionResumption"))
        assertFalse("Minimal V3 must not have contextWindowCompression", v3Setup.has("contextWindowCompression"))
        assertFalse("Minimal V3 must not have realtimeInputConfig", v3Setup.has("realtimeInputConfig"))
        assertFalse("Minimal V3 must not have tools", v3Setup.has("tools"))

        // No Resumption
        val noResPayload = LiveDiagnosticRunner.buildVariantSetupPayload(
            variant = DiagnosticSetupVariant.NO_RESUMPTION,
            model = "models/gemini-3.8-live"
        )
        val noResSetup = noResPayload.getJSONObject("setup")
        assertFalse("No Resumption variant must not have sessionResumption", noResSetup.has("sessionResumption"))
        assertFalse("No Resumption variant must not have contextWindowCompression", noResSetup.has("contextWindowCompression"))
        assertTrue("No Resumption variant must have realtimeInputConfig", noResSetup.has("realtimeInputConfig"))
        assertFalse("No Resumption variant must not have tools", noResSetup.has("tools"))

        // Standard
        val stdPayload = LiveDiagnosticRunner.buildVariantSetupPayload(
            variant = DiagnosticSetupVariant.STANDARD,
            model = "models/gemini-3.8-live"
        )
        val stdSetup = stdPayload.getJSONObject("setup")
        assertTrue("Standard must have sessionResumption", stdSetup.has("sessionResumption"))
        assertTrue("Standard must have contextWindowCompression", stdSetup.has("contextWindowCompression"))
        assertTrue("Standard must have realtimeInputConfig", stdSetup.has("realtimeInputConfig"))
        assertFalse("Standard must not have tools", stdSetup.has("tools"))

        // No Tools
        val noToolsPayload = LiveDiagnosticRunner.buildVariantSetupPayload(
            variant = DiagnosticSetupVariant.NO_TOOLS,
            model = "models/gemini-3.8-live"
        )
        val noToolsSetup = noToolsPayload.getJSONObject("setup")
        assertFalse("No Tools variant must not have tools", noToolsSetup.has("tools"))
    }

    @Test
    fun testStage4_ServerReturnsErrorFrame() = runBlocking {
        val customWsFactory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                val ws = MockWebSocket(request, listener)
                listener.onOpen(ws, dummyResponse(101, request))

                return object : WebSocket by ws {
                    override fun send(text: String): Boolean {
                        ws.sentMessages.add(text)
                        if (text.contains("\"setup\"")) {
                            listener.onMessage(ws, "{\"error\": {\"code\": 400, \"message\": \"Setup rejected by server\"}}")
                        }
                        return true
                    }
                }
            }
        }

        val runner = LiveDiagnosticRunner(customWsFactory = customWsFactory)
        val outcome = runner.runDiagnostic("AIzaSyTestKey123456789")

        assertFalse("Outcome must be failure when server returns error", outcome.isSuccess)
        assertEquals(StageStatus.SUCCESS, outcome.stages[0].status)
        assertEquals(StageStatus.SUCCESS, outcome.stages[1].status)
        assertEquals(StageStatus.SUCCESS, outcome.stages[2].status)
        assertEquals(StageStatus.FAILED, outcome.stages[3].status)
        assertEquals(StageStatus.SKIPPED, outcome.stages[4].status)
        assertEquals(StageStatus.SKIPPED, outcome.stages[7].status)
        assertTrue("Stage 4 detail must mention server rejection", outcome.stages[3].detail?.contains("Setup rejected") == true)
        assertTrue(outcome.outcomeMessage.contains("Stage 4"))
    }
}

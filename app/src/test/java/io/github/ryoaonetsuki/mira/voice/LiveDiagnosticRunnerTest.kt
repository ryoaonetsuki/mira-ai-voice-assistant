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
    fun testAllStages_EndToEndSuccess() = runBlocking {
        val customWsFactory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                lateinit var ws: MockWebSocket
                ws = MockWebSocket(request, listener)

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
        val outcome = runner.runDiagnostic("AIzaSyTestKey123456789")

        assertTrue("Diagnostic must succeed: ${outcome.outcomeMessage}", outcome.isSuccess)
        assertTrue("Outcome message must start with PASSED", outcome.outcomeMessage.startsWith("PASSED"))

        // Assert all 8 stages are SUCCESS
        assertEquals(8, outcome.stages.size)
        for (stage in outcome.stages) {
            assertEquals("Stage ${stage.stageNumber} (${stage.name}) must be SUCCESS", StageStatus.SUCCESS, stage.status)
            assertNotNull("Stage ${stage.stageNumber} must have detail", stage.detail)
        }
    }

    @Test
    fun testStage4_ServerReturnsErrorFrame() = runBlocking {
        val customWsFactory = object : WebSocket.Factory {
            override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
                lateinit var ws: MockWebSocket
                ws = MockWebSocket(request, listener)
                listener.onOpen(ws, dummyResponse(101, request))

                return object : WebSocket by ws {
                    override fun send(text: String): Boolean {
                        ws.sentMessages.add(text)
                        if (text.contains("\"setup\"")) {
                            // Server error frame
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

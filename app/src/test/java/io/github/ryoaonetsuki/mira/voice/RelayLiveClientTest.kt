package io.github.ryoaonetsuki.mira.voice

import org.junit.Assert.*
import org.junit.Test

class RelayLiveClientTest {

    @Test
    fun testBuildRelayRequest_CorrectUrlAndHeader_NoKeyInUrl() {
        val testKey = "AIzaSyTestRelayKey987654321"
        val request = RelayLiveClient.buildRelayRequest(testKey)

        // URL must be the Cloudflare relay /live endpoint
        assertEquals("wss://mira-gemini-live-relay.salman-dev.workers.dev/live", request.url.toString())

        // NEVER pass key in query parameter
        assertNull("Query parameter 'key' must not be present in relay URL", request.url.queryParameter("key"))

        // Forward key in x-mira-gemini-key header
        val headerVal = request.header(RelayLiveClient.HEADER_MIRA_KEY)
        assertEquals(testKey, headerVal)
    }

    @Test
    fun testBuildDirectRequest_CorrectUrlAndHeader_NoKeyInUrl() {
        val testKey = "AIzaSyTestDirectKey123456789"
        val request = RelayLiveClient.buildDirectRequest(GeminiLiveClient.LIVE_WS_URL, testKey)

        // URL must be Google Live BidiGenerateContent endpoint
        assertEquals(GeminiLiveClient.LIVE_WS_URL, request.url.toString())

        // NEVER pass key in query parameter
        assertNull("Query parameter 'key' must not be present in direct URL", request.url.queryParameter("key"))

        // Header must be x-goog-api-key
        val headerVal = request.header("x-goog-api-key")
        assertEquals(testKey, headerVal)
    }
}

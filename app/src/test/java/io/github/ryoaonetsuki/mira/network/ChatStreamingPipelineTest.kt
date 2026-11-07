package io.github.ryoaonetsuki.mira.network

import io.github.ryoaonetsuki.mira.data.local.entities.ChatMessageEntity
import io.github.ryoaonetsuki.mira.projects.WebsiteProjectManager
import io.github.ryoaonetsuki.mira.ui.screens.extractImagePrompt
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ChatStreamingPipelineTest {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun createStreamingMockClient(sseLines: List<String>): OkHttpClient {
        val sseBody = sseLines.joinToString("\n") + "\n\n"
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .code(200)
                    .message("OK")
                    .protocol(Protocol.HTTP_1_1)
                    .body(sseBody.toResponseBody(jsonMedia))
                    .request(chain.request())
                    .build()
            })
            .build()
    }

    @Test
    fun testGenerateChatResponseStreaming_SeparatesThoughtAndAnswerTokens() = runBlocking {
        // SSE chunks with thought chunk followed by answer chunk
        val sseData = listOf(
            "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Thinking step 1: analyze input\", \"thought\": true}]}}]}",
            "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Thinking step 2: evaluate facts\", \"thought\": true}]}}]}",
            "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Hello, \", \"thought\": false}]}}]}",
            "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"how can I help?\", \"thought\": false}]}}]}",
            "data: [DONE]"
        )

        val mockClient = createStreamingMockClient(sseData)
        val restClient = GeminiRestClient(client = mockClient)

        val capturedThoughts = mutableListOf<String>()
        val emittedChunks = restClient.generateChatResponseStreaming(
            apiKey = "AIzaSyFakeKey123",
            history = listOf(ChatMessageEntity(conversationId = "conv1", role = "user", content = "hi")),
            systemPrompt = "sys",
            onThoughtChunk = { capturedThoughts.add(it) }
        ).toList()

        // 1. Verify thought tokens were routed to onThoughtChunk callback
        assertEquals(2, capturedThoughts.size)
        assertEquals("Thinking step 1: analyze input", capturedThoughts[0])
        assertEquals("Thinking step 2: evaluate facts", capturedThoughts[1])

        // 2. Verify answer tokens were emitted to the Flow
        assertEquals(2, emittedChunks.size)
        assertEquals("Hello, ", emittedChunks[0])
        assertEquals("how can I help?", emittedChunks[1])
        assertEquals("Hello, how can I help?", emittedChunks.joinToString(""))
    }

    @Test
    fun testIsJustCodeRequest_DetectsKeywordsCorrectly() {
        assertTrue(WebsiteProjectManager.isJustCodeRequest("give me just code for quicksort in kotlin"))
        assertTrue(WebsiteProjectManager.isJustCodeRequest("Only Code"))
        assertTrue(WebsiteProjectManager.isJustCodeRequest("write python script no explanation"))
        assertTrue(WebsiteProjectManager.isJustCodeRequest("শুধুমাত্র কোড দাও"))

        assertFalse(WebsiteProjectManager.isJustCodeRequest("Can you explain how quicksort works?"))
        assertFalse(WebsiteProjectManager.isJustCodeRequest("What is the weather today?"))
        assertFalse(WebsiteProjectManager.isJustCodeRequest("Hello Mira!"))
    }

    @Test
    fun testExtractImagePrompt_RoutesPromptsAndIgnoresGeneralChat() {
        assertEquals("a cute kitten in the snow", extractImagePrompt("generate image of a cute kitten in the snow"))
        assertEquals("a red sports car", extractImagePrompt("create an image of a red sports car"))
        assertEquals("mountain landscape", extractImagePrompt("/image mountain landscape"))
        assertEquals("sunset over the ocean", extractImagePrompt("sunset over the ocean photo"))

        assertNull(extractImagePrompt("tell me a story about a kitten"))
        assertNull(extractImagePrompt("who founded Google?"))
        assertNull(extractImagePrompt("how do I make a cake?"))
    }

    @Test
    fun testAutoScrollSafeBounds_PreventsIndexOutOfBounds() {
        // Simulates the layoutInfo.totalItemsCount check logic implemented in ChatScreen LaunchedEffects
        fun computeSafeScrollIndex(totalItemsCount: Int): Int? {
            if (totalItemsCount <= 0) return null
            return (totalItemsCount - 1).coerceIn(0, totalItemsCount - 1)
        }

        // When LazyColumn is empty (totalItemsCount == 0), safe index is null (NEVER scroll)
        assertNull("Empty LazyColumn must never attempt scroll", computeSafeScrollIndex(0))

        // When LazyColumn has 1 item, safe index is 0
        assertEquals(0, computeSafeScrollIndex(1))

        // When LazyColumn has 10 items, safe index is 9
        assertEquals(9, computeSafeScrollIndex(10))
    }
}

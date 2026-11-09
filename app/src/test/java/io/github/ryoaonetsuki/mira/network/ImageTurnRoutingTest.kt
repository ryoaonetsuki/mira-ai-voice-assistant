package io.github.ryoaonetsuki.mira.network

import io.github.ryoaonetsuki.mira.ui.screens.extractImagePrompt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ImageTurnRoutingTest {

    /**
     * Fake image generation client for routing tests.
     */
    class FakeMiraImageApiClient(
        private val resultToReturn: MiraImageResult = MiraImageResult.Success(byteArrayOf(1, 2, 3))
    ) {
        val callCount = AtomicInteger(0)
        var lastPromptReceived: String? = null

        suspend fun generateImage(prompt: String): MiraImageResult {
            callCount.incrementAndGet()
            lastPromptReceived = prompt
            return resultToReturn
        }
    }

    /**
     * Simulates the exact routing decision executed in ChatScreen.sendMessage().
     */
    private suspend fun simulateChatRouting(
        text: String,
        isImageMode: Boolean,
        hasStagedAttachment: Boolean,
        isBangla: Boolean,
        client: FakeMiraImageApiClient,
        saveLocalImage: (String, ByteArray) -> String
    ): ChatTurnOutcome {
        val imagePrompt = extractImagePrompt(text, isImageMode)
        if (imagePrompt != null && !hasStagedAttachment) {
            val result = client.generateImage(imagePrompt)
            return when (result) {
                is MiraImageResult.Success -> {
                    val fileUri = saveLocalImage(imagePrompt, result.bytes)
                    val content = if (isBangla) {
                        "আপনার ছবি তৈরি হয়েছে:\n\n![$imagePrompt]($fileUri)"
                    } else {
                        "Here is your image:\n\n![$imagePrompt]($fileUri)"
                    }
                    ChatTurnOutcome.ImageMessage(prompt = imagePrompt, fileUri = fileUri, content = content)
                }
                is MiraImageResult.Failure -> {
                    val friendlyError = if (isBangla) {
                        "দুঃখিত, এই মুহূর্তে ছবিটি তৈরি করা সম্ভব হয়নি। অনুগ্রহ করে কিছুক্ষণ পর আবার চেষ্টা করুন।"
                    } else {
                        "I couldn't create that image right now. Please try again in a little while."
                    }
                    ChatTurnOutcome.ErrorMessage(prompt = imagePrompt, content = friendlyError)
                }
            }
        }

        // Non-image path: Gemini text / website builder / connector
        return ChatTurnOutcome.TextChat(userText = text)
    }

    sealed class ChatTurnOutcome {
        data class ImageMessage(val prompt: String, val fileUri: String, val content: String) : ChatTurnOutcome()
        data class ErrorMessage(val prompt: String, val content: String) : ChatTurnOutcome()
        data class TextChat(val userText: String) : ChatTurnOutcome()
    }

    @Test
    fun testNonImageRequests_DoNotCallImageClient() = runBlocking {
        val fakeClient = FakeMiraImageApiClient()
        val nonImageInputs = listOf(
            "Hello, what can you do?",
            "Write a Python script to sort files",
            "What is the weather today?",
            "Create a landing page for my bakery",
            "Explain how neural networks work"
        )

        for (input in nonImageInputs) {
            val outcome = simulateChatRouting(
                text = input,
                isImageMode = false,
                hasStagedAttachment = false,
                isBangla = false,
                client = fakeClient,
                saveLocalImage = { _, _ -> "file:///mock.jpg" }
            )

            assertTrue("Expected TextChat for input: '$input'", outcome is ChatTurnOutcome.TextChat)
        }

        assertEquals("Image client must NEVER be called for non-image prompts", 0, fakeClient.callCount.get())
    }

    @Test
    fun testImageRequests_CallClientAndProduceImageMarkdown() = runBlocking {
        val fakeClient = FakeMiraImageApiClient(
            resultToReturn = MiraImageResult.Success(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        )

        val imageInputs = listOf(
            "generate an image of a neon cat" to "a neon cat",
            "draw a sunset over the mountains" to "sunset over the mountains",
            "create a picture of an astronaut puppy" to "an astronaut puppy",
            "/image futuristic flying taxi" to "futuristic flying taxi"
        )

        for ((input, expectedPrompt) in imageInputs) {
            fakeClient.callCount.set(0)
            val outcome = simulateChatRouting(
                text = input,
                isImageMode = false,
                hasStagedAttachment = false,
                isBangla = false,
                client = fakeClient,
                saveLocalImage = { p, _ -> "file:///data/user/0/mira/generated_images/${p.take(5)}.jpg" }
            )

            assertEquals("Must call image client once for input: '$input'", 1, fakeClient.callCount.get())
            assertTrue(outcome is ChatTurnOutcome.ImageMessage)
            val imageOutcome = outcome as ChatTurnOutcome.ImageMessage
            assertTrue("Message must contain markdown image tag", imageOutcome.content.contains("![$expectedPrompt]"))
            assertTrue("Message must point to local file:/// URI", imageOutcome.content.contains("(file:///data/user/0/mira/generated_images/"))
            assertFalse("Must never contain pollinations URL", imageOutcome.content.contains("pollinations.ai"))
        }
    }

    @Test
    fun testImageModeActive_RoutesAnyTextToImageClient() = runBlocking {
        val fakeClient = FakeMiraImageApiClient()

        val outcome = simulateChatRouting(
            text = "peaceful bamboo garden with koi pond",
            isImageMode = true,
            hasStagedAttachment = false,
            isBangla = false,
            client = fakeClient,
            saveLocalImage = { _, _ -> "file:///garden.jpg" }
        )

        assertEquals(1, fakeClient.callCount.get())
        assertEquals("peaceful bamboo garden with koi pond", fakeClient.lastPromptReceived)
        assertTrue(outcome is ChatTurnOutcome.ImageMessage)
    }

    @Test
    fun testStagedAttachment_BypassesImageGeneration() = runBlocking {
        val fakeClient = FakeMiraImageApiClient()

        // When a file is attached, image extraction should not hijack file question
        val outcome = simulateChatRouting(
            text = "draw a chart from this data",
            isImageMode = false,
            hasStagedAttachment = true,
            isBangla = false,
            client = fakeClient,
            saveLocalImage = { _, _ -> "file:///chart.jpg" }
        )

        assertEquals("Must not call image client when attachment is staged", 0, fakeClient.callCount.get())
        assertTrue(outcome is ChatTurnOutcome.TextChat)
    }

    @Test
    fun testImageFailure_ReturnsFriendlyErrorMessage_NoPollinationsFallback() = runBlocking {
        val fakeClient = FakeMiraImageApiClient(
            resultToReturn = MiraImageResult.Failure(message = "quota exhausted", statusCode = 502)
        )

        val outcomeEnglish = simulateChatRouting(
            text = "generate an image of a cyber dragon",
            isImageMode = false,
            hasStagedAttachment = false,
            isBangla = false,
            client = fakeClient,
            saveLocalImage = { _, _ -> "file:///dragon.jpg" }
        )

        assertTrue(outcomeEnglish is ChatTurnOutcome.ErrorMessage)
        val errorOutcome = outcomeEnglish as ChatTurnOutcome.ErrorMessage
        assertFalse("Must not expose raw 502 or stack trace in user-facing message", errorOutcome.content.contains("502"))
        assertFalse("Must not fallback to pollinations.ai", errorOutcome.content.contains("pollinations.ai"))
        assertTrue("Must be a polite user-friendly message", errorOutcome.content.contains("try again"))

        // Test Bangla failure message
        val outcomeBangla = simulateChatRouting(
            text = "একটি ড্রাগনের ছবি আঁকো",
            isImageMode = false,
            hasStagedAttachment = false,
            isBangla = true,
            client = fakeClient,
            saveLocalImage = { _, _ -> "file:///dragon.jpg" }
        )

        assertTrue(outcomeBangla is ChatTurnOutcome.ErrorMessage)
        val errorBangla = outcomeBangla as ChatTurnOutcome.ErrorMessage
        assertTrue("Bangla message must be in Bengali", errorBangla.content.contains("ছবিটি তৈরি করা সম্ভব হয়নি"))
    }
}

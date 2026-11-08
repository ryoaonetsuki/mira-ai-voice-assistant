package io.github.ryoaonetsuki.mira.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionPromptTest {

    // --- Prompt Building Tests ---

    @Test
    fun testBuildVisionPrompt_withUserQuestion_banglaDefault() {
        val userQuestion = "এই ছবিতে বইটির নাম কী?"
        val prompt = VisionPromptHelper.buildVisionPrompt(question = userQuestion)

        // Must contain user question
        assertTrue("Prompt should contain user question", prompt.contains(userQuestion))
        // Must contain Bangla language rule
        assertTrue("Prompt must enforce Bangla response", prompt.contains("BANGLA"))
        assertTrue("Prompt must instruct single language response", prompt.contains("Never add an English translation"))
    }

    @Test
    fun testBuildVisionPrompt_withUserQuestion_explicitBangla() {
        val userQuestion = "গাছটির রং কী?"
        val prompt = VisionPromptHelper.buildVisionPrompt(question = userQuestion, language = "Bangla (Bengali)")

        assertTrue(prompt.contains(userQuestion))
        assertTrue(prompt.contains("BANGLA"))
    }

    @Test
    fun testBuildVisionPrompt_withUserQuestion_english() {
        val userQuestion = "What kind of bird is this?"
        val prompt = VisionPromptHelper.buildVisionPrompt(question = userQuestion, language = "English")

        assertTrue("Prompt should contain user question", prompt.contains(userQuestion))
        assertTrue("Prompt should contain English answer request", prompt.contains("Based on this image, please answer the following question"))
        assertTrue("Prompt must enforce English response", prompt.contains("RESPOND IN ENGLISH"))
    }

    @Test
    fun testBuildVisionPrompt_noQuestion_banglaDefault() {
        val prompt = VisionPromptHelper.buildVisionPrompt(question = null)

        // Must request description in Bangla
        assertTrue("Prompt should request image description in Bangla", prompt.contains("এই ছবিতে কী আছে") || prompt.contains("বর্ণনা করো"))
        assertTrue("Prompt must enforce Bangla", prompt.contains("BANGLA"))
    }

    @Test
    fun testBuildVisionPrompt_blankQuestion_banglaDefault() {
        val prompt = VisionPromptHelper.buildVisionPrompt(question = "   \n\t  ")

        // Whitespace-only question should fall back to description request
        assertTrue("Prompt should request description for blank input", prompt.contains("এই ছবিতে কী আছে") || prompt.contains("বর্ণনা করো"))
        assertTrue("Prompt must enforce Bangla", prompt.contains("BANGLA"))
    }

    @Test
    fun testBuildVisionPrompt_noQuestion_english() {
        val prompt = VisionPromptHelper.buildVisionPrompt(question = null, language = "English")

        assertTrue("Prompt should request description in English", prompt.contains("Describe what you see in this image"))
        assertTrue("Prompt must enforce English", prompt.contains("RESPOND IN ENGLISH"))
    }

    @Test
    fun testBuildVisionPrompt_emptyLanguageString_defaultsToBangla() {
        val prompt = VisionPromptHelper.buildVisionPrompt(question = null, language = "")

        assertTrue("Empty language string should default to Bangla description", prompt.contains("এই ছবিতে কী আছে"))
        assertTrue("Empty language string should default to Bangla rule", prompt.contains("BANGLA"))
    }

    // --- Frame Downscale Calculation Tests ---

    @Test
    fun testCalculateDownscaledSize_capsLongEdge_landscape() {
        val (w, h) = VisionPromptHelper.calculateDownscaledSize(width = 4000, height = 3000, maxLongEdge = 1024)
        assertEquals(1024, w)
        assertEquals(768, h)
        assertTrue("Long edge capped at limit", maxOf(w, h) <= 1024)
    }

    @Test
    fun testCalculateDownscaledSize_capsLongEdge_portrait() {
        val (w, h) = VisionPromptHelper.calculateDownscaledSize(width = 3000, height = 4000, maxLongEdge = 1024)
        assertEquals(768, w)
        assertEquals(1024, h)
        assertTrue("Long edge capped at limit", maxOf(w, h) <= 1024)
    }

    @Test
    fun testCalculateDownscaledSize_neverUpscales_whenSmaller() {
        val (w, h) = VisionPromptHelper.calculateDownscaledSize(width = 800, height = 600, maxLongEdge = 1024)
        assertEquals(800, w)
        assertEquals(600, h)
    }

    @Test
    fun testCalculateDownscaledSize_neverUpscales_smallSquare() {
        val (w, h) = VisionPromptHelper.calculateDownscaledSize(width = 500, height = 500, maxLongEdge = 1024)
        assertEquals(500, w)
        assertEquals(500, h)
    }

    @Test
    fun testCalculateDownscaledSize_exactLimit_remainsUnchanged() {
        val (w, h) = VisionPromptHelper.calculateDownscaledSize(width = 1024, height = 768, maxLongEdge = 1024)
        assertEquals(1024, w)
        assertEquals(768, h)
    }

    @Test
    fun testCalculateDownscaledSize_nonPositiveInputs_handledSafely() {
        val (w0, h0) = VisionPromptHelper.calculateDownscaledSize(width = 0, height = 0, maxLongEdge = 1024)
        assertEquals(0, w0)
        assertEquals(0, h0)

        val (wNeg, hNeg) = VisionPromptHelper.calculateDownscaledSize(width = -10, height = 100, maxLongEdge = 1024)
        assertEquals(0, wNeg)
        assertEquals(100, hNeg)
    }

    @Test
    fun testCalculateDownscaledSize_preservesAspectRatio() {
        val originalW = 1920
        val originalH = 1080
        val (w, h) = VisionPromptHelper.calculateDownscaledSize(width = originalW, height = originalH, maxLongEdge = 1024)

        assertEquals(1024, w)
        val expectedH = Math.round(1080.0 * 1024.0 / 1920.0).toInt()
        assertEquals(expectedH, h)
    }
}

package io.github.ryoaonetsuki.mira.vision

import io.github.ryoaonetsuki.mira.voice.LanguageUtils
import io.github.ryoaonetsuki.mira.voice.LivePromptBuilder

/**
 * Pure Kotlin helpers for Vision prompt building and image frame scaling calculations.
 * Pure JVM safe — no Android dependencies.
 */
object VisionPromptHelper {

    const val DEFAULT_MAX_LONG_EDGE = 1024

    /**
     * Calculates the downscaled target dimensions `(width, height)` such that the long edge
     * does not exceed [maxLongEdge].
     *
     * Invariants:
     * - Long edge is capped at [maxLongEdge] when larger.
     * - Never upscales if both width and height are <= [maxLongEdge].
     * - Preserves aspect ratio.
     * - Handles non-positive inputs safely.
     */
    fun calculateDownscaledSize(
        width: Int,
        height: Int,
        maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) {
            return Pair(maxOf(0, width), maxOf(0, height))
        }

        val currentLongEdge = maxOf(width, height)
        if (currentLongEdge <= maxLongEdge) {
            // Never upscale
            return Pair(width, height)
        }

        val scale = maxLongEdge.toDouble() / currentLongEdge.toDouble()
        val targetWidth = maxOf(1, Math.round(width * scale).toInt())
        val targetHeight = maxOf(1, Math.round(height * scale).toInt())
        return Pair(targetWidth, targetHeight)
    }

    /**
     * Builds the vision query prompt for Gemini understanding.
     * - If [question] is given (non-blank), asks Gemini to answer the question based on the image.
     * - If [question] is null or blank, asks Gemini for a clear, concise description of the image.
     * - Enforces the single-language rule using [LivePromptBuilder.buildLanguageRule],
     *   defaulting to Bangla.
     */
    fun buildVisionPrompt(
        question: String? = null,
        language: String = LivePromptBuilder.DEFAULT_LANGUAGE
    ): String {
        val effectiveLang = language.ifBlank { LivePromptBuilder.DEFAULT_LANGUAGE }
        val isBangla = LanguageUtils.isBangla(effectiveLang)
        val cleanQuestion = question?.trim().orEmpty()

        val promptBody = if (cleanQuestion.isNotBlank()) {
            if (isBangla) {
                "এই ছবিটি দেখে নিচের প্রশ্নের উত্তর দাও:\n$cleanQuestion"
            } else {
                "Based on this image, please answer the following question:\n$cleanQuestion"
            }
        } else {
            if (isBangla) {
                "এই ছবিতে কী আছে তা স্পষ্টভাবে বলো এবং সংক্ষেপে বর্ণনা করো।"
            } else {
                "Describe what you see in this image clearly and concisely."
            }
        }

        val languageRule = LivePromptBuilder.buildLanguageRule(effectiveLang)
        return "$promptBody\n\n$languageRule"
    }
}

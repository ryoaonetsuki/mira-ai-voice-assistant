package io.github.ryoaonetsuki.mira.voice

import io.github.ryoaonetsuki.mira.automation.MiraAutomationBridge
import io.github.ryoaonetsuki.mira.voice.pipecat.VoiceEchoDetector
import java.util.Locale

/** Where a recognised user voice turn goes. Pure JVM so the decision is unit-testable. */
enum class VoiceTurnRoute {
    /** Own-echo of recent model speech: ignore completely (no action, no answer). */
    IGNORE_ECHO,
    /** "stop"/"thamo" while an agent task runs: cancel it. */
    STOP_AUTOMATION,
    /** A real phone command: run the agent. */
    AUTOMATION,
    /** Everything else (incl. "can you control my phone?"): the Live model answers by itself. */
    LIVE_ANSWERS
}

object VoiceTurnRouter {
    private val STOP_WORDS = setOf("stop", "thamo", "থামো")

    fun route(
        text: String,
        recentModelSpeech: List<String>,
        automationRunning: Boolean,
    ): VoiceTurnRoute {
        val clean = text.trim()
        if (clean.isEmpty()) return VoiceTurnRoute.IGNORE_ECHO
        if (isEcho(clean, recentModelSpeech)) return VoiceTurnRoute.IGNORE_ECHO
        val tokens = clean.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (automationRunning && tokens.any { it in STOP_WORDS }) return VoiceTurnRoute.STOP_AUTOMATION
        if (MiraAutomationBridge.isAutomationIntent(clean)) return VoiceTurnRoute.AUTOMATION
        return VoiceTurnRoute.LIVE_ANSWERS
    }

    /** One echo check for every place a user turn is acted on: fuzzy [VoiceEchoDetector.matchesEcho]. */
    fun isEcho(userText: String, recentModelSpeech: List<String>): Boolean =
        recentModelSpeech.any { VoiceEchoDetector.matchesEcho(userText, it) }

    /**
     * SINGLE-VOICE INVARIANT. The separate Gemini TTS voice may speak only when
     *  - NO Live session is connected (then it is the only voice, not a second one), AND
     *  - the speech is user-initiated (the user asked this turn, or a requested action completed).
     * While Live is connected, results go to Live as context and Live announces them itself.
     */
    fun mayUseFallbackTts(liveConnected: Boolean, userInitiated: Boolean): Boolean =
        !liveConnected && userInitiated

    /** An automation result may be announced only if it came from an explicit user command in this session. */
    fun mayAnnounceAutomationResult(goalFromUserCommand: Boolean): Boolean = goalFromUserCommand

    /** Context note handed to Live so it announces the outcome in its OWN words (no verbatim recitation). */
    fun buildResultNote(message: String, language: String, success: Boolean): String {
        val state = if (success) "finished" else "did not complete"
        return "[Phone action $state. Outcome: ${message.trim()}. " +
            "Tell the user this briefly and warmly in your own words, in $language. " +
            "Do not read this note aloud and do not mention it.]"
    }

    fun buildAccessibilityNote(language: String): String =
        "[Phone control cannot run yet because Mira's accessibility service is off; the accessibility settings screen was just opened. " +
            "Tell the user briefly and warmly in your own words, in $language, to switch Mira on there. " +
            "Do not read this note aloud and do not mention it.]"
}

/**
 * Drops model audio while a phone action runs so Live cannot speak a denial/second answer for that
 * turn before the real result is known. Reliable (a flag read on the audio path), unlike a text
 * "keep silent" message that races the model.
 */
class ModelAudioGate {
    @Volatile private var suppressed = false
    fun suppress() { suppressed = true }
    fun release() { suppressed = false }
    fun shouldPlay(): Boolean = !suppressed
    val isSuppressed: Boolean get() = suppressed
}

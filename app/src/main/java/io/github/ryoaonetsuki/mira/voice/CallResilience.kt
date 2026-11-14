package io.github.ryoaonetsuki.mira.voice

import android.content.Context

/**
 * Turn G (Round 25): the "call stays alive" resilience rules.
 *
 * The voice call is owned by **call-active state**, not by a single
 * WebSocket connection: as long as the user has not tapped End, a dropped
 * session must come back by itself. These are pure, unit-testable rules;
 * [io.github.ryoaonetsuki.mira.service.MicService] wires them to the
 * session manager, and [GeminiSessionManager] owns the in-session retries.
 */
object CallRestartPolicy {
    /** Capped backoff between fresh-session restarts (1-based attempt). */
    val RESTART_DELAYS_MS = listOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L)
    const val MAX_RESTART_DELAY_MS = 30_000L
    const val MAX_AUTO_RESTART_ATTEMPTS = 5

    fun delayForAttempt(attempt: Int): Long {
        val index = (attempt - 1).coerceIn(0, RESTART_DELAYS_MS.size - 1)
        return RESTART_DELAYS_MS[index]
    }

    /**
     * Combined restart decision: only restart when under the attempt cap and the
     * failure is not terminal. Pure and unit-testable.
     */
    fun shouldRestart(attempt: Int, message: String): Boolean =
        attempt < MAX_AUTO_RESTART_ATTEMPTS && !isTerminalFailure(message)

    /**
     * Failures that must NOT auto-restart: the call can never succeed
     * without the user doing something (add a key / grant permission).
     * Retrying those forever would spin the radio and burn quota.
     */
    fun isTerminalFailure(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("key missing") ||
            lower.contains("microphone permission not granted") ||
            lower.contains("free-tier limit reached") ||
            lower.contains("invalid api key") ||
            lower.contains("api key not valid") ||
            lower.contains("api-key not valid") ||
            lower.contains("unauthorized") ||
            lower.contains("unauthenticated") ||
            lower.contains("permission denied") ||
            lower.contains("invalid model") ||
            lower.contains("model not found") ||
            lower.contains("bad request") ||
            lower.contains("invalid argument") ||
            Regex("\\b(400|401|403)\\b").containsMatchIn(lower)
    }
}

/**
 * Liveness watchdog rule: while the call is live and the mic is unmuted,
 * mic frames arrive roughly every 100 ms even in silence, so a long gap
 * with no audio-level callback means the capture path (or the transport)
 * is dead even though the state still says LISTENING. Pure function for
 * unit tests; the service supplies elapsed-realtime timestamps.
 */
object VoiceLivenessPolicy {
    /** No mic frame for this long while live => the call is deaf/dead. */
    const val MAX_SILENT_MS = 20_000L
    const val CHECK_INTERVAL_MS = 5_000L

    fun shouldTriggerRestart(
        lastAudioFrameElapsedMs: Long,
        nowElapsedMs: Long,
        isMuted: Boolean,
        isLive: Boolean,
        isReconnecting: Boolean
    ): Boolean {
        if (isMuted || !isLive || isReconnecting) return false
        if (lastAudioFrameElapsedMs <= 0L) return false
        return (nowElapsedMs - lastAudioFrameElapsedMs) > MAX_SILENT_MS
    }
}

/**
 * Persists "a voice call was active" across a service/process kill so a
 * recreated [io.github.ryoaonetsuki.mira.service.MicService] can resume the
 * call by itself. Set on voice start, cleared ONLY when the user taps End
 * (never in `onDestroy`, which also runs when the system kills the service).
 */
class LiveCallStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    fun setCallActive(active: Boolean) {
        val editor = prefs.edit().putBoolean(KEY_CALL_ACTIVE, active)
        if (!active) {
            editor.remove(KEY_VOICE_NAME)
                .remove(KEY_LANGUAGE)
                .remove(KEY_LAST_GOAL)
        }
        editor.apply()
    }

    fun isCallActive(): Boolean = prefs.getBoolean(KEY_CALL_ACTIVE, false)

    fun setVoiceName(voice: String?) {
        val editor = prefs.edit()
        if (voice == null) editor.remove(KEY_VOICE_NAME) else editor.putString(KEY_VOICE_NAME, voice)
        editor.apply()
    }

    fun getVoiceName(): String? = prefs.getString(KEY_VOICE_NAME, null)

    fun setLanguage(language: String?) {
        val editor = prefs.edit()
        if (language == null) editor.remove(KEY_LANGUAGE) else editor.putString(KEY_LANGUAGE, language)
        editor.apply()
    }

    fun getLanguage(): String? = prefs.getString(KEY_LANGUAGE, null)

    fun setLastGoal(goal: String?) {
        val editor = prefs.edit()
        if (goal == null) editor.remove(KEY_LAST_GOAL) else editor.putString(KEY_LAST_GOAL, goal)
        editor.apply()
    }

    fun getLastGoal(): String? = prefs.getString(KEY_LAST_GOAL, null)

    fun clear() {
        prefs.edit()
            .putBoolean(KEY_CALL_ACTIVE, false)
            .remove(KEY_VOICE_NAME)
            .remove(KEY_LANGUAGE)
            .remove(KEY_LAST_GOAL)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "mira_live_call"
        const val KEY_CALL_ACTIVE = "call_active"
        const val KEY_VOICE_NAME = "call_voice_name"
        const val KEY_LANGUAGE = "call_language"
        const val KEY_LAST_GOAL = "call_last_goal"
    }
}

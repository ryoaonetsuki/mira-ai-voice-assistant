package io.github.ryoaonetsuki.mira.service

import io.github.ryoaonetsuki.mira.voice.LiveVoiceDiagnostics

/**
 * Turn G Milestone 6: Per-goal tool-call cap (30) and 120s task timeout.
 *
 * Tracks tool sequence execution per goal in-memory:
 * - A goal starts at the first tool call carrying that goal.
 * - Caps tool calls at 30 calls per goal.
 * - Caps wall-clock duration at 120 seconds from the first tool call of the goal.
 * - Resets when a new goal starts or when the voice call ends.
 */
object GoalToolTracker {
    const val MAX_TOOL_CALLS_PER_GOAL = 30
    const val MAX_GOAL_DURATION_MS = 120_000L // 120 seconds

    @Volatile
    var currentGoal: String? = null
        private set

    @Volatile
    var callCount: Int = 0
        private set

    @Volatile
    var startTimeMs: Long = 0L
        private set

    /** Clock provider for unit testing. Defaults to [System.currentTimeMillis]. */
    var timeProvider: () -> Long = { System.currentTimeMillis() }

    sealed class CheckResult {
        data class Allowed(val callCount: Int) : CheckResult()
        data class CapExceeded(val callCount: Int, val maxCalls: Int) : CheckResult()
        data class TimeoutExceeded(val elapsedMs: Long, val maxDurationMs: Long) : CheckResult()
    }

    @Synchronized
    fun onToolCall(goal: String?): CheckResult {
        val normGoal = goal?.trim()?.takeIf { it.isNotEmpty() }
        val now = timeProvider()

        if (normGoal != null && normGoal != currentGoal) {
            // A new goal starts at the first tool call carrying that goal
            currentGoal = normGoal
            callCount = 0
            startTimeMs = now
            LiveVoiceDiagnostics.resetGoalToolCallCount()
        }

        if (startTimeMs == 0L) {
            startTimeMs = now
        }
        if (currentGoal == null && normGoal != null) {
            currentGoal = normGoal
        }

        val elapsed = now - startTimeMs
        if (elapsed >= MAX_GOAL_DURATION_MS) {
            return CheckResult.TimeoutExceeded(elapsed, MAX_GOAL_DURATION_MS)
        }

        if (callCount >= MAX_TOOL_CALLS_PER_GOAL) {
            return CheckResult.CapExceeded(callCount, MAX_TOOL_CALLS_PER_GOAL)
        }

        callCount++
        return CheckResult.Allowed(callCount)
    }

    @Synchronized
    fun reset() {
        currentGoal = null
        callCount = 0
        startTimeMs = 0L
        LiveVoiceDiagnostics.resetGoalToolCallCount()
    }
}

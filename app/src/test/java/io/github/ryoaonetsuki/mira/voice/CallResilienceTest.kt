package io.github.ryoaonetsuki.mira.voice

import android.content.Context
import io.github.ryoaonetsuki.mira.voice.pipecat.AudioCaptureRetryPolicy
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Turn G: the "call stays alive" rules — restart backoff, liveness
 * watchdog, call-active persistence across a service/process kill, and the
 * drop/restart diagnostics the next phone test reads to tell which suspect
 * fired. Pure rules (no service instance needed) + the SharedPreferences
 * store a recreated service reads back.
 */
@RunWith(RobolectricTestRunner::class)
class CallResilienceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        LiveVoiceDiagnostics.reset()
    }

    @Test
    fun testRestartBackoff_CappedAt30s() {
        assertEquals(2_000L, CallRestartPolicy.delayForAttempt(1))
        assertEquals(5_000L, CallRestartPolicy.delayForAttempt(2))
        assertEquals(10_000L, CallRestartPolicy.delayForAttempt(3))
        assertEquals(20_000L, CallRestartPolicy.delayForAttempt(4))
        assertEquals(30_000L, CallRestartPolicy.delayForAttempt(5))
        assertEquals(30_000L, CallRestartPolicy.delayForAttempt(6))
        assertEquals(30_000L, CallRestartPolicy.delayForAttempt(99))
    }

    @Test
    fun testTerminalFailures_NeverAutoRestart() {
        assertTrue(CallRestartPolicy.isTerminalFailure("Mira key missing — add it in Settings"))
        assertTrue(CallRestartPolicy.isTerminalFailure("Microphone permission not granted — allow it in Settings"))
        assertTrue(CallRestartPolicy.isTerminalFailure("Free-tier limit reached: maximum 1 concurrent sessions allowed"))
        assertTrue(CallRestartPolicy.isTerminalFailure("HTTP 401 Unauthorized"))
        assertTrue(CallRestartPolicy.isTerminalFailure("API key not valid. Please pass a valid API key"))
        assertTrue(CallRestartPolicy.isTerminalFailure("403 permission denied"))
        assertTrue(CallRestartPolicy.isTerminalFailure("400 bad request: invalid model"))
        assertFalse(CallRestartPolicy.isTerminalFailure("Live voice: Connection failed after 5 retries — tap to retry"))
        assertFalse(CallRestartPolicy.isTerminalFailure("socket-dropped: websocket closed (state=CONNECTED)"))
        assertFalse(CallRestartPolicy.isTerminalFailure("Live voice: Setup timed out after 15s — tap to retry"))
    }

    @Test
    fun testShouldRestart_CappedAtMaxAttempts() {
        assertTrue(CallRestartPolicy.shouldRestart(0, "Live voice: Connection failed after 5 retries — tap to retry"))
        assertTrue(CallRestartPolicy.shouldRestart(4, "Live voice: Connection failed after 5 retries — tap to retry"))
        assertFalse(CallRestartPolicy.shouldRestart(5, "Live voice: Connection failed after 5 retries — tap to retry"))
        assertFalse(CallRestartPolicy.shouldRestart(0, "HTTP 401 Unauthorized"))
        assertFalse(CallRestartPolicy.shouldRestart(0, "API key not valid. Please pass a valid API key"))
    }

    @Test
    fun testLivenessWatchdog_FiresOnlyWhenLiveUnmutedAndSilent() {
        val now = 100_000L
        // Live, unmuted, no frames for 25s -> restart.
        assertTrue(
            VoiceLivenessPolicy.shouldTriggerRestart(
                lastAudioFrameElapsedMs = now - 25_000L, nowElapsedMs = now,
                isMuted = false, isLive = true, isReconnecting = false
            )
        )
        // Only 5s of silence -> still alive (frames arrive ~every 100ms).
        assertFalse(
            VoiceLivenessPolicy.shouldTriggerRestart(
                lastAudioFrameElapsedMs = now - 5_000L, nowElapsedMs = now,
                isMuted = false, isLive = true, isReconnecting = false
            )
        )
        // Muted mic sends no frames by design — never fire.
        assertFalse(
            VoiceLivenessPolicy.shouldTriggerRestart(
                lastAudioFrameElapsedMs = now - 60_000L, nowElapsedMs = now,
                isMuted = true, isLive = true, isReconnecting = false
            )
        )
        // Not live (idle/connecting) -> the connecting watchdog owns it.
        assertFalse(
            VoiceLivenessPolicy.shouldTriggerRestart(
                lastAudioFrameElapsedMs = now - 60_000L, nowElapsedMs = now,
                isMuted = false, isLive = false, isReconnecting = false
            )
        )
        // Already reconnecting -> the in-flight reconnect owns recovery.
        assertFalse(
            VoiceLivenessPolicy.shouldTriggerRestart(
                lastAudioFrameElapsedMs = now - 60_000L, nowElapsedMs = now,
                isMuted = false, isLive = true, isReconnecting = true
            )
        )
        // No frame ever seen yet -> do not fire (call just started).
        assertFalse(
            VoiceLivenessPolicy.shouldTriggerRestart(
                lastAudioFrameElapsedMs = 0L, nowElapsedMs = now,
                isMuted = false, isLive = true, isReconnecting = false
            )
        )
    }

    @Test
    fun testCallActiveStore_SurvivesRecreation_AndClearsOnUserStop() {
        // A fresh service reads "no call active".
        assertFalse(LiveCallStateStore(context).isCallActive())

        // Voice starts: flag set.
        LiveCallStateStore(context).setCallActive(true)

        // Service killed + recreated: a NEW store instance reads it back —
        // this is the resume-on-recreate path (START_STICKY, null intent).
        assertTrue("Recreated service must see the call was active", LiveCallStateStore(context).isCallActive())

        // User taps End: flag cleared, no resume.
        LiveCallStateStore(context).setCallActive(false)
        assertFalse(LiveCallStateStore(context).isCallActive())
    }

    @Test
    fun testCallActiveStore_PersistsVoiceLanguageAndGoalAcrossKill() {
        val store1 = LiveCallStateStore(context)
        store1.setCallActive(true)
        store1.setVoiceName("Puck")
        store1.setLanguage("bn-BD")
        store1.setLastGoal("YouTube-এ গান চালাও")

        // Service killed + recreated: NEW store instance
        val store2 = LiveCallStateStore(context)
        assertTrue(store2.isCallActive())
        assertEquals("Puck", store2.getVoiceName())
        assertEquals("bn-BD", store2.getLanguage())
        assertEquals("YouTube-এ গান চালাও", store2.getLastGoal())

        // Clearing call active clears all continuity fields
        store2.setCallActive(false)
        assertFalse(store2.isCallActive())
        assertNull(store2.getVoiceName())
        assertNull(store2.getLanguage())
        assertNull(store2.getLastGoal())
    }

    @Test
    fun testDropDiagnostics_RecordsReasonAndCounts() {
        assertNull(LiveVoiceDiagnostics.data.value.lastDropReason)
        assertEquals(0, LiveVoiceDiagnostics.data.value.micRestartCount)
        assertEquals(0, LiveVoiceDiagnostics.data.value.autoRestartCount)
        assertEquals(0, LiveVoiceDiagnostics.data.value.interruptedCount)

        LiveVoiceDiagnostics.recordDropReason("mic-died: record.read returned -3")
        assertEquals("mic-died: record.read returned -3", LiveVoiceDiagnostics.data.value.lastDropReason)
        assertEquals("mic-died: record.read returned -3", LiveVoiceDiagnostics.data.value.lastError)
        assertTrue(LiveVoiceDiagnostics.data.value.frameLogs.any { it.contains("DROP_REASON") })

        LiveVoiceDiagnostics.recordMicRestart()
        LiveVoiceDiagnostics.recordMicRestart()
        assertEquals(2, LiveVoiceDiagnostics.data.value.micRestartCount)

        LiveVoiceDiagnostics.recordAutoRestart("terminal-error: websocket closed")
        assertEquals(1, LiveVoiceDiagnostics.data.value.autoRestartCount)
        assertNotNull(LiveVoiceDiagnostics.data.value.lastAutoRestartAt)

        LiveVoiceDiagnostics.recordResumedWithContext(false)
        assertEquals(false, LiveVoiceDiagnostics.data.value.lastResumedWithContext)

        LiveVoiceDiagnostics.recordInterrupted()
        LiveVoiceDiagnostics.recordInterrupted()
        assertEquals(2, LiveVoiceDiagnostics.data.value.interruptedCount)
        assertTrue(LiveVoiceDiagnostics.data.value.frameLogs.any { it.contains("INTERRUPTED") })
    }

    @Test
    fun testAudioCaptureRetryPolicy_BoundedRetriesAndBackoff() {
        val policy = AudioCaptureRetryPolicy()
        assertTrue(policy.shouldRetry(0))
        assertTrue(policy.shouldRetry(9))
        assertFalse(policy.shouldRetry(10))
        assertFalse(policy.shouldRetry(11))
        assertEquals(250L, policy.delayBeforeRetryMs(1))
        assertEquals(500L, policy.delayBeforeRetryMs(2))
        assertEquals(2_000L, policy.delayBeforeRetryMs(8))
        assertEquals(2_000L, policy.delayBeforeRetryMs(99))
    }
}

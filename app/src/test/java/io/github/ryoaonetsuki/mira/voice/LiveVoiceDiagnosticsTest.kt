package io.github.ryoaonetsuki.mira.voice

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LiveVoiceDiagnosticsTest {

    private lateinit var context: Context
    private lateinit var store: SessionResumptionStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        store = SessionResumptionStore(context)
        store.clearHandle()
        LiveVoiceDiagnostics.reset()
    }

    @Test
    fun testRecordReconnectAttempt() {
        assertEquals(0, LiveVoiceDiagnostics.data.value.reconnectAttemptCount)

        LiveVoiceDiagnostics.recordReconnectAttempt(1)
        assertEquals(1, LiveVoiceDiagnostics.data.value.reconnectAttemptCount)
        assertTrue(LiveVoiceDiagnostics.data.value.frameLogs.any { it.contains("RECONNECT_ATTEMPT attempt=1") })

        LiveVoiceDiagnostics.recordReconnectAttempt(3)
        assertEquals(3, LiveVoiceDiagnostics.data.value.reconnectAttemptCount)
        assertTrue(LiveVoiceDiagnostics.data.value.frameLogs.any { it.contains("RECONNECT_ATTEMPT attempt=3") })
    }

    @Test
    fun testRecordResumptionHandleEvent_LengthsOnlyNeverValues() {
        assertNull(LiveVoiceDiagnostics.data.value.lastResumptionHandleEvent)

        val secretHandle = "super_secret_resumption_token_12345"
        LiveVoiceDiagnostics.recordResumptionHandleEvent("saved (len=${secretHandle.length})")

        val event = LiveVoiceDiagnostics.data.value.lastResumptionHandleEvent
        assertNotNull(event)
        assertEquals("saved (len=35)", event)
        assertFalse("Raw handle value must NEVER be present in diagnostics", event!!.contains(secretHandle))

        // Check frame logs
        assertTrue(LiveVoiceDiagnostics.data.value.frameLogs.any {
            it.contains("RESUMPTION_HANDLE event=saved (len=35)") && !it.contains(secretHandle)
        })
    }

    @Test
    fun testRecordGoAway() {
        assertNull(LiveVoiceDiagnostics.data.value.lastGoAwayTimestamp)

        val timestamp = 1_700_000_000_000L
        LiveVoiceDiagnostics.recordGoAway(timestamp)

        assertEquals(timestamp, LiveVoiceDiagnostics.data.value.lastGoAwayTimestamp)
        assertTrue(LiveVoiceDiagnostics.data.value.frameLogs.any { it.contains("GOAWAY_RECEIVED timestamp=$timestamp") })
    }

    @Test
    fun testRecordStage4Timeout() {
        assertNull(LiveVoiceDiagnostics.data.value.lastStage4TimeoutTimestamp)

        val timestamp = 1_700_000_015_000L
        LiveVoiceDiagnostics.recordStage4Timeout(timestamp)

        assertEquals(timestamp, LiveVoiceDiagnostics.data.value.lastStage4TimeoutTimestamp)
        assertTrue(LiveVoiceDiagnostics.data.value.frameLogs.any { it.contains("STAGE4_TIMEOUT timestamp=$timestamp") })
    }

    @Test
    fun testUpdateSessionManagerState() {
        assertEquals("IDLE", LiveVoiceDiagnostics.data.value.sessionManagerState)

        LiveVoiceDiagnostics.updateSessionManagerState("CONNECTING")
        assertEquals("CONNECTING", LiveVoiceDiagnostics.data.value.sessionManagerState)

        LiveVoiceDiagnostics.updateSessionManagerState("CONNECTED")
        assertEquals("CONNECTED", LiveVoiceDiagnostics.data.value.sessionManagerState)

        LiveVoiceDiagnostics.updateSessionManagerState("RECONNECTING")
        assertEquals("RECONNECTING", LiveVoiceDiagnostics.data.value.sessionManagerState)
    }

    @Test
    fun testResumptionStore_RecordsHandleEventsAutomatically() {
        val handle = "test_handle_abc_123"

        // 1. Save handle -> records saved (len=19)
        store.saveHandle(handle, timestampMs = 1_000_000L)
        assertEquals("saved (len=${handle.length})", LiveVoiceDiagnostics.data.value.lastResumptionHandleEvent)

        // 2. Consume valid handle -> records consumed (len=19)
        val consumed = store.consumeHandle(currentTimeMs = 1_001_000L)
        assertEquals(handle, consumed)
        assertEquals("consumed (len=${handle.length})", LiveVoiceDiagnostics.data.value.lastResumptionHandleEvent)

        // 3. Expired handle -> records expired (len=19)
        store.saveHandle(handle, timestampMs = 1_000_000L)
        val expired = store.getHandle(currentTimeMs = 1_000_000L + 7_200_001L)
        assertNull(expired)
        assertEquals("expired (len=${handle.length})", LiveVoiceDiagnostics.data.value.lastResumptionHandleEvent)

        // 4. Clear handle -> records cleared
        store.saveHandle(handle)
        store.clearHandle()
        assertEquals("cleared", LiveVoiceDiagnostics.data.value.lastResumptionHandleEvent)
    }

    @Test
    fun testAecDiagnosticsStateAndReset() {
        assertFalse(LiveVoiceDiagnostics.data.value.aecAvailable)
        assertFalse(LiveVoiceDiagnostics.data.value.aecEnabled)

        LiveVoiceDiagnostics.updateAecAvailable(true)
        assertTrue(LiveVoiceDiagnostics.data.value.aecAvailable)
        assertFalse(LiveVoiceDiagnostics.data.value.aecEnabled)

        LiveVoiceDiagnostics.updateAecEnabled(true)
        assertTrue(LiveVoiceDiagnostics.data.value.aecAvailable)
        assertTrue(LiveVoiceDiagnostics.data.value.aecEnabled)

        LiveVoiceDiagnostics.updateAecState(available = true, enabled = false)
        assertTrue(LiveVoiceDiagnostics.data.value.aecAvailable)
        assertFalse(LiveVoiceDiagnostics.data.value.aecEnabled)

        LiveVoiceDiagnostics.updateAecState(available = false, enabled = false)
        assertFalse(LiveVoiceDiagnostics.data.value.aecAvailable)
        assertFalse(LiveVoiceDiagnostics.data.value.aecEnabled)

        LiveVoiceDiagnostics.updateAecState(available = true, enabled = true)
        LiveVoiceDiagnostics.reset()
        assertFalse(LiveVoiceDiagnostics.data.value.aecAvailable)
        assertFalse(LiveVoiceDiagnostics.data.value.aecEnabled)
    }

    @Test
    fun testReset() {
        LiveVoiceDiagnostics.recordReconnectAttempt(4)
        LiveVoiceDiagnostics.recordGoAway(12345L)
        LiveVoiceDiagnostics.recordStage4Timeout(67890L)
        LiveVoiceDiagnostics.updateSessionManagerState("RECONNECTING")
        LiveVoiceDiagnostics.updateAecState(available = true, enabled = true)

        LiveVoiceDiagnostics.reset()

        assertEquals(0, LiveVoiceDiagnostics.data.value.reconnectAttemptCount)
        assertNull(LiveVoiceDiagnostics.data.value.lastGoAwayTimestamp)
        assertNull(LiveVoiceDiagnostics.data.value.lastStage4TimeoutTimestamp)
        assertEquals("IDLE", LiveVoiceDiagnostics.data.value.sessionManagerState)
        assertNull(LiveVoiceDiagnostics.data.value.lastResumptionHandleEvent)
        assertFalse(LiveVoiceDiagnostics.data.value.aecAvailable)
        assertFalse(LiveVoiceDiagnostics.data.value.aecEnabled)
    }
}

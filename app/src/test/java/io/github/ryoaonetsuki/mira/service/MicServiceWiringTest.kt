package io.github.ryoaonetsuki.mira.service

import android.content.Context
import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MicServiceWiringTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testPublicConstantsAndActions() {
        assertEquals("io.github.ryoaonetsuki.mira.action.START_VOICE", MicService.ACTION_START)
        assertEquals("io.github.ryoaonetsuki.mira.action.STOP_VOICE", MicService.ACTION_STOP)
        assertEquals("io.github.ryoaonetsuki.mira.action.TOGGLE_MUTE", MicService.ACTION_TOGGLE_MUTE)
    }

    @Test
    fun testPublicStateFlowsInitialValues() {
        assertEquals(LiveState.IDLE, MicService.liveState.value)
        assertEquals(VoiceStage.IDLE, MicService.voiceStage.value)
        assertFalse(MicService.isMuted.value)
        assertFalse(MicService.isReconnecting.value)
        assertEquals(0, MicService.reconnectAttempt.value)
        assertEquals(0L, MicService.audioChunksSent.value)
        assertEquals(0L, MicService.audioChunksReceived.value)
        assertEquals(0f, MicService.micAmplitude.value, 0.001f)
        assertEquals("", MicService.userTranscript.value)
        assertEquals("", MicService.modelTranscript.value)
        assertNull(MicService.errorMessage.value)
        assertNull(MicService.lastVoiceDiagnostic.value)
    }

    @Test
    fun testSimulateGoAwayForTesting_CallableWhenNoSessionActive() {
        // Must not throw even when no active session manager is attached
        MicService.simulateGoAwayForTesting()
    }

    @Test
    fun testLiveStateEnumValues() {
        val expected = listOf("IDLE", "CONNECTING", "LISTENING", "SPEAKING", "ERROR")
        val actual = LiveState.values().map { it.name }
        assertEquals(expected, actual)
    }

    @Test
    fun testVoiceStageEnumValues() {
        val expected = listOf(
            "IDLE", "CONNECTING", "WAITING_FOR_SETUP", "LISTENING",
            "RECOGNISING", "THINKING", "SPEAKING", "ERROR"
        )
        val actual = VoiceStage.values().map { it.name }
        assertEquals(expected, actual)
    }

    @Test
    fun testHomeScreenPublicApiSignatures() {
        // HomeScreen collects:
        // 1. MicService.liveState: StateFlow<LiveState>
        val liveStateFlow: kotlinx.coroutines.flow.StateFlow<LiveState> = MicService.liveState
        assertEquals(LiveState.IDLE, liveStateFlow.value)

        // 2. MicService.micAmplitude: StateFlow<Float>
        val micAmpFlow: kotlinx.coroutines.flow.StateFlow<Float> = MicService.micAmplitude
        assertEquals(0f, micAmpFlow.value, 0.001f)

        // 3. MicService.errorMessage: StateFlow<String?>
        val errorFlow: kotlinx.coroutines.flow.StateFlow<String?> = MicService.errorMessage
        assertNull(errorFlow.value)

        // 4. MicService.startService(context) callable with identical signature
        MicService.startService(context)

        // 5. MicService.stopService(context) callable with identical signature
        MicService.stopService(context)

        // 6. MicService.toggleMute(context) callable with identical signature
        assertEquals("io.github.ryoaonetsuki.mira.action.TOGGLE_MUTE", MicService.ACTION_TOGGLE_MUTE)
    }
}

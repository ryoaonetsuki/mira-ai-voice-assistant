package io.github.ryoaonetsuki.mira.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoiceDiagnosticsData(
    val lastStage: String = "IDLE",
    val lastWsCode: Int? = null,
    val setupDurationMs: Long? = null,
    val chunksSent: Long = 0L,
    val chunksReceived: Long = 0L,
    val lastError: String? = null,
    val liveCapabilityResult: String = "Not checked yet"
)

object LiveVoiceDiagnostics {
    private val _data = MutableStateFlow(VoiceDiagnosticsData())
    val data: StateFlow<VoiceDiagnosticsData> = _data.asStateFlow()

    fun updateStage(stage: String) {
        _data.value = _data.value.copy(lastStage = stage)
    }

    fun updateWsCode(code: Int?) {
        _data.value = _data.value.copy(lastWsCode = code)
    }

    fun updateSetupDuration(ms: Long?) {
        _data.value = _data.value.copy(setupDurationMs = ms)
    }

    fun updateChunks(sent: Long, received: Long) {
        _data.value = _data.value.copy(chunksSent = sent, chunksReceived = received)
    }

    fun updateLastError(error: String?) {
        _data.value = _data.value.copy(lastError = error)
    }

    fun updateLiveCapability(result: String) {
        _data.value = _data.value.copy(liveCapabilityResult = result)
    }

    fun reset() {
        _data.value = VoiceDiagnosticsData(
            liveCapabilityResult = _data.value.liveCapabilityResult
        )
    }
}

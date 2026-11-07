package io.github.ryoaonetsuki.mira.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StageStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED
}

data class DiagnosticStageResult(
    val stageNumber: Int,
    val name: String,
    val status: StageStatus = StageStatus.PENDING,
    val detail: String? = null,
    val durationMs: Long? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class VoiceDiagnosticsData(
    val lastStage: String = "IDLE",
    val lastWsCode: Int? = null,
    val setupDurationMs: Long? = null,
    val chunksSent: Long = 0L,
    val chunksReceived: Long = 0L,
    val lastError: String? = null,
    val liveCapabilityResult: String = "Not checked yet",
    val isDiagnosticRunning: Boolean = false,
    val diagnosticStages: List<DiagnosticStageResult> = emptyList(),
    val diagnosticLastRunAt: Long? = null,
    val diagnosticOutcome: String? = null
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

    fun setDiagnosticRunning(running: Boolean) {
        _data.value = _data.value.copy(isDiagnosticRunning = running)
    }

    fun initDiagnosticStages(stages: List<DiagnosticStageResult>) {
        _data.value = _data.value.copy(
            isDiagnosticRunning = true,
            diagnosticStages = stages,
            diagnosticOutcome = "Running Live diagnostic...",
            diagnosticLastRunAt = System.currentTimeMillis()
        )
    }

    fun updateDiagnosticStage(
        stageNumber: Int,
        status: StageStatus,
        detail: String? = null,
        durationMs: Long? = null
    ) {
        val updated = _data.value.diagnosticStages.map { stage ->
            if (stage.stageNumber == stageNumber) {
                stage.copy(
                    status = status,
                    detail = detail ?: stage.detail,
                    durationMs = durationMs ?: stage.durationMs,
                    timestampMs = System.currentTimeMillis()
                )
            } else {
                stage
            }
        }
        _data.value = _data.value.copy(diagnosticStages = updated)
    }

    fun finishDiagnostic(outcome: String) {
        _data.value = _data.value.copy(
            isDiagnosticRunning = false,
            diagnosticOutcome = outcome,
            diagnosticLastRunAt = System.currentTimeMillis()
        )
    }

    fun reset() {
        _data.value = VoiceDiagnosticsData(
            liveCapabilityResult = _data.value.liveCapabilityResult
        )
    }
}

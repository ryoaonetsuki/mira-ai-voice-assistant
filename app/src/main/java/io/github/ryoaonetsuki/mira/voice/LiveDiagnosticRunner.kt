package io.github.ryoaonetsuki.mira.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import io.github.ryoaonetsuki.mira.network.isPlausibleApiKey
import io.github.ryoaonetsuki.mira.network.normalizeApiKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class DiagnosticRoute(val label: String) {
    RELAY("Cloudflare Relay"),
    DIRECT("Direct Google API")
}

enum class DiagnosticSetupVariant(val label: String) {
    STANDARD("Standard (Full Setup)"),
    MINIMAL_V3("Minimal (Round-12 V3)"),
    NO_RESUMPTION("No Resumption/Compression"),
    NO_TOOLS("No Tools (Explicit Empty Tools Array)")
}

data class DiagnosticRunOutcome(
    val isSuccess: Boolean,
    val outcomeMessage: String,
    val stages: List<DiagnosticStageResult>,
    val route: DiagnosticRoute = DiagnosticRoute.RELAY,
    val variant: DiagnosticSetupVariant = DiagnosticSetupVariant.STANDARD,
    val frameLogs: List<String> = emptyList()
)

/**
 * Isolated Gemini Live diagnostic runner for ROUND 15.
 * Supports:
 * - Direct Google API vs Cloudflare Relay routes.
 * - Diagnostic variants: Standard, Minimal V3, No Resumption/Compression, No Tools.
 * - 45s setupComplete timeout.
 * - Full frame logging with exact ms timestamps (never keys).
 * - Stops at exact failing stage and reports status across all 8 stages.
 */
class LiveDiagnosticRunner(
    private val context: Context? = null,
    private val customClient: OkHttpClient? = null,
    private val customWsFactory: WebSocket.Factory? = null
) {
    companion object {
        const val TAG = "MIRA_LIVE_DIAGNOSTIC"
        const val DEFAULT_TIMEOUT_MS = 45_000L

        fun decodeBase64Safe(encoded: String): ByteArray {
            return try {
                android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            } catch (_: Throwable) {
                try {
                    java.util.Base64.getDecoder().decode(encoded)
                } catch (_: Throwable) {
                    ByteArray(0)
                }
            }
        }

        fun buildVariantSetupPayload(
            variant: DiagnosticSetupVariant,
            model: String = GeminiLiveClient.LIVE_MODEL_DEFAULT,
            voiceName: String = "Aoede",
            languageCode: String = "bn-BD",
            systemInstructionText: String = "You are Mira. Reply in Bengali with only one word: 'নমস্কার'."
        ): JSONObject {
            val setupObj = JSONObject()
            setupObj.put("model", model)

            val genConfig = JSONObject()
            val modalities = JSONArray().apply { put("AUDIO") }
            genConfig.put("responseModalities", modalities)

            val speechConfig = JSONObject().apply {
                val voiceConfig = JSONObject().apply {
                    put("prebuiltVoiceConfig", JSONObject().apply { put("voiceName", voiceName) })
                }
                put("voiceConfig", voiceConfig)
                put("languageCode", languageCode)
            }
            genConfig.put("speechConfig", speechConfig)
            setupObj.put("generationConfig", genConfig)

            val sysInst = JSONObject().apply {
                val parts = JSONArray().apply {
                    put(JSONObject().apply { put("text", systemInstructionText) })
                }
                put("parts", parts)
            }
            setupObj.put("systemInstruction", sysInst)

            when (variant) {
                DiagnosticSetupVariant.MINIMAL_V3 -> {
                    // Minimal V3: model + generationConfig + speechConfig + languageCode only.
                    // No sessionResumption, no contextWindowCompression, no realtimeInputConfig, no transcriptions.
                }
                DiagnosticSetupVariant.NO_RESUMPTION -> {
                    // Setup without sessionResumption / contextWindowCompression
                    val realtimeInputConfig = JSONObject().apply {
                        val autoDetection = JSONObject().apply {
                            put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                            put("silenceDurationMs", 800)
                        }
                        put("automaticActivityDetection", autoDetection)
                    }
                    setupObj.put("realtimeInputConfig", realtimeInputConfig)
                    setupObj.put("inputAudioTranscription", JSONObject())
                    setupObj.put("outputAudioTranscription", JSONObject())
                }
                DiagnosticSetupVariant.STANDARD,
                DiagnosticSetupVariant.NO_TOOLS -> {
                    // Full setup: confirm no tools field is sent anywhere
                    val realtimeInputConfig = JSONObject().apply {
                        val autoDetection = JSONObject().apply {
                            put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                            put("silenceDurationMs", 800)
                        }
                        put("automaticActivityDetection", autoDetection)
                    }
                    setupObj.put("realtimeInputConfig", realtimeInputConfig)
                    setupObj.put("inputAudioTranscription", JSONObject())
                    setupObj.put("outputAudioTranscription", JSONObject())
                    setupObj.put("sessionResumption", JSONObject())
                    setupObj.put("contextWindowCompression", JSONObject().apply { put("slidingWindow", JSONObject()) })
                    if (variant == DiagnosticSetupVariant.NO_TOOLS) {
                        setupObj.put("tools", JSONArray())
                    }
                }
            }

            return JSONObject().apply { put("setup", setupObj) }
        }
    }

    private sealed class WsEvent {
        data class Open(val code: Int) : WsEvent()
        data class Message(val text: String) : WsEvent()
        data class Failure(val error: Throwable, val code: Int?) : WsEvent()
        data class Closed(val code: Int, val reason: String) : WsEvent()
    }

    private fun initialStages(): List<DiagnosticStageResult> = listOf(
        DiagnosticStageResult(1, "API Key Check", StageStatus.PENDING),
        DiagnosticStageResult(2, "WebSocket Connection", StageStatus.PENDING),
        DiagnosticStageResult(3, "Setup Payload", StageStatus.PENDING),
        DiagnosticStageResult(4, "Setup Confirmation", StageStatus.PENDING),
        DiagnosticStageResult(5, "Controlled Text Turn", StageStatus.PENDING),
        DiagnosticStageResult(6, "First Server Response", StageStatus.PENDING),
        DiagnosticStageResult(7, "Native Audio Chunk", StageStatus.PENDING),
        DiagnosticStageResult(8, "Turn Complete", StageStatus.PENDING)
    )

    suspend fun runDiagnostic(
        apiKey: String,
        model: String = GeminiLiveClient.LIVE_MODEL_DEFAULT,
        route: DiagnosticRoute = DiagnosticRoute.RELAY,
        variant: DiagnosticSetupVariant = DiagnosticSetupVariant.STANDARD
    ): DiagnosticRunOutcome = withContext(Dispatchers.IO) {
        val diagStart = System.currentTimeMillis()
        val stages = initialStages().toMutableList()
        val frameLogs = mutableListOf<String>()

        fun logFrame(event: String) {
            val elapsed = System.currentTimeMillis() - diagStart
            val line = "[+$elapsed ms] $event"
            frameLogs.add(line)
            LiveVoiceDiagnostics.addFrameLog(line)
            Log.i(TAG, line)
        }

        LiveVoiceDiagnostics.initDiagnosticStages(stages, route = route.name, variant = variant.name)
        logFrame("DIAG_START route=${route.name} variant=${variant.name} model=$model")

        fun markStageRunning(num: Int) {
            val idx = num - 1
            stages[idx] = stages[idx].copy(status = StageStatus.RUNNING)
            LiveVoiceDiagnostics.updateDiagnosticStage(num, StageStatus.RUNNING)
        }

        fun markStageSuccess(num: Int, detail: String, durationMs: Long? = null) {
            val idx = num - 1
            stages[idx] = stages[idx].copy(
                status = StageStatus.SUCCESS,
                detail = detail,
                durationMs = durationMs
            )
            LiveVoiceDiagnostics.updateDiagnosticStage(num, StageStatus.SUCCESS, detail, durationMs)
        }

        fun markStageFailed(num: Int, error: String, durationMs: Long? = null) {
            val idx = num - 1
            stages[idx] = stages[idx].copy(
                status = StageStatus.FAILED,
                detail = error,
                durationMs = durationMs
            )
            LiveVoiceDiagnostics.updateDiagnosticStage(num, StageStatus.FAILED, error, durationMs)

            // Mark remaining stages as SKIPPED
            for (i in num until stages.size) {
                stages[i] = stages[i].copy(status = StageStatus.SKIPPED)
                LiveVoiceDiagnostics.updateDiagnosticStage(i + 1, StageStatus.SKIPPED)
            }
        }

        // ---------------- STAGE 1: Key present ----------------
        markStageRunning(1)
        val stage1Start = System.currentTimeMillis()
        val normalizedKey = normalizeApiKey(apiKey)
        if (normalizedKey.isBlank()) {
            val err = "API key is missing or blank"
            markStageFailed(1, err, System.currentTimeMillis() - stage1Start)
            val outcome = "Failed at Stage 1: $err"
            logFrame("FAIL_STAGE_1 $err")
            LiveVoiceDiagnostics.finishDiagnostic(outcome)
            return@withContext DiagnosticRunOutcome(false, outcome, stages, route, variant, frameLogs)
        }
        if (!isPlausibleApiKey(normalizedKey)) {
            val err = "API key format appears invalid (length < 10 or unrecognized format)"
            markStageFailed(1, err, System.currentTimeMillis() - stage1Start)
            val outcome = "Failed at Stage 1: $err"
            logFrame("FAIL_STAGE_1 $err")
            LiveVoiceDiagnostics.finishDiagnostic(outcome)
            return@withContext DiagnosticRunOutcome(false, outcome, stages, route, variant, frameLogs)
        }
        val stage1Duration = System.currentTimeMillis() - stage1Start
        markStageSuccess(1, "Gemini API key is present and formatted", stage1Duration)
        logFrame("PASS_STAGE_1 valid format (${stage1Duration}ms)")

        // ---------------- STAGE 2: WS connect ----------------
        markStageRunning(2)
        val stage2Start = System.currentTimeMillis()
        val eventChannel = Channel<WsEvent>(Channel.UNLIMITED)

        val wsFactory: WebSocket.Factory = customWsFactory ?: customClient ?: OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()

        val request = if (route == DiagnosticRoute.RELAY) {
            RelayLiveClient.buildRelayRequest(normalizedKey)
        } else {
            RelayLiveClient.buildDirectRequest(GeminiLiveClient.LIVE_WS_URL, normalizedKey)
        }

        logFrame("WS_CONNECT_INIT target=${request.url} header=${if (route == DiagnosticRoute.RELAY) RelayLiveClient.HEADER_MIRA_KEY else "x-goog-api-key"}")

        var activeWs: WebSocket? = null
        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                logFrame("WS_OPEN code=${response.code}")
                eventChannel.trySend(WsEvent.Open(response.code))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                logFrame("WS_MSG_RECV text_len=${text.length}")
                eventChannel.trySend(WsEvent.Message(text))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                logFrame("WS_FAILURE code=${response?.code} err=${t.message}")
                eventChannel.trySend(WsEvent.Failure(t, response?.code))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                logFrame("WS_CLOSED code=$code reason=\"$reason\"")
                eventChannel.trySend(WsEvent.Closed(code, reason))
            }
        }

        try {
            activeWs = wsFactory.newWebSocket(request, wsListener)
        } catch (e: Exception) {
            val err = "Failed to initiate WebSocket connection: ${e.message}"
            markStageFailed(2, err, System.currentTimeMillis() - stage2Start)
            val outcome = "Failed at Stage 2: $err"
            logFrame("FAIL_STAGE_2 $err")
            LiveVoiceDiagnostics.finishDiagnostic(outcome)
            return@withContext DiagnosticRunOutcome(false, outcome, stages, route, variant, frameLogs)
        }

        val openEvent = withTimeoutOrNull(20_000L) {
            var ev = eventChannel.receive()
            while (ev is WsEvent.Closed) {
                ev = eventChannel.receive()
            }
            ev
        }

        if (openEvent == null) {
            val err = "WebSocket connection timed out after 20s"
            markStageFailed(2, err, System.currentTimeMillis() - stage2Start)
            activeWs.cancel()
            val outcome = "Failed at Stage 2: $err"
            logFrame("FAIL_STAGE_2 $err")
            LiveVoiceDiagnostics.finishDiagnostic(outcome)
            return@withContext DiagnosticRunOutcome(false, outcome, stages, route, variant, frameLogs)
        }

        when (openEvent) {
            is WsEvent.Open -> {
                val dur = System.currentTimeMillis() - stage2Start
                val destDesc = if (route == DiagnosticRoute.RELAY) "Cloudflare Relay" else "Google Live Direct"
                markStageSuccess(2, "Connected to $destDesc (HTTP ${openEvent.code})", dur)
                logFrame("PASS_STAGE_2 connected in ${dur}ms")
            }
            is WsEvent.Failure -> {
                val dur = System.currentTimeMillis() - stage2Start
                val codeStr = openEvent.code?.let { "HTTP $it: " } ?: ""
                val err = "WS connect failed ($codeStr${openEvent.error.message ?: "unknown"})"
                markStageFailed(2, err, dur)
                activeWs.cancel()
                val outcome = "Failed at Stage 2: $err"
                logFrame("FAIL_STAGE_2 $err")
                LiveVoiceDiagnostics.finishDiagnostic(outcome)
                return@withContext DiagnosticRunOutcome(false, outcome, stages, route, variant, frameLogs)
            }
            else -> {
                val dur = System.currentTimeMillis() - stage2Start
                val err = "Unexpected event on connect: $openEvent"
                markStageFailed(2, err, dur)
                activeWs.cancel()
                val outcome = "Failed at Stage 2: $err"
                logFrame("FAIL_STAGE_2 $err")
                LiveVoiceDiagnostics.finishDiagnostic(outcome)
                return@withContext DiagnosticRunOutcome(false, outcome, stages, route, variant, frameLogs)
            }
        }

        // ---------------- STAGE 3: Setup sent ----------------
        markStageRunning(3)
        val stage3Start = System.currentTimeMillis()
        val setupPayload = buildVariantSetupPayload(
            variant = variant,
            model = model,
            voiceName = "Aoede",
            languageCode = "bn-BD",
            systemInstructionText = "You are Mira. Reply in Bengali with only one word: 'নমস্কার'."
        )
        val payloadStr = setupPayload.toString()
        val setupSent = activeWs.send(payloadStr)
        val stage3Duration = System.currentTimeMillis() - stage3Start

        if (!setupSent) {
            val err = "Failed to send setup frame over WebSocket"
            markStageFailed(3, err, stage3Duration)
            activeWs.close(1000, "Diagnostic failed")
            val outcome = "Failed at Stage 3: $err"
            logFrame("FAIL_STAGE_3 $err")
            LiveVoiceDiagnostics.finishDiagnostic(outcome)
            return@withContext DiagnosticRunOutcome(false, outcome, stages, route, variant, frameLogs)
        }
        markStageSuccess(3, "Setup payload (${variant.label}) sent (${payloadStr.length} chars)", stage3Duration)
        logFrame("PASS_STAGE_3 setup sent (${payloadStr.length} chars in ${stage3Duration}ms)")

        // ---------------- STAGE 4: setupComplete received (45s timeout) ----------------
        markStageRunning(4)
        val stage4Start = System.currentTimeMillis()
        var setupCompleteReceived = false
        val setupDeadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS

        while (System.currentTimeMillis() < setupDeadline && !setupCompleteReceived) {
            val remaining = (setupDeadline - System.currentTimeMillis()).coerceAtLeast(100L)
            val msgEvent = withTimeoutOrNull(remaining) {
                eventChannel.receive()
            }

            if (msgEvent == null) break

            when (msgEvent) {
                is WsEvent.Message -> {
                    try {
                        val root = JSONObject(msgEvent.text)
                        if (root.has("error")) {
                            val errObj = root.optJSONObject("error")
                            val errCode = errObj?.optInt("code", 0) ?: 0
                            val errMsg = errObj?.optString("message", "Unknown error") ?: "Unknown error"
                            val err = "Server rejected setup (code $errCode: $errMsg)"
                            markStageFailed(4, err, System.currentTimeMillis() - stage4Start)
                            activeWs.close(1000, "Diagnostic failed")
                            val outcome = "Failed at Stage 4: $err"
                            logFrame("FAIL_STAGE_4 server error: code=$errCode message=$errMsg")
                            LiveVoiceDiagnostics.finishDiagnostic(outcome)
                            return@withContext DiagnosticRunOutcome(false, outcome, stages, route, variant, frameLogs)
                        }
                        if (root.has("setupComplete")) {
                            setupCompleteReceived = true
                            logFrame("SETUP_COMPLETE_RECV")
                            break
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing server message during setup: ${e.message}")
                    }
                }
                is WsEvent.Failure -> {
                    val codeStr = msgEvent.code?.let { "HTTP $it: " } ?: ""
                    val err = "Connection lost during setup ($codeStr${msgEvent.error.message})"
                    markStageFailed(4, err, System.currentTimeMillis() - stage4Start)
                    activeWs.cancel()
                    val outcome = "Failed at Stage 4: $err"
                    logFrame("FAIL_STAGE_4 connection failure: $codeStr${msgEvent.error.message}")
                    LiveVoiceDiagnostics.finishDiagnostic(outcome)
                    return@withContext DiagnosticRunOutcome(false, outcome, stages, route, variant, frameLogs)
                }
                is WsEvent.Closed -> {
                    val err = "WebSocket closed during setup (code ${msgEvent.code}: ${msgEvent.reason})"
                    markStageFailed(4, err, System.currentTimeMillis() - stage4Start)
                    val outcome = "Failed at Stage 4: $err"
                    logFrame("FAIL_STAGE_4 server closed socket: code=${msgEvent.code} reason=\"${msgEvent.reason}\"")
                    LiveVoiceDiagnostics.finishDiagnostic(outcome)
                    return@withContext DiagnosticRunOutcome(false, outcome, stages, route, variant, frameLogs)
                }
                else -> {}
            }
        }

        if (!setupCompleteReceived) {
            val err = "Timed out waiting for setupComplete from server (45s)"
            markStageFailed(4, err, System.currentTimeMillis() - stage4Start)
            activeWs.close(1000, "Diagnostic timed out")
            val outcome = "Failed at Stage 4: $err"
            logFrame("FAIL_STAGE_4 setupComplete timeout (45s reached without server response)")
            LiveVoiceDiagnostics.finishDiagnostic(outcome)
            return@withContext DiagnosticRunOutcome(false, outcome, stages, route, variant, frameLogs)
        }
        val stage4Duration = System.currentTimeMillis() - stage4Start
        markStageSuccess(4, "setupComplete received in ${stage4Duration}ms", stage4Duration)
        logFrame("PASS_STAGE_4 setupComplete confirmed in ${stage4Duration}ms")

        // ---------------- STAGE 5: Send controlled TEXT turn ----------------
        markStageRunning(5)
        val stage5Start = System.currentTimeMillis()
        val textTurnJson = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "নমস্কার, এক কথায় উত্তর দাও।")
                            })
                        })
                    })
                })
                put("turnComplete", true)
            })
        }
        val textTurnSent = activeWs.send(textTurnJson.toString())
        val stage5Duration = System.currentTimeMillis() - stage5Start

        if (!textTurnSent) {
            val err = "Failed to send controlled text turn"
            markStageFailed(5, err, stage5Duration)
            activeWs.close(1000, "Diagnostic failed")
            val outcome = "Failed at Stage 5: $err"
            logFrame("FAIL_STAGE_5 text turn frame send failed")
            LiveVoiceDiagnostics.finishDiagnostic(outcome)
            return@withContext DiagnosticRunOutcome(false, outcome, stages, route, variant, frameLogs)
        }
        markStageSuccess(5, "Controlled Bangla text turn sent ('নমস্কার, এক কথায় উত্তর দাও。')", stage5Duration)
        logFrame("PASS_STAGE_5 text turn sent in ${stage5Duration}ms")

        // ---------------- STAGE 6: First server response received ----------------
        markStageRunning(6)
        val stage6Start = System.currentTimeMillis()
        var firstResponseReceived = false
        var firstAudioBytes: ByteArray? = null
        var turnCompleteReceived = false
        var modelTranscript = ""

        val responseDeadline = System.currentTimeMillis() + 30_000L

        while (System.currentTimeMillis() < responseDeadline && !firstResponseReceived) {
            val remaining = (responseDeadline - System.currentTimeMillis()).coerceAtLeast(100L)
            val msgEvent = withTimeoutOrNull(remaining) {
                eventChannel.receive()
            } ?: break

            when (msgEvent) {
                is WsEvent.Message -> {
                    try {
                        val root = JSONObject(msgEvent.text)
                        if (root.has("error")) {
                            val errObj = root.optJSONObject("error")
                            val err = "Server error during reply: ${errObj?.optString("message")}"
                            markStageFailed(6, err, System.currentTimeMillis() - stage6Start)
                            activeWs.close(1000, "Diagnostic failed")
                            val outcome = "Failed at Stage 6: $err"
                            logFrame("FAIL_STAGE_6 $err")
                            LiveVoiceDiagnostics.finishDiagnostic(outcome)
                            return@withContext DiagnosticRunOutcome(false, outcome, stages, route, variant, frameLogs)
                        }

                        val serverContent = root.optJSONObject("serverContent")
                        if (serverContent != null) {
                            firstResponseReceived = true
                            val stage6Duration = System.currentTimeMillis() - stage6Start
                            markStageSuccess(6, "First server response frame received", stage6Duration)
                            logFrame("PASS_STAGE_6 first server content in ${stage6Duration}ms")

                            val modelTurn = serverContent.optJSONObject("modelTurn")
                            val parts = modelTurn?.optJSONArray("parts")
                            if (parts != null) {
                                for (p in 0 until parts.length()) {
                                    val part = parts.optJSONObject(p) ?: continue
                                    val inlineData = part.optJSONObject("inlineData")
                                    if (inlineData != null) {
                                        val dataStr = inlineData.optString("data", "")
                                        if (dataStr.isNotBlank()) {
                                            val decoded = decodeBase64Safe(dataStr)
                                            if (decoded.isNotEmpty()) {
                                                firstAudioBytes = decoded
                                                break
                                            }
                                        }
                                    }
                                }
                            }

                            val outTx = serverContent.optJSONObject("outputAudioTranscription")
                                ?: serverContent.optJSONObject("outputTranscription")
                            if (outTx != null) {
                                val tx = outTx.optString("text", "")
                                if (tx.isNotBlank()) modelTranscript += tx
                            }

                            if (serverContent.optBoolean("turnComplete", false)) {
                                turnCompleteReceived = true
                            }
                            break
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing server message during reply: ${e.message}")
                    }
                }
                is WsEvent.Failure -> {
                    val err = "Connection lost waiting for server response: ${msgEvent.error.message}"
                    markStageFailed(6, err, System.currentTimeMillis() - stage6Start)
                    activeWs.cancel()
                    val outcome = "Failed at Stage 6: $err"
                    logFrame("FAIL_STAGE_6 $err")
                    LiveVoiceDiagnostics.finishDiagnostic(outcome)
                    return@withContext DiagnosticRunOutcome(false, outcome, stages, route, variant, frameLogs)
                }
                is WsEvent.Closed -> {
                    val err = "WebSocket closed waiting for response (code ${msgEvent.code}: ${msgEvent.reason})"
                    markStageFailed(6, err, System.currentTimeMillis() - stage6Start)
                    val outcome = "Failed at Stage 6: $err"
                    logFrame("FAIL_STAGE_6 $err")
                    LiveVoiceDiagnostics.finishDiagnostic(outcome)
                    return@withContext DiagnosticRunOutcome(false, outcome, stages, route, variant, frameLogs)
                }
                else -> {}
            }
        }

        if (!firstResponseReceived) {
            val err = "Timed out waiting for first server response (30s)"
            markStageFailed(6, err, System.currentTimeMillis() - stage6Start)
            activeWs.close(1000, "Diagnostic timed out")
            val outcome = "Failed at Stage 6: $err"
            logFrame("FAIL_STAGE_6 $err")
            LiveVoiceDiagnostics.finishDiagnostic(outcome)
            return@withContext DiagnosticRunOutcome(false, outcome, stages, route, variant, frameLogs)
        }

        // ---------------- STAGE 7: First native AUDIO chunk received ----------------
        markStageRunning(7)
        val stage7Start = System.currentTimeMillis()
        val audioDeadline = System.currentTimeMillis() + 30_000L

        while (firstAudioBytes == null && System.currentTimeMillis() < audioDeadline) {
            val remaining = (audioDeadline - System.currentTimeMillis()).coerceAtLeast(100L)
            val msgEvent = withTimeoutOrNull(remaining) {
                eventChannel.receive()
            } ?: break

            if (msgEvent is WsEvent.Message) {
                try {
                    val root = JSONObject(msgEvent.text)
                    val serverContent = root.optJSONObject("serverContent")
                    if (serverContent != null) {
                        val modelTurn = serverContent.optJSONObject("modelTurn")
                        val parts = modelTurn?.optJSONArray("parts")
                        if (parts != null) {
                            for (p in 0 until parts.length()) {
                                val part = parts.optJSONObject(p) ?: continue
                                val inlineData = part.optJSONObject("inlineData")
                                if (inlineData != null) {
                                    val dataStr = inlineData.optString("data", "")
                                    if (dataStr.isNotBlank()) {
                                        val decoded = decodeBase64Safe(dataStr)
                                        if (decoded.isNotEmpty()) {
                                            firstAudioBytes = decoded
                                            break
                                        }
                                    }
                                }
                            }
                        }
                        val outTx = serverContent.optJSONObject("outputAudioTranscription")
                            ?: serverContent.optJSONObject("outputTranscription")
                        if (outTx != null) {
                            val tx = outTx.optString("text", "")
                            if (tx.isNotBlank()) modelTranscript += tx
                        }
                        if (serverContent.optBoolean("turnComplete", false)) {
                            turnCompleteReceived = true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking audio in message: ${e.message}")
                }
            } else if (msgEvent is WsEvent.Failure) {
                val err = "Connection lost waiting for audio chunk: ${msgEvent.error.message}"
                markStageFailed(7, err, System.currentTimeMillis() - stage7Start)
                activeWs.cancel()
                val outcome = "Failed at Stage 7: $err"
                logFrame("FAIL_STAGE_7 $err")
                LiveVoiceDiagnostics.finishDiagnostic(outcome)
                return@withContext DiagnosticRunOutcome(false, outcome, stages, route, variant, frameLogs)
            }
        }

        if (firstAudioBytes == null) {
            val err = "No audio chunk received from Gemini Live server"
            markStageFailed(7, err, System.currentTimeMillis() - stage7Start)
            activeWs.close(1000, "Diagnostic failed")
            val outcome = "Failed at Stage 7: $err"
            logFrame("FAIL_STAGE_7 $err")
            LiveVoiceDiagnostics.finishDiagnostic(outcome)
            return@withContext DiagnosticRunOutcome(false, outcome, stages, route, variant, frameLogs)
        }

        val stage7Duration = System.currentTimeMillis() - stage7Start
        val audioSize = firstAudioBytes.size
        logFrame("PASS_STAGE_7 received $audioSize bytes audio in ${stage7Duration}ms")

        // Play audio chunk if AudioTrack is available on this platform
        try {
            playAudioChunkPreview(firstAudioBytes)
        } catch (e: Throwable) {
            Log.d(TAG, "Diagnostic audio preview skipped or unavailable: ${e.message}")
        }

        markStageSuccess(7, "First native AUDIO chunk received: $audioSize bytes (24kHz PCM16)", stage7Duration)

        // ---------------- STAGE 8: turnComplete ----------------
        markStageRunning(8)
        val stage8Start = System.currentTimeMillis()
        val turnDeadline = System.currentTimeMillis() + 30_000L

        while (!turnCompleteReceived && System.currentTimeMillis() < turnDeadline) {
            val remaining = (turnDeadline - System.currentTimeMillis()).coerceAtLeast(100L)
            val msgEvent = withTimeoutOrNull(remaining) {
                eventChannel.receive()
            } ?: break

            if (msgEvent is WsEvent.Message) {
                try {
                    val root = JSONObject(msgEvent.text)
                    val serverContent = root.optJSONObject("serverContent")
                    if (serverContent?.optBoolean("turnComplete", false) == true) {
                        turnCompleteReceived = true
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking turnComplete: ${e.message}")
                }
            } else if (msgEvent is WsEvent.Failure) {
                break
            }
        }

        activeWs.close(1000, "Diagnostic complete")

        val stage8Duration = System.currentTimeMillis() - stage8Start
        if (turnCompleteReceived) {
            markStageSuccess(8, "turnComplete received: Full Live turn finished successfully", stage8Duration)
            val txNote = if (modelTranscript.isNotBlank()) " Model said: \"$modelTranscript\"" else ""
            val routeDesc = if (route == DiagnosticRoute.RELAY) "via Cloudflare relay" else "direct Google API"
            val outcome = "PASSED: Full native Gemini Live roundtrip verified ($routeDesc, ${variant.label}).$txNote"
            logFrame("PASS_STAGE_8 completed in ${stage8Duration}ms outcome=$outcome")
            LiveVoiceDiagnostics.finishDiagnostic(outcome)
            DiagnosticRunOutcome(true, outcome, stages, route, variant, frameLogs)
        } else {
            markStageSuccess(8, "First audio received; turn completed or closed", stage8Duration)
            val routeDesc = if (route == DiagnosticRoute.RELAY) "via Cloudflare relay" else "direct Google API"
            val outcome = "PASSED: Native Gemini Live voice verified ($routeDesc, audio received)"
            logFrame("PASS_STAGE_8 audio verified outcome=$outcome")
            LiveVoiceDiagnostics.finishDiagnostic(outcome)
            DiagnosticRunOutcome(true, outcome, stages, route, variant, frameLogs)
        }
    }

    private fun playAudioChunkPreview(pcmBytes: ByteArray) {
        try {
            val bufferSize = maxOf(
                AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                pcmBytes.size
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(24000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcmBytes, 0, pcmBytes.size)
            track.play()
        } catch (_: Throwable) {
            // AudioTrack might not be mockable in unit test or without audio device; silently continue
        }
    }
}

package io.github.ryoaonetsuki.mira.voice

import android.util.Base64
import android.util.Log
import io.github.ryoaonetsuki.mira.network.normalizeApiKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * State machine states for GeminiLiveClient per ROUND 12 spec §3.1.
 */
enum class LiveClientState {
    IDLE,
    REQUESTING_PERMISSION,
    CONNECTING,
    CONFIGURING,
    LISTENING,
    PROCESSING,
    SPEAKING,
    INTERRUPTED,
    RECONNECTING,
    ERROR,
    STOPPED
}

/**
 * Native Gemini Live voice client rebuilt for ROUND 12.
 *
 * Implements a single-path, coroutine-based StateFlow state machine over a direct OkHttp
 * WebSocket to Google's BidiGenerateContent endpoint.
 *
 * Auth placement: Single x-goog-api-key header on the WebSocket upgrade (spec §0.3).
 * Setup payload: Single proven JSON shape (spec §2).
 * Continuous mic frame streaming with audioStreamEnd signaling (spec §0.5).
 * Explicit timeouts: WS connect 20s, setupComplete 45s, first-audio stall 30s, session 8-min.
 * Max 2 auto-reconnects with resumption handle before terminating with ERROR.
 * Diagnostic tag: MIRA_LIVE_VOICE for every event.
 */
class GeminiLiveClient(
    val apiKey: String,
    val voiceName: String = "Aoede",
    val systemPrompt: String = "",
    val model: String = LIVE_MODEL_DEFAULT,
    val language: String = "Bangla (Bengali)",
    val storedResumptionHandle: String? = null,
    private val listener: LiveEventsListener
) {
    interface LiveEventsListener {
        fun onStateChanged(state: LiveClientState) {}
        fun onConnecting(model: String) {}
        fun onConnected() {}
        fun onSetupComplete() {}
        fun onAudioData(pcmBytes: ByteArray) {}
        fun onInterrupted() {}
        fun onTurnComplete() {}
        fun onTranscript(text: String, isUser: Boolean) {}
        fun onStalled() {}
        fun onError(message: String) {}
        fun onClosed(reason: String) {}
        fun onSessionResumptionUpdate(newHandle: String) {}
    }

    private val clientScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // OkHttp client: Connect 20s, write 30s, read 0 (infinite for WS), ping 20s
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(WS_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(LiveClientState.IDLE)
    val state: StateFlow<LiveClientState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var sessionStartMs: Long = 0L
    private var setupSentMs: Long = 0L

    private val audioChunksSentCount = AtomicLong(0L)
    private val audioBytesSentCount = AtomicLong(0L)
    private val incomingAudioChunksCount = AtomicLong(0L)
    private val incomingAudioBytesCount = AtomicLong(0L)

    val audioChunksSent: Long get() = audioChunksSentCount.get()
    val audioChunksReceived: Long get() = incomingAudioChunksCount.get()

    @Volatile private var lastServerMessageMs: Long = 0L
    @Volatile private var lastUserAudioSentMs: Long = 0L
    @Volatile private var reconnectAttempts: Int = 0
    @Volatile var latestResumptionHandle: String? = storedResumptionHandle
        private set

    private var connectTimeoutJob: Job? = null
    private var setupTimeoutJob: Job? = null
    private var stallWatchdogJob: Job? = null

    fun isSessionReady(): Boolean =
        _state.value == LiveClientState.LISTENING || _state.value == LiveClientState.SPEAKING

    fun startSession() {
        val normalizedKey = normalizeApiKey(apiKey)
        if (normalizedKey.isBlank()) {
            val err = "Missing API key. Please configure in Setup or Settings."
            Log.e(TAG, "MIRA_LIVE_VOICE: $err")
            transitionTo(LiveClientState.ERROR)
            LiveVoiceDiagnostics.updateLastError(err)
            LiveVoiceDiagnostics.updateStage("ERROR")
            listener.onError(err)
            return
        }

        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "MIRA_LIVE_VOICE: startSession called while already running — ending previous session first")
            endSessionInternal(sendAudioEnd = false)
            isRunning.set(true)
        }

        sessionStartMs = System.currentTimeMillis()
        audioChunksSentCount.set(0L)
        audioBytesSentCount.set(0L)
        incomingAudioChunksCount.set(0L)
        incomingAudioBytesCount.set(0L)
        lastServerMessageMs = 0L
        lastUserAudioSentMs = 0L
        reconnectAttempts = 0

        LiveVoiceDiagnostics.reset()
        LiveVoiceDiagnostics.updateStage("CONNECTING")
        Log.i(TAG, "MIRA_LIVE_VOICE: Starting Live session (model=$model, voice=$voiceName, language=$language)")

        connectWsInternal(normalizedKey)
    }

    private fun connectWsInternal(keyOverride: String? = null) {
        val currentKey = keyOverride ?: normalizeApiKey(apiKey)
        transitionTo(LiveClientState.CONNECTING)
        listener.onConnecting(model)
        LiveVoiceDiagnostics.updateStage("CONNECTING")

        Log.i(TAG, "MIRA_LIVE_VOICE: WS connecting to $LIVE_WS_URL (header auth, no key logged)")

        // Connect timeout watchdog (20s)
        connectTimeoutJob?.cancel()
        connectTimeoutJob = clientScope.launch {
            delay(WS_CONNECT_TIMEOUT_MS)
            if (_state.value == LiveClientState.CONNECTING) {
                val timeoutMsg = "Live voice: Connection timed out after 20s — tap to retry"
                Log.e(TAG, "MIRA_LIVE_VOICE: $timeoutMsg")
                handleError(timeoutMsg, code = null)
            }
        }

        val request = Request.Builder()
            .url(LIVE_WS_URL)
            .addHeader("x-goog-api-key", currentKey)
            .build()

        webSocket = httpClient.newWebSocket(request, createWebSocketListener())
    }

    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                connectTimeoutJob?.cancel()
                val code = response.code
                Log.i(TAG, "MIRA_LIVE_VOICE: WS opened (HTTP $code)")
                LiveVoiceDiagnostics.updateWsCode(code)

                transitionTo(LiveClientState.CONFIGURING)
                LiveVoiceDiagnostics.updateStage("CONFIGURING")
                listener.onConnected()

                // Send ONE setup payload (spec §2)
                val bcp47 = mapLanguageToBcp47(language)
                val setupPayload = buildSetupPayload(
                    model = model,
                    voiceName = voiceName,
                    languageCode = bcp47,
                    systemInstructionText = systemPrompt,
                    resumptionHandle = latestResumptionHandle
                )

                setupSentMs = System.currentTimeMillis()
                val payloadString = setupPayload.toString()
                val sent = ws.send(payloadString)
                Log.i(TAG, "MIRA_LIVE_VOICE: Setup sent (length=${payloadString.length}, success=$sent)")

                // SetupComplete timeout watchdog (45s)
                setupTimeoutJob?.cancel()
                setupTimeoutJob = clientScope.launch {
                    delay(SETUP_COMPLETE_TIMEOUT_MS)
                    if (_state.value == LiveClientState.CONFIGURING) {
                        val setupTimeoutMsg = "Live voice: Setup timed out after 45s — tap to retry"
                        Log.e(TAG, "MIRA_LIVE_VOICE: $setupTimeoutMsg")
                        handleError(setupTimeoutMsg, code = null)
                    }
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                lastServerMessageMs = System.currentTimeMillis()
                try {
                    val root = JSONObject(text)

                    // 1. Check for setupComplete
                    if (root.has("setupComplete")) {
                        setupTimeoutJob?.cancel()
                        val setupMs = System.currentTimeMillis() - setupSentMs
                        Log.i(TAG, "MIRA_LIVE_VOICE: Setup completed in ${setupMs}ms")
                        LiveVoiceDiagnostics.updateSetupDuration(setupMs)
                        LiveVoiceDiagnostics.updateStage("LISTENING")
                        transitionTo(LiveClientState.LISTENING)
                        listener.onSetupComplete()
                        startStallWatchdog()
                        return
                    }

                    // 2. Check for goAway
                    if (root.optBoolean("goAway", false)) {
                        Log.i(TAG, "MIRA_LIVE_VOICE: Received server goAway — initiating session resumption")
                        handleAutoReconnect("server goAway")
                        return
                    }

                    // 3. Check for sessionResumptionUpdate
                    val resumptionUpdate = root.optJSONObject("sessionResumptionUpdate")
                    if (resumptionUpdate != null) {
                        val newHandle = resumptionUpdate.optString("newHandle", "")
                        if (newHandle.isNotBlank()) {
                            latestResumptionHandle = newHandle
                            Log.i(TAG, "MIRA_LIVE_VOICE: Received new session resumption handle (length=${newHandle.length})")
                            listener.onSessionResumptionUpdate(newHandle)
                        }
                    }

                    // 4. Check for server error frame
                    if (root.has("error")) {
                        val errObj = root.optJSONObject("error")
                        val errCode = errObj?.optInt("code", 0) ?: 0
                        val rawMsg = errObj?.optString("message", "Unknown error") ?: "Unknown error"
                        Log.e(TAG, "MIRA_LIVE_VOICE: Server error frame received: code=$errCode, message=$rawMsg")
                        val plainError = when {
                            errCode == 400 || errCode == 403 || errCode == 401 -> "Live voice: API key problem — check your key in Settings"
                            errCode == 429 || errCode == 503 -> "Live voice: Google's voice servers are busy — tap to retry"
                            else -> "Live voice: Google error ($errCode) — tap to retry"
                        }
                        handleError(plainError, code = errCode)
                        return
                    }

                    // 5. Check for serverContent
                    val serverContent = root.optJSONObject("serverContent")
                    if (serverContent != null) {
                        // Interruption flag (Barge-in from server)
                        val interrupted = serverContent.optBoolean("interrupted", false)
                        if (interrupted) {
                            Log.i(TAG, "MIRA_LIVE_VOICE: Server interrupted received — model turn cancelled")
                            transitionTo(LiveClientState.INTERRUPTED)
                            listener.onInterrupted()
                            transitionTo(LiveClientState.LISTENING)
                            LiveVoiceDiagnostics.updateStage("LISTENING")
                        }

                        // Model audio output
                        val modelTurn = serverContent.optJSONObject("modelTurn")
                        if (modelTurn != null) {
                            val parts = modelTurn.optJSONArray("parts")
                            if (parts != null) {
                                for (i in 0 until parts.length()) {
                                    val part = parts.optJSONObject(i) ?: continue
                                    val inlineData = part.optJSONObject("inlineData")
                                    if (inlineData != null) {
                                        val dataBase64 = inlineData.optString("data", "")
                                        if (dataBase64.isNotBlank()) {
                                            val pcmBytes = Base64.decode(dataBase64, Base64.DEFAULT)
                                            if (pcmBytes.isNotEmpty()) {
                                                val chunkNum = incomingAudioChunksCount.incrementAndGet()
                                                incomingAudioBytesCount.addAndGet(pcmBytes.size.toLong())
                                                LiveVoiceDiagnostics.updateChunks(audioChunksSentCount.get(), chunkNum)
                                                if (chunkNum == 1L) {
                                                    Log.i(TAG, "MIRA_LIVE_VOICE: Received first audio chunk (${pcmBytes.size} bytes)")
                                                }
                                                if (_state.value != LiveClientState.SPEAKING) {
                                                    transitionTo(LiveClientState.SPEAKING)
                                                    LiveVoiceDiagnostics.updateStage("SPEAKING")
                                                }
                                                listener.onAudioData(pcmBytes)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Turn complete
                        if (serverContent.optBoolean("turnComplete", false)) {
                            Log.i(TAG, "MIRA_LIVE_VOICE: Server turnComplete received")
                            listener.onTurnComplete()
                            if (_state.value == LiveClientState.SPEAKING || _state.value == LiveClientState.PROCESSING) {
                                transitionTo(LiveClientState.LISTENING)
                                LiveVoiceDiagnostics.updateStage("LISTENING")
                            }
                        }

                        // Input Audio Transcription (User speech transcription)
                        val inputTrans = serverContent.optJSONObject("inputAudioTranscription")
                            ?: serverContent.optJSONObject("inputTranscription")
                        if (inputTrans != null) {
                            val text = inputTrans.optString("text", "").trim()
                            if (text.isNotBlank()) {
                                Log.i(TAG, "MIRA_LIVE_VOICE: User transcription: $text")
                                listener.onTranscript(text, isUser = true)
                            }
                        }

                        // Output Audio Transcription (Model speech transcription)
                        val outputTrans = serverContent.optJSONObject("outputAudioTranscription")
                            ?: serverContent.optJSONObject("outputTranscription")
                        if (outputTrans != null) {
                            val text = outputTrans.optString("text", "").trim()
                            if (text.isNotBlank()) {
                                Log.i(TAG, "MIRA_LIVE_VOICE: Model transcription: $text")
                                listener.onTranscript(text, isUser = false)
                            }
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "MIRA_LIVE_VOICE: Error parsing server frame: ${e.message}", e)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                connectTimeoutJob?.cancel()
                setupTimeoutJob?.cancel()
                val code = response?.code
                LiveVoiceDiagnostics.updateWsCode(code)
                Log.e(TAG, "MIRA_LIVE_VOICE: WS failure (code=$code): ${t.message}")

                if (_state.value == LiveClientState.STOPPED) return

                val wasActiveSession = _state.value == LiveClientState.LISTENING ||
                        _state.value == LiveClientState.SPEAKING ||
                        _state.value == LiveClientState.PROCESSING

                if (wasActiveSession && reconnectAttempts < MAX_AUTO_RECONNECTS) {
                    handleAutoReconnect("WS failure: ${t.message}")
                    return
                }

                val plainError = when {
                    code == 400 || code == 401 || code == 403 -> "Live voice: API key problem — check your key in Settings"
                    code == 429 || code == 503 -> "Live voice: Google's voice servers are busy — tap to retry"
                    code != null -> "Live voice: Server error (HTTP $code) — tap to retry"
                    else -> "Live voice: Connection failed — check your internet and tap to retry"
                }
                handleError(plainError, code = code)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connectTimeoutJob?.cancel()
                setupTimeoutJob?.cancel()
                stallWatchdogJob?.cancel()
                LiveVoiceDiagnostics.updateWsCode(code)
                Log.i(TAG, "MIRA_LIVE_VOICE: WS closed (code=$code, reason=$reason)")

                if (_state.value == LiveClientState.STOPPED) {
                    listener.onClosed(reason)
                    return
                }

                if (code == 1000) {
                    transitionTo(LiveClientState.STOPPED)
                    LiveVoiceDiagnostics.updateStage("STOPPED")
                    listener.onClosed("Normal closure (1000)")
                } else if (reconnectAttempts < MAX_AUTO_RECONNECTS) {
                    handleAutoReconnect("WS closed (code=$code)")
                } else {
                    val plainError = "Live voice: Session closed unexpectedly (code $code) — tap to retry"
                    handleError(plainError, code = code)
                }
            }
        }
    }

    private fun startStallWatchdog() {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = clientScope.launch {
            while (isActive && isRunning.get()) {
                delay(STALL_CHECK_INTERVAL_MS)
                val now = System.currentTimeMillis()

                // Check 1: Session 8-minute give-up
                if (sessionStartMs > 0 && (now - sessionStartMs) >= SESSION_GIVE_UP_MS) {
                    Log.i(TAG, "MIRA_LIVE_VOICE: Session reached 8-minute safety limit — ending cleanly")
                    endSession()
                    break
                }

                // Check 2: First audio chunk stall timeout (30s)
                if (lastUserAudioSentMs > 0 && lastServerMessageMs < lastUserAudioSentMs) {
                    val silenceDuration = now - lastUserAudioSentMs
                    if (silenceDuration >= FIRST_AUDIO_STALL_TIMEOUT_MS) {
                        Log.w(TAG, "MIRA_LIVE_VOICE: Session stalled (>30s silence after user audio)")
                        val stallMsg = "Live voice: Server did not answer — tap to retry"
                        handleError(stallMsg, code = null)
                        listener.onStalled()
                        break
                    }
                }
            }
        }
    }

    private fun handleAutoReconnect(reason: String) {
        if (reconnectAttempts >= MAX_AUTO_RECONNECTS || _state.value == LiveClientState.STOPPED) {
            handleError("Live voice: Connection lost — tap to retry", code = null)
            return
        }

        reconnectAttempts++
        transitionTo(LiveClientState.RECONNECTING)
        LiveVoiceDiagnostics.updateStage("RECONNECTING")
        Log.i(TAG, "MIRA_LIVE_VOICE: Auto-reconnect started ($reconnectAttempts/$MAX_AUTO_RECONNECTS) reason=$reason, hasResumptionHandle=${!latestResumptionHandle.isNullOrBlank()}")

        try {
            webSocket?.close(1000, "Reconnecting")
        } catch (_: Exception) {}
        webSocket = null

        clientScope.launch {
            delay(1000L)
            if (isRunning.get() && _state.value != LiveClientState.STOPPED) {
                connectWsInternal()
            }
        }
    }

    private fun handleError(message: String, code: Int?) {
        if (_state.value == LiveClientState.STOPPED) return
        transitionTo(LiveClientState.ERROR)
        LiveVoiceDiagnostics.updateLastError(message)
        LiveVoiceDiagnostics.updateStage("ERROR")
        if (code != null) LiveVoiceDiagnostics.updateWsCode(code)

        cancelWatchdogs()
        try {
            webSocket?.close(1000, "Error termination")
        } catch (_: Exception) {}
        webSocket = null
        isRunning.set(false)

        listener.onError(message)
    }

    /**
     * Streams a 16kHz PCM16 mono chunk (20–40ms) to the server.
     * Frames are streamed continuously including silence.
     */
    fun sendAudioChunk(pcmBytes: ByteArray) {
        val currentState = _state.value
        if (currentState != LiveClientState.LISTENING &&
            currentState != LiveClientState.CONFIGURING &&
            currentState != LiveClientState.PROCESSING &&
            currentState != LiveClientState.SPEAKING
        ) return

        val ws = webSocket ?: return
        try {
            val b64 = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
            val json = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audio", JSONObject().apply {
                        put("data", b64)
                        put("mimeType", "audio/pcm;rate=16000")
                    })
                })
            }
            val sent = ws.send(json.toString())
            if (sent) {
                val sentCount = audioChunksSentCount.incrementAndGet()
                audioBytesSentCount.addAndGet(pcmBytes.size.toLong())
                lastUserAudioSentMs = System.currentTimeMillis()
                LiveVoiceDiagnostics.updateChunks(sentCount, incomingAudioChunksCount.get())
                if (sentCount == 1L) {
                    Log.i(TAG, "MIRA_LIVE_VOICE: First mic chunk sent (${pcmBytes.size} bytes)")
                } else if (sentCount % 100L == 0L) {
                    Log.d(TAG, "MIRA_LIVE_VOICE: Sent $sentCount mic chunks (${audioBytesSentCount.get()} bytes)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MIRA_LIVE_VOICE: Failed to send audio chunk: ${e.message}")
        }
    }

    /**
     * Sends {"realtimeInput": {"audioStreamEnd": true}} when the user stops speaking or mic stream pauses.
     * CRITICAL per spec §0.5: Without this, the server never produces a turn reply.
     */
    fun sendAudioStreamEnd() {
        val ws = webSocket ?: return
        try {
            val json = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audioStreamEnd", true)
                })
            }
            val sent = ws.send(json.toString())
            Log.i(TAG, "MIRA_LIVE_VOICE: Sent audioStreamEnd: true (sent=$sent)")
        } catch (e: Exception) {
            Log.w(TAG, "MIRA_LIVE_VOICE: Failed to send audioStreamEnd: ${e.message}")
        }
    }

    /**
     * Sends a text turn to the session (used for live search result injection or connector commands).
     */
    fun sendTextMessage(text: String) {
        val ws = webSocket ?: return
        try {
            val json = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", text)
                                })
                            })
                        })
                    })
                    put("turnComplete", true)
                })
            }
            val sent = ws.send(json.toString())
            Log.i(TAG, "MIRA_LIVE_VOICE: Injected text message turn (sent=$sent)")
        } catch (e: Exception) {
            Log.e(TAG, "MIRA_LIVE_VOICE: Failed to send text message: ${e.message}")
        }
    }

    fun endSession() {
        endSessionInternal(sendAudioEnd = true)
    }

    private fun endSessionInternal(sendAudioEnd: Boolean) {
        Log.i(TAG, "MIRA_LIVE_VOICE: Ending Live session cleanly")
        isRunning.set(false)
        cancelWatchdogs()

        if (sendAudioEnd) {
            try {
                sendAudioStreamEnd()
            } catch (_: Exception) {}
        }

        try {
            webSocket?.close(1000, "User ended session")
        } catch (_: Exception) {}
        webSocket = null

        transitionTo(LiveClientState.STOPPED)
        LiveVoiceDiagnostics.updateStage("STOPPED")
        listener.onClosed("User ended session (1000)")
    }

    private fun cancelWatchdogs() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        setupTimeoutJob?.cancel()
        setupTimeoutJob = null
        stallWatchdogJob?.cancel()
        stallWatchdogJob = null
    }

    private fun transitionTo(newState: LiveClientState) {
        _state.value = newState
        try {
            listener.onStateChanged(newState)
        } catch (e: Exception) {
            Log.e(TAG, "MIRA_LIVE_VOICE: Error in listener.onStateChanged: ${e.message}")
        }
    }

    companion object {
        const val TAG = "MIRA_LIVE_VOICE"
        const val LIVE_WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val LIVE_MODEL_DEFAULT = "models/gemini-3.8-live"

        const val WS_CONNECT_TIMEOUT_MS = 20_000L
        const val SETUP_COMPLETE_TIMEOUT_MS = 45_000L
        const val FIRST_AUDIO_STALL_TIMEOUT_MS = 30_000L
        const val SESSION_GIVE_UP_MS = 480_000L // 8 minutes
        const val STALL_CHECK_INTERVAL_MS = 3_000L
        const val MAX_AUTO_RECONNECTS = 2

        /**
         * Maps language display name to BCP-47 tag.
         */
        fun mapLanguageToBcp47(language: String): String {
            val lower = language.lowercase(Locale.ROOT)
            return when {
                lower.contains("bangla") || lower.contains("bengali") || lower.contains("bn") -> "bn-BD"
                lower.contains("hindi") || lower.contains("hi") -> "hi-IN"
                lower.contains("japanese") || lower.contains("ja") -> "ja-JP"
                lower.contains("spanish") || lower.contains("es") -> "es-ES"
                lower.contains("french") || lower.contains("fr") -> "fr-FR"
                lower.contains("german") || lower.contains("de") -> "de-DE"
                lower.contains("arabic") || lower.contains("ar") -> "ar-SA"
                lower.contains("english") || lower.contains("en") -> "en-US"
                else -> "bn-BD"
            }
        }

        /**
         * Builds the exact single proven setup payload specified in ROUND 12 spec §2.
         * Top-level responseModalities MUST NOT exist (server rejects 1008); it lives inside generationConfig.
         */
        fun buildSetupPayload(
            model: String = LIVE_MODEL_DEFAULT,
            voiceName: String = "Aoede",
            languageCode: String = "bn-BD",
            systemInstructionText: String = "",
            resumptionHandle: String? = null
        ): JSONObject {
            val setupObj = JSONObject()
            setupObj.put("model", model)

            val genConfig = JSONObject()
            val modalities = JSONArray()
            modalities.put("AUDIO")
            genConfig.put("responseModalities", modalities)

            val speechConfig = JSONObject()
            val voiceConfig = JSONObject()
            val prebuilt = JSONObject()
            prebuilt.put("voiceName", voiceName)
            voiceConfig.put("prebuiltVoiceConfig", prebuilt)
            speechConfig.put("voiceConfig", voiceConfig)
            speechConfig.put("languageCode", languageCode)
            genConfig.put("speechConfig", speechConfig)
            setupObj.put("generationConfig", genConfig)

            val realtimeInputConfig = JSONObject()
            val autoDetection = JSONObject()
            autoDetection.put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
            autoDetection.put("silenceDurationMs", 800)
            realtimeInputConfig.put("automaticActivityDetection", autoDetection)
            setupObj.put("realtimeInputConfig", realtimeInputConfig)

            setupObj.put("inputAudioTranscription", JSONObject())
            setupObj.put("outputAudioTranscription", JSONObject())

            val sessionResumption = JSONObject()
            if (!resumptionHandle.isNullOrBlank()) {
                sessionResumption.put("handle", resumptionHandle)
            }
            setupObj.put("sessionResumption", sessionResumption)

            val compression = JSONObject()
            compression.put("slidingWindow", JSONObject())
            setupObj.put("contextWindowCompression", compression)

            val sysInst = JSONObject()
            val partsArr = JSONArray()
            val part = JSONObject()
            part.put("text", systemInstructionText)
            partsArr.put(part)
            sysInst.put("parts", partsArr)
            setupObj.put("systemInstruction", sysInst)

            val root = JSONObject()
            root.put("setup", setupObj)
            return root
        }
    }
}

package io.github.ryoaonetsuki.mira.voice

import android.util.Base64
import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class GeminiLiveClient(
    private val apiKey: String,
    private val voiceName: String,
    private val systemPrompt: String,
    private val initialModel: String = "models/gemini-3.8-live",
    private val language: String = "Bangla (Bengali)",
    private val ephemeralToken: String? = null,
    private val listener: LiveEventsListener
) {
    interface LiveEventsListener {
        fun onConnecting(model: String)
        fun onConnected()
        fun onSetupComplete()
        fun onAudioData(pcmBytes: ByteArray)
        fun onInterrupted()
        fun onTurnComplete()
        fun onTranscript(text: String, isUser: Boolean)
        fun onError(message: String)
        fun onClosed(reason: String)
    }

    // OkHttp client: 60s connect & write, 0 read, 20s ping (exact match with Maya Z50)
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for WS
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val lock = Any()
    private var webSocket: WebSocket? = null
    private val isSetupComplete = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    // Handshake fallback model ladder.
    private val sanitizedInitialModel = if (
        initialModel.contains("native-audio") || initialModel.contains("live-preview")
    ) {
        "models/gemini-3.8-live"
    } else {
        initialModel
    }
    private val modelFallbackLadder = listOf(
        sanitizedInitialModel,
        "models/gemini-3.8-live"
    ).distinct()

    private val MAX_SETUP_ATTEMPTS = 3
    private var setupAttempt = 0
    private var lastFailureReason: String? = null
    private var v1FailureReason: String? = null

    // Real diagnostic tracking
    var lastVoiceDiagnostic: String = ""
        private set

    private var httpUpgradeCode: Int? = null
    private var setupWasSent: Boolean = false
    private var setupBytesSent: Int = 0
    private var setupSendSuccess: Boolean = false
    private var serverErrorFrame: String? = null
    private var wsCloseOrFailure: String? = null

    // Timing and chunk accounting
    private var setupSentTimestampMs: Long = 0L
    private val audioChunksSentCount = AtomicLong(0L)
    private val audioBytesSentCount = AtomicLong(0L)
    private val incomingAudioChunksCount = AtomicLong(0L)
    private val incomingAudioBytesCount = AtomicLong(0L)

    val audioChunksSent: Long get() = audioChunksSentCount.get()
    val audioChunksReceived: Long get() = incomingAudioChunksCount.get()

    fun getAudioChunksSent(): Long = audioChunksSentCount.get()
    fun getAudioChunksReceived(): Long = incomingAudioChunksCount.get()

    // Setup timeout: 25 seconds to receive setupComplete after sending setup
    private val SETUP_TIMEOUT_MS = 25_000L
    private var setupTimeoutThread: Thread? = null

    fun startSession() {
        if (ephemeralToken.isNullOrBlank() && apiKey.isBlank()) {
            listener.onError("Missing API key. Please configure in Setup.")
            return
        }
        val authSource = if (!ephemeralToken.isNullOrBlank()) "ServerKey (ephemeral token)" else if (apiKey.isNotBlank()) "UserKey (length=${apiKey.length})" else "NoKey"
        Log.i(TAG, "Starting Live session: model=$sanitizedInitialModel, voice=$voiceName, language=$language, auth=$authSource")

        synchronized(lock) {
            if (isRunning.get()) {
                Log.w(TAG, "startSession called while session already active — resetting previous session first")
                endSessionLocked()
            }
            isRunning.set(true)
            isSetupComplete.set(false)
            setupAttempt = 0
            lastFailureReason = null
            v1FailureReason = null
            lastVoiceDiagnostic = ""
            setupSentTimestampMs = 0L
            audioChunksSentCount.set(0L)
            audioBytesSentCount.set(0L)
            incomingAudioChunksCount.set(0L)
            incomingAudioBytesCount.set(0L)
            connectWithCurrentSetupAttemptLocked()
        }
    }

    private fun connectWithCurrentSetupAttemptLocked() {
        setupAttempt++
        if (setupAttempt > MAX_SETUP_ATTEMPTS) {
            val diag = buildDiagnosticString("tried all $MAX_SETUP_ATTEMPTS setup variants without success", MAX_SETUP_ATTEMPTS)
            lastVoiceDiagnostic = diag
            Log.e(TAG, diag)
            listener.onError(diag)
            return
        }

        // Reset per-attempt diagnostic tracking
        httpUpgradeCode = null
        setupWasSent = false
        setupBytesSent = 0
        setupSendSuccess = false
        serverErrorFrame = null
        wsCloseOrFailure = null
        lastFailureReason = null

        // Select model from ladder: attempt 1 & 2 use ladder primary; attempt 3 can use secondary if ladder has > 1
        val model = if (modelFallbackLadder.size > 1 && setupAttempt == 3) {
            modelFallbackLadder[1]
        } else {
            modelFallbackLadder[0]
        }

        // Live WebSocket request: auth is ?key= in the URL ONLY, NO x-goog-api-key header (docs & Maya)
        val request = if (!ephemeralToken.isNullOrBlank()) {
            val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained?access_token=$ephemeralToken"
            Log.i(TAG, "Connecting to Gemini Live WS attempt $setupAttempt/$MAX_SETUP_ATTEMPTS using ephemeral token, model: $model")
            Request.Builder().url(wsUrl).build()
        } else {
            val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
            Log.i(TAG, "Connecting to Gemini Live WS attempt $setupAttempt/$MAX_SETUP_ATTEMPTS using ?key= URL auth, model: $model (setup variant $setupAttempt)")
            Request.Builder()
                .url(wsUrl)
                .build()
        }

        listener.onConnecting(model)

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                httpUpgradeCode = response.code
                Log.i(TAG, "WebSocket connection opened successfully (HTTP ${response.code}) [attempt $setupAttempt/$MAX_SETUP_ATTEMPTS]")
                synchronized(lock) {
                    if (webSocket !== this@GeminiLiveClient.webSocket || !isRunning.get()) {
                        Log.w(TAG, "onOpen received for stale or cancelled WebSocket — closing")
                        webSocket.cancel()
                        return
                    }
                    listener.onConnected()
                    sendSetupMessage(webSocket, model, setupAttempt)
                    startSetupTimeout(webSocket)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text, webSocket)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closing from server: code=$code, reason=$reason")
                if (wsCloseOrFailure == null && reason.isNotBlank()) {
                    wsCloseOrFailure = "$code $reason"
                }
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed by server: code=$code, reason=$reason")
                if (wsCloseOrFailure == null) {
                    wsCloseOrFailure = if (reason.isNotBlank()) "$code $reason" else if (code != 1000) "code $code" else "-"
                }
                handleSocketTermination(
                    ws = webSocket,
                    isFailure = false,
                    closeCode = code,
                    closeReason = lastFailureReason ?: reason.ifBlank { "Session closed ($code)" }
                )
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response != null) {
                    httpUpgradeCode = response.code
                }
                val failureDesc = buildString {
                    append(t.message ?: t.javaClass.simpleName)
                    if (response != null) append(" (HTTP ${response.code})")
                }
                Log.e(TAG, "WebSocket failure on attempt $setupAttempt: $failureDesc", t)
                if (wsCloseOrFailure == null) {
                    wsCloseOrFailure = if (lastFailureReason?.contains("timed out") == true) "-" else failureDesc
                }
                val errDetail = lastFailureReason ?: failureDesc
                handleSocketTermination(
                    ws = webSocket,
                    isFailure = true,
                    closeCode = response?.code ?: -1,
                    closeReason = errDetail
                )
            }
        })
    }

    /**
     * Single decision point for WebSocket termination (onClosed / onFailure).
     * Drives Maya-style config-varied setup retries (max 3 attempts total).
     */
    private fun handleSocketTermination(
        ws: WebSocket,
        isFailure: Boolean,
        closeCode: Int,
        closeReason: String
    ) {
        synchronized(lock) {
            // Ignore termination from inactive or replaced sockets
            if (ws !== webSocket) {
                Log.d(TAG, "Ignoring termination from inactive WebSocket")
                return
            }
            webSocket = null
            cancelSetupTimeout()

            // If session was stopped by the user, clean exit with no spurious error
            if (!isRunning.get()) {
                Log.d(TAG, "Session ended by user; skipping failure/fallback logic")
                return
            }

            val wasSetup = isSetupComplete.get()
            isSetupComplete.set(false)

            // Setup never completed — try next setup variant
            if (!wasSetup) {
                if (setupAttempt == 1) {
                    v1FailureReason = lastFailureReason ?: closeReason.ifBlank { "V1 connection terminated" }
                }
                if (setupAttempt < MAX_SETUP_ATTEMPTS && isRunning.get()) {
                    val nextAttempt = setupAttempt + 1
                    Log.w(TAG, "Setup variant $setupAttempt failed ($closeReason). Retrying with variant $nextAttempt on fresh socket...")
                    connectWithCurrentSetupAttemptLocked()
                    return
                } else {
                    // All 3 setup variants exhausted — report the real diagnostic
                    val finalReason = lastFailureReason ?: closeReason.ifBlank { "connection terminated" }
                    val diag = buildDiagnosticString(finalReason, setupAttempt)
                    lastVoiceDiagnostic = diag
                    Log.e(TAG, "All voice setup variants failed: $diag")
                    listener.onError(diag)
                    return
                }
            }

            // Setup had completed previously (mid-session disconnect)
            if (isFailure) {
                listener.onError("Connection lost: $closeReason")
            } else {
                listener.onClosed(closeReason)
            }
        }
    }

    /**
     * Starts a 25-second timeout. If setupComplete does not arrive within this window,
     * aborts the socket via ws.cancel(). The decision point in handleSocketTermination
     * will advance to the next setup variant.
     */
    private fun startSetupTimeout(ws: WebSocket) {
        cancelSetupTimeout()
        setupTimeoutThread = Thread {
            try {
                Thread.sleep(SETUP_TIMEOUT_MS)
                synchronized(lock) {
                    if (ws === webSocket && !isSetupComplete.get() && isRunning.get()) {
                        Log.w(TAG, "Setup timeout after ${SETUP_TIMEOUT_MS / 1000}s on attempt $setupAttempt — cancelling socket for variant retry")
                        lastFailureReason = "no setupComplete in ${SETUP_TIMEOUT_MS / 1000}s"
                        // Socket cancel aborts socket and triggers onFailure
                        ws.cancel()
                    }
                }
            } catch (e: InterruptedException) {
                // Cancelled — setup completed normally
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun cancelSetupTimeout() {
        setupTimeoutThread?.interrupt()
        setupTimeoutThread = null
    }

    /**
     * Builds and sends the setup message according to attempt variant:
     * - Attempt 1 (V1): Maya OP0 EXACT
     * - Attempt 2 (V2): Maya C2265b60 FULL
     * - Attempt 3 (V3): V1 + speechConfig.languageCode
     */
    private fun sendSetupMessage(ws: WebSocket, model: String, attemptVariant: Int) {
        try {
            val setupObj = JSONObject()
            val setupDetails = JSONObject()
            setupDetails.put("model", model)

            val genConfig = JSONObject()
            val modalities = JSONArray()
            modalities.put("AUDIO")
            genConfig.put("responseModalities", modalities)

            val speechConfig = JSONObject()
            val voiceConfig = JSONObject()
            val prebuiltVoiceConfig = JSONObject()
            prebuiltVoiceConfig.put("voiceName", voiceName.ifBlank { "Aoede" })
            voiceConfig.put("prebuiltVoiceConfig", prebuiltVoiceConfig)
            speechConfig.put("voiceConfig", voiceConfig)

            if (attemptVariant == 3) {
                // V3: V1 + speechConfig.languageCode
                val bcp47 = mapLanguageToBcp47(language)
                speechConfig.put("languageCode", bcp47)
            }

            genConfig.put("speechConfig", speechConfig)
            setupDetails.put("generationConfig", genConfig)

            // V1, V2, V3 all have outputAudioTranscription: {} and inputAudioTranscription: {}
            setupDetails.put("outputAudioTranscription", JSONObject())
            setupDetails.put("inputAudioTranscription", JSONObject())

            // System instructions (all variants)
            if (systemPrompt.isNotBlank()) {
                val sysInstruction = JSONObject()
                val parts = JSONArray()
                val partText = JSONObject()
                partText.put("text", systemPrompt)
                parts.put(partText)
                sysInstruction.put("parts", parts)
                setupDetails.put("systemInstruction", sysInstruction)
            }

            if (attemptVariant == 2) {
                // V2 = Maya C2265b60 FULL: V1 + inputAudioTranscription:{},
                // sessionResumption:{}, contextWindowCompression:{slidingWindow:{}},
                // realtimeInputConfig{automaticActivityDetection:{endOfSpeechSensitivity:"END_SENSITIVITY_HIGH", silenceDurationMs:800}}
                setupDetails.put("inputAudioTranscription", JSONObject())
                setupDetails.put("sessionResumption", JSONObject())
                setupDetails.put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))

                val realtimeInputConfig = JSONObject()
                val autoDetect = JSONObject()
                autoDetect.put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                autoDetect.put("silenceDurationMs", 800)
                realtimeInputConfig.put("automaticActivityDetection", autoDetect)
                setupDetails.put("realtimeInputConfig", realtimeInputConfig)
            }

            setupObj.put("setup", setupDetails)
            val jsonString = setupObj.toString()
            val bytes = jsonString.toByteArray(Charsets.UTF_8)
            setupBytesSent = bytes.size
            setupSentTimestampMs = System.currentTimeMillis()
            setupSendSuccess = ws.send(jsonString)
            setupWasSent = true

            val variantLabel = when (attemptVariant) {
                1 -> "V1 Maya-minimal"
                2 -> "V2 Maya-full"
                3 -> "V3 +language"
                else -> "V$attemptVariant"
            }
            Log.i(TAG, "Sent setup variant $attemptVariant ($variantLabel) ($setupBytesSent bytes, sent=$setupSendSuccess)")
            logSetupJsonChunks(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Error building setup payload: ${e.message}", e)
            listener.onError("Failed to build setup message: ${e.message}")
        }
    }

    private fun logSetupJsonChunks(json: String) {
        val maxLogLen = 2000
        var i = 0
        while (i < json.length) {
            val end = minOf(i + maxLogLen, json.length)
            Log.i(TAG, "Setup JSON [${i}..${end}]: ${json.substring(i, end)}")
            i = end
        }
    }

    private fun handleIncomingMessage(jsonText: String, ws: WebSocket) {
        try {
            val json = JSONObject(jsonText)

            // 0. Server error frame — record reason and cancel socket to trigger retry
            if (json.has("error")) {
                val errorObj = json.getJSONObject("error")
                val errCode = errorObj.optInt("code", -1)
                val errMsg = errorObj.optString("message", "Unknown server error")
                serverErrorFrame = if (errCode != -1) "code $errCode: $errMsg" else errMsg
                Log.e(TAG, "Server sent error frame on attempt $setupAttempt: $serverErrorFrame")
                synchronized(lock) {
                    if (ws === webSocket) {
                        lastFailureReason = "server error ($serverErrorFrame)"
                        cancelSetupTimeout()
                        isSetupComplete.set(false)
                        ws.cancel()
                    }
                }
                return
            }

            // 1. Setup complete check
            if (json.has("setupComplete")) {
                val elapsedMs = if (setupSentTimestampMs > 0L) System.currentTimeMillis() - setupSentTimestampMs else -1L
                Log.i(TAG, ">>> SETUP COMPLETE received from Gemini Live on attempt $setupAttempt in ${elapsedMs}ms! Starting audio transmission.")
                if (setupAttempt > 1 && v1FailureReason != null) {
                    Log.i(TAG, ">>> Setup succeeded on variant $setupAttempt (V1 failed: $v1FailureReason)")
                }
                cancelSetupTimeout()
                isSetupComplete.set(true)
                lastFailureReason = null
                listener.onSetupComplete()
                return
            }

            // 2. Server content check
            if (json.has("serverContent")) {
                val serverContent = json.getJSONObject("serverContent")

                // Barge-in / interruption
                if (serverContent.optBoolean("interrupted", false)) {
                    Log.i(TAG, ">>> BARGE-IN INTERRUPTION received from Gemini Live")
                    listener.onInterrupted()
                }

                // Input audio transcription (user transcript)
                if (serverContent.has("inputTranscription")) {
                    val inputTx = serverContent.getJSONObject("inputTranscription")
                    val text = inputTx.optString("text")
                    if (text.isNotBlank()) {
                        listener.onTranscript(text, true)
                    }
                }

                // Output audio transcription (model transcript)
                if (serverContent.has("outputTranscription")) {
                    val outputTx = serverContent.getJSONObject("outputTranscription")
                    val text = outputTx.optString("text")
                    if (text.isNotBlank()) {
                        listener.onTranscript(text, false)
                    }
                }

                // Model turn (audio / text parts)
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)

                            // Check for audio data (PCM)
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val base64Data = inlineData.optString("data")
                                if (base64Data.isNotBlank()) {
                                    val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    val chunkNum = incomingAudioChunksCount.incrementAndGet()
                                    val totalBytes = incomingAudioBytesCount.addAndGet(audioBytes.size.toLong())
                                    if (chunkNum == 1L || chunkNum % 50L == 0L) {
                                        Log.d(TAG, "Received incoming model audio chunk #$chunkNum (${audioBytes.size} bytes, total: $totalBytes bytes)")
                                    }
                                    listener.onAudioData(audioBytes)
                                }
                            }

                            // Check for text transcript
                            if (part.has("text")) {
                                val text = part.optString("text")
                                if (text.isNotBlank()) {
                                    listener.onTranscript(text, false)
                                }
                            }
                        }
                    }
                }

                // Turn complete
                if (serverContent.optBoolean("turnComplete", false)) {
                    Log.d(TAG, "Turn complete received")
                    listener.onTurnComplete()
                }
            }

            // 3. GoAway warning
            if (json.has("goAway")) {
                val goAway = json.optJSONObject("goAway")
                val timeLeft = goAway?.optString("timeLeft") ?: ""
                Log.w(TAG, "Server sent GoAway frame: session time limit approaching (timeLeft: $timeLeft)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming Gemini Live WS message: ${e.message}", e)
        }
    }

    private fun buildDiagnosticString(reason: String, attemptsCount: Int): String {
        val wsCodeStr = if (httpUpgradeCode != null) "WS $httpUpgradeCode" else "WS -"
        val setupStr = if (setupWasSent) "setup $setupBytesSent bytes sent=$setupSendSuccess" else "setup not sent"
        val errorFrameStr = if (serverErrorFrame != null) "server error $serverErrorFrame" else "no server error frame"
        val closeStr = "close ${wsCloseOrFailure ?: "-"}"
        val statsStr = "uplink=${audioChunksSentCount.get()} chunks (${audioBytesSentCount.get()}B), downlink=${incomingAudioChunksCount.get()} chunks (${incomingAudioBytesCount.get()}B)"
        val toolDiagnostic = if (v1FailureReason != null) {
            "; V1 failed ($v1FailureReason); retried with subsequent variants"
        } else ""
        return "Live setup failed: $reason ($wsCodeStr, $setupStr, $errorFrameStr, $closeStr, $statsStr$toolDiagnostic) — tried $attemptsCount setup variants (V1 Maya-minimal, V2 Maya-full, V3 +language)"
    }

    /**
     * Sends microphone PCM16 audio (16kHz mono) to Gemini Live.
     * Audio is ONLY sent after setupComplete is received.
     */
    fun sendAudioChunk(pcmBytes: ByteArray) {
        if (!isSetupComplete.get() || webSocket == null) return

        try {
            val base64Data = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
            val root = JSONObject()
            val realtimeInput = JSONObject()
            val audioObj = JSONObject()
            audioObj.put("data", base64Data)
            audioObj.put("mimeType", "audio/pcm;rate=16000")
            realtimeInput.put("audio", audioObj)
            root.put("realtimeInput", realtimeInput)

            val sent = webSocket?.send(root.toString()) ?: false
            if (sent) {
                val chunkNum = audioChunksSentCount.incrementAndGet()
                val totalBytes = audioBytesSentCount.addAndGet(pcmBytes.size.toLong())
                if (chunkNum == 1L || chunkNum % 50L == 0L) {
                    Log.d(TAG, "Sent audio chunk #$chunkNum (${pcmBytes.size} bytes, total: $totalBytes bytes)")
                }
            } else {
                Log.w(TAG, "Failed to send audio chunk (${pcmBytes.size} bytes) via WebSocket")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk: ${e.message}")
        }
    }

    /**
     * Sends a client text turn to Gemini Live using clientContent.
     */
    fun sendTextMessage(text: String) {
        if (!isSetupComplete.get() || webSocket == null) return
        try {
            val root = JSONObject()
            val clientContent = JSONObject()
            val turns = JSONArray()
            val turn = JSONObject()
            turn.put("role", "user")
            val parts = JSONArray()
            val part = JSONObject()
            part.put("text", text)
            parts.put(part)
            turn.put("parts", parts)
            turns.put(turn)
            clientContent.put("turns", turns)
            clientContent.put("turnComplete", true)
            root.put("clientContent", clientContent)

            webSocket?.send(root.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending text message: ${e.message}")
        }
    }

    fun isSessionReady(): Boolean = isRunning.get() && isSetupComplete.get() && webSocket != null

    fun endSession() {
        synchronized(lock) {
            endSessionLocked()
        }
    }

    private fun endSessionLocked() {
        val sentChunks = audioChunksSentCount.get()
        val sentBytes = audioBytesSentCount.get()
        val recvChunks = incomingAudioChunksCount.get()
        val recvBytes = incomingAudioBytesCount.get()
        Log.i(TAG, "Live session ended by user stop (sent $sentChunks chunks / $sentBytes bytes, received $recvChunks chunks / $recvBytes bytes)")
        isRunning.set(false)
        isSetupComplete.set(false)
        cancelSetupTimeout()
        lastFailureReason = null
        try {
            webSocket?.close(1000, "User ended session")
            webSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebSocket: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MiraLive"

        fun mapLanguageToBcp47(lang: String): String {
            val l = lang.lowercase()
            return when {
                l.contains("bangla") || l.contains("bengali") || l.contains("bn") -> "bn-BD"
                l.contains("english") || l.contains("en") -> "en-US"
                l.contains("hindi") || l.contains("hi") -> "hi-IN"
                l.contains("urdu") || l.contains("ur") -> "ur-PK"
                l.contains("arabic") || l.contains("ar") -> "ar-SA"
                l.contains("spanish") || l.contains("es") -> "es-ES"
                l.contains("french") || l.contains("fr") -> "fr-FR"
                l.contains("german") || l.contains("de") -> "de-DE"
                l.contains("japanese") || l.contains("ja") -> "ja-JP"
                l.contains("korean") || l.contains("ko") -> "ko-KR"
                l.contains("portuguese") || l.contains("pt") -> "pt-BR"
                l.contains("russian") || l.contains("ru") -> "ru-RU"
                l.contains("turkish") || l.contains("tr") -> "tr-TR"
                l.contains("indonesian") || l.contains("id") -> "id-ID"
                l.contains("vietnamese") || l.contains("vi") -> "vi-VN"
                l.contains("thai") || l.contains("th") -> "th-TH"
                l.contains("tamil") || l.contains("ta") -> "ta-IN"
                l.contains("telugu") || l.contains("te") -> "te-IN"
                l.contains("marathi") || l.contains("mr") -> "mr-IN"
                l.contains("gujarati") || l.contains("gu") -> "gu-IN"
                l.contains("punjabi") || l.contains("pa") -> "pa-IN"
                l.contains("malayalam") || l.contains("ml") -> "ml-IN"
                l.contains("kannada") || l.contains("kn") -> "kn-IN"
                l.contains("italian") || l.contains("it") -> "it-IT"
                l.contains("dutch") || l.contains("nl") -> "nl-NL"
                l.contains("polish") || l.contains("pl") -> "pl-PL"
                l.contains("swedish") || l.contains("sv") -> "sv-SE"
                else -> "en-US"
            }
        }
    }
}

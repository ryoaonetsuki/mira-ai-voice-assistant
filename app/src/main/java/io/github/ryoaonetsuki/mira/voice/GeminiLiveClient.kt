package io.github.ryoaonetsuki.mira.voice

import android.util.Base64
import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for WS
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isSetupComplete = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    // Handshake fallback model ladder.
    // NOTE: models/gemini-2.5-flash-native-audio-preview-12-2025 and
    //       models/gemini-3.1-flash-live-preview are legacy models scheduled
    //       to shut down on 2026-11-17 but are still live as of now.
    private val modelFallbackLadder = listOf(
        initialModel,
        "models/gemini-3.8-live",
        "models/gemini-2.5-flash-native-audio-preview-12-2025",
        "models/gemini-3.1-flash-live-preview"
    ).distinct()

    private val currentModelIndex = AtomicInteger(0)
    // Track which model indices have already been attempted this session (no infinite loops)
    private val attemptedIndices = mutableSetOf<Int>()

    // Setup timeout: 25 seconds to receive setupComplete after sending setup
    private val SETUP_TIMEOUT_MS = 25_000L
    private var setupTimeoutThread: Thread? = null

    fun startSession() {
        if (ephemeralToken.isNullOrBlank() && apiKey.isBlank()) {
            listener.onError("Missing API key. Please configure in Setup.")
            return
        }
        isRunning.set(true)
        isSetupComplete.set(false)
        currentModelIndex.set(0)
        attemptedIndices.clear()
        connectWithCurrentModel()
    }

    private fun connectWithCurrentModel() {
        val idx = currentModelIndex.get()
        if (idx >= modelFallbackLadder.size) {
            listener.onError("All voice models failed. Check your API key and network connection.")
            return
        }
        if (attemptedIndices.contains(idx)) {
            // Safety: skip already-attempted indices
            if (idx + 1 < modelFallbackLadder.size) {
                currentModelIndex.incrementAndGet()
                connectWithCurrentModel()
            } else {
                listener.onError("All voice models already attempted — no more fallbacks.")
            }
            return
        }
        attemptedIndices.add(idx)

        val model = modelFallbackLadder[idx]
        val request = if (!ephemeralToken.isNullOrBlank()) {
            val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained?access_token=$ephemeralToken"
            Log.i(TAG, "Connecting to Gemini Live WS using ephemeral token, model: $model (index $idx)")
            Request.Builder().url(wsUrl).build()
        } else {
            val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
            Log.i(TAG, "Connecting to Gemini Live WS using model: $model (index $idx)")
            Request.Builder()
                .url(wsUrl)
                .header("x-goog-api-key", apiKey)
                .build()
        }

        listener.onConnecting(model)

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connection opened successfully (HTTP ${response.code})")
                listener.onConnected()
                sendSetupMessage(webSocket, model)
                startSetupTimeout(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text, webSocket)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closing: code=$code, reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: code=$code, reason=$reason")
                cancelSetupTimeout()
                val wasSetup = isSetupComplete.get()
                isSetupComplete.set(false)

                if (isRunning.get() && !wasSetup) {
                    val nextIdx = currentModelIndex.get() + 1
                    if (nextIdx < modelFallbackLadder.size && !attemptedIndices.contains(nextIdx)) {
                        currentModelIndex.set(nextIdx)
                        val nextModel = modelFallbackLadder[nextIdx]
                        Log.w(TAG, "Setup failed or refused. Falling back to next model: $nextModel")
                        connectWithCurrentModel()
                        return
                    }
                }
                if (isRunning.get()) {
                    listener.onClosed(reason.ifBlank { "Session closed ($code)" })
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}, code=${response?.code}", t)
                cancelSetupTimeout()
                val wasSetup = isSetupComplete.get()
                isSetupComplete.set(false)

                val errDetail = buildString {
                    append(t.message ?: "Network error")
                    if (response != null) append(" (HTTP ${response.code})")
                }

                if (isRunning.get() && !wasSetup) {
                    val nextIdx = currentModelIndex.get() + 1
                    if (nextIdx < modelFallbackLadder.size && !attemptedIndices.contains(nextIdx)) {
                        currentModelIndex.set(nextIdx)
                        val nextModel = modelFallbackLadder[nextIdx]
                        Log.w(TAG, "Handshake failed. Trying next model: $nextModel")
                        connectWithCurrentModel()
                        return
                    }
                }
                listener.onError("Connection failed: $errDetail")
            }
        })
    }

    /**
     * Starts a 25-second timeout. If setupComplete does not arrive within this window,
     * close the socket and fall through to the next model in the ladder.
     */
    private fun startSetupTimeout(ws: WebSocket) {
        cancelSetupTimeout()
        setupTimeoutThread = Thread {
            try {
                Thread.sleep(SETUP_TIMEOUT_MS)
                if (!isSetupComplete.get() && isRunning.get()) {
                    Log.w(TAG, "Setup timeout after ${SETUP_TIMEOUT_MS}ms — closing socket and trying next model")
                    ws.close(1001, "Setup timeout")
                    isSetupComplete.set(false)

                    val nextIdx = currentModelIndex.get() + 1
                    if (nextIdx < modelFallbackLadder.size && !attemptedIndices.contains(nextIdx)) {
                        currentModelIndex.set(nextIdx)
                        connectWithCurrentModel()
                    } else {
                        listener.onError("Voice session timed out waiting for server setup (tried all models).")
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

    private fun sendSetupMessage(ws: WebSocket, model: String) {
        try {
            val setupObj = JSONObject()
            val setupDetails = JSONObject()
            setupDetails.put("model", model)

            // Generation config with responseModalities inside it (ai.google.dev/api/live)
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
            speechConfig.put("languageCode", mapLanguageToBcp47(language))
            genConfig.put("speechConfig", speechConfig)

            setupDetails.put("generationConfig", genConfig)

            // System instructions
            if (systemPrompt.isNotBlank()) {
                val sysInstruction = JSONObject()
                val parts = JSONArray()
                val partText = JSONObject()
                partText.put("text", systemPrompt)
                parts.put(partText)
                sysInstruction.put("parts", parts)
                setupDetails.put("systemInstruction", sysInstruction)
            }

            // Real-time audio transcriptions
            setupDetails.put("inputAudioTranscription", JSONObject())
            setupDetails.put("outputAudioTranscription", JSONObject())

            setupObj.put("setup", setupDetails)
            val jsonString = setupObj.toString()
            Log.d(TAG, "Sending setup payload to Gemini Live...")
            ws.send(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Error building setup payload: ${e.message}", e)
            listener.onError("Failed to build setup message: ${e.message}")
        }
    }

    private fun handleIncomingMessage(jsonText: String, ws: WebSocket) {
        try {
            val json = JSONObject(jsonText)

            // 0. Server error frame — parse and treat as failure, fall through ladder
            if (json.has("error")) {
                val errorObj = json.getJSONObject("error")
                val errCode = errorObj.optInt("code", -1)
                val errMsg = errorObj.optString("message", "Unknown server error")
                Log.e(TAG, "Server sent error frame: code=$errCode, message=$errMsg")
                cancelSetupTimeout()
                isSetupComplete.set(false)
                ws.close(1001, "Server error: $errMsg")

                if (isRunning.get()) {
                    val nextIdx = currentModelIndex.get() + 1
                    if (nextIdx < modelFallbackLadder.size && !attemptedIndices.contains(nextIdx)) {
                        currentModelIndex.set(nextIdx)
                        Log.w(TAG, "Falling through to next model after server error")
                        connectWithCurrentModel()
                    } else {
                        val displayCode = if (errCode != -1) " (code $errCode)" else ""
                        listener.onError("Voice session error$displayCode: $errMsg")
                    }
                }
                return
            }

            // 1. Setup complete check
            if (json.has("setupComplete")) {
                Log.i(TAG, ">>> SETUP COMPLETE received from Gemini Live! Starting audio transmission.")
                cancelSetupTimeout()
                isSetupComplete.set(true)
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

                // Input audio transcription (user transcript from serverContent.inputTranscription)
                if (serverContent.has("inputTranscription")) {
                    val inputTx = serverContent.getJSONObject("inputTranscription")
                    val text = inputTx.optString("text")
                    if (text.isNotBlank()) {
                        listener.onTranscript(text, true)
                    }
                }

                // Output audio transcription (model transcript from serverContent.outputTranscription)
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

                            // Check for audio data
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val base64Data = inlineData.optString("data")
                                if (base64Data.isNotBlank()) {
                                    val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
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
                Log.w(TAG, "Server sent GoAway frame: session time limit approaching")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming Gemini Live WS message: ${e.message}", e)
        }
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

            webSocket?.send(root.toString())
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

    fun endSession() {
        isRunning.set(false)
        isSetupComplete.set(false)
        cancelSetupTimeout()
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
                l.contains("bangla") || l.contains("bengali") || l.contains("bn") -> "bn-IN"
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

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
        if (apiKey.isBlank()) {
            listener.onError("Missing Gemini API key. Please configure in Setup.")
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
        // Send both ?key= param and x-goog-api-key header
        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"

        Log.i(TAG, "Connecting to Gemini Live WS using model: $model (index $idx)")
        listener.onConnecting(model)

        val request = Request.Builder()
            .url(wsUrl)
            .header("x-goog-api-key", apiKey)
            .build()

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

            // Response modalities at setup top level (AUDIO)
            val modalities = JSONArray()
            modalities.put("AUDIO")
            setupDetails.put("responseModalities", modalities)

            // Generation config
            val genConfig = JSONObject()
            val speechConfig = JSONObject()
            val voiceConfig = JSONObject()
            val prebuiltVoiceConfig = JSONObject()
            prebuiltVoiceConfig.put("voiceName", voiceName.ifBlank { "Aoede" })
            voiceConfig.put("prebuiltVoiceConfig", prebuiltVoiceConfig)
            speechConfig.put("voiceConfig", voiceConfig)
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

            // 3. User input transcription
            if (json.has("realtimeInputTranscription")) {
                val transcript = json.getJSONObject("realtimeInputTranscription").optString("text")
                if (transcript.isNotBlank()) {
                    listener.onTranscript(transcript, true)
                }
            }

            // 4. GoAway warning
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
    }
}

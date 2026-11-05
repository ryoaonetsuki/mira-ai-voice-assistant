package io.github.ryoaonetsuki.mira.voice

import android.util.Base64
import android.util.Log
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    // Handshake fallback model ladder
    private val modelFallbackLadder = listOf(
        initialModel,
        "models/gemini-3.8-live",
        "models/gemini-2.5-flash-native-audio-preview-12-2025",
        "models/gemini-3.1-flash-live-preview"
    ).distinct()

    private var currentModelIndex = 0
    private var hasAutoReconnected = false

    fun startSession() {
        if (apiKey.isBlank()) {
            listener.onError("Missing Gemini API key. Please configure in Setup.")
            return
        }
        isRunning.set(true)
        isSetupComplete.set(false)
        connectWithCurrentModel()
    }

    private fun connectWithCurrentModel() {
        val model = modelFallbackLadder.getOrElse(currentModelIndex) { modelFallbackLadder.first() }
        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"

        Log.i(TAG, "Connecting to Gemini Live WS using model: $model")
        listener.onConnecting(model)

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connection opened successfully (HTTP ${response.code})")
                listener.onConnected()
                sendSetupMessage(webSocket, model)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket closing: code=$code, reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: code=$code, reason=$reason")
                val wasSetup = isSetupComplete.get()
                isSetupComplete.set(false)

                if (isRunning.get()) {
                    if (!wasSetup && currentModelIndex + 1 < modelFallbackLadder.size) {
                        currentModelIndex++
                        val nextModel = modelFallbackLadder[currentModelIndex]
                        Log.w(TAG, "Setup failed or refused. Falling back to next model: $nextModel")
                        connectWithCurrentModel()
                        return
                    } else if (!hasAutoReconnected) {
                        hasAutoReconnected = true
                        Log.i(TAG, "Unexpected close. Attempting one-time auto-reconnect...")
                        connectWithCurrentModel()
                        return
                    }
                }
                listener.onClosed(reason.ifBlank { "Session closed ($code)" })
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}, code=${response?.code}", t)
                val wasSetup = isSetupComplete.get()
                isSetupComplete.set(false)

                if (isRunning.get() && !wasSetup && currentModelIndex + 1 < modelFallbackLadder.size) {
                    currentModelIndex++
                    val nextModel = modelFallbackLadder[currentModelIndex]
                    Log.w(TAG, "Handshake failed. Trying next model: $nextModel")
                    connectWithCurrentModel()
                } else {
                    listener.onError("Connection failed: ${t.message ?: "Network error"}")
                }
            }
        })
    }

    private fun sendSetupMessage(ws: WebSocket, model: String) {
        try {
            val setupObj = JSONObject()
            val setupDetails = JSONObject()
            setupDetails.put("model", model)

            // Generation config
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

    private fun handleIncomingMessage(jsonText: String) {
        try {
            val json = JSONObject(jsonText)

            // 1. Setup complete check
            if (json.has("setupComplete")) {
                Log.i(TAG, ">>> SETUP COMPLETE received from Gemini Live! Starting audio transmission.")
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
            val mediaChunks = JSONArray()
            val audioObj = JSONObject()
            audioObj.put("data", base64Data)
            audioObj.put("mimeType", "audio/pcm;rate=16000")
            mediaChunks.put(audioObj)
            realtimeInput.put("mediaChunks", mediaChunks)
            root.put("realtimeInput", realtimeInput)

            webSocket?.send(root.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk: ${e.message}")
        }
    }

    fun endSession() {
        isRunning.set(false)
        isSetupComplete.set(false)
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

package io.github.ryoaonetsuki.mira.voice

import android.content.Context
import android.util.Log
import io.github.ryoaonetsuki.mira.automation.MacroRunner
import io.github.ryoaonetsuki.mira.network.*
import io.github.ryoaonetsuki.mira.service.LiveState
import io.github.ryoaonetsuki.mira.service.VoiceStage
import kotlinx.coroutines.*
import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming server voice session (Round 9 default voice path).
 * Owns one persistent WebSocket to mira-server-voice, an AudioRecorder,
 * a VoiceActivityDetector, and interfaces with the shared AudioPlayer.
 *
 * Implements client-side instant barge-in, rolling partials,
 * Web search handshake, connector commands, and one-time reconnect.
 *
 * NEVER logs keys, transcripts, or audio values.
 */
class ServerVoiceSession(
    private val context: Context,
    private val groqKey: String,
    private val geminiKey: String,
    private val voiceName: String,
    private val language: String,
    private val systemPrompt: String,
    private val dateLine: String,
    private val audioPlayer: AudioPlayer,
    private val onStateChanged: (LiveState) -> Unit,
    private val onStageChanged: (VoiceStage) -> Unit,
    private val onUserTranscript: (String) -> Unit,
    private val onModelTranscript: (String) -> Unit,
    private val onAppendModelTranscript: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onAudioChunkSent: () -> Unit = {},
    private val onAudioChunkReceived: () -> Unit = {},
    private val onMicAmplitude: (Float) -> Unit = {},
    private val onRequestLiveFallback: (reason: String) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val serverVoiceClient = ServerVoiceClient()
    private var webSocket: WebSocket? = null
    private var audioRecorder: AudioRecorder? = null
    private var vad: VoiceActivityDetector? = null

    private val isRunning = AtomicBoolean(false)
    private var isUserStopping = false
    private var reconnectAttempt = 0
    private var hasFirstAudioArrivedForTurn = false
    private val accumulatedModelTranscript = StringBuilder()

    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "ServerVoiceSession already started")
            return
        }
        isUserStopping = false
        reconnectAttempt = 0

        onStateChanged(LiveState.CONNECTING)
        onStageChanged(VoiceStage.CONNECTING)

        initVadAndRecorder()
        connectWebSocket()
    }

    private fun initVadAndRecorder() {
        vad = VoiceActivityDetector(object : VoiceActivityDetector.VadListener {
            override fun onSpeechStart() {
                // Client-side instant barge-in if speaking or playing audio
                if (audioPlayer.isSpeaking()) {
                    Log.i(TAG, "Barge-in triggered by local VAD speech start")
                    audioPlayer.stopImmediatelyAndFlush()
                    sendBargeIn()
                    accumulatedModelTranscript.clear()
                    onModelTranscript("")
                    onStateChanged(LiveState.LISTENING)
                    onStageChanged(VoiceStage.RECOGNISING)
                } else {
                    onStageChanged(VoiceStage.RECOGNISING)
                }
            }

            override fun onSpeechEndpoint() {
                // Send client endpoint hint to Worker (Worker silence detection also acts)
                sendEndpointHint()
            }
        })

        audioRecorder = AudioRecorder(
            onAudioChunk = { chunk ->
                if (!isRunning.get() || isUserStopping) return@AudioRecorder
                vad?.processFrame(chunk)
                val ws = webSocket
                if (ws != null) {
                    val sent = ws.send(chunk.toByteString())
                    if (sent) {
                        onAudioChunkSent()
                    }
                }
            },
            onAmplitude = { amp ->
                onMicAmplitude(amp)
            }
        )

        val started = audioRecorder?.startRecording() ?: false
        if (!started) {
            Log.e(TAG, "AudioRecorder failed to start")
            onError("Microphone could not start — check mic permission")
            onStateChanged(LiveState.ERROR)
            onStageChanged(VoiceStage.ERROR)
            return
        }
    }

    private fun connectWebSocket() {
        if (!isRunning.get() || isUserStopping) return

        webSocket = serverVoiceClient.connect(groqKey, geminiKey, object : ServerVoiceClient.ServerVoiceListener {
            override fun onConnected() {
                scope.launch {
                    reconnectAttempt = 0
                    sendStartMessage()
                    onStateChanged(LiveState.LISTENING)
                    onStageChanged(VoiceStage.LISTENING)
                }
            }

            override fun onPartial(text: String) {
                scope.launch {
                    onUserTranscript(text)
                    onStageChanged(VoiceStage.RECOGNISING)
                }
            }

            override fun onTranscript(text: String) {
                scope.launch {
                    onUserTranscript(text)
                    onStageChanged(VoiceStage.THINKING)
                    accumulatedModelTranscript.clear()
                    hasFirstAudioArrivedForTurn = false
                    handleFinalTranscriptAndProceed(text)
                }
            }

            override fun onToken(text: String) {
                scope.launch {
                    accumulatedModelTranscript.append(text)
                    onAppendModelTranscript(text)
                    if (!hasFirstAudioArrivedForTurn) {
                        onStageChanged(VoiceStage.THINKING)
                    }
                }
            }

            override fun onAudio(pcm24kBytes: ByteArray, sentence: Int) {
                scope.launch {
                    hasFirstAudioArrivedForTurn = true
                    onAudioChunkReceived()
                    onStateChanged(LiveState.SPEAKING)
                    onStageChanged(VoiceStage.SPEAKING)
                    playPcmChunks(pcm24kBytes)
                }
            }

            override fun onReplyDone() {
                scope.launch {
                    hasFirstAudioArrivedForTurn = false
                    onStateChanged(LiveState.LISTENING)
                    onStageChanged(VoiceStage.LISTENING)
                }
            }

            override fun onError(message: String) {
                scope.launch {
                    Log.w(TAG, "ServerVoiceSession error: $message")
                    if (message.contains("API key problem", ignoreCase = true) ||
                        message.contains("check your keys", ignoreCase = true)
                    ) {
                        onStateChanged(LiveState.ERROR)
                        onStageChanged(VoiceStage.ERROR)
                        onError(message)
                    } else {
                        // Network/upstream error: fallback to Live
                        stopInternal()
                        onRequestLiveFallback(message)
                    }
                }
            }

            override fun onClosed(code: Int, reason: String) {
                scope.launch {
                    if (isUserStopping || !isRunning.get()) return@launch

                    if (reconnectAttempt == 0) {
                        reconnectAttempt++
                        Log.i(TAG, "WebSocket dropped mid-session, reconnecting once...")
                        connectWebSocket()
                    } else {
                        Log.w(TAG, "WebSocket dropped after reconnect attempt (code=$code): $reason")
                        stopInternal()
                        onRequestLiveFallback("Server voice disconnected: $reason")
                    }
                }
            }
        })
    }

    private fun sendStartMessage() {
        val voiceRules = buildString {
            append(systemPrompt)
            append("\nKeep voice answers short and fast — a few sentences at most unless the user asks for detail.")
        }
        val startJson = JSONObject().apply {
            put("type", "start")
            put("language", language)
            put("voiceName", voiceName)
            put("systemPrompt", voiceRules)
            put("dateLine", dateLine)
        }
        webSocket?.send(startJson.toString())
    }

    private fun sendBargeIn() {
        val json = JSONObject().apply { put("type", "barge-in") }
        webSocket?.send(json.toString())
    }

    private fun sendEndpointHint() {
        val json = JSONObject().apply { put("type", "endpoint") }
        webSocket?.send(json.toString())
    }

    private fun sendProceed(searchContext: String?) {
        val json = JSONObject().apply {
            put("type", "proceed")
            if (searchContext != null) {
                put("searchContext", searchContext)
            } else {
                put("searchContext", JSONObject.NULL)
            }
        }
        webSocket?.send(json.toString())
    }

    private suspend fun handleFinalTranscriptAndProceed(finalText: String) = withContext(Dispatchers.IO) {
        val cleanText = finalText.trim()
        if (cleanText.isBlank()) {
            sendProceed(null)
            return@withContext
        }

        // 1. Emergency macro stop check
        if (MacroRunner.isRunning.value) {
            val lower = cleanText.lowercase(Locale.ROOT)
            val stopTokens = lower.split("\\s+".toRegex())
            val stopWords = setOf("stop", "thamo", "থামো")
            if (stopTokens.any { it in stopWords }) {
                MacroRunner.cancel("Stopped by voice: $cleanText")
                sendProceed(null)
                return@withContext
            }
        }

        // 2. Connector command check
        if (ConnectorIntentHandler.isConnectorCommand(cleanText)) {
            try {
                val result = ConnectorIntentHandler.handleCommand(context, cleanText)
                if (result != null) {
                    val searchCtx = "User command result: $result"
                    sendProceed(searchCtx)
                    return@withContext
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connector execution failed in server voice: ${e.message}")
            }
        }

        // 3. Web Search check
        if (MiraChatSearchHeuristic.isSearchRequest(cleanText)) {
            val ackPhrase = SearchAcknowledgement.getPhrase(language)
            withContext(Dispatchers.Main) {
                onModelTranscript(ackPhrase)
            }

            // Speak acknowledgement aloud first via GeminiTtsClient
            val ttsClient = GeminiTtsClient(context)
            val ttsResult = ttsClient.speakText(
                apiKey = geminiKey,
                text = ackPhrase,
                voiceName = voiceName,
                useServerProxy = false
            )
            if (ttsResult.isSuccess) {
                playPcmChunks(ttsResult.getOrThrow())
            }

            // Run web search
            val searchResults = FreeWebSearch.search(cleanText, maxResults = 5)
            val searchContext = buildString {
                append("You have already spoken the search acknowledgement aloud. Do NOT repeat it — give ONLY the final answer.")
                append("\n\nCurrent date and time: ")
                append(currentDateTimeLine())
                if (searchResults.isNotEmpty()) {
                    append("\n\nFresh search results:\n")
                    append(searchResults.mapIndexed { idx, res ->
                        "${idx + 1}. ${res.title} — ${res.snippet} (${res.url})"
                    }.joinToString("\n"))
                } else {
                    append("\n\nNo fresh web results were found for this query. Say so briefly.")
                }
            }
            sendProceed(searchContext)
        } else {
            sendProceed(null)
        }
    }

    private fun playPcmChunks(pcmBytes: ByteArray) {
        val chunkSize = 4096
        var offset = 0
        while (offset < pcmBytes.size && isRunning.get() && !isUserStopping) {
            val end = minOf(offset + chunkSize, pcmBytes.size)
            val chunk = pcmBytes.copyOfRange(offset, end)
            audioPlayer.playAudioChunk(chunk)
            offset = end
        }
    }

    private fun stopInternal() {
        audioRecorder?.stopRecording()
        audioRecorder = null
        try {
            webSocket?.close(1000, "Session ended")
        } catch (_) {}
        webSocket = null
    }

    fun stop() {
        isUserStopping = true
        if (!isRunning.getAndSet(false)) {
            return
        }

        try {
            val stopMsg = JSONObject().apply { put("type", "stop") }
            webSocket?.send(stopMsg.toString())
        } catch (_) {}

        stopInternal()
        scope.cancel()
        audioPlayer.stopImmediatelyAndFlush()
        vad?.reset()
        onStateChanged(LiveState.IDLE)
        onStageChanged(VoiceStage.IDLE)
    }

    companion object {
        private const val TAG = "MiraServerVoice"
    }
}

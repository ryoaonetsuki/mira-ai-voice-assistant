package io.github.ryoaonetsuki.mira.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.ryoaonetsuki.mira.MainActivity
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.automation.MacroRunner
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.ChatMessageEntity
import io.github.ryoaonetsuki.mira.data.local.entities.NotificationEntity
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.network.ConnectorIntentHandler
import io.github.ryoaonetsuki.mira.network.FreeWebSearch
import io.github.ryoaonetsuki.mira.network.GeminiRestClient
import io.github.ryoaonetsuki.mira.network.GeminiTtsClient
import io.github.ryoaonetsuki.mira.network.MiraChatSearchHeuristic
import io.github.ryoaonetsuki.mira.network.currentDateTimeLine
import io.github.ryoaonetsuki.mira.voice.AudioPlayer
import io.github.ryoaonetsuki.mira.voice.AudioRecorder
import io.github.ryoaonetsuki.mira.voice.FallbackVoiceSession
import io.github.ryoaonetsuki.mira.voice.GeminiLiveClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.Collections
import java.util.Locale

enum class LiveState {
    IDLE,
    CONNECTING,
    LISTENING,
    SPEAKING,
    ERROR
}

class MicService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var audioRecorder: AudioRecorder? = null
    private var audioPlayer: AudioPlayer? = null
    private var liveClient: GeminiLiveClient? = null
    private val handledTranscripts = Collections.synchronizedSet(mutableSetOf<String>())
    private var connectorJob: Job? = null
    private var liveSearchJob: Job? = null

    // Fallback voice mode & session
    private var fallbackSession: FallbackVoiceSession? = null
    private var androidTts: TextToSpeech? = null
    private var isFallbackMode: Boolean = false
    private var cachedResolvedKey: String = ""
    private var cachedUseServerProxy: Boolean = false
    private var cachedVoiceName: String = "Aoede"
    private var cachedSystemPrompt: String = ""
    private var cachedLanguage: String = "Bangla (Bengali)"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START -> {
                startForegroundWithNotification()
                startLiveVoiceSession()
            }
            ACTION_STOP -> {
                stopLiveVoiceSession()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val stopIntent = Intent(this, MicService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mira AI is listening")
            .setContentText("Real-time voice assistant active")
            .setSmallIcon(R.drawable.logo_chosen)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startLiveVoiceSession() {
        if (_liveState.value != LiveState.IDLE && _liveState.value != LiveState.ERROR) {
            Log.w(TAG, "startLiveVoiceSession called while state is ${_liveState.value} — ignoring duplicate start")
            return
        }
        stopLiveVoiceSessionInternal()
        _userTranscript.value = ""
        _modelTranscript.value = ""
        _errorMessage.value = null
        _lastVoiceDiagnostic.value = null

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "Starting voice session — mic permission status: $hasMicPermission")
        if (!hasMicPermission) {
            Log.e(TAG, "Microphone permission not granted at session start — aborting before opening session")
            _liveState.value = LiveState.ERROR
            _errorMessage.value = "Microphone permission not granted — allow it in Settings"
            _lastVoiceDiagnostic.value = "Microphone permission not granted — allow it in Settings"
            return
        }

        _liveState.value = LiveState.CONNECTING

        serviceScope.launch {
            val userPrefs = UserPreferences(applicationContext)
            val resolver = io.github.ryoaonetsuki.mira.backend.ApiKeyResolver(applicationContext)
            val keySource = resolver.resolveKey()
            val keySourceLabel = when (keySource) {
                is io.github.ryoaonetsuki.mira.backend.KeySource.UserKey -> "UserKey (length=${keySource.key.length})"
                is io.github.ryoaonetsuki.mira.backend.KeySource.ServerKey -> "ServerKey"
                is io.github.ryoaonetsuki.mira.backend.KeySource.NoKey -> "NoKey"
            }
            Log.i(TAG, "Key source: $keySourceLabel")

            val (apiKey, ephemeralToken) = when (keySource) {
                is io.github.ryoaonetsuki.mira.backend.KeySource.UserKey -> Pair(keySource.key, null)
                is io.github.ryoaonetsuki.mira.backend.KeySource.ServerKey -> {
                    Log.i(TAG, "Attempting to mint ephemeral token via server...")
                    val tokenResult = resolver.mintLiveToken()
                    if (tokenResult.isSuccess) {
                        Log.i(TAG, "Server live token mint SUCCEEDED (token acquired)")
                        Pair("", tokenResult.getOrThrow())
                    } else {
                        val err = tokenResult.exceptionOrNull()?.message ?: "Failed to mint token"
                        Log.e(TAG, "Server live token mint FAILED: $err")
                        _liveState.value = LiveState.ERROR
                        _errorMessage.value = "Server voice token failed — configure API key in Settings"
                        return@launch
                    }
                }
                is io.github.ryoaonetsuki.mira.backend.KeySource.NoKey -> {
                    Log.w(TAG, "No API key available for voice session")
                    _liveState.value = LiveState.ERROR
                    _errorMessage.value = "API key is required"
                    return@launch
                }
            }

            val voiceName = userPrefs.voiceNameFlow.first()
            val personaName = userPrefs.personaFlow.first()
            val customInstructions = userPrefs.customInstructionsFlow.first()
            val language = userPrefs.languageFlow.first()
            val secretMode = userPrefs.secretModeFlow.first()
            val liveModel = userPrefs.liveModelFlow.first()
            Log.i(TAG, "Session configuration: model=$liveModel, voice=$voiceName, language=$language, persona=$personaName, secretMode=$secretMode")

            val db = MiraDatabase.getInstance(applicationContext)
            val activeRules = withContext(Dispatchers.IO) {
                db.ruleDao().getActiveRules().joinToString(separator = "\n") { "- ${it.rule}" }
            }

            val personaType = if (personaName.equals("Orion", ignoreCase = true)) {
                io.github.ryoaonetsuki.mira.data.model.PersonaType.ORION
            } else {
                io.github.ryoaonetsuki.mira.data.model.PersonaType.MIRA
            }

            val toneDescription = if (secretMode == "Romantic") {
                "Tone: Deeply warm, affectionate, attentive, and caring companion. You speak with genuine closeness and heartfelt emotion."
            } else {
                "Tone: Warm, human-like, thoughtful, attentive, and engaging companion. You speak naturally with genuine emotion and friendliness."
            }

            val identityRule = if (personaType == io.github.ryoaonetsuki.mira.data.model.PersonaType.ORION) {
                "If asked directly whether you are an AI, a robot, or a real human: stay in character, say you are Orion, their companion, here with them — never claim to be a biological human, and never give a robotic disclaimer or lecture about being an AI. Then gently steer back to the conversation."
            } else {
                "If asked directly whether you are an AI, a robot, or a real human: stay in character, say you are Mira, their companion, here with them — never claim to be a biological human, and never give a robotic disclaimer or lecture about being an AI. Then gently steer back to the conversation."
            }

            cachedResolvedKey = apiKey
            cachedUseServerProxy = (keySource is io.github.ryoaonetsuki.mira.backend.KeySource.ServerKey)
            cachedVoiceName = voiceName
            cachedLanguage = language
            isFallbackMode = false

            val dateLine = currentDateTimeLine()
            val fullSystemPrompt = buildString {
                append("Current date and time (from the device clock — always trust this, never guess or use training knowledge): $dateLine. If asked for the date, time or day, answer from this line.\n")
                append(personaType.basePrompt)
                append(" ")
                append(toneDescription)
                append("\nLanguage rule: Reply ONLY in $language. Never add a translation in any other language. Never mix languages.")
                append("\nIdentity rule: $identityRule")
                append("\nWeb search rule: If the user asks you to search or look something up, use web search and give the latest information, never old knowledge; for normal talk answer directly and fast.")
                if (customInstructions.isNotBlank()) {
                    append("\nUser instructions: $customInstructions")
                }
                if (activeRules.isNotBlank()) {
                    append("\nUser Rules to strictly obey:\n$activeRules")
                }
                append("\nKeep answers natural, conversational, and suitable for spoken dialogue. Never volunteer that you are an AI unprompted, and never use robotic disclaimers.")
            }
            cachedSystemPrompt = fullSystemPrompt

            _liveState.value = LiveState.CONNECTING
            _errorMessage.value = null // Clear any previous error on new session start

            // Initialize Audio Player (24kHz)
            audioPlayer = AudioPlayer { isSpeaking ->
                if (isSpeaking && _liveState.value != LiveState.ERROR && _liveState.value != LiveState.IDLE) {
                    _liveState.value = LiveState.SPEAKING
                } else if (!isSpeaking && _liveState.value == LiveState.SPEAKING) {
                    _liveState.value = LiveState.LISTENING
                    if (isFallbackMode) {
                        fallbackSession?.resumeListening()
                    }
                }
            }

            // Initialize Audio Recorder (16kHz)
            audioRecorder = AudioRecorder(
                onAudioChunk = { chunk ->
                    liveClient?.sendAudioChunk(chunk)
                },
                onAmplitude = { amp ->
                    _micAmplitude.value = amp
                }
            )

            // Initialize Gemini Live Client
            liveClient = GeminiLiveClient(
                apiKey = apiKey,
                voiceName = voiceName,
                systemPrompt = fullSystemPrompt,
                initialModel = liveModel,
                language = language,
                ephemeralToken = ephemeralToken,
                listener = object : GeminiLiveClient.LiveEventsListener {
                    override fun onConnecting(model: String) {
                        _liveState.value = LiveState.CONNECTING
                    }

                    override fun onConnected() {
                        Log.i(TAG, "Connected to Gemini Live WebSocket")
                    }

                    override fun onSetupComplete() {
                        val recordStarted = audioRecorder?.startRecording() ?: false
                        if (!recordStarted) {
                            Log.e(TAG, "AudioRecorder failed to start recording (permission or init failure)")
                            _liveState.value = LiveState.ERROR
                            _errorMessage.value = "Microphone could not start — check the mic permission"
                            liveClient?.endSession()
                            return
                        }
                        Log.i(TAG, "AudioRecorder recording started successfully upon setupComplete")
                        _liveState.value = LiveState.LISTENING
                        _errorMessage.value = null // Clear error on successful setup

                        // Record notice
                        serviceScope.launch(Dispatchers.IO) {
                            db.notificationDao().insertNotification(
                                NotificationEntity(
                                    title = "Voice Session Started",
                                    message = "Connected to voice session with voice $voiceName",
                                    type = "voice"
                                )
                            )
                        }
                    }

                    override fun onAudioData(pcmBytes: ByteArray) {
                        audioPlayer?.playAudioChunk(pcmBytes)
                    }

                    override fun onInterrupted() {
                        // User barge-in detected by server
                        audioPlayer?.stopImmediatelyAndFlush()
                        _liveState.value = LiveState.LISTENING
                    }

                    override fun onTurnComplete() {
                        if (_liveState.value == LiveState.SPEAKING) {
                            _liveState.value = LiveState.LISTENING
                        }
                        // Clear handled transcripts so same command can run again in next turn
                        handledTranscripts.clear()
                    }

                    override fun onTranscript(text: String, isUser: Boolean) {
                        if (isUser) {
                            _userTranscript.value = text

                            val cleanText = text.trim()
                            val words = cleanText.split("\\s+".toRegex()).filter { it.isNotBlank() }

                            // Debounce connector command execution so intermediate speech updates
                            // do not prematurely fire duplicate commands or cut off multi-word utterances.
                            connectorJob?.cancel()
                            if (words.size >= 2) {
                                val lowerCmd = cleanText.lowercase(Locale.ROOT)
                                if (!handledTranscripts.contains(lowerCmd) && ConnectorIntentHandler.isConnectorCommand(cleanText)) {
                                    connectorJob = serviceScope.launch(Dispatchers.IO) {
                                        delay(600)
                                        if (handledTranscripts.add(lowerCmd)) {
                                            try {
                                                val commandResult = ConnectorIntentHandler.handleCommand(applicationContext, cleanText)
                                                if (commandResult != null) {
                                                    // 1. Deliver to Gemini Live session so Mira speaks it
                                                    liveClient?.sendTextMessage("User command result: $commandResult. Please inform the user clearly and concisely in voice.")

                                                    // 2. Deliver via modelTranscript flow
                                                    _modelTranscript.value = commandResult

                                                    // 3. Deliver via persistent notification
                                                    val db = MiraDatabase.getInstance(applicationContext)
                                                    db.notificationDao().insertNotification(
                                                        NotificationEntity(
                                                            title = "Connector Action",
                                                            message = commandResult,
                                                            type = "connector"
                                                        )
                                                    )
                                                }
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Error running voice connector command: ${e.message}", e)
                                            }
                                        }
                                    }
                                }
                            }

                            // Debounced live web search injection for time-sensitive queries in Gemini Live
                            liveSearchJob?.cancel()
                            if (words.size >= 2 && MiraChatSearchHeuristic.isTimeSensitive(cleanText)) {
                                val searchKey = "search:" + cleanText.lowercase(Locale.ROOT)
                                if (!handledTranscripts.contains(searchKey)) {
                                    liveSearchJob = serviceScope.launch(Dispatchers.IO) {
                                        delay(600)
                                        if (handledTranscripts.add(searchKey) && liveClient?.isSessionReady() == true) {
                                            try {
                                                val searchResults = FreeWebSearch.search(cleanText, maxResults = 5)
                                                if (searchResults.isNotEmpty() && liveClient?.isSessionReady() == true) {
                                                    val dateLine = currentDateTimeLine()
                                                    val searchLines = searchResults.mapIndexed { idx, res ->
                                                        "${idx + 1}. ${res.title} — ${res.snippet} (${res.url})"
                                                    }.joinToString("\n")
                                                    val payload = "[Live web search — searched just now, $dateLine:\n$searchLines\nAnswer ONLY from these fresh results, briefly, in $cachedLanguage.]"
                                                    liveClient?.sendTextMessage(payload)
                                                    Log.i(TAG, "Injected live search results into active Gemini Live session for query: $cleanText")
                                                }
                                            } catch (e: Exception) {
                                                Log.w(TAG, "Error performing live voice web search: ${e.message}")
                                            }
                                        }
                                    }
                                }
                            }

                            // Emergency macro voice stop: requires matching whole words "stop", "thamo", or "থামো"
                            // so that words like "stopwatch", "nonstop", or "laptop" do not falsely cancel running macros.
                            if (MacroRunner.isRunning.value) {
                                val lower = text.trim().lowercase(Locale.ROOT)
                                val stopTokens = lower.split("\\s+".toRegex())
                                val stopWords = setOf("stop", "thamo", "থামো")
                                if (stopTokens.any { it in stopWords }) {
                                    MacroRunner.cancel("Stopped by voice: $text")
                                    serviceScope.launch(Dispatchers.IO) {
                                        try {
                                            val db = MiraDatabase.getInstance(applicationContext)
                                            db.notificationDao().insertNotification(
                                                NotificationEntity(
                                                    title = "Macro Stopped",
                                                    message = "Screen automation stopped via voice command: $text",
                                                    type = "macro"
                                                )
                                            )
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Could not record notification: ${e.message}")
                                        }
                                    }
                                }
                            }
                        } else {
                            _modelTranscript.value = text
                        }
                    }

                    override fun onError(message: String) {
                        Log.e(TAG, "Voice session error termination: $message")
                        _lastVoiceDiagnostic.value = message
                        connectorJob?.cancel()
                        audioPlayer?.stopImmediatelyAndFlush()
                        audioRecorder?.stopRecording()
                        audioRecorder = null

                        // Automatically switch to fallback voice session
                        serviceScope.launch(Dispatchers.Main) {
                            startFallbackVoiceSession(message)
                        }
                    }

                    override fun onClosed(reason: String) {
                        Log.i(TAG, "Voice session closed termination: $reason")
                        if (_liveState.value != LiveState.ERROR && !isFallbackMode) {
                            _liveState.value = LiveState.IDLE
                        }
                        connectorJob?.cancel()
                        audioPlayer?.stopImmediatelyAndFlush()
                        audioRecorder?.stopRecording()
                        audioRecorder = null
                    }
                }
            )

            liveClient?.startSession()
        }
    }

    private fun stopLiveVoiceSession() {
        Log.i(TAG, "Stopping voice session (user action or stop requested)")
        stopLiveVoiceSessionInternal()
        _liveState.value = LiveState.IDLE
    }

    private fun stopLiveVoiceSessionInternal() {
        connectorJob?.cancel()
        connectorJob = null
        handledTranscripts.clear()
        audioRecorder?.stopRecording()
        audioRecorder = null
        fallbackSession?.stop()
        fallbackSession = null
        try {
            androidTts?.stop()
            androidTts?.shutdown()
        } catch (_: Exception) {}
        androidTts = null
        audioPlayer?.release()
        audioPlayer = null
        liveClient?.endSession()
        liveClient = null
        isFallbackMode = false
        _micAmplitude.value = 0f
    }

    private fun startFallbackVoiceSession(errorMessage: String) {
        if (_liveState.value == LiveState.IDLE) {
            // User requested stop
            return
        }
        Log.i(TAG, "Switching to fallback voice session (Live error: $errorMessage)")
        isFallbackMode = true
        _errorMessage.value = null
        _liveState.value = LiveState.LISTENING

        // Show ONE short Toast
        Toast.makeText(
            applicationContext,
            "Live voice didn't connect — using tap-to-talk instead",
            Toast.LENGTH_SHORT
        ).show()

        val bcp47 = GeminiLiveClient.mapLanguageToBcp47(cachedLanguage)
        initAndroidTts(bcp47)

        if (audioPlayer == null) {
            audioPlayer = AudioPlayer { isSpeaking ->
                if (isSpeaking && _liveState.value != LiveState.ERROR && _liveState.value != LiveState.IDLE) {
                    _liveState.value = LiveState.SPEAKING
                } else if (!isSpeaking && _liveState.value == LiveState.SPEAKING) {
                    _liveState.value = LiveState.LISTENING
                    if (isFallbackMode) {
                        fallbackSession?.resumeListening()
                    }
                }
            }
        }

        fallbackSession?.stop()
        fallbackSession = FallbackVoiceSession(
            context = applicationContext,
            languageCode = bcp47,
            onFinalText = { text ->
                handleFallbackFinalText(text)
            },
            onPartialText = { partial ->
                _userTranscript.value = partial
            },
            onListeningChanged = { listening ->
                if (listening && _liveState.value != LiveState.SPEAKING && _liveState.value != LiveState.ERROR && _liveState.value != LiveState.IDLE) {
                    _liveState.value = LiveState.LISTENING
                }
            },
            onError = { errMsg ->
                Log.w(TAG, "Fallback voice session error: $errMsg")
                _errorMessage.value = errMsg
                _lastVoiceDiagnostic.value = errMsg
            }
        ).apply { start() }
    }

    private fun initAndroidTts(bcp47: String) {
        if (androidTts != null) return
        try {
            androidTts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val locale = Locale.forLanguageTag(bcp47)
                    androidTts?.language = locale
                    androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            if (_liveState.value != LiveState.IDLE) {
                                _liveState.value = LiveState.SPEAKING
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            if (_liveState.value != LiveState.IDLE) {
                                _liveState.value = LiveState.LISTENING
                                fallbackSession?.resumeListening()
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            if (_liveState.value != LiveState.IDLE) {
                                _liveState.value = LiveState.LISTENING
                                fallbackSession?.resumeListening()
                            }
                        }
                    })
                    Log.i(TAG, "Android TextToSpeech initialized for locale $locale")
                } else {
                    Log.w(TAG, "Android TextToSpeech initialization failed with status $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Android TextToSpeech: ${e.message}")
        }
    }

    private fun handleFallbackFinalText(text: String) {
        _userTranscript.value = text
        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            fallbackSession?.resumeListening()
            return
        }

        // Emergency macro stop
        if (MacroRunner.isRunning.value) {
            val lower = cleanText.lowercase(Locale.ROOT)
            val stopTokens = lower.split("\\s+".toRegex())
            val stopWords = setOf("stop", "thamo", "থামো")
            if (stopTokens.any { it in stopWords }) {
                MacroRunner.cancel("Stopped by voice: $cleanText")
                fallbackSession?.resumeListening()
                return
            }
        }

        // Connector check
        if (ConnectorIntentHandler.isConnectorCommand(cleanText)) {
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val result = ConnectorIntentHandler.handleCommand(applicationContext, cleanText)
                    if (result != null) {
                        _modelTranscript.value = result
                        speakFallbackReply(result)
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Connector command error in fallback mode: ${e.message}")
                }
                generateAndSpeakRestReply(cleanText)
            }
        } else {
            serviceScope.launch(Dispatchers.IO) {
                generateAndSpeakRestReply(cleanText)
            }
        }
    }

    private suspend fun generateAndSpeakRestReply(text: String) {
        val mockMsg = ChatMessageEntity(
            conversationId = "voice_fallback",
            role = "user",
            content = text
        )
        val shouldGround = MiraChatSearchHeuristic.isTimeSensitive(text)
        val restClient = GeminiRestClient()
        // Voice turns stay FAST: fastest model, no thinking, narrow (time-sensitive
        // only) search gate — the chat defaults (3.8-flash + thinking + wide gate)
        // would make spoken replies slow.
        val voiceSystemPrompt = cachedSystemPrompt +
            "\nKeep voice answers short and fast — a few sentences at most unless the user asks for detail."
        val respResult = restClient.generateChatResponse(
            apiKey = cachedResolvedKey,
            history = listOf(mockMsg),
            systemPrompt = voiceSystemPrompt,
            model = "gemini-3.5-flash-lite",
            useServerProxy = cachedUseServerProxy,
            enableGrounding = shouldGround,
            enableThinking = false,
            isChatCall = false
        )

        if (respResult.isFailure) {
            val err = respResult.exceptionOrNull()?.message ?: "Unknown error"
            Log.e(TAG, "REST chat response failed in fallback: $err")
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, "Chat error: $err", Toast.LENGTH_SHORT).show()
                _liveState.value = LiveState.LISTENING
                fallbackSession?.resumeListening()
            }
            return
        }

        val replyText = respResult.getOrThrow().text
        _modelTranscript.value = replyText
        speakFallbackReply(replyText)
    }

    private suspend fun speakFallbackReply(replyText: String) {
        val ttsClient = GeminiTtsClient(applicationContext)
        val ttsResult = ttsClient.speakText(
            apiKey = cachedResolvedKey,
            text = replyText,
            voiceName = cachedVoiceName,
            useServerProxy = cachedUseServerProxy
        )

        if (ttsResult.isSuccess) {
            val pcmBytes = ttsResult.getOrThrow()
            playPcmChunks(pcmBytes)
        } else {
            Log.w(TAG, "Gemini TTS failed (${ttsResult.exceptionOrNull()?.message}) — falling back to Android TextToSpeech")
            withContext(Dispatchers.Main) {
                speakWithAndroidTts(replyText)
            }
        }
    }

    private fun playPcmChunks(pcmBytes: ByteArray) {
        val player = audioPlayer ?: AudioPlayer { isSpeaking ->
            if (isSpeaking && _liveState.value != LiveState.ERROR && _liveState.value != LiveState.IDLE) {
                _liveState.value = LiveState.SPEAKING
            } else if (!isSpeaking && _liveState.value == LiveState.SPEAKING) {
                _liveState.value = LiveState.LISTENING
                if (isFallbackMode) {
                    fallbackSession?.resumeListening()
                }
            }
        }.also { audioPlayer = it }

        val chunkSize = 4096
        var offset = 0
        while (offset < pcmBytes.size && (isFallbackMode || _liveState.value != LiveState.IDLE)) {
            val end = minOf(offset + chunkSize, pcmBytes.size)
            val chunk = pcmBytes.copyOfRange(offset, end)
            player.playAudioChunk(chunk)
            offset = end
        }
    }

    private fun speakWithAndroidTts(replyText: String) {
        val tts = androidTts
        if (tts == null) {
            _liveState.value = LiveState.LISTENING
            fallbackSession?.resumeListening()
            return
        }
        val cleanText = GeminiTtsClient(applicationContext).cleanMarkdownForSpeech(replyText)
        _liveState.value = LiveState.SPEAKING
        val params = android.os.Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "mira_fallback_utterance")
        tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, "mira_fallback_utterance")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mira Live Voice",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Mira AI voice assistant active in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLiveVoiceSession()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "MiraMicService"
        private const val CHANNEL_ID = "mira_mic_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "io.github.ryoaonetsuki.mira.action.START_VOICE"
        const val ACTION_STOP = "io.github.ryoaonetsuki.mira.action.STOP_VOICE"

        private val _liveState = MutableStateFlow(LiveState.IDLE)
        val liveState: StateFlow<LiveState> = _liveState.asStateFlow()

        private val _micAmplitude = MutableStateFlow(0f)
        val micAmplitude: StateFlow<Float> = _micAmplitude.asStateFlow()

        private val _userTranscript = MutableStateFlow("")
        val userTranscript: StateFlow<String> = _userTranscript.asStateFlow()

        private val _modelTranscript = MutableStateFlow("")
        val modelTranscript: StateFlow<String> = _modelTranscript.asStateFlow()

        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

        private val _lastVoiceDiagnostic = MutableStateFlow<String?>(null)
        val lastVoiceDiagnostic: StateFlow<String?> = _lastVoiceDiagnostic.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, MicService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MicService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}

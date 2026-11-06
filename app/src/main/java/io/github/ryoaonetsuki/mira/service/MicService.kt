package io.github.ryoaonetsuki.mira.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.ryoaonetsuki.mira.MainActivity
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.automation.MacroRunner
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.NotificationEntity
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.network.ConnectorIntentHandler
import io.github.ryoaonetsuki.mira.voice.AudioPlayer
import io.github.ryoaonetsuki.mira.voice.AudioRecorder
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
        _liveState.value = LiveState.CONNECTING

        serviceScope.launch {
            val userPrefs = UserPreferences(applicationContext)
            val resolver = io.github.ryoaonetsuki.mira.backend.ApiKeyResolver(applicationContext)
            val keySource = resolver.resolveKey()

            val (apiKey, ephemeralToken) = when (keySource) {
                is io.github.ryoaonetsuki.mira.backend.KeySource.UserKey -> Pair(keySource.key, null)
                is io.github.ryoaonetsuki.mira.backend.KeySource.ServerKey -> {
                    val tokenResult = resolver.mintLiveToken()
                    if (tokenResult.isSuccess) {
                        Pair("", tokenResult.getOrThrow())
                    } else {
                        val err = tokenResult.exceptionOrNull()?.message ?: "Failed to mint token"
                        Log.e(TAG, "Server live token mint failed: $err")
                        _liveState.value = LiveState.ERROR
                        _errorMessage.value = "Server voice token failed — configure API key in Settings"
                        return@launch
                    }
                }
                is io.github.ryoaonetsuki.mira.backend.KeySource.NoKey -> {
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

            val fullSystemPrompt = buildString {
                append(personaType.basePrompt)
                append(" ")
                append(toneDescription)
                append("\nLanguage rule: Reply ONLY in $language. Never add a translation in any other language. Never mix languages.")
                append("\nIdentity rule: $identityRule")
                if (customInstructions.isNotBlank()) {
                    append("\nUser instructions: $customInstructions")
                }
                if (activeRules.isNotBlank()) {
                    append("\nUser Rules to strictly obey:\n$activeRules")
                }
                append("\nKeep answers natural, conversational, and suitable for spoken dialogue. Never volunteer that you are an AI unprompted, and never use robotic disclaimers.")
            }

            _liveState.value = LiveState.CONNECTING
            _errorMessage.value = null // Clear any previous error on new session start

            // Initialize Audio Player (24kHz)
            audioPlayer = AudioPlayer { isSpeaking ->
                if (isSpeaking && _liveState.value != LiveState.ERROR) {
                    _liveState.value = LiveState.SPEAKING
                } else if (!isSpeaking && _liveState.value == LiveState.SPEAKING) {
                    _liveState.value = LiveState.LISTENING
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
                        _liveState.value = LiveState.ERROR
                        _errorMessage.value = message
                        connectorJob?.cancel()
                        audioPlayer?.stopImmediatelyAndFlush()
                        audioRecorder?.stopRecording()
                    }

                    override fun onClosed(reason: String) {
                        if (_liveState.value != LiveState.ERROR) {
                            _liveState.value = LiveState.IDLE
                        }
                        connectorJob?.cancel()
                        audioPlayer?.stopImmediatelyAndFlush()
                        audioRecorder?.stopRecording()
                    }
                }
            )

            liveClient?.startSession()
        }
    }

    private fun stopLiveVoiceSession() {
        stopLiveVoiceSessionInternal()
        _liveState.value = LiveState.IDLE
    }

    private fun stopLiveVoiceSessionInternal() {
        connectorJob?.cancel()
        connectorJob = null
        handledTranscripts.clear()
        audioRecorder?.stopRecording()
        audioRecorder = null
        audioPlayer?.release()
        audioPlayer = null
        liveClient?.endSession()
        liveClient = null
        _micAmplitude.value = 0f
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

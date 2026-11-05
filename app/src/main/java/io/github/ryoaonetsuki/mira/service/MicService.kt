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
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.NotificationEntity
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.voice.AudioPlayer
import io.github.ryoaonetsuki.mira.voice.AudioRecorder
import io.github.ryoaonetsuki.mira.voice.GeminiLiveClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

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
        serviceScope.launch {
            val securePrefs = SecurePreferences(applicationContext)
            val userPrefs = UserPreferences(applicationContext)
            val apiKey = securePrefs.getGeminiApiKey()

            if (apiKey.isNullOrBlank()) {
                _liveState.value = LiveState.ERROR
                _errorMessage.value = "Gemini API key is required"
                return@launch
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

            val toneDescription = if (secretMode == "Romantic") {
                "Tone: Warm, affectionate, attentive and deeply caring companion (Romantic mode)."
            } else {
                "Tone: Friendly, highly intelligent, helpful and natural companion."
            }

            val fullSystemPrompt = buildString {
                append("You are $personaName, a real-time conversational AI voice assistant. ")
                append(toneDescription)
                append(" Spoken language preference: $language. ")
                if (customInstructions.isNotBlank()) {
                    append("\nUser instructions: $customInstructions")
                }
                if (activeRules.isNotBlank()) {
                    append("\nUser Rules to strictly obey:\n$activeRules")
                }
                append("\nKeep answers concise and conversational, suitable for spoken dialogue.")
            }

            _liveState.value = LiveState.CONNECTING

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
                listener = object : GeminiLiveClient.LiveEventsListener {
                    override fun onConnecting(model: String) {
                        _liveState.value = LiveState.CONNECTING
                    }

                    override fun onConnected() {
                        Log.i(TAG, "Connected to Gemini Live WebSocket")
                    }

                    override fun onSetupComplete() {
                        _liveState.value = LiveState.LISTENING
                        audioRecorder?.startRecording()

                        // Record notice
                        serviceScope.launch(Dispatchers.IO) {
                            db.notificationDao().insertNotification(
                                NotificationEntity(
                                    title = "Voice Session Started",
                                    message = "Connected to Gemini Live with voice $voiceName",
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
                    }

                    override fun onTranscript(text: String, isUser: Boolean) {
                        if (isUser) {
                            _userTranscript.value = text
                        } else {
                            _modelTranscript.value = text
                        }
                    }

                    override fun onError(message: String) {
                        _liveState.value = LiveState.ERROR
                        _errorMessage.value = message
                        audioPlayer?.stopImmediatelyAndFlush()
                        audioRecorder?.stopRecording()
                    }

                    override fun onClosed(reason: String) {
                        if (_liveState.value != LiveState.ERROR) {
                            _liveState.value = LiveState.IDLE
                        }
                        audioPlayer?.stopImmediatelyAndFlush()
                        audioRecorder?.stopRecording()
                    }
                }
            )

            liveClient?.startSession()
        }
    }

    private fun stopLiveVoiceSession() {
        audioRecorder?.stopRecording()
        audioRecorder = null
        audioPlayer?.release()
        audioPlayer = null
        liveClient?.endSession()
        liveClient = null
        _liveState.value = LiveState.IDLE
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

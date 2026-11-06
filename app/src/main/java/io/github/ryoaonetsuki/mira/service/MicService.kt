package io.github.ryoaonetsuki.mira.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.ryoaonetsuki.mira.BuildConfig
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
import io.github.ryoaonetsuki.mira.voice.LiveAuthMode
import io.github.ryoaonetsuki.mira.voice.ServerVoiceSession
import io.github.ryoaonetsuki.mira.voice.SessionResumptionStore
import io.github.ryoaonetsuki.mira.voice.VoiceActivityDetector
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

enum class VoiceStage(val label: String) {
    IDLE("Idle"),
    CONNECTING("Connecting…"),
    WAITING_FOR_SETUP("Waiting for setup"),
    LISTENING("Listening"),
    RECOGNISING("Recognising"),
    THINKING("Thinking"),
    SPEAKING("Speaking"),
    ERROR("Error")
}

class MicService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var audioRecorder: AudioRecorder? = null
    private var audioPlayer: AudioPlayer? = null
    private var liveClient: GeminiLiveClient? = null
    private var serverVoiceSession: ServerVoiceSession? = null
    private val handledTranscripts = Collections.synchronizedSet(mutableSetOf<String>())
    private var connectorJob: Job? = null
    private var liveSearchJob: Job? = null

    // Fallback voice mode & session
    private var fallbackSession: FallbackVoiceSession? = null
    private var androidTts: TextToSpeech? = null
    private var isFallbackMode: Boolean
        get() = _isFallbackMode.value
        set(value) {
            _isFallbackMode.value = value
        }
    private var cachedResolvedKey: String = ""
    private var cachedUseServerProxy: Boolean = false
    private var cachedVoiceName: String = "Aoede"
    private var cachedSystemPrompt: String = ""
    private var cachedLanguage: String = "Bangla (Bengali)"
    @Volatile
    private var isAwaitingFinalReply: Boolean = false

    // Watchdog for server voice path
    private var serverWatchdogJob: Job? = null
    @Volatile private var serverSpeechDetectedAt: Long = 0L
    @Volatile private var serverLastAudioFlowingAt: Long = 0L
    @Volatile private var serverTranscriptReceivedAt: Long = 0L
    @Volatile private var serverTurnActive: Boolean = false

    private lateinit var resumptionStore: SessionResumptionStore
    private var vad: VoiceActivityDetector? = null
    @Volatile
    private var isEndpointSuppressed: Boolean = false

    // Dual-slot session rotation
    private var standbyLiveClient: GeminiLiveClient? = null
    private var rotationJob: Job? = null
    private var rotationRetryCount: Int = 0

    // Cached session parameters for standby rotation
    private var cachedLiveModel: String = "models/gemini-3.8-live"
    private var cachedEphemeralToken: String? = null
    private var cachedRelayUrl: String? = null
    private var cachedAnonKey: String? = null
    private var cachedCfRelayUrl: String? = null
    private var cachedAuthModes: List<LiveAuthMode> = emptyList()

    private fun handlePlaybackSpeakingChanged(isSpeaking: Boolean) {
        if (isSpeaking && _liveState.value != LiveState.ERROR && _liveState.value != LiveState.IDLE) {
            _liveState.value = LiveState.SPEAKING
            _voiceStage.value = VoiceStage.SPEAKING
        } else if (!isSpeaking && _liveState.value == LiveState.SPEAKING) {
            if (isFallbackMode && isAwaitingFinalReply) {
                _voiceStage.value = VoiceStage.THINKING
            } else {
                _liveState.value = LiveState.LISTENING
                _voiceStage.value = VoiceStage.LISTENING
                if (isFallbackMode) {
                    fallbackSession?.resumeListening()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        resumptionStore = SessionResumptionStore(applicationContext)
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
        _audioChunksSent.value = 0L
        _audioChunksReceived.value = 0L
        _isFallbackMode.value = false

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "Starting voice session — mic permission status: $hasMicPermission")
        if (!hasMicPermission) {
            Log.e(TAG, "Microphone permission not granted at session start — aborting before opening session")
            _liveState.value = LiveState.ERROR
            _voiceStage.value = VoiceStage.ERROR
            _errorMessage.value = "Microphone permission not granted — allow it in Settings"
            _lastVoiceDiagnostic.value = "Microphone permission not granted — allow it in Settings"
            return
        }

        _liveState.value = LiveState.CONNECTING
        _voiceStage.value = VoiceStage.CONNECTING

        serviceScope.launch {
            val resolver = io.github.ryoaonetsuki.mira.backend.ApiKeyResolver(applicationContext)
            val keySource = resolver.resolveKey()
            val geminiKey = when (keySource) {
                is io.github.ryoaonetsuki.mira.backend.KeySource.UserKey -> keySource.key
                else -> ""
            }
            val securePrefs = SecurePreferences(applicationContext)
            val groqKey = securePrefs.getGroqApiKey() ?: ""

            if (geminiKey.isNotBlank() && groqKey.isNotBlank()) {
                Log.i(TAG, "Both Groq and Gemini keys present — starting ServerVoiceSession (Round 9 default)")
                startServerVoiceSessionInternal(groqKey, geminiKey)
            } else if (geminiKey.isNotBlank()) {
                Log.i(TAG, "Groq key missing — starting Live voice session as secondary attempt")
                Toast.makeText(
                    applicationContext,
                    "Groq key missing — add it in Settings for Server Voice",
                    Toast.LENGTH_SHORT
                ).show()
                startExistingLiveVoiceSession()
            } else if (keySource is io.github.ryoaonetsuki.mira.backend.KeySource.ServerKey) {
                Log.i(TAG, "Server key configured — starting Live voice session")
                startExistingLiveVoiceSession()
            } else {
                Log.w(TAG, "No API key available for voice session")
                _liveState.value = LiveState.ERROR
                _voiceStage.value = VoiceStage.ERROR
                _errorMessage.value = "Gemini key missing — add it in Settings"
                _lastVoiceDiagnostic.value = "Gemini key missing — add it in Settings"
            }
        }
    }

    private fun startServerVoiceSessionInternal(groqKey: String, geminiKey: String) {
        serviceScope.launch {
            val userPrefs = UserPreferences(applicationContext)
            val voiceName = userPrefs.voiceNameFlow.first()
            val personaName = userPrefs.personaFlow.first()
            val customInstructions = userPrefs.customInstructionsFlow.first()
            val language = userPrefs.languageFlow.first()
            val secretMode = userPrefs.secretModeFlow.first()

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

            cachedResolvedKey = geminiKey
            cachedVoiceName = voiceName
            cachedLanguage = language

            val dateLine = currentDateTimeLine()
            val fullSystemPrompt = buildString {
                append("Current date and time (from the device clock — always trust this, never guess or use training knowledge): $dateLine. If asked for the date, time or day, answer from this line.\n")
                append(personaType.basePrompt)
                append(" ")
                append(toneDescription)
                val searchAckPhrase = MiraChatSearchHeuristic.getAcknowledgementPhrase(language)
                append("\nLanguage rule: Reply ONLY in $language. Never add a translation in any other language. Never mix languages.")
                append("\nIdentity rule: $identityRule")
                append("\nWeb search rule: When the user asks you to search or look something up, or asks about something you do not know or have no latest news about: you must FIRST SAY ALOUD the acknowledgement in $language: \"$searchAckPhrase\", then wait for the fresh search results to be provided, then speak the final answer. Never stay silent while searching, and never guess when you do not know. For normal voice talk answer directly and fast.")
                if (customInstructions.isNotBlank()) {
                    append("\nUser instructions: $customInstructions")
                }
                if (activeRules.isNotBlank()) {
                    append("\nUser Rules to strictly obey:\n$activeRules")
                }
                append("\nKeep answers natural, conversational, and suitable for spoken dialogue. Never volunteer that you are an AI unprompted, and never use robotic disclaimers.")
            }
            cachedSystemPrompt = fullSystemPrompt

            if (audioPlayer == null) {
                audioPlayer = AudioPlayer { isSpeaking ->
                    handlePlaybackSpeakingChanged(isSpeaking)
                }
            }

            Log.i(TAG, "Starting ServerVoiceSession (streaming Worker path)")
            serverVoiceSession?.stop()
            serverWatchdogJob?.cancel()
            serverSpeechDetectedAt = 0L
            serverLastAudioFlowingAt = 0L
            serverTranscriptReceivedAt = 0L
            serverTurnActive = false

            val session = ServerVoiceSession(
                context = applicationContext,
                groqKey = groqKey,
                geminiKey = geminiKey,
                voiceName = voiceName,
                language = language,
                systemPrompt = fullSystemPrompt,
                dateLine = dateLine,
                audioPlayer = audioPlayer!!,
                onStateChanged = { state ->
                    _liveState.value = state
                    if (state == LiveState.LISTENING) {
                        serverSpeechDetectedAt = 0L
                        serverTurnActive = false
                    }
                },
                onStageChanged = { stage ->
                    _voiceStage.value = stage
                    if (stage == VoiceStage.RECOGNISING) {
                        if (serverSpeechDetectedAt == 0L) {
                            serverSpeechDetectedAt = System.currentTimeMillis()
                        }
                        serverTurnActive = true
                    } else if (stage == VoiceStage.SPEAKING || stage == VoiceStage.LISTENING) {
                        serverTurnActive = false
                        serverSpeechDetectedAt = 0L
                    }
                },
                onUserTranscript = { text ->
                    _userTranscript.value = text
                    serverTranscriptReceivedAt = System.currentTimeMillis()
                },
                onModelTranscript = { text ->
                    _modelTranscript.value = text
                    serverTranscriptReceivedAt = System.currentTimeMillis()
                },
                onAppendModelTranscript = { token ->
                    _modelTranscript.value = _modelTranscript.value + token
                    serverTranscriptReceivedAt = System.currentTimeMillis()
                },
                onError = { err ->
                    val plainError = if (err.startsWith("Server voice:", ignoreCase = true)) err else "Server voice: $err"
                    _errorMessage.value = plainError
                    _lastVoiceDiagnostic.value = plainError
                },
                onAudioChunkSent = { _audioChunksSent.value = _audioChunksSent.value + 1L },
                onAudioChunkReceived = { _audioChunksReceived.value = _audioChunksReceived.value + 1L },
                onMicAmplitude = { amp ->
                    _micAmplitude.value = amp
                    if (amp > 0.12f) {
                        serverLastAudioFlowingAt = System.currentTimeMillis()
                        if (serverSpeechDetectedAt == 0L && _liveState.value == LiveState.LISTENING && _voiceStage.value != VoiceStage.SPEAKING) {
                            serverSpeechDetectedAt = System.currentTimeMillis()
                            serverTurnActive = true
                        }
                    }
                },
                onRequestLiveFallback = { reason ->
                    Log.i(TAG, "Server voice session falling back to Live: $reason")
                    serverVoiceSession?.stop()
                    serverVoiceSession = null
                    serverWatchdogJob?.cancel()
                    if (reason.isNotBlank()) {
                        Toast.makeText(
                            applicationContext,
                            "Server voice: $reason",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    Toast.makeText(
                        applicationContext,
                        "Server voice unavailable — switching to Live voice",
                        Toast.LENGTH_SHORT
                    ).show()
                    startExistingLiveVoiceSession()
                }
            )
            serverVoiceSession = session
            session.start()
            startServerWatchdog()
        }
    }

    private fun startServerWatchdog() {
        serverWatchdogJob?.cancel()
        serverWatchdogJob = serviceScope.launch {
            val sessionStartTime = System.currentTimeMillis()
            var checkedZeroChunks = false

            while (isActive && serverVoiceSession != null) {
                delay(500)
                val now = System.currentTimeMillis()

                // Check 1: Session is LISTENING but 0 audio chunks sent after 4 seconds
                if (!checkedZeroChunks && _liveState.value == LiveState.LISTENING && (now - sessionStartTime) > 4000L) {
                    checkedZeroChunks = true
                    if (_audioChunksSent.value == 0L) {
                        Log.w(TAG, "Server voice watchdog: zero audio chunks sent after 4s in LISTENING")
                        val err = "Server voice: No microphone audio sending"
                        _errorMessage.value = err
                        _lastVoiceDiagnostic.value = err
                        _liveState.value = LiveState.ERROR
                        _voiceStage.value = VoiceStage.ERROR
                        Toast.makeText(applicationContext, err, Toast.LENGTH_SHORT).show()
                        serverVoiceSession?.stop()
                        break
                    }
                }

                // Check 2: Speech detected, but no partial/transcript arrives within timeout (~6s after speech ends)
                val isSpeaking = _liveState.value == LiveState.SPEAKING || (audioPlayer?.isSpeaking() == true)
                val isAudioFlowing = (now - serverLastAudioFlowingAt) < 1500L
                if (!isSpeaking && !isAudioFlowing && serverTurnActive && serverSpeechDetectedAt > 0L) {
                    val speechAge = now - serverSpeechDetectedAt
                    val silenceAfterSpeech = now - serverLastAudioFlowingAt
                    if (speechAge > 6000L && silenceAfterSpeech > 2000L && serverTranscriptReceivedAt < serverSpeechDetectedAt) {
                        Log.w(TAG, "Server voice watchdog: Speech detected $speechAge ms ago with silence, but no transcript arrived")
                        val err = "Server did not answer — could not hear you clearly"
                        _errorMessage.value = err
                        _lastVoiceDiagnostic.value = err
                        _liveState.value = LiveState.ERROR
                        _voiceStage.value = VoiceStage.ERROR
                        Toast.makeText(applicationContext, err, Toast.LENGTH_SHORT).show()
                        serverVoiceSession?.stop()
                        break
                    }
                }
            }
        }
    }

    private fun startExistingLiveVoiceSession() {
        if (_liveState.value != LiveState.IDLE && _liveState.value != LiveState.ERROR) {
            serverVoiceSession?.stop()
            serverVoiceSession = null
        }
        _isFallbackMode.value = false

        // Speaker pre-primed at session start
        if (audioPlayer == null) {
            audioPlayer = AudioPlayer { isSpeaking ->
                handlePlaybackSpeakingChanged(isSpeaking)
            }
        }

        // On-device Voice Activity Detector
        val vadInstance = VoiceActivityDetector(object : VoiceActivityDetector.VadListener {
            override fun onSpeechStart() {
                isEndpointSuppressed = false
                if (_liveState.value == LiveState.LISTENING) {
                    _voiceStage.value = VoiceStage.RECOGNISING
                }
            }

            override fun onSpeechEndpoint() {
                // Trailing silence suppression: stop sending dead silence after 300ms endpoint
                isEndpointSuppressed = true
            }
        })
        vad = vadInstance
        isEndpointSuppressed = false

        // Mic recording starts immediately at session start into 8s pre-roll ring buffer
        val recorder = AudioRecorder(
            onAudioChunk = { chunk ->
                vadInstance.processFrame(chunk)
                if (!isEndpointSuppressed) {
                    liveClient?.sendAudioChunk(chunk)
                    _audioChunksSent.value = liveClient?.audioChunksSent ?: 0L
                }
            },
            onAmplitude = { amp ->
                _micAmplitude.value = amp
            }
        )
        audioRecorder = recorder
        val recordStarted = recorder.startRecording()
        if (!recordStarted) {
            Log.e(TAG, "AudioRecorder failed to start recording at session start")
            _liveState.value = LiveState.ERROR
            _voiceStage.value = VoiceStage.ERROR
            _errorMessage.value = "Microphone could not start — check the mic permission"
            _lastVoiceDiagnostic.value = "Microphone could not start — check the mic permission"
            return
        }
        Log.i(TAG, "AudioRecorder started at Live session start (8s pre-roll ring active)")

        _liveState.value = LiveState.CONNECTING
        _voiceStage.value = VoiceStage.CONNECTING

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

            val relayBase = try { BuildConfig.SUPABASE_URL } catch (_: Throwable) { "https://zvnfwlqxdbommtozugzt.supabase.co" }
            val relayUrl = "$relayBase/functions/v1/gemini-live-relay"
            val cfRelayUrl = try { BuildConfig.CLOUDFLARE_RELAY_URL } catch (_: Throwable) {
                "https://mira-gemini-live-relay.salman-dev.workers.dev"
            }
            val anonKey = resolver.anonKey

            val authModes: List<LiveAuthMode> = when (keySource) {
                is io.github.ryoaonetsuki.mira.backend.KeySource.UserKey -> {
                    val userKey = keySource.key
                    Log.i(TAG, "Attempting to mint ephemeral token for UserKey...")
                    val mintResult = resolver.mintLiveTokenForUserKey(userKey)
                    if (mintResult.isSuccess) {
                        val token = mintResult.getOrThrow()
                        Log.i(TAG, "UserKey ephemeral token mint SUCCEEDED")
                        listOf(
                            LiveAuthMode.Ephemeral(token),
                            LiveAuthMode.DirectKey(userKey),
                            LiveAuthMode.Relay(userKey, relayUrl),
                            LiveAuthMode.CloudflareRelay(userKey, cfRelayUrl)
                        )
                    } else {
                        val err = mintResult.exceptionOrNull()?.message ?: "unknown"
                        Log.w(TAG, "UserKey ephemeral token mint FAILED: $err — continuing with direct, Supabase and Cloudflare relay modes")
                        listOf(
                            LiveAuthMode.DirectKey(userKey),
                            LiveAuthMode.Relay(userKey, relayUrl),
                            LiveAuthMode.CloudflareRelay(userKey, cfRelayUrl)
                        )
                    }
                }
                is io.github.ryoaonetsuki.mira.backend.KeySource.ServerKey -> {
                    Log.i(TAG, "Attempting to mint ephemeral token via server...")
                    val tokenResult = resolver.mintLiveToken()
                    if (tokenResult.isSuccess) {
                        Log.i(TAG, "Server live token mint SUCCEEDED (token acquired)")
                        listOf(LiveAuthMode.Ephemeral(tokenResult.getOrThrow()))
                    } else {
                        val err = tokenResult.exceptionOrNull()?.message ?: "Failed to mint token"
                        Log.e(TAG, "Server live token mint FAILED: $err")
                        _liveState.value = LiveState.ERROR
                        _voiceStage.value = VoiceStage.ERROR
                        _errorMessage.value = "Server voice token failed — configure API key in Settings"
                        return@launch
                    }
                }
                is io.github.ryoaonetsuki.mira.backend.KeySource.NoKey -> {
                    Log.w(TAG, "No API key available for voice session")
                    _liveState.value = LiveState.ERROR
                    _voiceStage.value = VoiceStage.ERROR
                    _errorMessage.value = "API key is required"
                    return@launch
                }
            }

            val startingMode = authModes.firstOrNull()?.label ?: "none"
            Log.i(TAG, "Live session starting with auth mode: $startingMode")

            val apiKey = if (keySource is io.github.ryoaonetsuki.mira.backend.KeySource.UserKey) keySource.key else ""
            val ephemeralToken = (authModes.firstOrNull() as? LiveAuthMode.Ephemeral)?.token

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
            cachedLiveModel = liveModel
            cachedEphemeralToken = ephemeralToken
            cachedRelayUrl = relayUrl
            cachedAnonKey = anonKey
            cachedCfRelayUrl = cfRelayUrl
            cachedAuthModes = authModes
            isFallbackMode = false

            val dateLine = currentDateTimeLine()
            val fullSystemPrompt = buildString {
                append("Current date and time (from the device clock — always trust this, never guess or use training knowledge): $dateLine. If asked for the date, time or day, answer from this line.\n")
                append(personaType.basePrompt)
                append(" ")
                append(toneDescription)
                val searchAckPhrase = MiraChatSearchHeuristic.getAcknowledgementPhrase(language)
                append("\nLanguage rule: Reply ONLY in $language. Never add a translation in any other language. Never mix languages.")
                append("\nIdentity rule: $identityRule")
                append("\nWeb search rule: When the user asks you to search or look something up, or asks about something you do not know or have no latest news about: you must FIRST SAY ALOUD the acknowledgement in $language: \"$searchAckPhrase\", then wait for the fresh search results to be provided, then speak the final answer. Never stay silent while searching, and never guess when you do not know. For normal voice talk answer directly and fast.")
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
            _voiceStage.value = VoiceStage.CONNECTING
            _errorMessage.value = null // Clear any previous error on new session start

            // Retrieve persisted session resumption handle if any
            val storedHandle = resumptionStore.getHandle()

            // Initialize Gemini Live Client
            liveClient = GeminiLiveClient(
                apiKey = apiKey,
                voiceName = voiceName,
                systemPrompt = fullSystemPrompt,
                initialModel = liveModel,
                language = language,
                ephemeralToken = ephemeralToken,
                relayUrl = relayUrl,
                anonKey = anonKey,
                cfRelayUrl = cfRelayUrl,
                storedResumptionHandle = storedHandle,
                authModes = authModes,
                listener = createLiveEventsListener(isStandby = false)
            )

            liveClient?.startSession()
        }
    }

    private fun createLiveEventsListener(isStandby: Boolean): GeminiLiveClient.LiveEventsListener {
        var hasSwapped = !isStandby
        return object : GeminiLiveClient.LiveEventsListener {
            override fun onConnecting(model: String) {
                if (hasSwapped) {
                    _liveState.value = LiveState.CONNECTING
                    _voiceStage.value = VoiceStage.CONNECTING
                } else {
                    Log.i(TAG, "Standby session connecting with model: $model")
                }
            }

            override fun onConnected() {
                if (hasSwapped) {
                    Log.i(TAG, "Connected to Gemini Live WebSocket")
                    _voiceStage.value = VoiceStage.WAITING_FOR_SETUP
                } else {
                    Log.i(TAG, "Standby session connected to WebSocket")
                }
            }

            override fun onSetupComplete() {
                if (!hasSwapped) {
                    Log.i(TAG, "rotation-swapped (standby setup complete, swapping active sessions)")
                    hasSwapped = true
                    val oldClient = liveClient
                    liveClient = standbyLiveClient
                    standbyLiveClient = null
                    rotationRetryCount = 0
                    oldClient?.endSession()
                    scheduleSessionRotation()
                    _liveState.value = LiveState.LISTENING
                    _voiceStage.value = VoiceStage.LISTENING
                    _errorMessage.value = null
                    isEndpointSuppressed = false
                    vad?.reset()
                    return
                }

                // Primary session setup complete: flush pre-roll ring into Live session
                val preRollChunks = audioRecorder?.flushPreRoll() ?: emptyList()
                for (chunk in preRollChunks) {
                    liveClient?.sendAudioChunk(chunk)
                }
                _audioChunksSent.value = liveClient?.audioChunksSent ?: 0L

                _liveState.value = LiveState.LISTENING
                _voiceStage.value = VoiceStage.LISTENING
                _errorMessage.value = null // Clear error on successful setup
                isEndpointSuppressed = false
                vad?.reset()

                // Schedule 8-minute session rotation
                scheduleSessionRotation()

                // Record notice
                serviceScope.launch(Dispatchers.IO) {
                    val db = MiraDatabase.getInstance(applicationContext)
                    db.notificationDao().insertNotification(
                        NotificationEntity(
                            title = "Voice Session Started",
                            message = "Connected to voice session with voice $cachedVoiceName",
                            type = "voice"
                        )
                    )
                }
            }

            override fun onAudioData(pcmBytes: ByteArray) {
                if (!hasSwapped) return
                _audioChunksReceived.value = liveClient?.audioChunksReceived ?: 0L
                audioPlayer?.playAudioChunk(pcmBytes)
            }

            override fun onInterrupted() {
                if (!hasSwapped) return
                liveSearchJob?.cancel()
                audioPlayer?.stopImmediatelyAndFlush()
                _liveState.value = LiveState.LISTENING
                _voiceStage.value = VoiceStage.LISTENING
                handledTranscripts.clear()
                isEndpointSuppressed = false
                vad?.reset()
            }

            override fun onTurnComplete() {
                if (!hasSwapped) return
                if (_liveState.value == LiveState.SPEAKING) {
                    _liveState.value = LiveState.LISTENING
                    _voiceStage.value = VoiceStage.LISTENING
                }
                liveSearchJob?.cancel()
                handledTranscripts.clear()
                isEndpointSuppressed = false
                vad?.reset()
            }

            override fun onTranscript(text: String, isUser: Boolean) {
                if (!hasSwapped) return
                if (isUser) {
                    _userTranscript.value = text
                    if (_liveState.value != LiveState.SPEAKING && _liveState.value != LiveState.ERROR) {
                        _voiceStage.value = VoiceStage.RECOGNISING
                    }

                    val cleanText = text.trim()
                    val words = cleanText.split("\\s+".toRegex()).filter { it.isNotBlank() }

                    // Debounce connector command execution
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
                                            liveClient?.sendTextMessage("User command result: $commandResult. Please inform the user clearly and concisely in voice.")
                                            _modelTranscript.value = commandResult
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

                    // Debounced live web search injection
                    liveSearchJob?.cancel()
                    if (words.size >= 2 && MiraChatSearchHeuristic.isSearchRequest(cleanText)) {
                        val ackKey = "ack:search_turn"
                        if (handledTranscripts.add(ackKey) && liveClient?.isSessionReady() == true) {
                            val ackPhrase = MiraChatSearchHeuristic.getAcknowledgementPhrase(cachedLanguage)
                            val ackInstruction = "[Search request detected. Say aloud immediately in $cachedLanguage: \"$ackPhrase\". Do not answer yet; fresh search results are being retrieved.]"
                            liveClient?.sendTextMessage(ackInstruction)
                            Log.i(TAG, "Sent immediate search acknowledgement instruction to Gemini Live: $ackPhrase")
                        }

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
                                            val payload = "[Live web search — searched just now, $dateLine:\n$searchLines\nAnswer ONLY from these fresh results, briefly, in $cachedLanguage. Do not repeat the acknowledgement.]"
                                            liveClient?.sendTextMessage(payload)
                                            Log.i(TAG, "Injected live search results into active Gemini Live session for query: $cleanText")
                                        } else if (liveClient?.isSessionReady() == true) {
                                            val payload = "[No fresh web results found for \"$cleanText\". Briefly inform the user in $cachedLanguage that you could not find current information on this.]"
                                            liveClient?.sendTextMessage(payload)
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Error performing live voice web search: ${e.message}")
                                    }
                                }
                            }
                        }
                    }

                    // Emergency macro voice stop
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

            override fun onStalled() {
                if (!hasSwapped) return
                Log.w(TAG, "Gemini Live session stalled (no server reply for >30s after audio)")
                val stallMsg = "Live voice: Server did not answer"
                _errorMessage.value = stallMsg
                _lastVoiceDiagnostic.value = stallMsg
                _liveState.value = LiveState.ERROR
                _voiceStage.value = VoiceStage.ERROR
                Toast.makeText(applicationContext, stallMsg, Toast.LENGTH_SHORT).show()
            }

            override fun onError(message: String) {
                if (!hasSwapped) {
                    Log.w(TAG, "rotation-standby failed ($message); keeping current session")
                    standbyLiveClient?.endSession()
                    standbyLiveClient = null
                    if (rotationRetryCount == 0) {
                        rotationRetryCount++
                        serviceScope.launch {
                            delay(ROTATION_RETRY_DELAY_MS)
                            if (_liveState.value == LiveState.LISTENING || _liveState.value == LiveState.SPEAKING) {
                                performSessionRotation()
                            }
                        }
                    }
                    return
                }

                Log.e(TAG, "Voice session error termination: $message")
                val liveErrMsg = "Live voice error: $message"
                _errorMessage.value = liveErrMsg
                _lastVoiceDiagnostic.value = liveErrMsg
                _liveState.value = LiveState.ERROR
                _voiceStage.value = VoiceStage.ERROR
                Toast.makeText(applicationContext, liveErrMsg, Toast.LENGTH_SHORT).show()

                rotationJob?.cancel()
                rotationJob = null
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
                if (!hasSwapped) {
                    standbyLiveClient = null
                    return
                }

                Log.i(TAG, "Voice session closed termination: $reason")
                rotationJob?.cancel()
                rotationJob = null
                if (_liveState.value != LiveState.ERROR && !isFallbackMode) {
                    _liveState.value = LiveState.IDLE
                    _voiceStage.value = VoiceStage.IDLE
                }
                connectorJob?.cancel()
                audioPlayer?.stopImmediatelyAndFlush()
                audioRecorder?.stopRecording()
                audioRecorder = null
            }

            override fun onSessionResumptionUpdate(newHandle: String) {
                resumptionStore.saveHandle(newHandle)
            }
        }
    }

    private fun scheduleSessionRotation() {
        rotationJob?.cancel()
        rotationJob = serviceScope.launch {
            delay(ROTATION_INTERVAL_MS)
            if (_liveState.value == LiveState.LISTENING || _liveState.value == LiveState.SPEAKING) {
                performSessionRotation()
            }
        }
    }

    private fun performSessionRotation() {
        Log.i(TAG, "rotation-standby (initiating zero-gap 8-minute session rotation)")
        val storedHandle = resumptionStore.getHandle()
        val standby = GeminiLiveClient(
            apiKey = cachedResolvedKey,
            voiceName = cachedVoiceName,
            systemPrompt = cachedSystemPrompt,
            initialModel = cachedLiveModel,
            language = cachedLanguage,
            ephemeralToken = cachedEphemeralToken,
            relayUrl = cachedRelayUrl,
            anonKey = cachedAnonKey,
            cfRelayUrl = cachedCfRelayUrl,
            storedResumptionHandle = storedHandle,
            authModes = cachedAuthModes,
            listener = createLiveEventsListener(isStandby = true)
        )
        standbyLiveClient = standby
        standby.startSession()
    }

    private fun stopLiveVoiceSession() {
        Log.i(TAG, "Stopping voice session (user action or stop requested)")
        stopLiveVoiceSessionInternal()
        _liveState.value = LiveState.IDLE
        _voiceStage.value = VoiceStage.IDLE
    }

    private fun stopLiveVoiceSessionInternal() {
        serverVoiceSession?.stop()
        serverVoiceSession = null
        serverWatchdogJob?.cancel()
        serverWatchdogJob = null
        serverSpeechDetectedAt = 0L
        serverLastAudioFlowingAt = 0L
        serverTranscriptReceivedAt = 0L
        serverTurnActive = false
        rotationJob?.cancel()
        rotationJob = null
        rotationRetryCount = 0
        standbyLiveClient?.endSession()
        standbyLiveClient = null
        vad?.reset()
        vad = null
        isEndpointSuppressed = false
        connectorJob?.cancel()
        connectorJob = null
        liveSearchJob?.cancel()
        liveSearchJob = null
        isAwaitingFinalReply = false
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
        _audioChunksSent.value = 0L
        _audioChunksReceived.value = 0L
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

        if (!SpeechRecognizer.isRecognitionAvailable(applicationContext)) {
            Log.e(TAG, "SpeechRecognizer is NOT available on this device")
            val noSpeechMsg = "SpeechRecognizer unavailable — voice recognition isn't available on this device"
            _liveState.value = LiveState.ERROR
            _voiceStage.value = VoiceStage.ERROR
            _errorMessage.value = noSpeechMsg
            _lastVoiceDiagnostic.value = noSpeechMsg
            Toast.makeText(applicationContext, noSpeechMsg, Toast.LENGTH_SHORT).show()
            return
        }

        _liveState.value = LiveState.LISTENING
        _voiceStage.value = VoiceStage.LISTENING

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
                handlePlaybackSpeakingChanged(isSpeaking)
            }
        }

        fallbackSession?.stop()
        fallbackSession = FallbackVoiceSession(
            context = applicationContext,
            languageCode = bcp47,
            onFinalText = { text ->
                _voiceStage.value = VoiceStage.THINKING
                _audioChunksSent.value = _audioChunksSent.value + 1L
                handleFallbackFinalText(text)
            },
            onPartialText = { partial ->
                _voiceStage.value = VoiceStage.RECOGNISING
                _userTranscript.value = partial
            },
            onListeningChanged = { listening ->
                if (listening && _liveState.value != LiveState.SPEAKING && _liveState.value != LiveState.ERROR && _liveState.value != LiveState.IDLE) {
                    _liveState.value = LiveState.LISTENING
                    _voiceStage.value = VoiceStage.LISTENING
                }
            },
            onError = { errMsg ->
                isAwaitingFinalReply = false
                Log.w(TAG, "Fallback voice session error: $errMsg")
                val fallbackErr = if (errMsg.startsWith("SpeechRecognizer", ignoreCase = true)) errMsg else "SpeechRecognizer: $errMsg"
                _errorMessage.value = fallbackErr
                _lastVoiceDiagnostic.value = fallbackErr
                _liveState.value = LiveState.ERROR
                _voiceStage.value = VoiceStage.ERROR
                _micAmplitude.value = 0f
                Toast.makeText(applicationContext, fallbackErr, Toast.LENGTH_SHORT).show()
            },
            onAmplitude = { amp ->
                _micAmplitude.value = amp
            },
            onSpeechStart = {
                if (_liveState.value != LiveState.SPEAKING && _liveState.value != LiveState.ERROR && _liveState.value != LiveState.IDLE) {
                    _voiceStage.value = VoiceStage.RECOGNISING
                }
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
                                _voiceStage.value = VoiceStage.SPEAKING
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            if (_liveState.value != LiveState.IDLE) {
                                if (isAwaitingFinalReply || utteranceId == "mira_ack_utterance") {
                                    _voiceStage.value = VoiceStage.THINKING
                                } else {
                                    _liveState.value = LiveState.LISTENING
                                    _voiceStage.value = VoiceStage.LISTENING
                                    fallbackSession?.resumeListening()
                                }
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            if (_liveState.value != LiveState.IDLE) {
                                if (isAwaitingFinalReply || utteranceId == "mira_ack_utterance") {
                                    _voiceStage.value = VoiceStage.THINKING
                                } else {
                                    _liveState.value = LiveState.LISTENING
                                    _voiceStage.value = VoiceStage.LISTENING
                                    fallbackSession?.resumeListening()
                                }
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
        val isSearch = MiraChatSearchHeuristic.isSearchRequest(text)
        if (isSearch) {
            isAwaitingFinalReply = true
            val ackText = MiraChatSearchHeuristic.getAcknowledgementPhrase(cachedLanguage)
            _modelTranscript.value = ackText
            speakFallbackReply(ackText, isAck = true)
        }

        val mockMsg = ChatMessageEntity(
            conversationId = "voice_fallback",
            role = "user",
            content = text
        )
        val restClient = GeminiRestClient()
        // Voice turns stay FAST: fastest model, no thinking, narrow (time-sensitive
        // only) search gate — the chat defaults (3.8-flash + thinking + wide gate)
        // would make spoken replies slow.
        val voiceSystemPrompt = buildString {
            append(cachedSystemPrompt)
            append("\nKeep voice answers short and fast — a few sentences at most unless the user asks for detail.")
            if (isSearch) {
                append("\nYou have already spoken the search acknowledgement aloud to the user. Do NOT repeat the acknowledgement phrase — provide ONLY the final answer directly and concisely.")
            }
        }
        val respResult = restClient.generateChatResponse(
            apiKey = cachedResolvedKey,
            history = listOf(mockMsg),
            systemPrompt = voiceSystemPrompt,
            model = "gemini-3.5-flash-lite",
            useServerProxy = cachedUseServerProxy,
            enableGrounding = isSearch,
            enableThinking = false,
            isChatCall = false
        )

        if (respResult.isFailure) {
            isAwaitingFinalReply = false
            val err = respResult.exceptionOrNull()?.message ?: "Unknown error"
            Log.e(TAG, "REST chat response failed in fallback: $err")
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, "Chat error: $err", Toast.LENGTH_SHORT).show()
                _liveState.value = LiveState.LISTENING
                _voiceStage.value = VoiceStage.LISTENING
                fallbackSession?.resumeListening()
            }
            return
        }

        val replyText = respResult.getOrThrow().text
        _modelTranscript.value = replyText
        _audioChunksReceived.value = _audioChunksReceived.value + 1L
        isAwaitingFinalReply = false
        speakFallbackReply(replyText, isAck = false)
    }

    private suspend fun speakFallbackReply(replyText: String, isAck: Boolean = false) {
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
                speakWithAndroidTts(replyText, isAck = isAck)
            }
        }
    }

    private fun playPcmChunks(pcmBytes: ByteArray) {
        val player = audioPlayer ?: AudioPlayer { isSpeaking ->
            handlePlaybackSpeakingChanged(isSpeaking)
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

    private fun speakWithAndroidTts(replyText: String, isAck: Boolean = false) {
        val tts = androidTts
        if (tts == null) {
            if (!isAck) {
                _liveState.value = LiveState.LISTENING
                _voiceStage.value = VoiceStage.LISTENING
                fallbackSession?.resumeListening()
            }
            return
        }
        val cleanText = GeminiTtsClient.cleanMarkdownForSpeech(replyText)
        _liveState.value = LiveState.SPEAKING
        _voiceStage.value = VoiceStage.SPEAKING
        val params = android.os.Bundle()
        val utteranceId = if (isAck) "mira_ack_utterance" else "mira_fallback_utterance"
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        val queueMode = if (isAck) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(cleanText, queueMode, params, utteranceId)
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

        const val ROTATION_INTERVAL_MS = 480_000L // 8 minutes
        const val ROTATION_RETRY_DELAY_MS = 30_000L // 30 seconds

        private val _liveState = MutableStateFlow(LiveState.IDLE)
        val liveState: StateFlow<LiveState> = _liveState.asStateFlow()

        private val _voiceStage = MutableStateFlow(VoiceStage.IDLE)
        val voiceStage: StateFlow<VoiceStage> = _voiceStage.asStateFlow()

        private val _isFallbackMode = MutableStateFlow(false)
        val isFallbackMode: StateFlow<Boolean> = _isFallbackMode.asStateFlow()

        private val _audioChunksSent = MutableStateFlow(0L)
        val audioChunksSent: StateFlow<Long> = _audioChunksSent.asStateFlow()

        private val _audioChunksReceived = MutableStateFlow(0L)
        val audioChunksReceived: StateFlow<Long> = _audioChunksReceived.asStateFlow()

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

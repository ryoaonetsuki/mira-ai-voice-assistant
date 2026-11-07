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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.ryoaonetsuki.mira.MainActivity
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.automation.MacroRunner
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.NotificationEntity
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.network.ConnectorIntentHandler
import io.github.ryoaonetsuki.mira.network.FreeWebSearch
import io.github.ryoaonetsuki.mira.network.GeminiTtsClient
import io.github.ryoaonetsuki.mira.network.MiraChatSearchHeuristic
import io.github.ryoaonetsuki.mira.network.currentDateTimeLine
import io.github.ryoaonetsuki.mira.voice.*
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

/**
 * Foreground service managing Mira voice sessions.
 *
 * ROUND 12 core routing rule (spec §3.3):
 * The voice button ALWAYS starts Gemini Live first.
 * Groq key presence or absence NEVER changes this.
 * No silent fallback to any other engine — on Live failure, shows real error + retry.
 * Old server voice pipeline is gated behind explicit Settings toggle "Alternative voice (Server Voice)" (default OFF).
 */
class MicService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var audioRecorder: AudioRecorder? = null
    private var audioPlayer: AudioPlayer? = null
    private var liveClient: GeminiLiveClient? = null
    private var serverVoiceSession: ServerVoiceSession? = null
    private val handledTranscripts = Collections.synchronizedSet(mutableSetOf<String>())
    private var connectorJob: Job? = null
    private var liveSearchJob: Job? = null
    private var connectingWatchdogJob: Job? = null

    /**
     * Guarantees terminal ERROR state and complete resource cleanup on every failure path (spec §4).
     * Stops recorder, flushes/releases player, ends Live client, stops server voice,
     * resets mic amplitude to 0, cancels watchdogs, and allows Home to return to idle.
     */
    private fun handleTerminalError(message: String) {
        connectingWatchdogJob?.cancel()
        connectingWatchdogJob = null
        connectorJob?.cancel()
        connectorJob = null
        liveSearchJob?.cancel()
        liveSearchJob = null

        _errorMessage.value = message
        _lastVoiceDiagnostic.value = message
        _liveState.value = LiveState.ERROR
        _voiceStage.value = VoiceStage.ERROR
        _micAmplitude.value = 0f
        LiveVoiceDiagnostics.updateLastError(message)
        LiveVoiceDiagnostics.updateStage("ERROR")
        dismissNotification()

        try {
            audioRecorder?.stopRecording()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recorder on terminal error: ${e.message}")
        }
        audioRecorder = null

        try {
            audioPlayer?.stopImmediatelyAndFlush()
            audioPlayer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping player on terminal error: ${e.message}")
        }
        audioPlayer = null

        try {
            liveClient?.endSession()
        } catch (e: Exception) {
            Log.w(TAG, "Error ending live client on terminal error: ${e.message}")
        }
        liveClient = null

        try {
            serverVoiceSession?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping server voice on terminal error: ${e.message}")
        }
        serverVoiceSession = null
    }

    // Legacy Fallback voice mode & session (unreachable unless explicitly enabled)
    private var fallbackSession: FallbackVoiceSession? = null
    private var androidTts: TextToSpeech? = null
    private var isFallbackMode: Boolean
        get() = _isFallbackMode.value
        set(value) {
            _isFallbackMode.value = value
        }

    private var cachedVoiceName: String = "Aoede"
    private var cachedLanguage: String = "Bangla (Bengali)"

    private lateinit var resumptionStore: SessionResumptionStore
    private var vad: VoiceActivityDetector? = null

    private fun handlePlaybackSpeakingChanged(isSpeaking: Boolean) {
        if (isSpeaking && _liveState.value != LiveState.ERROR && _liveState.value != LiveState.IDLE) {
            _liveState.value = LiveState.SPEAKING
            _voiceStage.value = VoiceStage.SPEAKING
        } else if (!isSpeaking && _liveState.value == LiveState.SPEAKING) {
            _liveState.value = LiveState.LISTENING
            _voiceStage.value = VoiceStage.LISTENING
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        resumptionStore = SessionResumptionStore(applicationContext)
        createNotificationChannel()

        serviceScope.launch {
            _liveState.collect { state ->
                if (state != LiveState.IDLE && state != LiveState.ERROR) {
                    updateNotification()
                } else {
                    dismissNotification()
                }
            }
        }
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
                dismissNotification()
                stopSelf()
            }
            ACTION_TOGGLE_MUTE -> {
                toggleMute()
            }
        }
        return START_NOT_STICKY
    }

    private fun toggleMute() {
        val next = !_isMuted.value
        _isMuted.value = next
        Log.i(TAG, "MIRA_LIVE_VOICE: Mic mute toggled to $next")
        updateNotification()
    }

    fun dismissNotification() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        val manager = getSystemService(NotificationManager::class.java)
        manager?.cancel(NOTIFICATION_ID)
        _isMuted.value = false
    }

    fun buildCallNotification(): Notification {
        val stopIntent = Intent(this, MicService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(this, MicService::class.java).apply { action = ACTION_TOGGLE_MUTE }
        val mutePendingIntent = PendingIntent.getService(
            this, 2, muteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isMutedState = _isMuted.value
        val muteActionTitle = if (isMutedState) "Unmute" else "Mute"
        val muteIcon = if (isMutedState) android.R.drawable.ic_lock_silent_mode_off else android.R.drawable.ic_lock_silent_mode

        val statusText = when {
            isMutedState -> "Microphone muted • Tap Unmute to speak"
            _liveState.value == LiveState.CONNECTING -> "Connecting to Mira Live..."
            _liveState.value == LiveState.SPEAKING -> "Mira is speaking..."
            _liveState.value == LiveState.LISTENING -> "Listening • Mira Live call active"
            else -> "Live voice call active"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mira AI Voice Call")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.logo_chosen)
            .setContentIntent(openAppPendingIntent)
            .addAction(muteIcon, muteActionTitle, mutePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End", stopPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildCallNotification())
    }

    private fun startForegroundWithNotification() {
        val notification = buildCallNotification()
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
        // Prevent duplicate sessions from repeated taps (spec §3.3)
        if (_liveState.value != LiveState.IDLE && _liveState.value != LiveState.ERROR) {
            Log.w(TAG, "MIRA_LIVE_VOICE: Voice button tapped while session active (${_liveState.value}) — ignoring duplicate tap")
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
        _micAmplitude.value = 0f

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "MIRA_LIVE_VOICE: Voice button tapped — mic permission status: $hasMicPermission")
        if (!hasMicPermission) {
            Log.e(TAG, "MIRA_LIVE_VOICE: Microphone permission not granted at session start — aborting")
            _liveState.value = LiveState.ERROR
            _voiceStage.value = VoiceStage.ERROR
            val permErr = "Microphone permission not granted — allow it in Settings"
            _errorMessage.value = permErr
            _lastVoiceDiagnostic.value = permErr
            LiveVoiceDiagnostics.updateLastError(permErr)
            LiveVoiceDiagnostics.updateStage("ERROR")
            return
        }

        _liveState.value = LiveState.CONNECTING
        _voiceStage.value = VoiceStage.CONNECTING

        serviceScope.launch {
            val userPrefs = UserPreferences(applicationContext)
            val altVoiceEnabled = userPrefs.alternativeVoiceEnabledFlow.first()

            val securePrefs = SecurePreferences(applicationContext)
            val resolver = io.github.ryoaonetsuki.mira.backend.ApiKeyResolver(applicationContext)
            val keySource = resolver.resolveKey()
            val geminiKey = when (keySource) {
                is io.github.ryoaonetsuki.mira.backend.KeySource.UserKey -> keySource.key
                else -> securePrefs.getGeminiApiKey() ?: ""
            }

            // Gated alternative voice check (spec §3.6)
            if (altVoiceEnabled) {
                val groqKey = securePrefs.getGroqApiKey() ?: ""
                if (geminiKey.isNotBlank() && groqKey.isNotBlank()) {
                    Log.i(TAG, "Alternative voice enabled in Settings: starting ServerVoiceSession")
                    startServerVoiceSessionInternal(groqKey, geminiKey)
                    return@launch
                } else if (geminiKey.isNotBlank()) {
                    _errorMessage.value = "Alternative voice requires Groq key — using Native Gemini Live"
                }
            }

            // THE CORE FIX (spec §3.3): Voice button ALWAYS starts Gemini Live first.
            // Groq key presence or absence NEVER changes this.
            if (geminiKey.isNotBlank()) {
                Log.i(TAG, "MIRA_LIVE_VOICE: Starting Native Gemini Live primary voice session")
                startPrimaryGeminiLiveSession(geminiKey)
            } else if (keySource is io.github.ryoaonetsuki.mira.backend.KeySource.ServerKey) {
                Log.i(TAG, "MIRA_LIVE_VOICE: Server key configured — starting Gemini Live session")
                startPrimaryGeminiLiveSession(resolver.anonKey)
            } else {
                Log.w(TAG, "MIRA_LIVE_VOICE: No Gemini API key available for voice session")
                _liveState.value = LiveState.ERROR
                _voiceStage.value = VoiceStage.ERROR
                val noKeyErr = "Gemini key missing — add it in Settings"
                _errorMessage.value = noKeyErr
                _lastVoiceDiagnostic.value = noKeyErr
                LiveVoiceDiagnostics.updateLastError(noKeyErr)
                LiveVoiceDiagnostics.updateStage("ERROR")
            }
        }
    }

    private fun startPrimaryGeminiLiveSession(geminiKey: String) {
        serviceScope.launch {
            val userPrefs = UserPreferences(applicationContext)
            val voiceName = userPrefs.voiceNameFlow.first()
            val personaName = userPrefs.personaFlow.first()
            val customInstructions = userPrefs.customInstructionsFlow.first()
            val language = userPrefs.languageFlow.first()
            val secretMode = userPrefs.secretModeFlow.first()

            cachedVoiceName = voiceName
            cachedLanguage = language

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

            val dateLine = currentDateTimeLine()
            val searchAckPhrase = MiraChatSearchHeuristic.getAcknowledgementPhrase(language)

            // Proven system instruction shape with Mira persona + Bangla-only rule + device date/time (spec §2)
            val fullSystemPrompt = buildString {
                append("Current date and time (from the device clock — always trust this, never guess or use training knowledge): $dateLine. If asked for the date, time or day, answer from this line.\n")
                append(personaType.basePrompt)
                append(" ")
                append(toneDescription)
                append("\nRESPOND IN BANGLA (BENGALI). YOU MUST RESPOND UNMISTAKABLY IN BANGLA. Reply ONLY in Bangla. Never add an English translation. Never mix languages.")
                append("\nIdentity rule: $identityRule")
                append("\nWeb search rule: When the user asks you to search or look something up, or asks about something you do not know or have no latest news about: you must FIRST SAY ALOUD the acknowledgement in Bangla: \"$searchAckPhrase\", then wait for the fresh search results to be provided, then speak the final answer. Never stay silent while searching, and never guess when you do not know. For normal voice talk answer directly and fast.")
                if (customInstructions.isNotBlank()) {
                    append("\nUser instructions: $customInstructions")
                }
                if (activeRules.isNotBlank()) {
                    append("\nUser Rules to strictly obey:\n$activeRules")
                }
                append("\nKeep answers natural, conversational, and suitable for spoken dialogue. Never volunteer that you are an AI unprompted, and never use robotic disclaimers.")
            }

            // Audio Player initialization
            if (audioPlayer == null) {
                audioPlayer = AudioPlayer { isSpeaking ->
                    handlePlaybackSpeakingChanged(isSpeaking)
                }
            }

            // Local Voice Activity Detector (for barge-in and speech detection)
            val vadInstance = VoiceActivityDetector(object : VoiceActivityDetector.VadListener {
                override fun onSpeechStart() {
                    // Barge-in: user began speaking while model was playing audio (spec §3.2)
                    if (audioPlayer?.isSpeaking() == true) {
                        Log.i(TAG, "MIRA_LIVE_VOICE: User speech detected during model playback — instant barge-in flush")
                        audioPlayer?.stopImmediatelyAndFlush()
                    }
                    if (_liveState.value == LiveState.LISTENING) {
                        _voiceStage.value = VoiceStage.RECOGNISING
                    }
                }

                override fun onSpeechEndpoint() {
                    // Signal audio stream end when speech turn pauses (spec §0.5)
                    liveClient?.sendAudioStreamEnd()
                }
            })
            vad = vadInstance

            // Audio Recorder initialization (continuous frame streaming including silence, spec §0.5, §3.2)
            val recorder = AudioRecorder(
                onAudioChunk = { chunk ->
                    if (!_isMuted.value) {
                        vadInstance.processFrame(chunk)
                        liveClient?.sendAudioChunk(chunk)
                        _audioChunksSent.value = liveClient?.audioChunksSent ?: 0L
                    }
                },
                onAmplitude = { amp ->
                    if (!_isMuted.value && (_liveState.value == LiveState.LISTENING || _liveState.value == LiveState.SPEAKING)) {
                        _micAmplitude.value = amp
                    } else {
                        _micAmplitude.value = 0f
                    }
                }
            )
            audioRecorder = recorder
            val recordStarted = recorder.startRecording()
            if (!recordStarted) {
                Log.e(TAG, "MIRA_LIVE_VOICE: AudioRecorder failed to start")
                _liveState.value = LiveState.ERROR
                _voiceStage.value = VoiceStage.ERROR
                val err = "Microphone could not start — check the mic permission"
                _errorMessage.value = err
                _lastVoiceDiagnostic.value = err
                LiveVoiceDiagnostics.updateLastError(err)
                LiveVoiceDiagnostics.updateStage("ERROR")
                return@launch
            }

            _liveState.value = LiveState.CONNECTING
            _voiceStage.value = VoiceStage.CONNECTING
            _errorMessage.value = null

            // MicService-level 45s CONNECTING watchdog (spec §4)
            connectingWatchdogJob?.cancel()
            connectingWatchdogJob = serviceScope.launch {
                delay(45_000L)
                if (_liveState.value == LiveState.CONNECTING) {
                    Log.e(TAG, "MIRA_LIVE_VOICE: MicService connecting watchdog fired after 45s")
                    handleTerminalError("Live voice: Connection timed out — tap to retry")
                }
            }

            val storedHandle = resumptionStore.getHandle()
            val voiceRouting = userPrefs.voiceRoutingFlow.first()
            val useRelay = (voiceRouting != "DIRECT")

            // Initialize single-path Gemini Live Client (spec §3.1)
            liveClient = GeminiLiveClient(
                apiKey = geminiKey,
                voiceName = voiceName,
                systemPrompt = fullSystemPrompt,
                model = GeminiLiveClient.LIVE_MODEL_DEFAULT,
                language = language,
                storedResumptionHandle = storedHandle,
                useRelay = useRelay,
                listener = object : GeminiLiveClient.LiveEventsListener {
                    override fun onStateChanged(state: LiveClientState) {
                        when (state) {
                            LiveClientState.CONNECTING -> {
                                _liveState.value = LiveState.CONNECTING
                                _voiceStage.value = VoiceStage.CONNECTING
                            }
                            LiveClientState.CONFIGURING -> {
                                _liveState.value = LiveState.CONNECTING
                                _voiceStage.value = VoiceStage.WAITING_FOR_SETUP
                            }
                            LiveClientState.LISTENING -> {
                                connectingWatchdogJob?.cancel()
                                connectingWatchdogJob = null
                                _liveState.value = LiveState.LISTENING
                                _voiceStage.value = VoiceStage.LISTENING
                            }
                            LiveClientState.SPEAKING -> {
                                connectingWatchdogJob?.cancel()
                                connectingWatchdogJob = null
                                _liveState.value = LiveState.SPEAKING
                                _voiceStage.value = VoiceStage.SPEAKING
                            }
                            LiveClientState.INTERRUPTED -> {
                                audioPlayer?.stopImmediatelyAndFlush()
                                _liveState.value = LiveState.LISTENING
                                _voiceStage.value = VoiceStage.LISTENING
                            }
                            LiveClientState.RECONNECTING -> {
                                _liveState.value = LiveState.CONNECTING
                                _voiceStage.value = VoiceStage.CONNECTING
                            }
                            LiveClientState.ERROR -> {
                                connectingWatchdogJob?.cancel()
                                connectingWatchdogJob = null
                                _liveState.value = LiveState.ERROR
                                _voiceStage.value = VoiceStage.ERROR
                            }
                            LiveClientState.STOPPED, LiveClientState.IDLE -> {
                                connectingWatchdogJob?.cancel()
                                connectingWatchdogJob = null
                                if (_liveState.value != LiveState.ERROR) {
                                    _liveState.value = LiveState.IDLE
                                    _voiceStage.value = VoiceStage.IDLE
                                }
                            }
                            else -> {}
                        }
                    }

                    override fun onSetupComplete() {
                        connectingWatchdogJob?.cancel()
                        connectingWatchdogJob = null
                        Log.i(TAG, "MIRA_LIVE_VOICE: Setup complete received in MicService")
                        _liveState.value = LiveState.LISTENING
                        _voiceStage.value = VoiceStage.LISTENING
                        _errorMessage.value = null

                        // Flush pre-roll ring buffer into live session
                        val preRoll = audioRecorder?.flushPreRoll() ?: emptyList()
                        for (chunk in preRoll) {
                            liveClient?.sendAudioChunk(chunk)
                        }
                        _audioChunksSent.value = liveClient?.audioChunksSent ?: 0L

                        serviceScope.launch(Dispatchers.IO) {
                            val notifDb = MiraDatabase.getInstance(applicationContext)
                            notifDb.notificationDao().insertNotification(
                                NotificationEntity(
                                    title = "Voice Session Started",
                                    message = "Connected to Gemini Live ($cachedVoiceName)",
                                    type = "voice"
                                )
                            )
                        }
                    }

                    override fun onAudioData(pcmBytes: ByteArray) {
                        _audioChunksReceived.value = liveClient?.audioChunksReceived ?: 0L
                        audioPlayer?.playAudioChunk(pcmBytes)
                    }

                    override fun onInterrupted() {
                        Log.i(TAG, "MIRA_LIVE_VOICE: Server interruption — halting playback")
                        liveSearchJob?.cancel()
                        audioPlayer?.stopImmediatelyAndFlush()
                        _liveState.value = LiveState.LISTENING
                        _voiceStage.value = VoiceStage.LISTENING
                        handledTranscripts.clear()
                        vad?.reset()
                    }

                    override fun onTurnComplete() {
                        Log.i(TAG, "MIRA_LIVE_VOICE: Turn complete")
                        if (_liveState.value == LiveState.SPEAKING) {
                            _liveState.value = LiveState.LISTENING
                            _voiceStage.value = VoiceStage.LISTENING
                        }
                        liveSearchJob?.cancel()
                        handledTranscripts.clear()
                        vad?.reset()
                    }

                    override fun onTranscript(text: String, isUser: Boolean) {
                        if (isUser) {
                            _userTranscript.value = text
                            if (_liveState.value != LiveState.SPEAKING && _liveState.value != LiveState.ERROR) {
                                _voiceStage.value = VoiceStage.RECOGNISING
                            }
                            handleUserVoiceTurn(text)
                        } else {
                            _modelTranscript.value = text
                        }
                    }

                    override fun onStalled() {
                        Log.w(TAG, "MIRA_LIVE_VOICE: Session stalled after user speech")
                        handleTerminalError("Live voice: Server did not answer — tap to retry")
                    }

                    override fun onError(message: String) {
                        Log.e(TAG, "MIRA_LIVE_VOICE: Voice session error: $message")
                        handleTerminalError(message)
                    }

                    override fun onClosed(reason: String) {
                        connectingWatchdogJob?.cancel()
                        connectingWatchdogJob = null
                        Log.i(TAG, "MIRA_LIVE_VOICE: Voice session closed: $reason")
                        try {
                            audioRecorder?.stopRecording()
                        } catch (_: Exception) {}
                        audioRecorder = null
                        try {
                            audioPlayer?.stopImmediatelyAndFlush()
                        } catch (_: Exception) {}
                        _micAmplitude.value = 0f
                        dismissNotification()
                        if (_liveState.value != LiveState.ERROR) {
                            _liveState.value = LiveState.IDLE
                            _voiceStage.value = VoiceStage.IDLE
                        }
                    }

                    override fun onSessionResumptionUpdate(newHandle: String) {
                        resumptionStore.saveHandle(newHandle)
                    }
                }
            )

            liveClient?.startSession()
        }
    }

    private fun handleUserVoiceTurn(text: String) {
        val cleanText = text.trim()
        val words = cleanText.split("\\s+".toRegex()).filter { it.isNotBlank() }

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
                        Log.w(TAG, "MIRA_LIVE_VOICE: Could not record notification: ${e.message}")
                    }
                }
            }
        }

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
                            Log.e(TAG, "MIRA_LIVE_VOICE: Error running voice connector command: ${e.message}", e)
                        }
                    }
                }
            }
        }

        // Debounced live web search injection with spoken acknowledgement
        liveSearchJob?.cancel()
        if (words.size >= 2 && MiraChatSearchHeuristic.isSearchRequest(cleanText)) {
            val ackKey = "ack:search_turn"
            if (handledTranscripts.add(ackKey) && liveClient?.isSessionReady() == true) {
                val ackPhrase = MiraChatSearchHeuristic.getAcknowledgementPhrase(cachedLanguage)
                val ackInstruction = "[Search request detected. Say aloud immediately in $cachedLanguage: \"$ackPhrase\". Do not answer yet; fresh search results are being retrieved.]"
                liveClient?.sendTextMessage(ackInstruction)
                Log.i(TAG, "MIRA_LIVE_VOICE: Sent immediate search acknowledgement instruction: $ackPhrase")
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
                                Log.i(TAG, "MIRA_LIVE_VOICE: Injected live search results into active session for query: $cleanText")
                            } else if (liveClient?.isSessionReady() == true) {
                                val payload = "[No fresh web results found for \"$cleanText\". Briefly inform the user in $cachedLanguage that you could not find current information on this.]"
                                liveClient?.sendTextMessage(payload)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "MIRA_LIVE_VOICE: Error performing live web search: ${e.message}")
                        }
                    }
                }
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

            if (audioPlayer == null) {
                audioPlayer = AudioPlayer { isSpeaking ->
                    handlePlaybackSpeakingChanged(isSpeaking)
                }
            }

            Log.i(TAG, "Starting ServerVoiceSession (alternative voice path)")
            serverVoiceSession?.stop()

            val session = ServerVoiceSession(
                context = applicationContext,
                groqKey = groqKey,
                geminiKey = geminiKey,
                voiceName = voiceName,
                language = language,
                systemPrompt = fullSystemPrompt,
                dateLine = dateLine,
                audioPlayer = audioPlayer!!,
                onStateChanged = { state -> _liveState.value = state },
                onStageChanged = { stage -> _voiceStage.value = stage },
                onUserTranscript = { text -> _userTranscript.value = text },
                onModelTranscript = { text -> _modelTranscript.value = text },
                onAppendModelTranscript = { token -> _modelTranscript.value = _modelTranscript.value + token },
                onError = { err ->
                    val plainError = if (err.startsWith("Server voice:", ignoreCase = true)) err else "Server voice: $err"
                    _errorMessage.value = plainError
                    _lastVoiceDiagnostic.value = plainError
                },
                onAudioChunkSent = { _audioChunksSent.value = _audioChunksSent.value + 1L },
                onAudioChunkReceived = { _audioChunksReceived.value = _audioChunksReceived.value + 1L },
                onMicAmplitude = { amp -> _micAmplitude.value = amp },
                onRequestLiveFallback = { reason ->
                    Log.i(TAG, "Server voice requested Live: $reason")
                    serverVoiceSession?.stop()
                    serverVoiceSession = null
                    startPrimaryGeminiLiveSession(geminiKey)
                }
            )
            serverVoiceSession = session
            session.start()
        }
    }

    private fun stopLiveVoiceSession() {
        Log.i(TAG, "MIRA_LIVE_VOICE: Stopping voice session (user action)")
        stopLiveVoiceSessionInternal()
        _liveState.value = LiveState.IDLE
        _voiceStage.value = VoiceStage.IDLE
    }

    private fun stopLiveVoiceSessionInternal() {
        connectingWatchdogJob?.cancel()
        connectingWatchdogJob = null
        serverVoiceSession?.stop()
        serverVoiceSession = null
        vad?.reset()
        vad = null
        connectorJob?.cancel()
        connectorJob = null
        liveSearchJob?.cancel()
        liveSearchJob = null
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
        dismissNotification()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "MIRA_LIVE_VOICE"
        private const val CHANNEL_ID = "mira_mic_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "io.github.ryoaonetsuki.mira.action.START_VOICE"
        const val ACTION_STOP = "io.github.ryoaonetsuki.mira.action.STOP_VOICE"
        const val ACTION_TOGGLE_MUTE = "io.github.ryoaonetsuki.mira.action.TOGGLE_MUTE"

        private val _isMuted = MutableStateFlow(false)
        val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

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

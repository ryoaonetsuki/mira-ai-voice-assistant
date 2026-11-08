package io.github.ryoaonetsuki.mira.service

import ai.pipecat.client.types.Value
import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.ryoaonetsuki.mira.MainActivity
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.NotificationEntity
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.network.ConnectorIntentHandler
import io.github.ryoaonetsuki.mira.network.FreeWebSearch
import io.github.ryoaonetsuki.mira.network.MiraChatSearchHeuristic
import io.github.ryoaonetsuki.mira.network.currentDateTimeLine
import io.github.ryoaonetsuki.mira.voice.*
import io.github.ryoaonetsuki.mira.voice.pipecat.GeminiSessionManager
import io.github.ryoaonetsuki.mira.voice.pipecat.SessionManagerListener
import io.github.ryoaonetsuki.mira.voice.pipecat.SessionManagerState
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
 * Factory for constructing the ongoing CallStyle notification for Mira voice sessions.
 * Extracted from MicService to allow direct unit testing without instantiating the full Service lifecycle.
 */
internal object CallNotificationFactory {
    const val CHANNEL_ID = "mira_call_channel"

    fun build(
        context: Context,
        isMuted: Boolean = false,
        isReconnecting: Boolean = false,
        liveState: LiveState = LiveState.IDLE
    ): Notification {
        val stopIntent = Intent(context, MicService::class.java).apply { action = MicService.ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            context, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(context, MicService::class.java).apply { action = MicService.ACTION_TOGGLE_MUTE }
        val mutePendingIntent = PendingIntent.getService(
            context, 2, muteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteActionTitle = if (isMuted) "Unmute" else "Mute"
        val muteIcon = if (isMuted) android.R.drawable.ic_lock_silent_mode_off else android.R.drawable.ic_lock_silent_mode

        val statusText = when {
            isMuted -> "Microphone muted • Tap Unmute to speak"
            isReconnecting -> "Reconnecting to Mira Live..."
            liveState == LiveState.CONNECTING -> "Connecting to Mira Live..."
            liveState == LiveState.SPEAKING -> "Mira is speaking..."
            liveState == LiveState.LISTENING -> "Listening • Mira Live call active"
            else -> "Live voice call active"
        }

        // Fallback: IconCompat.createWithResource throws IllegalArgumentException if the
        // resource name cannot be resolved (e.g. in Robolectric test environments).
        val avatarIcon = runCatching {
            IconCompat.createWithResource(context, R.drawable.logo_chosen)
        }.getOrNull()
        val miraPerson = Person.Builder()
            .setName("Mira")
            .apply { avatarIcon?.let { setIcon(it) } }
            .build()

        val callStyle = NotificationCompat.CallStyle.forOngoingCall(miraPerson, stopPendingIntent)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Mira AI Voice Call")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification_mira)
            .setContentIntent(openAppPendingIntent)
            .setStyle(callStyle)
            .addAction(muteIcon, muteActionTitle, mutePendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun buildCallNotification(
        context: Context,
        isMuted: Boolean = false,
        isReconnecting: Boolean = false,
        liveState: LiveState = LiveState.IDLE
    ): Notification = build(context, isMuted, isReconnecting, liveState)
}

/**
 * Foreground service managing Mira voice sessions.
 *
 * ROUND 19 Architecture:
 * Delegates Gemini Live voice sessions to [GeminiSessionManager] which wraps
 * Pipecat RTVI client, session resumption handle persistence (2h TTL, single use),
 * proactive GoAway auto-reconnection, and Stage-4 silence watchdog (15s).
 */
class MicService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var sessionManager: GeminiSessionManager? = null
    private val handledTranscripts = Collections.synchronizedSet(mutableSetOf<String>())
    private var connectorJob: Job? = null
    private var liveSearchJob: Job? = null
    private var connectingWatchdogJob: Job? = null

    private var cachedVoiceName: String = "Aoede"
    private var cachedLanguage: String = "Bangla (Bengali)"

    private lateinit var resumptionStore: SessionResumptionStore

    private val automationBridge by lazy {
        io.github.ryoaonetsuki.mira.automation.MiraAutomationBridge(applicationContext) {
            val securePrefs = SecurePreferences(applicationContext)
            val resolver = io.github.ryoaonetsuki.mira.backend.ApiKeyResolver(applicationContext)
            when (val keySource = resolver.resolveKey()) {
                is io.github.ryoaonetsuki.mira.backend.KeySource.UserKey -> keySource.key
                else -> securePrefs.getGeminiApiKey() ?: ""
            }
        }
    }

    private val notificationController by lazy {
        CallNotificationController(
            startForegroundAction = { id, notification ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        id,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(id, notification)
                }
            },
            stopForegroundAction = {
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (_: Exception) {}
            },
            cancelNotificationAction = { id ->
                val manager = getSystemService(NotificationManager::class.java)
                manager?.cancel(id)
            }
        )
    }

    val isForeground: Boolean
        get() = notificationController.isForeground

    /**
     * Guarantees terminal ERROR state and complete resource cleanup on every failure path.
     * Stops session manager, resets mic amplitude to 0, cancels watchdogs, and allows Home to return to idle.
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
        _isReconnecting.value = false
        LiveVoiceDiagnostics.updateLastError(message)
        LiveVoiceDiagnostics.updateStage("ERROR")
        dismissNotification()

        try {
            sessionManager?.stopSession()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping session manager on terminal error: ${e.message}")
        }
        sessionManager = null
        activeSessionManager = null
    }

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
                    postCallNotificationIfForeground()
                } else if (isForeground) {
                    dismissNotification()
                }
            }
        }

        serviceScope.launch {
            _isReconnecting.collect {
                if (_liveState.value != LiveState.IDLE && _liveState.value != LiveState.ERROR) {
                    postCallNotificationIfForeground()
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
        sessionManager?.enableMic(!next)
        postCallNotificationIfForeground()
    }

    fun dismissNotification() {
        notificationController.dismissNotification(NOTIFICATION_ID)
        _isMuted.value = false
    }

    fun postCallNotificationIfForeground() {
        notificationController.postCallNotificationIfForeground(NOTIFICATION_ID) {
            buildCallNotification()
        }
    }

    fun updateNotification() {
        postCallNotificationIfForeground()
    }

    fun buildCallNotification(): Notification {
        return CallNotificationFactory.build(
            context = this,
            isMuted = _isMuted.value,
            isReconnecting = _isReconnecting.value,
            liveState = _liveState.value
        )
    }

    private fun startForegroundWithNotification() {
        notificationController.startForegroundWithNotification(buildCallNotification(), NOTIFICATION_ID)
    }

    private fun startLiveVoiceSession() {
        // Prevent duplicate sessions from repeated taps
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
        _isReconnecting.value = false
        _reconnectAttempt.value = 0
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
            dismissNotification()
            return
        }

        _liveState.value = LiveState.CONNECTING
        _voiceStage.value = VoiceStage.CONNECTING

        serviceScope.launch {
            val securePrefs = SecurePreferences(applicationContext)
            val resolver = io.github.ryoaonetsuki.mira.backend.ApiKeyResolver(applicationContext)
            val keySource = resolver.resolveKey()
            val geminiKey = when (keySource) {
                is io.github.ryoaonetsuki.mira.backend.KeySource.UserKey -> keySource.key
                else -> securePrefs.getGeminiApiKey() ?: ""
            }

            // Voice button ALWAYS starts Gemini Live.
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
                dismissNotification()
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
            val echoGuard = userPrefs.echoGuardFlow.first()
            val userName = userPrefs.userNameFlow.first()
            val assistantName = userPrefs.assistantNameFlow.first()

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

            val dateLine = currentDateTimeLine(language)
            val searchAckPhrase = MiraChatSearchHeuristic.getAcknowledgementPhrase(language)

            val fullSystemPrompt = LivePromptBuilder.buildLiveSystemPrompt(
                language = language,
                personaType = personaType,
                userName = userName,
                assistantName = assistantName,
                secretMode = secretMode,
                dateLine = dateLine,
                searchAckPhrase = searchAckPhrase,
                customInstructions = customInstructions,
                activeRules = activeRules
            )

            val bcp47Language = LanguageUtils.mapLanguageToBcp47(language)

            Log.i(TAG, "MIRA_LIVE_VOICE: Initializing GeminiSessionManager with voice=$voiceName, language=$bcp47Language, echoGuard=$echoGuard")

            _liveState.value = LiveState.CONNECTING
            _voiceStage.value = VoiceStage.CONNECTING
            _errorMessage.value = null

            // Service-level connecting watchdog
            connectingWatchdogJob?.cancel()
            connectingWatchdogJob = serviceScope.launch {
                delay(100_000L)
                if (_liveState.value == LiveState.CONNECTING) {
                    Log.e(TAG, "MIRA_LIVE_VOICE: MicService connecting watchdog fired after 100s")
                    handleTerminalError("Live voice: Connection timed out — tap to retry")
                }
            }

            val manager = GeminiSessionManager(
                context = applicationContext,
                apiKey = geminiKey,
                voiceName = voiceName,
                language = bcp47Language,
                systemPrompt = fullSystemPrompt,
                resumptionStore = resumptionStore,
                listener = createSessionListener(),
                externalScope = serviceScope,
                echoGuardEnabled = echoGuard
            )
            sessionManager = manager
            activeSessionManager = manager

            LiveVoiceDiagnostics.updateActiveRoute("DIRECT")
            LiveVoiceDiagnostics.updateStage("CONNECTING")

            // Connect manager.audioLevel to _micAmplitude (the orb driver)
            serviceScope.launch {
                manager.audioLevel.collect { level ->
                    if (!_isMuted.value && (_liveState.value == LiveState.LISTENING || _liveState.value == LiveState.SPEAKING)) {
                        _micAmplitude.value = level
                    } else {
                        _micAmplitude.value = 0f
                    }
                }
            }

            manager.startSession()
        }
    }

    private fun createSessionListener(): SessionManagerListener {
        return object : SessionManagerListener {
            private var lastState = SessionManagerState.IDLE

            override fun onStateChanged(state: SessionManagerState) {
                Log.i(TAG, "MIRA_LIVE_VOICE: SessionManager state changed: $state")
                LiveVoiceDiagnostics.updateSessionManagerState(state.name)
                LiveVoiceDiagnostics.updateStage(state.name)

                if (lastState == SessionManagerState.CONFIGURING && state == SessionManagerState.RECONNECTING) {
                    LiveVoiceDiagnostics.recordStage4Timeout()
                }
                if (state == SessionManagerState.GOAWAY_RECEIVED) {
                    LiveVoiceDiagnostics.recordGoAway()
                }
                lastState = state

                when (state) {
                    SessionManagerState.IDLE -> {
                        connectingWatchdogJob?.cancel()
                        connectingWatchdogJob = null
                        _isReconnecting.value = false
                        if (_liveState.value != LiveState.ERROR) {
                            _liveState.value = LiveState.IDLE
                            _voiceStage.value = VoiceStage.IDLE
                        }
                    }
                    SessionManagerState.CONNECTING -> {
                        _isReconnecting.value = false
                        _liveState.value = LiveState.CONNECTING
                        _voiceStage.value = VoiceStage.CONNECTING
                    }
                    SessionManagerState.CONFIGURING -> {
                        _isReconnecting.value = false
                        _liveState.value = LiveState.CONNECTING
                        _voiceStage.value = VoiceStage.WAITING_FOR_SETUP
                    }
                    SessionManagerState.CONNECTED -> {
                        connectingWatchdogJob?.cancel()
                        connectingWatchdogJob = null
                        _isReconnecting.value = false
                        _errorMessage.value = null
                        _lastVoiceDiagnostic.value = null

                        _liveState.value = LiveState.LISTENING
                        _voiceStage.value = VoiceStage.LISTENING

                        serviceScope.launch(Dispatchers.IO) {
                            try {
                                val notifDb = MiraDatabase.getInstance(applicationContext)
                                notifDb.notificationDao().insertNotification(
                                    NotificationEntity(
                                        title = "Voice Session Started",
                                        message = "Connected to Gemini Live ($cachedVoiceName)",
                                        type = "voice"
                                    )
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "MIRA_LIVE_VOICE: Could not record notification: ${e.message}")
                            }
                        }
                    }
                    SessionManagerState.GOAWAY_RECEIVED -> {
                        _isReconnecting.value = true
                        _liveState.value = LiveState.CONNECTING
                        _voiceStage.value = VoiceStage.CONNECTING
                    }
                    SessionManagerState.RECONNECTING -> {
                        _isReconnecting.value = true
                        _liveState.value = LiveState.CONNECTING
                        _voiceStage.value = VoiceStage.CONNECTING
                    }
                    SessionManagerState.ERROR -> {
                        connectingWatchdogJob?.cancel()
                        connectingWatchdogJob = null
                        _isReconnecting.value = false
                        _liveState.value = LiveState.ERROR
                        _voiceStage.value = VoiceStage.ERROR
                        _micAmplitude.value = 0f
                        dismissNotification()
                        return
                    }
                    SessionManagerState.STOPPED -> {
                        connectingWatchdogJob?.cancel()
                        connectingWatchdogJob = null
                        _isReconnecting.value = false
                        _micAmplitude.value = 0f
                        dismissNotification()
                        if (_liveState.value != LiveState.ERROR) {
                            _liveState.value = LiveState.IDLE
                            _voiceStage.value = VoiceStage.IDLE
                        }
                        return
                    }
                }
                postCallNotificationIfForeground()
            }

            override fun onAudioLevel(level: Float) {
                if (!_isMuted.value && (_liveState.value == LiveState.LISTENING || _liveState.value == LiveState.SPEAKING)) {
                    _micAmplitude.value = level
                } else {
                    _micAmplitude.value = 0f
                }
            }

            override fun onBotSpeakingChanged(isSpeaking: Boolean) {
                Log.d(TAG, "MIRA_LIVE_VOICE: onBotSpeakingChanged: $isSpeaking")
                if (isSpeaking && _liveState.value != LiveState.ERROR && _liveState.value != LiveState.IDLE) {
                    _liveState.value = LiveState.SPEAKING
                    _voiceStage.value = VoiceStage.SPEAKING
                } else if (!isSpeaking && _liveState.value == LiveState.SPEAKING) {
                    _liveState.value = LiveState.LISTENING
                    _voiceStage.value = VoiceStage.LISTENING
                }
                postCallNotificationIfForeground()
            }

            override fun onUserSpeakingChanged(isSpeaking: Boolean) {
                Log.d(TAG, "MIRA_LIVE_VOICE: onUserSpeakingChanged: $isSpeaking")
                if (isSpeaking && _liveState.value == LiveState.LISTENING) {
                    _voiceStage.value = VoiceStage.RECOGNISING
                } else if (!isSpeaking && _voiceStage.value == VoiceStage.RECOGNISING) {
                    _voiceStage.value = VoiceStage.LISTENING
                }
            }

            override fun onUserTranscript(text: String) {
                Log.i(TAG, "MIRA_LIVE_VOICE: User transcript: $text")
                _userTranscript.value = text
                if (_liveState.value != LiveState.SPEAKING && _liveState.value != LiveState.ERROR) {
                    _voiceStage.value = VoiceStage.RECOGNISING
                }
                handleUserVoiceTurn(text)
            }

            override fun onModelTranscript(text: String) {
                Log.i(TAG, "MIRA_LIVE_VOICE: Model transcript: $text")
                _modelTranscript.value = io.github.ryoaonetsuki.mira.identity.MiraOutputFilter.sanitize(text)
            }

            override fun onReconnecting(attempt: Int, maxAttempts: Int) {
                Log.i(TAG, "MIRA_LIVE_VOICE: onReconnecting: attempt $attempt of $maxAttempts")
                _isReconnecting.value = true
                _reconnectAttempt.value = attempt
                _liveState.value = LiveState.CONNECTING
                _voiceStage.value = VoiceStage.CONNECTING
                LiveVoiceDiagnostics.recordReconnectAttempt(attempt)
                LiveVoiceDiagnostics.updateStage("RECONNECTING")
                postCallNotificationIfForeground()
            }

            override fun onError(message: String) {
                Log.e(TAG, "MIRA_LIVE_VOICE: Session manager error: $message")
                if (message.contains("timed out after 15s", ignoreCase = true)) {
                    LiveVoiceDiagnostics.recordStage4Timeout()
                }
                handleTerminalError(message)
            }

            override fun onSessionEnded(reason: String, error: Throwable?) {
                Log.i(TAG, "MIRA_LIVE_VOICE: Session ended (reason=$reason, error=${error?.message})")
            }

            override fun onFunctionCall(id: String, name: String, args: Value.Array, rawArgs: Map<String, String>) {
                Log.i(TAG, "MIRA_LIVE_VOICE: onFunctionCall: id=$id name=$name rawArgs=$rawArgs")
                handleFunctionCall(id, name, args, rawArgs)
            }
        }
    }

    private fun handleFunctionCall(id: String, name: String, args: Value.Array, rawArgs: Map<String, String>) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                // Forward function calls through the existing function-call / command handling path (preserve rawArgs keys)
                val query = rawArgs["query"] ?: rawArgs["text"] ?: rawArgs["command"] ?: rawArgs.values.firstOrNull() ?: ""
                var resultString: String? = null
                if (query.isNotBlank() && ConnectorIntentHandler.isConnectorCommand(query)) {
                    resultString = ConnectorIntentHandler.handleCommand(applicationContext, query)
                    if (resultString != null) {
                        val db = MiraDatabase.getInstance(applicationContext)
                        db.notificationDao().insertNotification(
                            NotificationEntity(
                                title = "Connector Action",
                                message = resultString,
                                type = "connector"
                            )
                        )
                    }
                } else if (name.equals("search", ignoreCase = true) || name.equals("web_search", ignoreCase = true)) {
                    val searchResults = FreeWebSearch.search(query, maxResults = 3)
                    resultString = searchResults.joinToString("\n") { "${it.title}: ${it.snippet}" }
                }

                val finalResult = resultString ?: "Executed $name"
                val responseObj = Value.Object(
                    "result" to Value.Str(finalResult)
                )
                sessionManager?.sendFunctionResponse(id, name, responseObj)
            } catch (e: Exception) {
                Log.e(TAG, "MIRA_LIVE_VOICE: Error handling function call $name: ${e.message}", e)
                val errObj = Value.Object(
                    "error" to Value.Str(e.message ?: "Function execution failed")
                )
                sessionManager?.sendFunctionResponse(id, name, errObj)
            }
        }
    }

    private fun handleUserVoiceTurn(text: String) {
        val cleanText = text.trim()
        val words = cleanText.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val lower = text.trim().lowercase(Locale.ROOT)
        val stopTokens = lower.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val stopWords = setOf("stop", "thamo", "থামো")
        val isStop = stopTokens.any { it in stopWords }

        // Emergency agent voice stop
        if (isStop && automationBridge.isRunning) {
            automationBridge.cancel()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    val db = MiraDatabase.getInstance(applicationContext)
                    db.notificationDao().insertNotification(
                        NotificationEntity(
                            title = "Automation Stopped",
                            message = "Screen automation stopped via voice command: $text",
                            type = "automation"
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "MIRA_LIVE_VOICE: Could not record notification: ${e.message}")
                }
            }
            return
        }

        // Layer 1 — MiraIdentityInterceptor wiring: BEFORE sending transcribed text to Gemini
        val cannedIdentityResponse = io.github.ryoaonetsuki.mira.identity.MiraIdentityInterceptor.getCannedResponse(cleanText)
        if (cannedIdentityResponse != null) {
            // Suppress the Live model's spoken reply for THAT turn only
            sessionManager?.sendUserMessage("[Identity question detected: \"$cleanText\". Do not reply or speak. Keep silent.]")
            _modelTranscript.value = cannedIdentityResponse

            serviceScope.launch(Dispatchers.IO) {
                val wasMuted = _isMuted.value
                if (!wasMuted) {
                    _isMuted.value = true
                    sessionManager?.enableMic(false)
                }
                try {
                    speakAutomationResult(cannedIdentityResponse)
                } catch (e: Exception) {
                    Log.e(TAG, "MIRA_LIVE_VOICE: Error speaking canned identity response: ${e.message}", e)
                } finally {
                    if (!wasMuted) {
                        _isMuted.value = false
                        sessionManager?.enableMic(true)
                    }
                }
            }
            return
        }

        // Autonomous Screen Agent wiring: BEFORE existing handling
        if (io.github.ryoaonetsuki.mira.automation.MiraAutomationBridge.isAutomationIntent(cleanText) &&
            MiraAccessibilityService.isEnabled(applicationContext)
        ) {
            // Suppress the Live model's spoken reply for THAT turn only
            sessionManager?.sendUserMessage("[Automation command detected: \"$cleanText\". Do not reply or speak. Keep silent.]")

            serviceScope.launch(Dispatchers.IO) {
                val wasMuted = _isMuted.value
                if (!wasMuted) {
                    _isMuted.value = true
                    sessionManager?.enableMic(false)
                }
                try {
                    val result = automationBridge.executeGoal(cleanText)

                    // Record notification like existing macro notifications
                    try {
                        val db = MiraDatabase.getInstance(applicationContext)
                        db.notificationDao().insertNotification(
                            NotificationEntity(
                                title = if (result.status == io.github.ryoaonetsuki.mira.automation.agent.TaskStatus.SUCCESS)
                                    "Task Completed" else "Task Failed",
                                message = result.message,
                                type = "macro"
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "MIRA_LIVE_VOICE: Could not record automation notification: ${e.message}")
                    }

                    // Speak result message via existing TTS path
                    speakAutomationResult(result.message)
                } catch (e: Exception) {
                    Log.e(TAG, "MIRA_LIVE_VOICE: Error executing voice automation: ${e.message}", e)
                } finally {
                    if (!wasMuted) {
                        _isMuted.value = false
                        sessionManager?.enableMic(true)
                    }
                }
            }
            return
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
                                sessionManager?.sendUserMessage("User command result: $commandResult. Please inform the user clearly and concisely in voice.")
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
            if (handledTranscripts.add(ackKey) && sessionManager?.state?.value == SessionManagerState.CONNECTED) {
                val ackPhrase = MiraChatSearchHeuristic.getAcknowledgementPhrase(cachedLanguage)
                val ackInstruction = "[Search request detected. Say aloud immediately in $cachedLanguage: \"$ackPhrase\". Do not answer yet; fresh search results are being retrieved.]"
                sessionManager?.sendUserMessage(ackInstruction)
                Log.i(TAG, "MIRA_LIVE_VOICE: Sent immediate search acknowledgement instruction: $ackPhrase")
            }

            val searchKey = "search:" + cleanText.lowercase(Locale.ROOT)
            if (!handledTranscripts.contains(searchKey)) {
                liveSearchJob = serviceScope.launch(Dispatchers.IO) {
                    delay(600)
                    if (handledTranscripts.add(searchKey) && sessionManager?.state?.value == SessionManagerState.CONNECTED) {
                        try {
                            val searchResults = FreeWebSearch.search(cleanText, maxResults = 5)
                            if (searchResults.isNotEmpty() && sessionManager?.state?.value == SessionManagerState.CONNECTED) {
                                val dateLine = currentDateTimeLine(cachedLanguage)
                                val searchLines = searchResults.mapIndexed { idx, res ->
                                    "${idx + 1}. ${res.title} — ${res.snippet} (${res.url})"
                                }.joinToString("\n")
                                val payload = "[Live web search — searched just now, $dateLine:\n$searchLines\nAnswer ONLY from these fresh results, briefly, in $cachedLanguage. Do not repeat the acknowledgement.]"
                                sessionManager?.sendUserMessage(payload)
                                Log.i(TAG, "MIRA_LIVE_VOICE: Injected live search results into active session for query: $cleanText")
                            } else if (sessionManager?.state?.value == SessionManagerState.CONNECTED) {
                                val payload = "[No fresh web results found for \"$cleanText\". Briefly inform the user in $cachedLanguage that you could not find current information on this.]"
                                sessionManager?.sendUserMessage(payload)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "MIRA_LIVE_VOICE: Error performing live web search: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private suspend fun speakAutomationResult(message: String) {
        if (message.isBlank()) return
        try {
            val resolver = io.github.ryoaonetsuki.mira.backend.ApiKeyResolver(applicationContext)
            val securePrefs = SecurePreferences(applicationContext)
            val keySource = resolver.resolveKey()
            val rawApiKey = (keySource as? io.github.ryoaonetsuki.mira.backend.KeySource.UserKey)?.key
                ?: (securePrefs.getGeminiApiKey() ?: "")
            val useServer = keySource is io.github.ryoaonetsuki.mira.backend.KeySource.ServerKey
            val ttsClient = io.github.ryoaonetsuki.mira.network.GeminiTtsClient(applicationContext)
            val result = ttsClient.speakText(
                apiKey = rawApiKey,
                text = message,
                voiceName = cachedVoiceName,
                useServerProxy = useServer
            )
            if (result.isSuccess) {
                val pcmBytes = result.getOrThrow()
                val player = AudioPlayer { isSpeaking ->
                    handlePlaybackSpeakingChanged(isSpeaking)
                }
                player.beginPreview()
                val rampedBytes = AudioPlayer.applyPreviewFadeRamps(pcmBytes)
                val chunkSize = 4096
                var offset = 0
                while (offset < rampedBytes.size) {
                    val length = minOf(chunkSize, rampedBytes.size - offset)
                    player.playAudioChunk(rampedBytes.copyOfRange(offset, offset + length))
                    offset += length
                    delay(10)
                }
                player.endPreviewCleanly()
            }
        } catch (e: Exception) {
            Log.w(TAG, "MIRA_LIVE_VOICE: Failed to speak automation result via TTS: ${e.message}")
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
        connectorJob?.cancel()
        connectorJob = null
        liveSearchJob?.cancel()
        liveSearchJob = null
        handledTranscripts.clear()

        sessionManager?.stopSession()
        sessionManager = null
        activeSessionManager = null

        _isReconnecting.value = false
        _reconnectAttempt.value = 0
        _audioChunksSent.value = 0L
        _audioChunksReceived.value = 0L
        _micAmplitude.value = 0f
    }

    fun simulateGoAwayForTesting() {
        Log.i(TAG, "MIRA_LIVE_VOICE: simulateGoAwayForTesting() called on service instance")
        sessionManager?.simulateGoAwayForTesting()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val callChannel = NotificationChannel(
                CHANNEL_ID,
                "Mira Voice Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Ongoing Mira AI voice call controls"
                enableVibration(true)
                enableLights(true)
            }
            val micChannel = NotificationChannel(
                MIC_CHANNEL_ID,
                "Mira Live Voice",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Mira AI voice assistant active in the background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(callChannel)
            manager?.createNotificationChannel(micChannel)
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
        private const val CHANNEL_ID = CallNotificationFactory.CHANNEL_ID
        private const val MIC_CHANNEL_ID = "mira_mic_channel"
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

        private val _isReconnecting = MutableStateFlow(false)
        val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

        private val _reconnectAttempt = MutableStateFlow(0)
        val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

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

        @Volatile
        private var activeSessionManager: GeminiSessionManager? = null

        fun simulateGoAwayForTesting() {
            Log.i(TAG, "MIRA_LIVE_VOICE: simulateGoAwayForTesting() called on MicService companion")
            activeSessionManager?.simulateGoAwayForTesting()
        }

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

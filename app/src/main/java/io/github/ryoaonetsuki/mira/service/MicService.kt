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
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.ryoaonetsuki.mira.MainActivity
import io.github.ryoaonetsuki.mira.R
import io.github.ryoaonetsuki.mira.automation.MiraAutomationBridge
import io.github.ryoaonetsuki.mira.automation.agent.AutomationPort
import io.github.ryoaonetsuki.mira.automation.agent.ScreenAutomationService
import io.github.ryoaonetsuki.mira.automation.agent.ScreenNode
import io.github.ryoaonetsuki.mira.automation.agent.Skill
import io.github.ryoaonetsuki.mira.automation.agent.SkillMemoryService
import io.github.ryoaonetsuki.mira.automation.agent.TaskExecutor
import io.github.ryoaonetsuki.mira.automation.agent.TaskStatus
import io.github.ryoaonetsuki.mira.automation.agent.TaskStep
import io.github.ryoaonetsuki.mira.automation.agent.TapNumbersResult
import io.github.ryoaonetsuki.mira.automation.agent.TargetSource
import io.github.ryoaonetsuki.mira.automation.agent.PreTapResult
import io.github.ryoaonetsuki.mira.automation.agent.AppPacing
import io.github.ryoaonetsuki.mira.automation.agent.FrameDiff
import io.github.ryoaonetsuki.mira.automation.agent.MikaReliableActions
import io.github.ryoaonetsuki.mira.automation.agent.MiraActionResult
import io.github.ryoaonetsuki.mira.automation.agent.MiraAndroidScreen
import io.github.ryoaonetsuki.mira.automation.agent.MiraScreenPort
import io.github.ryoaonetsuki.mira.automation.agent.MIRA_DEFAULT_OPERATING_PACE
import io.github.ryoaonetsuki.mira.automation.agent.VisionFirstPerception
import io.github.ryoaonetsuki.mira.automation.agent.formatNumberedList
import io.github.ryoaonetsuki.mira.data.local.MiraDatabase
import io.github.ryoaonetsuki.mira.data.local.entities.ChatMessageEntity
import io.github.ryoaonetsuki.mira.data.local.entities.NotificationEntity
import io.github.ryoaonetsuki.mira.data.preferences.SecurePreferences
import io.github.ryoaonetsuki.mira.data.preferences.UserPreferences
import io.github.ryoaonetsuki.mira.network.ConnectorIntentHandler
import io.github.ryoaonetsuki.mira.network.FreeWebSearch
import io.github.ryoaonetsuki.mira.network.GeminiRestClient
import io.github.ryoaonetsuki.mira.network.currentDateTimeLine
import io.github.ryoaonetsuki.mira.voice.*
import io.github.ryoaonetsuki.mira.voice.pipecat.GeminiSessionManager
import io.github.ryoaonetsuki.mira.voice.pipecat.SessionManagerListener
import io.github.ryoaonetsuki.mira.voice.pipecat.SessionManagerState
import io.github.ryoaonetsuki.mira.voice.pipecat.VoiceEchoDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    private var connectingWatchdogJob: Job? = null
    private var startVoiceSessionJob: Job? = null
    /**
     * Turn G: the call is owned by "call active" state, not by one WebSocket.
     * [autoRestartJob] re-starts a fresh session after a terminal error
     * while the user has not tapped End; [livenessWatchdogJob] watches for
     * a live-but-deaf session; [callStateStore] persists "call active"
     * across a service/process kill so a recreated service resumes the call.
     */
    private var autoRestartJob: Job? = null
    private var autoRestartAttempt: Int = 0
    private var livenessWatchdogJob: Job? = null
    @Volatile private var lastAudioFrameElapsedMs: Long = 0L
    @Volatile private var lastServerActivityElapsedMs: Long = 0L
    private val functionCallMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var userRequestedStop: Boolean = false
    @Volatile private var connectsThisCall: Int = 0
    @Volatile private var lastAutomationGoal: String? = null
    @Volatile private var currentAutomationTarget: String? = null
    private var cachedScreenPort: MiraAndroidScreen? = null
    private var cachedScreenService: MiraAccessibilityService? = null
    private val appPacing: AppPacing = MIRA_DEFAULT_OPERATING_PACE.appPacing()
    /** Turn G: voice + language captured once at call start, reused for every reconnect in the call. */
    @Volatile private var callVoiceName: String? = null
    @Volatile private var callLanguage: String? = null
    private val callStateStore by lazy { LiveCallStateStore(applicationContext) }
    private val sessionGenerationToken = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var lastBotSpeakingEndTimeMs: Long = 0L
    @Volatile private var isTtsPlaying: Boolean = false
    private val recentModelTranscripts = ArrayDeque<String>()
    private var pendingModelTranscript = ""
    private var lastSavedVoiceText = ""
    private var lastSavedVoiceRole = ""

    private var cachedVoiceName: String = "Aoede"
    private var cachedLanguage: String = "Bangla (Bengali)"

    private lateinit var resumptionStore: SessionResumptionStore

    private val automationBridge by lazy {
        io.github.ryoaonetsuki.mira.automation.MiraAutomationBridge.getInstance(applicationContext)
    }

    private val defaultAutomationPort by lazy {
        ScreenAutomationService(applicationContext)
    }

    internal var automationPortProvider: () -> AutomationPort = {
        defaultAutomationPort
    }
    internal val automationPort: AutomationPort get() = automationPortProvider()
    internal var activeGeminiApiKey: String? = null

    private fun screenPort(): MiraScreenPort? {
        val s = MiraAccessibilityService.instance ?: return cachedScreenPort
        if (cachedScreenService !== s) {
            cachedScreenPort = MiraAndroidScreen(s)
            cachedScreenService = s
        }
        return cachedScreenPort
    }

    internal val appKnowledge by lazy {
        io.github.ryoaonetsuki.mira.automation.AppKnowledge { path ->
            try {
                applicationContext.assets.open(path).bufferedReader().use { it.readText() }
            } catch (_: Throwable) {
                null
            }
        }
    }

    private val userTurnWatchdog by lazy {
        TurnWatchdog(
            scope = serviceScope,
            kind = WatchdogKind.USER_TURN
        ).apply {
            setOnFire { transcript ->
                handleUserTurnWatchdogFired(transcript)
            }
        }
    }

    private val userTurnEscalator by lazy {
        UserTurnEscalator(
            scope = serviceScope,
            timeoutMs = UserTurnEscalator.DEFAULT_ESCALATION_TIMEOUT_MS
        ) { reason ->
            Log.w(TAG, "MIRA_LIVE_VOICE: User-turn second-stage escalation fired ($reason) — requesting reconnect")
            LiveVoiceDiagnostics.recordDropReason("escalation-$reason")
            _liveState.value = LiveState.CONNECTING
            _voiceStage.value = VoiceStage.CONNECTING
            _isReconnecting.value = true
            postCallNotificationIfForeground()
            sessionManager?.requestReconnect(reason)
        }
    }

    private fun noteServerActivity() {
        lastServerActivityElapsedMs = android.os.SystemClock.elapsedRealtime()
        userTurnEscalator.cancel()
    }

    private val postCallWatchdog by lazy {
        TurnWatchdog(
            scope = serviceScope,
            kind = WatchdogKind.POST_FUNCTION_CALL
        ).apply {
            setOnFire { outcome ->
                handlePostCallWatchdogFired(outcome)
            }
        }
    }

    private fun handleUserTurnWatchdogFired(transcript: String) {
        val isConnected = sessionManager?.state?.value == SessionManagerState.CONNECTED
        Log.i(TAG, "MIRA_LIVE_VOICE: User turn watchdog fired (connected=$isConnected): $transcript")
        if (isConnected) {
            Log.i(TAG, "MIRA_LIVE_VOICE: Injecting user turn nudge into same Live session: $transcript")
            sessionManager?.sendUserMessage(transcript)
            // Symptom 3(b): arm second-stage escalation
            userTurnEscalator.arm("no-model-response-after-nudge")
        } else {
            val action = WatchdogActionResolver.resolve(isLiveConnected = false, isUserInitiated = true)
            Log.i(TAG, "MIRA_LIVE_VOICE: Live not connected, action=$action; running direct path for: $transcript")
            serviceScope.launch(Dispatchers.IO) {
                val directAction = MiraAutomationBridge.parseDirectAction(transcript)
                if (directAction != null) {
                    val steps = TurnWatchdog.directActionToSteps(directAction)
                    if (steps.isNotEmpty()) {
                        val skill = Skill(goal = transcript, steps = steps)
                        val result = automationBridge.executeSkill(skill)
                        recordAutomationNotification(
                            title = "Direct Action ${if (result.status == TaskStatus.SUCCESS) "Completed" else "Failed"}",
                            message = "${result.message} ($transcript)"
                        )
                    }
                } else {
                    // Symptom 5(f): near-miss acknowledgement in disconnected direct path
                    if (containsAutomationVerbOrAppNoun(transcript)) {
                        val skillService = SkillMemoryService.getInstance(applicationContext)
                        val match = skillService.findMatch(transcript)
                        if (match != null) {
                            Log.i(TAG, "MIRA_LIVE_VOICE: Direct path near-miss found skill match for: $transcript")
                            val result = automationBridge.executeSkill(match)
                            recordAutomationNotification(
                                title = "Replayed Skill ${if (result.status == TaskStatus.SUCCESS) "Completed" else "Failed"}",
                                message = "${result.message} ($transcript)"
                            )
                        } else {
                            Log.w(TAG, "MIRA_LIVE_VOICE: Command not understood in disconnected direct path: $transcript")
                            recordAutomationNotification(
                                title = "Command Not Understood",
                                message = "Could not understand voice command: \"$transcript\""
                            )
                        }
                    }
                }
            }
        }
    }

    private fun containsAutomationVerbOrAppNoun(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.isEmpty()) return false
        return MiraAutomationBridge.isAutomationIntent(text) ||
            MiraAutomationBridge.extractTargetApp(text) != null ||
            MiraAutomationBridge.parseDirectAction(text) != null
    }

    private fun handlePostCallWatchdogFired(outcome: String) {
        val isConnected = sessionManager?.state?.value == SessionManagerState.CONNECTED
        Log.i(TAG, "MIRA_LIVE_VOICE: Post-call watchdog fired (connected=$isConnected, outcome=$outcome)")
        if (isConnected) {
            val nudge = TurnWatchdog.formatPostCallNudge(outcome)
            Log.i(TAG, "MIRA_LIVE_VOICE: Injecting post-call outcome nudge into same Live session: $nudge")
            sessionManager?.sendUserMessage(nudge)
        }
    }

    private fun recordAutomationNotification(title: String, message: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val db = MiraDatabase.getInstance(applicationContext)
                db.notificationDao().insertNotification(
                    NotificationEntity(
                        title = title,
                        message = message,
                        type = "automation"
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "MIRA_LIVE_VOICE: Could not record notification: ${e.message}")
            }
        }
    }

    private fun extractOutcomeString(response: Value.Object): String {
        val map = response.value
        (map["result"] as? Value.Str)?.value?.let { if (it.isNotBlank()) return it }
        (map["message"] as? Value.Str)?.value?.let { if (it.isNotBlank()) return it }
        (map["error"] as? Value.Str)?.value?.let { if (it.isNotBlank()) return "Error: $it" }
        val success = (map["success"] as? Value.Bool)?.value
        val target = (map["package"] as? Value.Str)?.value ?: (map["target"] as? Value.Str)?.value
        if (success == true) {
            return if (!target.isNullOrBlank()) "Successfully opened $target" else "Action completed successfully"
        } else if (success == false) {
            return if (!target.isNullOrBlank()) "Failed to open $target" else "Action failed"
        }
        val entries = map.entries.mapNotNull { (k, v) ->
            val vStr = (v as? Value.Str)?.value ?: (v as? Value.Bool)?.value?.toString()
            if (vStr != null) "$k: $vStr" else null
        }
        return if (entries.isNotEmpty()) entries.take(2).joinToString(", ") else "Action completed"
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
                } catch (_: Throwable) {}
            },
            cancelNotificationAction = { id ->
                try {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager?.cancel(id)
                } catch (_: Throwable) {}
            }
        )
    }

    val isForeground: Boolean
        get() = notificationController.isForeground

    /**
     * Guarantees terminal ERROR state and complete resource cleanup on every failure path.
     * Turn G: a terminal error is terminal ONLY when the user ended the call
     * or the failure can never succeed (no key / no permission / free-tier
     * limit). Otherwise the call is expected live, so the old session is
     * torn down and a fresh one is auto-started silently on a capped
     * backoff — the UI honestly shows reconnecting, never a dead ERROR.
     */
    private fun handleTerminalError(message: String) {
        sessionGenerationToken.incrementAndGet()
        startVoiceSessionJob?.cancel()
        startVoiceSessionJob = null
        connectingWatchdogJob?.cancel()
        connectingWatchdogJob = null
        livenessWatchdogJob?.cancel()
        livenessWatchdogJob = null

        if (!userRequestedStop && callStateStore.isCallActive() &&
            !CallRestartPolicy.isTerminalFailure(message)
        ) {
            Log.w(TAG, "MIRA_LIVE_VOICE: Terminal error while call expected live — auto-restarting: $message")
            LiveVoiceDiagnostics.recordDropReason("terminal-error: $message")
            try {
                sessionManager?.stopSession()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping session manager on terminal error: ${e.message}")
            }
            sessionManager = null
            activeSessionManager = null
            _errorMessage.value = null
            _lastVoiceDiagnostic.value = "Reconnecting… ($message)"
            _liveState.value = LiveState.CONNECTING
            _voiceStage.value = VoiceStage.CONNECTING
            _isReconnecting.value = true
            _micAmplitude.value = 0f
            postCallNotificationIfForeground()
            scheduleAutoRestart(message)
            return
        }

        _errorMessage.value = message
        _lastVoiceDiagnostic.value = message
        _liveState.value = LiveState.ERROR
        _voiceStage.value = VoiceStage.ERROR
        _micAmplitude.value = 0f
        _isReconnecting.value = false
        callStateStore.setCallActive(false)
        LiveVoiceDiagnostics.updateLastError(message)
        LiveVoiceDiagnostics.updateStage("ERROR")
        dismissNotification()

        // Symptom 1: hide tap numbers on terminal call end
        try {
            automationPort.hideTapNumbers()
            Log.i(TAG, "MIRA_LIVE_VOICE: Tap numbers hidden on call end (terminal error)")
        } catch (e: Exception) {
            Log.w(TAG, "MIRA_LIVE_VOICE: Failed to hide tap numbers on terminal error: ${e.message}")
        }

        try {
            sessionManager?.stopSession()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping session manager on terminal error: ${e.message}")
        }
        sessionManager = null
        activeSessionManager = null
    }

    /** Turn G: schedule a fresh session start on the capped restart backoff. */
    private fun scheduleAutoRestart(reason: String) {
        autoRestartJob?.cancel()
        autoRestartAttempt++
        val delayMs = CallRestartPolicy.delayForAttempt(autoRestartAttempt)
        _reconnectAttempt.value = autoRestartAttempt
        LiveVoiceDiagnostics.recordAutoRestart(reason)
        Log.i(TAG, "MIRA_LIVE_VOICE: Auto-restarting call in ${delayMs}ms (attempt $autoRestartAttempt, reason=$reason)")
        autoRestartJob = serviceScope.launch {
            delay(delayMs)
            if (userRequestedStop || !callStateStore.isCallActive()) {
                Log.i(TAG, "MIRA_LIVE_VOICE: Auto-restart cancelled — call no longer expected live")
                return@launch
            }
            // Let startLiveVoiceSession's duplicate-start guard pass.
            _liveState.value = LiveState.IDLE
            _isReconnecting.value = false
            startLiveVoiceSession(isAutoRestart = true)
        }
    }

    /** Turn G: a mic frame arrived — the capture path and transport are alive. */
    private fun noteAudioFrame() {
        lastAudioFrameElapsedMs = android.os.SystemClock.elapsedRealtime()
    }

    /**
     * Turn G liveness watchdog: while the call is live and unmuted, mic
     * frames arrive ~every 100 ms even in silence. A long gap means the
     * session is CONNECTED-but-deaf (mic died / transport dead) — force a
     * reconnect instead of leaving the user talking to a dead call.
     */
    private fun startLivenessWatchdog() {
        livenessWatchdogJob?.cancel()
        livenessWatchdogJob = serviceScope.launch {
            while (isActive) {
                delay(VoiceLivenessPolicy.CHECK_INTERVAL_MS)
                val live = _liveState.value == LiveState.LISTENING ||
                    _liveState.value == LiveState.SPEAKING
                val now = android.os.SystemClock.elapsedRealtime()

                // Symptom 3(a): check server activity when a user turn is armed
                if (userTurnWatchdog.state == WatchdogState.ARMED &&
                    lastServerActivityElapsedMs > 0L &&
                    (now - lastServerActivityElapsedMs > 15_000L)
                ) {
                    Log.w(TAG, "MIRA_LIVE_VOICE: Liveness watchdog fired — user turn armed but no server activity for >15s; requesting reconnect")
                    LiveVoiceDiagnostics.recordDropReason(
                        "watchdog-no-server-activity: no server activity for >15s while turn armed"
                    )
                    _liveState.value = LiveState.CONNECTING
                    _voiceStage.value = VoiceStage.CONNECTING
                    _isReconnecting.value = true
                    postCallNotificationIfForeground()
                    noteServerActivity()
                    sessionManager?.requestReconnect("no-server-activity")
                } else if (VoiceLivenessPolicy.shouldTriggerRestart(
                        lastAudioFrameElapsedMs,
                        now,
                        _isMuted.value, live, _isReconnecting.value
                    )
                ) {
                    Log.w(TAG, "MIRA_LIVE_VOICE: Liveness watchdog fired — no mic frames for ${VoiceLivenessPolicy.MAX_SILENT_MS}ms while live; requesting reconnect")
                    LiveVoiceDiagnostics.recordDropReason(
                        "watchdog-no-audio-frames: no mic frames for ${VoiceLivenessPolicy.MAX_SILENT_MS}ms"
                    )
                    // Avoid a tight loop while the reconnect is in flight.
                    noteAudioFrame()
                    sessionManager?.requestReconnect("no-audio-frames")
                }
            }
        }
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
                userRequestedStop = false
                startForegroundWithNotification()
                startLiveVoiceSession()
            }
            ACTION_STOP -> {
                userRequestedStop = true
                callStateStore.setCallActive(false)
                GoalToolTracker.reset()
                autoRestartJob?.cancel()
                autoRestartJob = null
                stopLiveVoiceSession()
                dismissNotification()
                stopSelf()
            }
            ACTION_TOGGLE_MUTE -> {
                toggleMute()
            }
            null -> {
                // Turn G START_STICKY restart: the system killed the service
                // while a call was active — resume it with no tap from the
                // user. (onDestroy deliberately does NOT clear the flag.)
                if (callStateStore.isCallActive() && !userRequestedStop) {
                    Log.i(TAG, "MIRA_LIVE_VOICE: Service recreated with an active call — resuming session")
                    LiveVoiceDiagnostics.recordDropReason("service-killed: service recreated, resuming call")
                    callVoiceName = callStateStore.getVoiceName()
                    callLanguage = callStateStore.getLanguage()
                    lastAutomationGoal = callStateStore.getLastGoal()
                    connectsThisCall = 1
                    startForegroundWithNotification()
                    startLiveVoiceSession(isAutoRestart = true)
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun toggleMute() {
        val next = !_isMuted.value
        _isMuted.value = next
        Log.i(TAG, "MIRA_LIVE_VOICE: Mic mute toggled to $next")
        if (!next) noteAudioFrame() // unmuted: frames resume now, not 20s ago
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

    private fun startLiveVoiceSession(isAutoRestart: Boolean = false) {
        // Prevent duplicate sessions from repeated taps
        if (_liveState.value != LiveState.IDLE && _liveState.value != LiveState.ERROR) {
            Log.w(TAG, "MIRA_LIVE_VOICE: Voice button tapped while session active (${_liveState.value}) — ignoring duplicate tap")
            return
        }

        // Turn G: the call is expected live from here until the user taps
        // End. An auto-restart keeps the same call (same connect count and
        // task context); a fresh user start begins a new one.
        callStateStore.setCallActive(true)
        userRequestedStop = false
        autoRestartJob?.cancel()
        autoRestartJob = null
        if (!isAutoRestart) {
            connectsThisCall = 0
            lastAutomationGoal = null
            callVoiceName = null
            callLanguage = null
            callStateStore.setVoiceName(null)
            callStateStore.setLanguage(null)
            callStateStore.setLastGoal(null)
        }

        stopLiveVoiceSessionInternal(hideOverlay = !isAutoRestart)
        startLivenessWatchdog()
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
            // Turn G: a call that can never start must not be resumed later.
            callStateStore.setCallActive(false)
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

        val currentGeneration = sessionGenerationToken.incrementAndGet()
        startVoiceSessionJob?.cancel()
        startVoiceSessionJob = serviceScope.launch {
            val securePrefs = SecurePreferences(applicationContext)
            val resolver = io.github.ryoaonetsuki.mira.backend.ApiKeyResolver(applicationContext)
            val keySource = resolver.resolveKey()
            val geminiKey = when (keySource) {
                is io.github.ryoaonetsuki.mira.backend.KeySource.UserKey -> keySource.key
                else -> securePrefs.getGeminiApiKey() ?: ""
            }

            if (!isActive || currentGeneration != sessionGenerationToken.get()) {
                Log.w(TAG, "MIRA_LIVE_VOICE: Session start cancelled or superseded before key resolution (gen=$currentGeneration, current=${sessionGenerationToken.get()})")
                return@launch
            }

            // Voice button ALWAYS starts Gemini Live.
            if (geminiKey.isNotBlank()) {
                Log.i(TAG, "MIRA_LIVE_VOICE: Starting Native Gemini Live primary voice session (gen=$currentGeneration)")
                startPrimaryGeminiLiveSession(geminiKey, currentGeneration)
            } else if (keySource is io.github.ryoaonetsuki.mira.backend.KeySource.ServerKey) {
                Log.i(TAG, "MIRA_LIVE_VOICE: Server key configured — starting Gemini Live session (gen=$currentGeneration)")
                startPrimaryGeminiLiveSession(resolver.anonKey, currentGeneration)
            } else {
                Log.w(TAG, "MIRA_LIVE_VOICE: No Gemini API key available for voice session")
                // Turn G: a call that can never start must not be resumed later.
                callStateStore.setCallActive(false)
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

    private suspend fun startPrimaryGeminiLiveSession(geminiKey: String, generation: Long) {
        val userPrefs = UserPreferences(applicationContext)
        // Turn G: ONE voice for the whole call. The voice + language are
        // captured ONCE at call start and reused for every reconnect inside
        // the call — a rebuild that re-read Settings mid-call could switch
        // voices (the "another voice" the owner hears). Only a new call
        // (callVoiceName reset in startLiveVoiceSession) may change it.
        val voiceName = callVoiceName ?: userPrefs.voiceNameFlow.first().also { callVoiceName = it }
        val personaName = userPrefs.personaFlow.first()
        val customInstructions = userPrefs.customInstructionsFlow.first()
        val language = callLanguage ?: userPrefs.languageFlow.first().also { callLanguage = it }
        callStateStore.setVoiceName(voiceName)
        callStateStore.setLanguage(language)
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

        val memorySection = try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(2000L) {
                    val rows = db.memoryDao().getAllMemoriesOnce().map {
                        VoiceMemoryPreload.MemoryRow(it.id, it.content, it.category, it.importance, it.lastUsedAt)
                    }
                    val top = VoiceMemoryPreload.rankFactsForPreload(rows)
                    val recent = db.chatDao().getRecentMessages(6).reversed().map { msg ->
                        (if (msg.role == "user") "user" else "model") to msg.content
                    }
                    val section = VoiceMemoryPreload.buildMemorySection(
                        top.map { Triple(it.id, it.text, it.category) },
                        recent
                    )
                    top.forEach {
                        runCatching {
                            db.memoryDao().markMemoryUsed(it.id, System.currentTimeMillis())
                        }
                    }
                    Log.i(TAG, "MIRA_LIVE_VOICE: Voice memory preload: ${top.size} facts, ${recent.size} recent lines")
                    section
                } ?: ""
            }
        } catch (_: Exception) {
            ""
        }

        if (generation != sessionGenerationToken.get()) {
            Log.w(TAG, "MIRA_LIVE_VOICE: Session start superseded before manager init (gen=$generation, current=${sessionGenerationToken.get()})")
            return
        }

        val personaType = if (personaName.equals("Orion", ignoreCase = true)) {
            io.github.ryoaonetsuki.mira.data.model.PersonaType.ORION
        } else {
            io.github.ryoaonetsuki.mira.data.model.PersonaType.MIRA
        }

        activeGeminiApiKey = geminiKey

        val dateLine = currentDateTimeLine(language)

        val appKnowledgeSummary = runCatching {
            appKnowledge.summaryForPrompt()
        }.getOrNull().orEmpty()

        val fullSystemPrompt = LivePromptBuilder.buildLiveSystemPrompt(
            language = language,
            personaType = personaType,
            userName = userName,
            assistantName = assistantName,
            secretMode = secretMode,
            dateLine = dateLine,
            customInstructions = customInstructions,
            activeRules = activeRules,
            memorySection = memorySection,
            appKnowledgeSection = appKnowledgeSummary
        )

        val bcp47Language = LanguageUtils.mapLanguageToBcp47(language)

        Log.i(TAG, "MIRA_LIVE_VOICE: Initializing GeminiSessionManager with voice=$voiceName, language=$bcp47Language, echoGuard=$echoGuard (gen=$generation, resumedVoice=${callVoiceName != null})")

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

        if (generation != sessionGenerationToken.get()) {
            Log.w(TAG, "MIRA_LIVE_VOICE: Session start cancelled right after manager creation (gen=$generation, current=${sessionGenerationToken.get()})")
            try {
                manager.stopSession()
            } catch (_: Throwable) {}
            return
        }

        sessionManager = manager
        activeSessionManager = manager

        LiveVoiceDiagnostics.updateActiveRoute("DIRECT")
        LiveVoiceDiagnostics.updateStage("CONNECTING")

        // Connect manager.audioLevel to _micAmplitude (the orb driver)
        serviceScope.launch {
            manager.audioLevel.collect { level ->
                noteAudioFrame() // mic frames flowing = capture path alive
                if (!_isMuted.value && (_liveState.value == LiveState.LISTENING || _liveState.value == LiveState.SPEAKING)) {
                    _micAmplitude.value = level
                } else {
                    _micAmplitude.value = 0f
                }
            }
        }

        if (generation != sessionGenerationToken.get() || currentCoroutineContext()[Job]?.isActive == false) {
            Log.w(TAG, "MIRA_LIVE_VOICE: Session start cancelled before startSession() (gen=$generation, current=${sessionGenerationToken.get()})")
            try {
                manager.stopSession()
            } catch (_: Throwable) {}
            sessionManager = null
            activeSessionManager = null
            return
        }

        manager.startSession()
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
                        // Turn G: a connect that recovered the call resets
                        // the restart backoff; the liveness clock restarts now.
                        autoRestartAttempt = 0
                        _reconnectAttempt.value = 0
                        noteAudioFrame()
                        noteServerActivity()
                        connectsThisCall++
                        Log.i(TAG, "MIRA_LIVE_VOICE: Connected (connect #$connectsThisCall of this call, voice=$cachedVoiceName)")

                        _liveState.value = LiveState.LISTENING
                        _voiceStage.value = VoiceStage.LISTENING

                        // Turn G: "Voice Session Started" on EVERY connect
                        // made each reconnect feel like a fresh call. Announce
                        // only the first connect of the call; reconnects are
                        // silent continuity, not a new call.
                        if (connectsThisCall == 1) {
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
                noteAudioFrame()
                if (!_isMuted.value && (_liveState.value == LiveState.LISTENING || _liveState.value == LiveState.SPEAKING)) {
                    _micAmplitude.value = level
                } else {
                    _micAmplitude.value = 0f
                }
            }

            override fun onBotSpeakingChanged(isSpeaking: Boolean) {
                if (isSpeaking) {
                    noteServerActivity()
                    userTurnWatchdog.cancel()
                    postCallWatchdog.cancel()
                }
                Log.d(TAG, "MIRA_LIVE_VOICE: onBotSpeakingChanged: $isSpeaking")
                if (isSpeaking && _liveState.value != LiveState.ERROR && _liveState.value != LiveState.IDLE) {
                    _liveState.value = LiveState.SPEAKING
                    _voiceStage.value = VoiceStage.SPEAKING
                } else if (!isSpeaking && _liveState.value == LiveState.SPEAKING) {
                    lastBotSpeakingEndTimeMs = android.os.SystemClock.elapsedRealtime()
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
                onUserTranscript(text, isFinal = true)
            }

            override fun onUserTranscript(text: String, isFinal: Boolean) {
                noteAudioFrame()
                Log.i(TAG, "MIRA_LIVE_VOICE: User transcript (final=$isFinal): $text")
                val now = android.os.SystemClock.elapsedRealtime()
                val isSpeakingOrDraining = _liveState.value == LiveState.SPEAKING ||
                    isTtsPlaying ||
                    (now - lastBotSpeakingEndTimeMs < 600L)

                if (isSpeakingOrDraining && isEchoOfModelSpeech(text)) {
                    Log.i(TAG, "MIRA_LIVE_VOICE: Dropping user transcript during/shortly after model speech matching echo: $text")
                    return
                }

                // Proactive bug fix: do not drop user transcripts as echo when the bot is silent and it is an intentional automation command
                val isIntentionalCommand = MiraAutomationBridge.parseDirectAction(text) != null ||
                    MiraAutomationBridge.isAutomationIntent(text)
                if (!isIntentionalCommand && isEchoOfModelSpeech(text)) {
                    Log.i(TAG, "MIRA_LIVE_VOICE: Dropping user transcript matching recent model speech: $text")
                    return
                }

                _userTranscript.value = text
                if (_liveState.value != LiveState.SPEAKING && _liveState.value != LiveState.ERROR) {
                    _voiceStage.value = VoiceStage.RECOGNISING
                }
                if (isFinal) {
                    flushPendingModelTranscript()
                    saveVoiceTranscript("user", text)
                    handleUserVoiceTurn(text)
                }
            }

            override fun onTurnComplete() {}

            override fun onModelTranscript(text: String) {
                noteServerActivity()
                userTurnWatchdog.cancel()
                postCallWatchdog.cancel()
                noteAudioFrame()
                Log.i(TAG, "MIRA_LIVE_VOICE: Model transcript: $text")
                val sanitized = io.github.ryoaonetsuki.mira.identity.MiraOutputFilter.sanitizeForVoice(text)
                if (sanitized != text) {
                    Log.w(TAG, "MIRA_LIVE_VOICE: Model leaked forbidden token — sanitized for transcript display.")
                }
                // Record/display only; the Live session speaks the model's audio directly.
                _modelTranscript.value = sanitized
                recordRecentModelTranscript(sanitized)
                pendingModelTranscript = when {
                    pendingModelTranscript.isBlank() -> sanitized
                    sanitized.startsWith(pendingModelTranscript) -> sanitized
                    pendingModelTranscript.startsWith(sanitized) -> pendingModelTranscript
                    else -> (pendingModelTranscript + " " + sanitized).trim()
                }
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
                LiveVoiceDiagnostics.recordDropReason("session-ended: $reason")
                flushPendingModelTranscript()
                // Symptom 3(e): trigger auto-restart instead of silently staying at LISTENING
                if (!userRequestedStop && callStateStore.isCallActive()) {
                    Log.w(TAG, "MIRA_LIVE_VOICE: Session ended unexpectedly while call active ($reason) — auto-restarting")
                    _liveState.value = LiveState.CONNECTING
                    _voiceStage.value = VoiceStage.CONNECTING
                    _isReconnecting.value = true
                    _micAmplitude.value = 0f
                    postCallNotificationIfForeground()
                    scheduleAutoRestart("session-ended: $reason")
                }
            }

            override fun onSessionResumed(resumed: Boolean) {
                Log.i(TAG, "MIRA_LIVE_VOICE: Session (re)connect resumed=$resumed (connect #$connectsThisCall of this call, voice=$cachedVoiceName)")
                if (resumed) return
                if (connectsThisCall <= 1) return // first connect: nothing to re-seed
                val goal = lastAutomationGoal
                val lastModel = _modelTranscript.value
                if (goal.isNullOrBlank() && lastModel.isBlank()) return
                val note = buildString {
                    append("[Context: the previous voice session dropped and this is a fresh session in the same call. ")
                    if (!goal.isNullOrBlank()) append("The user was working on: \"$goal\". ")
                    append("Continue naturally; a follow-up from the user refers to that task. Do not announce this note.]")
                }
                sessionManager?.sendUserMessage(note)
            }

            override fun onFunctionCall(id: String, name: String, args: Value.Array, rawArgs: Map<String, String>) {
                noteServerActivity()
                userTurnWatchdog.cancel()
                Log.i(TAG, "MIRA_LIVE_VOICE: onFunctionCall: id=$id name=$name rawArgs=$rawArgs")
                handleFunctionCall(id, name, args, rawArgs, emptyMap())
            }

            override fun onFunctionCall(
                id: String,
                name: String,
                args: Value.Array,
                rawArgs: Map<String, String>,
                typedArgs: Map<String, Any?>
            ) {
                noteServerActivity()
                userTurnWatchdog.cancel()
                Log.i(TAG, "MIRA_LIVE_VOICE: onFunctionCall: id=$id name=$name rawArgs=$rawArgs typedArgs=$typedArgs")
                handleFunctionCall(id, name, args, rawArgs, typedArgs)
            }
        }
    }

    private fun handleFunctionCall(
        id: String,
        name: String,
        args: Value.Array,
        rawArgs: Map<String, String>,
        typedArgs: Map<String, Any?> = emptyMap()
    ) {
        userTurnWatchdog.cancel()
        _voiceStage.value = VoiceStage.THINKING
        _statusText.value = "Running: $name"
        postCallNotificationIfForeground()
        serviceScope.launch(Dispatchers.IO) {
            functionCallMutex.withLock {
                try {
                    val responseObj = executeFunctionCall(
                        automationPort = automationPort,
                        context = applicationContext,
                        name = name,
                        rawArgs = rawArgs,
                        typedArgs = typedArgs,
                        targetApp = currentAutomationTarget,
                        goal = lastAutomationGoal,
                        appKnowledge = appKnowledge
                    )
                    sessionManager?.sendFunctionResponse(id, name, responseObj)
                    val outcome = extractOutcomeString(responseObj)
                    postCallWatchdog.arm(outcome, TurnWatchdog.DEFAULT_POST_CALL_TIMEOUT_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "MIRA_LIVE_VOICE: Error handling function call $name: ${e.message}", e)
                    val errObj = Value.Object(
                        "error" to Value.Str(e.message ?: "Function execution failed")
                    )
                    sessionManager?.sendFunctionResponse(id, name, errObj)
                    postCallWatchdog.arm("Function failed: ${e.message}", TurnWatchdog.DEFAULT_POST_CALL_TIMEOUT_MS)
                } finally {
                    _statusText.value = ""
                    postCallNotificationIfForeground()
                }
            }
        }
    }

    internal suspend fun executeFunctionCall(
        automationPort: AutomationPort,
        context: Context?,
        name: String,
        rawArgs: Map<String, String>,
        typedArgs: Map<String, Any?> = emptyMap(),
        pollTimeoutMs: Long = 4000L,
        pollIntervalMs: Long = 200L,
        settleDelayMs: Long = 100L,
        targetApp: String? = null,
        goal: String? = null,
        skillsService: SkillMemoryService? = null,
        appKnowledge: io.github.ryoaonetsuki.mira.automation.AppKnowledge? = null
    ): Value.Object = io.github.ryoaonetsuki.mira.service.executeFunctionCall(
        automationPort = automationPort,
        context = context,
        name = name,
        rawArgs = rawArgs,
        typedArgs = typedArgs,
        pollTimeoutMs = pollTimeoutMs,
        pollIntervalMs = pollIntervalMs,
        settleDelayMs = settleDelayMs,
        targetApp = targetApp ?: currentAutomationTarget,
        goal = goal ?: lastAutomationGoal,
        skillsService = skillsService,
        appKnowledge = appKnowledge ?: this@MicService.appKnowledge
    ).also { response ->
        val success = (response.value["success"] as? Value.Bool)?.value == true
        if (success && name.equals("open_app", ignoreCase = true)) {
            val pkg = (response.value["package"] as? Value.Str)?.value
            if (!pkg.isNullOrBlank()) {
                currentAutomationTarget = pkg
            }
        }
    }

    private fun handleUserVoiceTurn(text: String) {
        val cleanText = text.trim()
        if (cleanText.isEmpty() || isEchoOfModelSpeech(cleanText)) {
            Log.i(TAG, "MIRA_LIVE_VOICE: Ignoring turn (empty or echo of own speech): $cleanText")
            return
        }

        // One routing decision (pure, unit-tested): echo / stop / Live answers by itself.
        val recentSpeech = synchronized(recentModelTranscripts) { recentModelTranscripts.toList() } +
            listOfNotNull(_modelTranscript.value.takeIf { it.isNotBlank() })
        when (io.github.ryoaonetsuki.mira.voice.VoiceTurnRouter.route(cleanText, recentSpeech, automationBridge.isRunning)) {
            io.github.ryoaonetsuki.mira.voice.VoiceTurnRoute.IGNORE_ECHO -> {
                Log.i(TAG, "MIRA_LIVE_VOICE: Ignoring turn (empty or echo of own speech): $cleanText")
                return
            }
            io.github.ryoaonetsuki.mira.voice.VoiceTurnRoute.STOP_AUTOMATION -> {
                userTurnWatchdog.cancel()
                postCallWatchdog.cancel()
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
            io.github.ryoaonetsuki.mira.voice.VoiceTurnRoute.LIVE_ANSWERS -> {
                userTurnWatchdog.arm(cleanText, TurnWatchdog.DEFAULT_USER_TURN_TIMEOUT_MS)
            }
        }
    }

    private fun stopLiveVoiceSession() {
        Log.i(TAG, "MIRA_LIVE_VOICE: Stopping voice session (user action)")
        stopLiveVoiceSessionInternal()
        _liveState.value = LiveState.IDLE
        _voiceStage.value = VoiceStage.IDLE
    }

    private fun stopLiveVoiceSessionInternal(hideOverlay: Boolean = true) {
        userTurnWatchdog.cancel()
        postCallWatchdog.cancel()
        userTurnEscalator.cancel()
        sessionGenerationToken.incrementAndGet()
        automationBridge.cancel()
        if (hideOverlay) {
            try {
                automationPort.hideTapNumbers()
                Log.i(TAG, "MIRA_LIVE_VOICE: Tap numbers hidden on call end")
            } catch (e: Exception) {
                Log.w(TAG, "MIRA_LIVE_VOICE: Error hiding tap numbers on call end: ${e.message}")
            }
        }
        _statusText.value = ""
        startVoiceSessionJob?.cancel()
        startVoiceSessionJob = null
        autoRestartJob?.cancel()
        autoRestartJob = null
        livenessWatchdogJob?.cancel()
        livenessWatchdogJob = null
        isTtsPlaying = false
        connectingWatchdogJob?.cancel()
        connectingWatchdogJob = null
        currentAutomationTarget = null
        GoalToolTracker.reset()

        try {
            sessionManager?.stopSession()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping session manager on user stop: ${e.message}")
        }
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

    private fun recordRecentModelTranscript(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        synchronized(recentModelTranscripts) {
            recentModelTranscripts.addLast(clean)
            while (recentModelTranscripts.size > 5) {
                recentModelTranscripts.removeFirst()
            }
        }
    }

    private fun saveVoiceTranscript(role: String, text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        if (clean == lastSavedVoiceText && role == lastSavedVoiceRole) return
        lastSavedVoiceText = clean
        lastSavedVoiceRole = role
        serviceScope.launch(Dispatchers.IO) {
            try {
                val db = MiraDatabase.getInstance(applicationContext)
                db.chatDao().insertMessageEnsuringConversation(
                    ChatMessageEntity(conversationId = "voice", role = role, content = clean),
                    defaultTitle = "Voice"
                )
                db.chatDao().getConversationById("voice")?.let {
                    db.chatDao().updateConversation(it.copy(updatedAt = System.currentTimeMillis()))
                }
            } catch (_: Exception) {}
        }
    }

    private fun flushPendingModelTranscript() {
        val text = pendingModelTranscript
        pendingModelTranscript = ""
        if (text.isNotBlank()) {
            saveVoiceTranscript("model", text)
        }
    }

    private fun isEchoOfModelSpeech(userTranscript: String): Boolean {
        val lastModel = _modelTranscript.value
        if (lastModel.isNotBlank() && matchesEcho(userTranscript, lastModel)) {
            return true
        }
        synchronized(recentModelTranscripts) {
            return recentModelTranscripts.any { matchesEcho(userTranscript, it) }
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
        @Volatile var lastExecutedStep: TaskStep? = null
        private const val CHANNEL_ID = CallNotificationFactory.CHANNEL_ID
        private const val MIC_CHANNEL_ID = "mira_mic_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "io.github.ryoaonetsuki.mira.action.START_VOICE"
        const val ACTION_STOP = "io.github.ryoaonetsuki.mira.action.STOP_VOICE"
        const val ACTION_TOGGLE_MUTE = "io.github.ryoaonetsuki.mira.action.TOGGLE_MUTE"

        /**
         * Returns true if [userText] is determined to be an echo of [modelText]
         * (exact match, high token overlap, or substantial substring).
         */
        fun matchesEcho(userText: String, modelText: String): Boolean =
            VoiceEchoDetector.matchesEcho(userText, modelText)

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

        private val _statusText = MutableStateFlow("")
        val statusText: StateFlow<String> = _statusText.asStateFlow()

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

internal suspend fun getEffectiveGeminiApiKey(context: Context?): String? {
    if (context == null) return null
    return try {
        val resolver = io.github.ryoaonetsuki.mira.backend.ApiKeyResolver(context)
        when (val keySource = resolver.resolveKey()) {
            is io.github.ryoaonetsuki.mira.backend.KeySource.UserKey -> keySource.key
            else -> SecurePreferences(context).getGeminiApiKey()
        }
    } catch (_: Throwable) {
        try {
            SecurePreferences(context).getGeminiApiKey()
        } catch (_: Throwable) {
            null
        }
    }
}

internal data class VisionResolution(
    val coordinates: Pair<Int, Int>?,
    val failureReason: String? = null
)

internal suspend fun resolveVisionCoordinates(
    context: Context?,
    target: String,
    nodes: List<ScreenNode>,
    automationPort: AutomationPort
): VisionResolution {
    val TAG = "MiraVisionLocator"
    val apiKey = (context as? MicService)?.activeGeminiApiKey
        ?: getEffectiveGeminiApiKey(context)
    if (apiKey.isNullOrBlank()) {
        Log.w(TAG, "Vision locator failed: Gemini API key is missing or blank")
        return VisionResolution(null, "Gemini API key is missing in Settings")
    }

    val jpeg = automationPort.takeScreenshotJpeg(720)
    if (jpeg == null || jpeg.isEmpty()) {
        Log.w(TAG, "Vision locator failed: screenshot capture returned null (API < 30, timeout, debounce, or secure window)")
        return VisionResolution(null, "Screenshot capture failed (screen may be protected or service timed out)")
    }

    val dimensions = automationPort.getScreenDimensions()
        ?: context?.resources?.displayMetrics?.let { Pair(it.widthPixels, it.heightPixels) }
        ?: Pair(1080, 2400)
    val screenWidth = dimensions.first
    val screenHeight = dimensions.second

    val client = io.github.ryoaonetsuki.mira.network.GeminiRestClient()
    val nodeText = automationPort.formatNodes(nodes)
    return try {
        val result = client.locateElementWithVision(
            apiKey = apiKey,
            imageBytes = jpeg,
            targetDescription = target,
            nodeDump = nodeText,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        if (result.isSuccess) {
            val coords = result.getOrNull()
            if (coords != null) {
                Log.i(TAG, "Vision locator found '$target' at ($screenWidth x $screenHeight): $coords")
                VisionResolution(coords, null)
            } else {
                VisionResolution(null, "Vision could not find coordinates for '$target'")
            }
        } else {
            val err = result.exceptionOrNull()?.message ?: "Vision call failed"
            Log.w(TAG, "Vision locator failed for '$target': $err")
            VisionResolution(null, err)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Vision locator call threw for '$target': ${e.message}", e)
        VisionResolution(null, e.message ?: "Vision call threw exception")
    }
}

private suspend fun buildActionResult(
    automationPort: AutomationPort,
    outcome: String,
    success: Boolean,
    postPackage: String,
    postNodes: List<ScreenNode>,
    isStepOpenApp: Boolean,
    descriptionContext: String,
    appKnowledge: io.github.ryoaonetsuki.mira.automation.AppKnowledge? = null,
    preSignature: Int? = null,
    postSignature: Int? = null,
    target: String? = null
): Value.Object {
    var screenText = if (postNodes.isNotEmpty()) {
        automationPort.getCompressedScreenDescription(postNodes, descriptionContext)
    } else {
        "Screen has no accessible UI elements (empty node tree)"
    }

    var appNote: String? = null
    if (appKnowledge != null && postPackage.isNotBlank()) {
        appNote = appKnowledge.noteForTarget(postPackage) ?: appKnowledge.searchFlowNote()
    }
    if (appNote != null) {
        screenText = "$screenText\n[App UI Knowledge: $appNote]"
    }

    val screenSignatureChanged = (preSignature != null && postSignature != null && preSignature != postSignature)
    val targetFoundInNodes = if (!target.isNullOrBlank()) {
        val normTarget = VisionFirstPerception.normalizeTarget(target)
        postNodes.any {
            it.text.contains(normTarget, ignoreCase = true) ||
                it.contentDescription.contains(normTarget, ignoreCase = true) ||
                (it.viewId.isNotBlank() && (it.viewId.equals(normTarget, ignoreCase = true) || it.viewId.endsWith(":id/$normTarget", ignoreCase = true)))
        }
    } else false

    val attachScreenshot = TaskExecutor.shouldAttachScreenshot(
        nodes = postNodes,
        lastStepHadNoEffect = !success,
        lastStepWasOpenApp = isStepOpenApp,
        screenSignatureChanged = screenSignatureChanged,
        targetFoundInNodes = targetFoundInNodes
    )

    var screenshotBase64: String? = null
    if (attachScreenshot) {
        try {
            val jpegBytes = automationPort.takeScreenshotJpeg(720)
            if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                screenshotBase64 = java.util.Base64.getEncoder().encodeToString(jpegBytes)
            }
        } catch (e: Exception) {
            Log.w("MicService", "Failed to capture screenshot for action result: ${e.message}")
        }
    }

    val resultMap = mutableMapOf<String, Value>(
        "result" to Value.Str(outcome),
        "success" to Value.Bool(success),
        "package" to Value.Str(postPackage),
        "screen" to Value.Str(screenText),
        "screen_text" to Value.Str(screenText)
    )
    if (screenshotBase64 != null) {
        resultMap["screenshot"] = Value.Str(screenshotBase64)
    }
    if (appNote != null) {
        resultMap["app_knowledge"] = Value.Str(appNote)
    }
    return Value.Object(resultMap)
}

private fun isWrongPackageChange(
    action: String,
    prePackage: String,
    postPackage: String,
    target: String?,
    goalText: String?
): Boolean {
    if (postPackage == prePackage || postPackage.isEmpty()) return false
    if (action == TaskStep.ACTION_PRESS_HOME && TaskExecutor.isLauncherPackage(postPackage)) return false
    if (action == TaskStep.ACTION_PRESS_BACK && TaskExecutor.isLauncherPackage(postPackage)) return false

    val isWeb = TaskExecutor.isWebGoal(goalText) || (target != null && TaskExecutor.isBrowserPackage(target))
    if (isWeb && TaskExecutor.isBrowserPackage(postPackage)) return false

    if (target != null) {
        return !TaskExecutor.matchesTarget(target, postPackage)
    }
    return false
}

internal fun isOffTargetForAction(
    action: String,
    currentPackage: String,
    target: String?,
    goalText: String?,
    automationPort: AutomationPort? = null
): Boolean {
    if (action != TaskStep.ACTION_CLICK_TEXT && action != TaskStep.ACTION_TYPE_TEXT && action != "tap_number" && action != TaskStep.ACTION_CLICK_NUMBER) {
        return false
    }
    val effectiveTarget = target ?: goalText?.let { MiraAutomationBridge.extractTargetApp(it) }
    if (effectiveTarget.isNullOrBlank()) {
        val isWeb = TaskExecutor.isWebGoal(goalText)
        if (isWeb) {
            return !TaskExecutor.isBrowserPackage(currentPackage)
        }
        return false
    }
    val isWeb = TaskExecutor.isWebGoal(goalText) || TaskExecutor.isBrowserPackage(effectiveTarget)
    if (isWeb && TaskExecutor.isBrowserPackage(currentPackage)) {
        return false
    }
    if (TaskExecutor.matchesTarget(effectiveTarget, currentPackage)) {
        return false
    }
    // Symptom 2(b)(ii): fallback to label-based app verification
    if (automationPort?.isTargetPackage(effectiveTarget, currentPackage) == true) {
        return false
    }
    return true
}

internal suspend fun executeFunctionCall(
    automationPort: AutomationPort,
    context: Context?,
    name: String,
    rawArgs: Map<String, String>,
    typedArgs: Map<String, Any?> = emptyMap(),
    pollTimeoutMs: Long = 4000L,
    pollIntervalMs: Long = 200L,
    settleDelayMs: Long = 100L,
    targetApp: String? = null,
    goal: String? = null,
    skillsService: SkillMemoryService? = null,
    appKnowledge: io.github.ryoaonetsuki.mira.automation.AppKnowledge? = null
): Value.Object {
    val startTimeMs = System.currentTimeMillis()
    val argsSummary = (typedArgs.ifEmpty { rawArgs }).toString()

    val resolvedGoal = goal
        ?: (typedArgs["goal"]?.toString() ?: rawArgs["goal"])
    val resolvedTarget = targetApp
        ?: (typedArgs["targetApp"]?.toString() ?: rawArgs["targetApp"] ?: typedArgs["target"]?.toString() ?: rawArgs["target"])
        ?: resolvedGoal?.let { MiraAutomationBridge.extractTargetApp(it) }

    // Milestone 6: Per-goal tool-call cap (30) and 120s task timeout tracking
    val trackerCheck = GoalToolTracker.onToolCall(resolvedGoal)
    val currentGoalCount = GoalToolTracker.callCount
    LiveVoiceDiagnostics.recordToolCall(name, argsSummary, startTimeMs, goalCallCount = currentGoalCount)

    if (trackerCheck is GoalToolTracker.CheckResult.CapExceeded) {
        val limitMsg = "Tool call limit reached (${trackerCheck.maxCalls} calls for this goal). Please summarize what was done and stop calling tools for this goal."
        val postPackage = automationPort.getCurrentPackage().orEmpty()
        val postNodes = automationPort.dumpScreen()
        val response = buildActionResult(
            automationPort = automationPort,
            outcome = limitMsg,
            success = false,
            postPackage = postPackage,
            postNodes = postNodes,
            isStepOpenApp = false,
            descriptionContext = resolvedGoal.orEmpty(),
            appKnowledge = appKnowledge
        )
        val latencyMs = System.currentTimeMillis() - startTimeMs
        LiveVoiceDiagnostics.recordToolResult(name, latencyMs, false)
        return response
    }

    if (trackerCheck is GoalToolTracker.CheckResult.TimeoutExceeded) {
        val limitMsg = "Task duration timeout reached (120s limit for this goal). Please summarize what was done and stop calling tools for this goal."
        val postPackage = automationPort.getCurrentPackage().orEmpty()
        val postNodes = automationPort.dumpScreen()
        val response = buildActionResult(
            automationPort = automationPort,
            outcome = limitMsg,
            success = false,
            postPackage = postPackage,
            postNodes = postNodes,
            isStepOpenApp = false,
            descriptionContext = resolvedGoal.orEmpty(),
            appKnowledge = appKnowledge
        )
        val latencyMs = System.currentTimeMillis() - startTimeMs
        LiveVoiceDiagnostics.recordToolResult(name, latencyMs, false)
        return response
    }

    try {
        val response = executeFunctionCallInternal(
            automationPort = automationPort,
            context = context,
            name = name,
            rawArgs = rawArgs,
            typedArgs = typedArgs,
            pollTimeoutMs = pollTimeoutMs,
            pollIntervalMs = pollIntervalMs,
            settleDelayMs = settleDelayMs,
            resolvedTarget = resolvedTarget,
            resolvedGoal = resolvedGoal,
            skillsService = skillsService,
            appKnowledge = appKnowledge
        )
        val latencyMs = System.currentTimeMillis() - startTimeMs
        val success = (response.value["success"] as? Value.Bool)?.value
            ?: !response.value.containsKey("error")
        LiveVoiceDiagnostics.recordToolResult(name, latencyMs, success)
        return response
    } catch (e: Throwable) {
        val latencyMs = System.currentTimeMillis() - startTimeMs
        LiveVoiceDiagnostics.recordToolError(name, e.message ?: "Function execution failed")
        LiveVoiceDiagnostics.recordToolResult(name, latencyMs, false)
        throw e
    }
}

private suspend fun executeFunctionCallInternal(
    automationPort: AutomationPort,
    context: Context?,
    name: String,
    rawArgs: Map<String, String>,
    typedArgs: Map<String, Any?> = emptyMap(),
    pollTimeoutMs: Long = 4000L,
    pollIntervalMs: Long = 200L,
    settleDelayMs: Long = 100L,
    resolvedTarget: String? = null,
    resolvedGoal: String? = null,
    skillsService: SkillMemoryService? = null,
    appKnowledge: io.github.ryoaonetsuki.mira.automation.AppKnowledge? = null
): Value.Object {
    val normName = name.lowercase(Locale.ROOT)

    val skills = skillsService ?: context?.let {
        try {
            SkillMemoryService.getInstance(it)
        } catch (_: Exception) {
            null
        }
    }

    // Phone-action tool findMatch check: when a phone-action tool (open_app, click_text, click_at, type_text, scroll, tap_number, show_numbers, hide_numbers)
    // is invoked and SkillMemoryService.findMatch matches the current goal, replay the skill directly
    // instead of executing the single step, and return the replay result as the tool result.
    val isPhoneAction = normName in setOf("open_app", "click_text", "click_at", "type_text", "scroll", "tap_number", "show_numbers", "hide_numbers")
    if (isPhoneAction && !resolvedGoal.isNullOrBlank() && skills != null) {
        val matchedSkill = skills.findMatch(resolvedGoal)
        if (matchedSkill != null) {
            val executor = TaskExecutor(
                automation = automationPort,
                skills = skills,
                stepDelayMs = if (pollTimeoutMs == 0L) 0L else 600L,
                apiKeyProvider = { (context as? MicService)?.activeGeminiApiKey ?: getEffectiveGeminiApiKey(context).orEmpty() },
                visionLocator = { target, currentNodes -> resolveVisionCoordinates(context, target, currentNodes, automationPort).coordinates },
                appKnowledge = appKnowledge
            )
            val replayResult = executor.replaySkillDirect(matchedSkill)
            val postPackage = automationPort.getCurrentPackage().orEmpty()
            val postNodes = automationPort.dumpScreen()
            return buildActionResult(
                automationPort = automationPort,
                outcome = replayResult.message,
                success = replayResult.status == TaskStatus.SUCCESS,
                postPackage = postPackage,
                postNodes = postNodes,
                isStepOpenApp = matchedSkill.steps.any { it.action == TaskStep.ACTION_OPEN_APP },
                descriptionContext = matchedSkill.goal,
                appKnowledge = appKnowledge
            )
        }
    }

    when (normName) {
        "open_app" -> {
            val appTarget = (typedArgs["app"]?.toString() ?: rawArgs["app"] ?: rawArgs["package"] ?: rawArgs["name"] ?: "").trim()
            if (appTarget.isEmpty()) {
                return Value.Object("error" to Value.Str("Missing app argument"))
            }

            // Direct fast path grammar: resolve app target
            val directAction = MiraAutomationBridge.parseDirectAction(appTarget)
                ?: MiraAutomationBridge.parseDirectAction("open $appTarget")
            val resolvedApp = if (directAction is MiraAutomationBridge.DirectAction.OpenApp) {
                directAction.target
            } else {
                MiraAutomationBridge.extractTargetApp(appTarget) ?: appTarget
            }

            val prePackage = automationPort.getCurrentPackage().orEmpty()
            val preNodes = automationPort.dumpScreen()
            val preSignature = TaskExecutor.computeNodeSignature(preNodes)
            delay(appPacing.waitBefore(System.currentTimeMillis()))
            automationPort.openApp(resolvedApp)

            // Poll for target app foreground (fix #3 form: wait for foreground package before returning screen)
            val step = TaskStep(action = TaskStep.ACTION_OPEN_APP, params = mapOf("app" to resolvedApp))
            MicService.lastExecutedStep = step
            val deadline = System.currentTimeMillis() + pollTimeoutMs
            var currentPackage = automationPort.getCurrentPackage().orEmpty()
            while (System.currentTimeMillis() < deadline) {
                if (TaskExecutor.openAppTargetReached(step, prePackage, currentPackage, automationPort)) break
                delay(pollIntervalMs)
                currentPackage = automationPort.getCurrentPackage().orEmpty()
            }
            if (settleDelayMs > 0L) {
                delay(settleDelayMs)
            }

            val postPackage = automationPort.getCurrentPackage().orEmpty()
            val targetReached = TaskExecutor.openAppTargetReached(step, prePackage, postPackage, automationPort)
            val postNodes = automationPort.dumpScreen()
            val postSignature = TaskExecutor.computeNodeSignature(postNodes)
            appPacing.noteAction(postPackage, System.currentTimeMillis(), newScreen = postPackage != prePackage)

            val outcome = if (targetReached) {
                "Successfully opened $resolvedApp (package: $postPackage)"
            } else {
                "Dispatched open for $resolvedApp (current package: $postPackage)"
            }

            return buildActionResult(
                automationPort = automationPort,
                outcome = outcome,
                success = targetReached,
                postPackage = postPackage,
                postNodes = postNodes,
                isStepOpenApp = true,
                descriptionContext = "Opened $resolvedApp",
                appKnowledge = appKnowledge,
                preSignature = preSignature,
                postSignature = postSignature,
                target = resolvedApp
            )
        }

        "click_text" -> {
            val text = (typedArgs["text"]?.toString() ?: rawArgs["text"] ?: "").trim()
            if (text.isEmpty()) {
                return Value.Object("error" to Value.Str("Missing text argument for click_text"))
            }

            val prePackage = automationPort.getCurrentPackage().orEmpty()
            val serviceInstance = MiraAccessibilityService.instance
            val beforeFrame = if (serviceInstance != null) MikaReliableActions.captureFrameGrid(serviceInstance) else null

            // Milestone 6 Off-target refusal guard: refuse BEFORE executing click_text
            if (isOffTargetForAction("click_text", prePackage, resolvedTarget, resolvedGoal, automationPort)) {
                val postNodes = automationPort.dumpScreen()
                val targetName = resolvedTarget ?: resolvedGoal?.let { MiraAutomationBridge.extractTargetApp(it) } ?: "target app"
                val outcome = "Current screen package '$prePackage' is not on the target app '$targetName'. You must call open_app first before clicking."
                return buildActionResult(
                    automationPort = automationPort,
                    outcome = outcome,
                    success = false,
                    postPackage = prePackage,
                    postNodes = postNodes,
                    isStepOpenApp = false,
                    descriptionContext = resolvedGoal.orEmpty(),
                    appKnowledge = appKnowledge
                )
            }

            val perceptionHelper = VisionFirstPerception(
                automationPort = automationPort,
                apiKeyProvider = { (context as? MicService)?.activeGeminiApiKey ?: getEffectiveGeminiApiKey(context).orEmpty() },
                visionDescribe = { key, bytes, dump, w, h, g ->
                    GeminiRestClient().describeScreenWithVision(key, bytes, dump, w, h, g)
                },
                visionLocator = { target, currentNodes ->
                    resolveVisionCoordinates(context, target, currentNodes, automationPort).coordinates
                }
            )

            // Step 1: Perceive fresh
            var perception = perceptionHelper.perceive(goal = resolvedGoal.orEmpty(), target = text)
            val preSignature = perception.screenSignature
            var resolution = perceptionHelper.findTarget(perception, text, resolvedGoal.orEmpty())

            var tapX: Int? = when (val x = typedArgs["x"]) {
                is Number -> x.toInt()
                is String -> x.toIntOrNull()
                else -> rawArgs["x"]?.toIntOrNull()
            }
            var tapY: Int? = when (val y = typedArgs["y"]) {
                is Number -> y.toInt()
                is String -> y.toIntOrNull()
                else -> rawArgs["y"]?.toIntOrNull()
            }

            if (tapX == null || tapY == null) {
                if (resolution.x > 0 && resolution.y > 0) {
                    tapX = resolution.x
                    tapY = resolution.y
                }
            }

            var visionUsed = (resolution.source == TargetSource.VISION)
            var visionFailureReason = resolution.failureReason

            // Pre-tap verify if vision was used
            if (resolution.source == TargetSource.VISION && tapX != null && tapY != null) {
                var preTap = perceptionHelper.verifyPreTap(Pair(tapX, tapY), resolution.matchedVisionElement, perception.nodes)
                if (preTap is PreTapResult.Mismatch) {
                    Log.w(GeminiRestClient.TAG_VISION, "Pre-tap mismatch for '$text' at ($tapX, $tapY): vision='${preTap.visionText}', node='${preTap.nodeText}'. Re-perceiving once.")
                    perceptionHelper.recordFailedAttempt(perception.screenSignature, text)
                    perception = perceptionHelper.perceive(goal = resolvedGoal.orEmpty(), target = text, forceVision = true)
                    resolution = perceptionHelper.findTarget(perception, text, resolvedGoal.orEmpty())
                    if (resolution.x > 0 && resolution.y > 0) {
                        tapX = resolution.x
                        tapY = resolution.y
                        preTap = perceptionHelper.verifyPreTap(Pair(tapX, tapY), resolution.matchedVisionElement, perception.nodes)
                        if (preTap is PreTapResult.Mismatch) {
                            val mismatchReason = "vision/node mismatch at ($tapX,$tapY): vision said '${preTap.visionText}', screen says '${preTap.nodeText}'"
                            Log.w(GeminiRestClient.TAG_VISION, mismatchReason)
                            val postNodes = automationPort.dumpScreen()
                            return buildActionResult(
                                automationPort = automationPort,
                                outcome = mismatchReason,
                                success = false,
                                postPackage = prePackage,
                                postNodes = postNodes,
                                isStepOpenApp = false,
                                descriptionContext = resolvedGoal.orEmpty(),
                                appKnowledge = appKnowledge,
                                preSignature = preSignature,
                                postSignature = TaskExecutor.computeNodeSignature(postNodes),
                                target = text
                            )
                        }
                    }
                }
            }

            delay(appPacing.waitBefore(System.currentTimeMillis()))

            // Dispatch tap
            var actionSuccess = if (resolution.source == TargetSource.NODES_EXACT) {
                automationPort.clickText(text, perception.nodes, tapX, tapY)
            } else if (tapX != null && tapY != null && tapX > 0 && tapY > 0) {
                screenPort()?.let { sp ->
                    val result = sp.tapXY(tapX, tapY)
                    result is MiraActionResult.Ok
                } ?: automationPort.clickAt(tapX.toFloat(), tapY.toFloat())
            } else {
                automationPort.clickText(text, perception.nodes, tapX, tapY)
            }

            if (settleDelayMs > 0L) {
                delay(settleDelayMs)
            }

            var postPackage = automationPort.getCurrentPackage().orEmpty()
            var postNodes = automationPort.dumpScreen()
            var postSignature = TaskExecutor.computeNodeSignature(postNodes)
            val afterFrame = if (serviceInstance != null) MikaReliableActions.captureFrameGrid(serviceInstance) else null
            val frameChanged = if (beforeFrame != null && afterFrame != null) {
                FrameDiff.compare(beforeFrame, afterFrame, null)?.visible == true
            } else null

            // Post-action verify: if tap dispatched but had NO verified effect, re-perceive once and retry once
            val noEffectInitially = actionSuccess && (postSignature == preSignature && postPackage == prePackage && !automationPort.isInputFocused())
            if (noEffectInitially) {
                Log.i(GeminiRestClient.TAG_VISION, "click_text '$text' had no verified effect. Re-perceiving once and retrying once.")
                perceptionHelper.recordFailedAttempt(preSignature, text)
                perception = perceptionHelper.perceive(goal = resolvedGoal.orEmpty(), target = text, forceVision = true)
                resolution = perceptionHelper.findTarget(perception, text, resolvedGoal.orEmpty())
                if (resolution.x > 0 && resolution.y > 0) {
                    tapX = resolution.x
                    tapY = resolution.y
                    visionUsed = (resolution.source == TargetSource.VISION)
                    visionFailureReason = resolution.failureReason
                    actionSuccess = automationPort.clickAt(tapX.toFloat(), tapY.toFloat())
                    if (settleDelayMs > 0L) {
                        delay(settleDelayMs)
                    }
                    postPackage = automationPort.getCurrentPackage().orEmpty()
                    postNodes = automationPort.dumpScreen()
                    postSignature = TaskExecutor.computeNodeSignature(postNodes)
                }
            }

            val stepParams = mutableMapOf<String, Any?>("text" to text).apply {
                if (tapX != null) {
                    put("fallbackX", tapX)
                    put("x", tapX)
                }
                if (tapY != null) {
                    put("fallbackY", tapY)
                }
            }
            val step = TaskStep(action = TaskStep.ACTION_CLICK_TEXT, params = stepParams)
            MicService.lastExecutedStep = step

            val effectiveTarget = resolvedTarget ?: if (prePackage.isNotEmpty() && !TaskExecutor.isLauncherPackage(prePackage) && prePackage != "io.github.ryoaonetsuki.mira") prePackage else null
            val wrongPackage = isWrongPackageChange(step.action, prePackage, postPackage, effectiveTarget, resolvedGoal)

            val effectVerified = !wrongPackage && actionSuccess && TaskExecutor.verifyEffect(
                step = step,
                prePackage = prePackage,
                preSignature = preSignature,
                postPackage = postPackage,
                postSignature = postSignature,
                goal = resolvedGoal,
                targetApp = effectiveTarget,
                frameChanged = frameChanged
            )

            val outcome = if (wrongPackage) {
                "Action click_text '$text' changed package from '$prePackage' to '$postPackage' which does not match target '$effectiveTarget' (wrong-package change failure)"
            } else if (effectVerified) {
                if (visionUsed) "Successfully located and clicked '$text' via vision ($tapX, $tapY)" else "Successfully clicked '$text'"
            } else if (!actionSuccess) {
                if (tapX != null && tapY != null) {
                    "click_text '$text' not found and coordinate tap ($tapX, $tapY) failed"
                } else if (!visionFailureReason.isNullOrBlank()) {
                    "click_text '$text' not found on screen and vision fallback failed: $visionFailureReason"
                } else {
                    "click_text '$text' not found on screen and vision fallback could not locate coordinates"
                }
            } else {
                "click_text '$text' dispatched but had no verified effect on screen"
            }

            appPacing.noteAction(postPackage, System.currentTimeMillis(), newScreen = postPackage != prePackage)

            return buildActionResult(
                automationPort = automationPort,
                outcome = outcome,
                success = effectVerified,
                postPackage = postPackage,
                postNodes = postNodes,
                isStepOpenApp = false,
                descriptionContext = "Clicked $text",
                appKnowledge = appKnowledge,
                preSignature = preSignature,
                postSignature = postSignature,
                target = text
            )
        }

        "click_at" -> {
            val x = when (val rawX = typedArgs["x"]) {
                is Number -> rawX.toInt()
                is String -> rawX.toIntOrNull()
                else -> rawArgs["x"]?.toIntOrNull()
            }
            val y = when (val rawY = typedArgs["y"]) {
                is Number -> rawY.toInt()
                is String -> rawY.toIntOrNull()
                else -> rawArgs["y"]?.toIntOrNull()
            }
            if (x == null || y == null) {
                return Value.Object("error" to Value.Str("Missing x or y argument for click_at"))
            }

            val prePackage = automationPort.getCurrentPackage().orEmpty()
            val serviceInstance = MiraAccessibilityService.instance
            val beforeFrame = if (serviceInstance != null) MikaReliableActions.captureFrameGrid(serviceInstance) else null
            val preNodes = automationPort.dumpScreen()
            val preSignature = TaskExecutor.computeNodeSignature(preNodes)

            delay(appPacing.waitBefore(System.currentTimeMillis()))
            val actionSuccess = screenPort()?.let { sp ->
                val result = sp.tapXY(x, y)
                result is MiraActionResult.Ok
            } ?: automationPort.clickAt(x.toFloat(), y.toFloat())
            if (settleDelayMs > 0L) {
                delay(settleDelayMs)
            }

            val postPackage = automationPort.getCurrentPackage().orEmpty()
            val postNodes = automationPort.dumpScreen()
            val postSignature = TaskExecutor.computeNodeSignature(postNodes)
            val afterFrame = if (serviceInstance != null) MikaReliableActions.captureFrameGrid(serviceInstance) else null
            val frameChanged = if (beforeFrame != null && afterFrame != null) {
                FrameDiff.compare(beforeFrame, afterFrame, null)?.visible == true
            } else null

            val step = TaskStep(action = TaskStep.ACTION_CLICK_AT, params = mapOf("x" to x, "y" to y))
            MicService.lastExecutedStep = step

            val effectiveTarget = resolvedTarget ?: if (prePackage.isNotEmpty() && !TaskExecutor.isLauncherPackage(prePackage) && prePackage != "io.github.ryoaonetsuki.mira") prePackage else null
            val wrongPackage = isWrongPackageChange(step.action, prePackage, postPackage, effectiveTarget, resolvedGoal)

            val effectVerified = !wrongPackage && actionSuccess && TaskExecutor.verifyEffect(
                step = step,
                prePackage = prePackage,
                preSignature = preSignature,
                postPackage = postPackage,
                postSignature = postSignature,
                goal = resolvedGoal,
                targetApp = effectiveTarget,
                frameChanged = frameChanged
            )

            val outcome = if (wrongPackage) {
                "Action click_at ($x, $y) changed package from '$prePackage' to '$postPackage' which does not match target '$effectiveTarget' (wrong-package change failure)"
            } else if (effectVerified) {
                "Successfully clicked at ($x, $y)"
            } else if (!actionSuccess) {
                "click_at ($x, $y) failed"
            } else {
                "click_at ($x, $y) dispatched but had no verified effect on screen"
            }

            appPacing.noteAction(postPackage, System.currentTimeMillis(), newScreen = postPackage != prePackage)

            return buildActionResult(
                automationPort = automationPort,
                outcome = outcome,
                success = effectVerified,
                postPackage = postPackage,
                postNodes = postNodes,
                isStepOpenApp = false,
                descriptionContext = "Clicked at ($x, $y)",
                appKnowledge = appKnowledge,
                preSignature = preSignature,
                postSignature = postSignature
            )
        }

        "tap_number" -> {
            val number = (typedArgs["number"] as? Number)?.toInt()
                ?: (typedArgs["number"] as? String)?.toIntOrNull()
                ?: rawArgs["number"]?.toIntOrNull()
            if (number == null || number <= 0) {
                return Value.Object("error" to Value.Str("Missing or invalid number argument for tap_number"))
            }

            val prePackage = automationPort.getCurrentPackage().orEmpty()

            // Milestone 6 Off-target refusal guard: refuse BEFORE executing tap_number
            if (isOffTargetForAction("tap_number", prePackage, resolvedTarget, resolvedGoal, automationPort)) {
                val postNodes = automationPort.dumpScreen()
                val targetName = resolvedTarget ?: resolvedGoal?.let { MiraAutomationBridge.extractTargetApp(it) } ?: "target app"
                val outcome = "Current screen package '$prePackage' is not on the target app '$targetName'. You must call open_app first before tapping a number."
                return buildActionResult(
                    automationPort = automationPort,
                    outcome = outcome,
                    success = false,
                    postPackage = prePackage,
                    postNodes = postNodes,
                    isStepOpenApp = false,
                    descriptionContext = resolvedGoal.orEmpty(),
                    appKnowledge = appKnowledge
                )
            }

            val preNodes = automationPort.dumpScreen()
            val preSignature = TaskExecutor.computeNodeSignature(preNodes)

            val actionSuccess = automationPort.clickNumber(number)
            if (settleDelayMs > 0L) {
                delay(settleDelayMs)
            }

            val postPackage = automationPort.getCurrentPackage().orEmpty()
            val postNodes = automationPort.dumpScreen()
            val postSignature = TaskExecutor.computeNodeSignature(postNodes)

            val step = TaskStep(action = TaskStep.ACTION_CLICK_NUMBER, params = mapOf("number" to number))
            MicService.lastExecutedStep = step

            val effectiveTarget = resolvedTarget ?: if (prePackage.isNotEmpty() && !TaskExecutor.isLauncherPackage(prePackage) && prePackage != "io.github.ryoaonetsuki.mira") prePackage else null
            val wrongPackage = isWrongPackageChange(step.action, prePackage, postPackage, effectiveTarget, resolvedGoal)

            val effectVerified = !wrongPackage && actionSuccess && TaskExecutor.verifyEffect(
                step = step,
                prePackage = prePackage,
                preSignature = preSignature,
                postPackage = postPackage,
                postSignature = postSignature,
                goal = resolvedGoal,
                targetApp = effectiveTarget
            )

            val outcome = if (wrongPackage) {
                "Action tap_number '$number' changed package from '$prePackage' to '$postPackage' which does not match target '$effectiveTarget' (wrong-package change failure)"
            } else if (effectVerified) {
                "Successfully tapped number $number"
            } else if (!actionSuccess) {
                "tap_number $number failed (no tappable element with that number)"
            } else {
                "tap_number $number dispatched but had no verified effect on screen"
            }

            return buildActionResult(
                automationPort = automationPort,
                outcome = outcome,
                success = effectVerified,
                postPackage = postPackage,
                postNodes = postNodes,
                isStepOpenApp = false,
                descriptionContext = "Tapped number $number",
                appKnowledge = appKnowledge,
                preSignature = preSignature,
                postSignature = postSignature,
                target = number.toString()
            )
        }

        "show_numbers" -> {
            val detailed = automationPort.showTapNumbersDetailed()
            when (detailed) {
                is TapNumbersResult.PermissionMissing -> {
                    val msg = "Please enable 'Display over other apps' in Mira setup or Android Settings to show number badges."
                    val postPackage = automationPort.getCurrentPackage().orEmpty()
                    val postNodes = automationPort.dumpScreen()
                    val resObj = buildActionResult(
                        automationPort = automationPort,
                        outcome = msg,
                        success = false,
                        postPackage = postPackage,
                        postNodes = postNodes,
                        isStepOpenApp = false,
                        descriptionContext = "Tap numbers permission missing",
                        appKnowledge = appKnowledge
                    )
                    return Value.Object(resObj.value + ("error" to Value.Str(msg)))
                }
                is TapNumbersResult.NoTappableElements -> {
                    val msg = "Could not show tap numbers: no tappable elements found on current screen."
                    val postPackage = automationPort.getCurrentPackage().orEmpty()
                    val postNodes = automationPort.dumpScreen()
                    val resObj = buildActionResult(
                        automationPort = automationPort,
                        outcome = msg,
                        success = false,
                        postPackage = postPackage,
                        postNodes = postNodes,
                        isStepOpenApp = false,
                        descriptionContext = "No tappable elements",
                        appKnowledge = appKnowledge
                    )
                    return Value.Object(resObj.value + ("error" to Value.Str(msg)))
                }
                is TapNumbersResult.Error -> {
                    val msg = "Could not show tap numbers: ${detailed.message}"
                    val postPackage = automationPort.getCurrentPackage().orEmpty()
                    val postNodes = automationPort.dumpScreen()
                    val resObj = buildActionResult(
                        automationPort = automationPort,
                        outcome = msg,
                        success = false,
                        postPackage = postPackage,
                        postNodes = postNodes,
                        isStepOpenApp = false,
                        descriptionContext = "Tap numbers error",
                        appKnowledge = appKnowledge
                    )
                    return Value.Object(resObj.value + ("error" to Value.Str(msg)))
                }
                is TapNumbersResult.Success -> {
                    val postPackage = automationPort.getCurrentPackage().orEmpty()
                    val postNodes = automationPort.dumpScreen()
                    return buildActionResult(
                        automationPort = automationPort,
                        outcome = formatNumberedList(detailed.numbered),
                        success = true,
                        postPackage = postPackage,
                        postNodes = postNodes,
                        isStepOpenApp = false,
                        descriptionContext = "Tap numbers",
                        appKnowledge = appKnowledge
                    )
                }
            }
        }

        "hide_numbers" -> {
            automationPort.hideTapNumbers()
            val postPackage = automationPort.getCurrentPackage().orEmpty()
            val postNodes = automationPort.dumpScreen()

            return buildActionResult(
                automationPort = automationPort,
                outcome = "Tap numbers hidden",
                success = true,
                postPackage = postPackage,
                postNodes = postNodes,
                isStepOpenApp = false,
                descriptionContext = "Tap numbers hidden",
                appKnowledge = appKnowledge
            )
        }

        "type_text" -> {
            val text = (typedArgs["text"]?.toString() ?: rawArgs["text"] ?: "").trim()
            if (text.isEmpty()) {
                return Value.Object("error" to Value.Str("Missing text argument for type_text"))
            }

            val prePackage = automationPort.getCurrentPackage().orEmpty()
            val serviceInstance = MiraAccessibilityService.instance
            val beforeFrame = if (serviceInstance != null) MikaReliableActions.captureFrameGrid(serviceInstance) else null

            // Milestone 6 Off-target refusal guard: refuse BEFORE executing type_text
            if (isOffTargetForAction("type_text", prePackage, resolvedTarget, resolvedGoal, automationPort)) {
                val postNodes = automationPort.dumpScreen()
                val targetName = resolvedTarget ?: resolvedGoal?.let { MiraAutomationBridge.extractTargetApp(it) } ?: "target app"
                val outcome = "Current screen package '$prePackage' is not on the target app '$targetName'. You must call open_app first before typing."
                return buildActionResult(
                    automationPort = automationPort,
                    outcome = outcome,
                    success = false,
                    postPackage = prePackage,
                    postNodes = postNodes,
                    isStepOpenApp = false,
                    descriptionContext = resolvedGoal.orEmpty(),
                    appKnowledge = appKnowledge
                )
            }

            val fallbackX = when (val x = typedArgs["x"]) {
                is Number -> x.toInt()
                is String -> x.toIntOrNull()
                else -> rawArgs["x"]?.toIntOrNull()
            }
            val fallbackY = when (val y = typedArgs["y"]) {
                is Number -> y.toInt()
                is String -> y.toIntOrNull()
                else -> rawArgs["y"]?.toIntOrNull()
            }
            val pressEnter = when (val pe = typedArgs["pressEnter"] ?: rawArgs["pressEnter"]) {
                is Boolean -> pe
                is String -> pe.toBoolean()
                else -> true
            }

            val preNodes = automationPort.dumpScreen()
            val preSignature = TaskExecutor.computeNodeSignature(preNodes)

            val perceptionHelper = VisionFirstPerception(
                automationPort = automationPort,
                apiKeyProvider = { (context as? MicService)?.activeGeminiApiKey ?: getEffectiveGeminiApiKey(context).orEmpty() },
                visionDescribe = { key, bytes, dump, w, h, g ->
                    GeminiRestClient().describeScreenWithVision(key, bytes, dump, w, h, g)
                },
                visionLocator = { query, nodes ->
                    resolveVisionCoordinates(context, query, nodes, automationPort).coordinates
                }
            )

            // Coordinator fix (round 3 review): capture the vision failure reason so
            // the Live model hears WHY the field could not be found, not just that it failed.
            delay(appPacing.waitBefore(System.currentTimeMillis()))
            var lastTypeVisionReason: String? = null
            val typeResult = TaskExecutor.executeTypeFlow(
                automation = automationPort,
                text = text,
                targetQuery = rawArgs["target"] ?: typedArgs["target"]?.toString(),
                fallbackX = fallbackX,
                fallbackY = fallbackY,
                pressEnter = pressEnter,
                visionLocator = { query, nodes ->
                    val res = resolveVisionCoordinates(context, query, nodes, automationPort)
                    lastTypeVisionReason = res.failureReason
                    res.coordinates
                },
                visionFailureReasonProvider = { lastTypeVisionReason },
                pollTimeoutMs = if (pollTimeoutMs == 0L) 0L else 2000L,
                pollIntervalMs = pollIntervalMs,
                visionPerception = perceptionHelper
            )

            if (settleDelayMs > 0L) {
                delay(settleDelayMs)
            }

            val postPackage = automationPort.getCurrentPackage().orEmpty()
            val postNodes = automationPort.dumpScreen()
            val postSignature = TaskExecutor.computeNodeSignature(postNodes)
            val afterFrame = if (serviceInstance != null) MikaReliableActions.captureFrameGrid(serviceInstance) else null
            val frameChanged = if (beforeFrame != null && afterFrame != null) {
                FrameDiff.compare(beforeFrame, afterFrame, null)?.visible == true
            } else null

            val step = TaskStep(action = TaskStep.ACTION_TYPE_TEXT, params = mapOf("text" to text))
            MicService.lastExecutedStep = step
            val effectiveTarget = resolvedTarget ?: if (prePackage.isNotEmpty() && !TaskExecutor.isLauncherPackage(prePackage) && prePackage != "io.github.ryoaonetsuki.mira") prePackage else null
            val wrongPackage = isWrongPackageChange(step.action, prePackage, postPackage, effectiveTarget, resolvedGoal)

            val effectVerified = !wrongPackage && typeResult.success && (
                postSignature != preSignature || typeResult.textTyped.isNotEmpty() || TaskExecutor.verifyEffect(
                    step = step,
                    prePackage = prePackage,
                    preSignature = preSignature,
                    postPackage = postPackage,
                    postSignature = postSignature,
                    goal = resolvedGoal,
                    targetApp = effectiveTarget,
                    frameChanged = frameChanged
                )
            )

            val outcome = if (wrongPackage) {
                "Action type_text changed package from '$prePackage' to '$postPackage' which does not match target '$effectiveTarget' (wrong-package change failure)"
            } else if (effectVerified) {
                typeResult.message
            } else {
                typeResult.message
            }

            appPacing.noteAction(postPackage, System.currentTimeMillis(), newScreen = postPackage != prePackage)

            return buildActionResult(
                automationPort = automationPort,
                outcome = outcome,
                success = effectVerified,
                postPackage = postPackage,
                postNodes = postNodes,
                isStepOpenApp = false,
                descriptionContext = "Typed $text",
                appKnowledge = appKnowledge,
                preSignature = preSignature,
                postSignature = postSignature,
                target = text
            )
        }

        "scroll" -> {
            val direction = (typedArgs["direction"]?.toString() ?: rawArgs["direction"] ?: "down").trim().lowercase(Locale.ROOT).ifEmpty { "down" }

            val prePackage = automationPort.getCurrentPackage().orEmpty()
            val serviceInstance = MiraAccessibilityService.instance
            val beforeFrame = if (serviceInstance != null) MikaReliableActions.captureFrameGrid(serviceInstance) else null
            val preNodes = automationPort.dumpScreen()
            val preSignature = TaskExecutor.computeNodeSignature(preNodes)

            delay(appPacing.waitBefore(System.currentTimeMillis()))
            val actionSuccess = screenPort()?.let { sp ->
                val result = sp.scroll(ref = null, snapshotId = null, down = direction == "down")
                result is MiraActionResult.Ok
            } ?: automationPort.scroll(direction)
            if (settleDelayMs > 0L) {
                delay(settleDelayMs)
            }

            val postPackage = automationPort.getCurrentPackage().orEmpty()
            val postNodes = automationPort.dumpScreen()
            val postSignature = TaskExecutor.computeNodeSignature(postNodes)
            val afterFrame = if (serviceInstance != null) MikaReliableActions.captureFrameGrid(serviceInstance) else null
            val frameChanged = if (beforeFrame != null && afterFrame != null) {
                FrameDiff.compare(beforeFrame, afterFrame, null)?.visible == true
            } else null

            val step = TaskStep(action = TaskStep.ACTION_SCROLL, params = mapOf("direction" to direction))
            MicService.lastExecutedStep = step
            val effectiveTarget = resolvedTarget ?: if (prePackage.isNotEmpty() && !TaskExecutor.isLauncherPackage(prePackage) && prePackage != "io.github.ryoaonetsuki.mira") prePackage else null
            val wrongPackage = isWrongPackageChange(step.action, prePackage, postPackage, effectiveTarget, resolvedGoal)

            val effectVerified = !wrongPackage && actionSuccess && TaskExecutor.verifyEffect(
                step = step,
                prePackage = prePackage,
                preSignature = preSignature,
                postPackage = postPackage,
                postSignature = postSignature,
                goal = resolvedGoal,
                targetApp = effectiveTarget,
                frameChanged = frameChanged
            )

            val overallSuccess = !wrongPackage && actionSuccess && (effectVerified || postPackage == prePackage)
            val outcome = if (wrongPackage) {
                "Action scroll $direction changed package from '$prePackage' to '$postPackage' which does not match target '$effectiveTarget' (wrong-package change failure)"
            } else if (overallSuccess) {
                "Successfully scrolled $direction"
            } else if (!actionSuccess) {
                "scroll $direction failed (gesture rejected)"
            } else {
                "scroll $direction dispatched but screen did not scroll"
            }

            appPacing.noteAction(postPackage, System.currentTimeMillis(), newScreen = postPackage != prePackage)

            return buildActionResult(
                automationPort = automationPort,
                outcome = outcome,
                success = overallSuccess,
                postPackage = postPackage,
                postNodes = postNodes,
                isStepOpenApp = false,
                descriptionContext = "Scrolled $direction",
                appKnowledge = appKnowledge,
                preSignature = preSignature,
                postSignature = postSignature,
                target = direction
            )
        }

        "press_back" -> {
            val prePackage = automationPort.getCurrentPackage().orEmpty()
            val preNodes = automationPort.dumpScreen()
            val preSignature = TaskExecutor.computeNodeSignature(preNodes)

            val actionSuccess = automationPort.pressBack()
            if (settleDelayMs > 0L) {
                delay(settleDelayMs)
            }

            val postPackage = automationPort.getCurrentPackage().orEmpty()
            val postNodes = automationPort.dumpScreen()
            val postSignature = TaskExecutor.computeNodeSignature(postNodes)

            val step = TaskStep(action = TaskStep.ACTION_PRESS_BACK)
            MicService.lastExecutedStep = step
            val effectiveTarget = resolvedTarget ?: if (prePackage.isNotEmpty() && !TaskExecutor.isLauncherPackage(prePackage) && prePackage != "io.github.ryoaonetsuki.mira") prePackage else null
            val wrongPackage = isWrongPackageChange(step.action, prePackage, postPackage, effectiveTarget, resolvedGoal)

            val effectVerified = !wrongPackage && actionSuccess && TaskExecutor.verifyEffect(
                step = step,
                prePackage = prePackage,
                preSignature = preSignature,
                postPackage = postPackage,
                postSignature = postSignature,
                goal = resolvedGoal,
                targetApp = effectiveTarget
            )

            val outcome = if (wrongPackage) {
                "Action press_back changed package from '$prePackage' to '$postPackage' which does not match target '$effectiveTarget' (wrong-package change failure)"
            } else if (effectVerified) {
                "Successfully pressed Back"
            } else if (!actionSuccess) {
                "press_back failed"
            } else {
                "press_back dispatched (package: $postPackage)"
            }

            return buildActionResult(
                automationPort = automationPort,
                outcome = outcome,
                success = effectVerified,
                postPackage = postPackage,
                postNodes = postNodes,
                isStepOpenApp = false,
                descriptionContext = "Pressed Back",
                appKnowledge = appKnowledge,
                preSignature = preSignature,
                postSignature = postSignature
            )
        }

        "press_home" -> {
            val prePackage = automationPort.getCurrentPackage().orEmpty()
            val preNodes = automationPort.dumpScreen()
            val preSignature = TaskExecutor.computeNodeSignature(preNodes)

            val actionSuccess = automationPort.pressHome()
            if (settleDelayMs > 0L) {
                delay(settleDelayMs)
            }

            val postPackage = automationPort.getCurrentPackage().orEmpty()
            val postNodes = automationPort.dumpScreen()
            val postSignature = TaskExecutor.computeNodeSignature(postNodes)

            val step = TaskStep(action = TaskStep.ACTION_PRESS_HOME)
            MicService.lastExecutedStep = step
            val homeReached = TaskExecutor.isLauncherPackage(postPackage)
            val effectVerified = actionSuccess && (homeReached || TaskExecutor.verifyEffect(
                step = step,
                prePackage = prePackage,
                preSignature = preSignature,
                postPackage = postPackage,
                postSignature = postSignature,
                goal = resolvedGoal,
                targetApp = resolvedTarget
            ))

            val outcome = if (homeReached) {
                "Successfully pressed Home (returned to home screen)"
            } else if (effectVerified) {
                "Successfully pressed Home"
            } else if (!actionSuccess) {
                "press_home failed"
            } else {
                "press_home dispatched (package: $postPackage)"
            }

            return buildActionResult(
                automationPort = automationPort,
                outcome = outcome,
                success = effectVerified,
                postPackage = postPackage,
                postNodes = postNodes,
                isStepOpenApp = false,
                descriptionContext = "Pressed Home",
                appKnowledge = appKnowledge,
                preSignature = preSignature,
                postSignature = postSignature
            )
        }

        "wait" -> {
            val msRaw = typedArgs["ms"] ?: rawArgs["ms"]
            val ms = when (msRaw) {
                is Number -> msRaw.toLong()
                is String -> msRaw.toLongOrNull() ?: 1000L
                else -> 1000L
            }.coerceIn(100L, 10000L)

            delay(ms)

            val postPackage = automationPort.getCurrentPackage().orEmpty()
            val postNodes = automationPort.dumpScreen()

            return buildActionResult(
                automationPort = automationPort,
                outcome = "Waited ${ms}ms",
                success = true,
                postPackage = postPackage,
                postNodes = postNodes,
                isStepOpenApp = false,
                descriptionContext = "Waited ${ms}ms",
                appKnowledge = appKnowledge
            )
        }

        "get_screen" -> {
            val postPackage = automationPort.getCurrentPackage().orEmpty()
            val postNodes = automationPort.dumpScreen()

            return buildActionResult(
                automationPort = automationPort,
                outcome = "Current screen inspected",
                success = true,
                postPackage = postPackage,
                postNodes = postNodes,
                isStepOpenApp = false,
                descriptionContext = "Current screen",
                appKnowledge = appKnowledge
            )
        }

        "run_skill" -> {
            val goalArg = (typedArgs["goal"]?.toString() ?: rawArgs["goal"] ?: resolvedGoal ?: "").trim()
            val skillArg = (typedArgs["skill"]?.toString() ?: rawArgs["skill"] ?: "").trim()
            if (goalArg.isEmpty() && skillArg.isEmpty()) {
                return Value.Object(
                    "result" to Value.Str("Missing goal argument for run_skill"),
                    "success" to Value.Bool(false)
                )
            }
            if (skills == null) {
                return Value.Object(
                    "result" to Value.Str("Skill memory service not available"),
                    "success" to Value.Bool(false)
                )
            }
            val matchedSkill = if (skillArg.isNotEmpty()) {
                skills.all().firstOrNull { it.id == skillArg || it.goal.equals(skillArg, ignoreCase = true) }
                    ?: skills.findMatch(skillArg)
                    ?: if (goalArg.isNotEmpty()) skills.findMatch(goalArg) else null
            } else {
                skills.findMatch(goalArg)
            }

            if (matchedSkill == null) {
                val postPackage = automationPort.getCurrentPackage().orEmpty()
                val postNodes = automationPort.dumpScreen()
                return buildActionResult(
                    automationPort = automationPort,
                    outcome = "No saved skill macro found matching '${if (skillArg.isNotEmpty()) skillArg else goalArg}'",
                    success = false,
                    postPackage = postPackage,
                    postNodes = postNodes,
                    isStepOpenApp = false,
                    descriptionContext = if (skillArg.isNotEmpty()) skillArg else goalArg,
                    appKnowledge = appKnowledge
                )
            }

            val executor = TaskExecutor(
                automation = automationPort,
                skills = skills,
                stepDelayMs = if (pollTimeoutMs == 0L) 0L else 600L,
                apiKeyProvider = { (context as? MicService)?.activeGeminiApiKey ?: getEffectiveGeminiApiKey(context).orEmpty() },
                visionLocator = { target, currentNodes -> resolveVisionCoordinates(context, target, currentNodes, automationPort).coordinates },
                appKnowledge = appKnowledge
            )
            val replayResult = executor.replaySkillDirect(matchedSkill)
            val postPackage = automationPort.getCurrentPackage().orEmpty()
            val postNodes = automationPort.dumpScreen()
            return buildActionResult(
                automationPort = automationPort,
                outcome = replayResult.message,
                success = replayResult.status == TaskStatus.SUCCESS,
                postPackage = postPackage,
                postNodes = postNodes,
                isStepOpenApp = matchedSkill.steps.any { it.action == TaskStep.ACTION_OPEN_APP },
                descriptionContext = matchedSkill.goal,
                appKnowledge = appKnowledge
            )
        }

        "web_search", "search" -> {
            val query = (typedArgs["query"]?.toString() ?: rawArgs["query"] ?: typedArgs["text"]?.toString() ?: rawArgs["text"] ?: rawArgs["command"] ?: "").trim()
            val searchResults = if (query.isNotBlank()) {
                FreeWebSearch.search(query, maxResults = 3)
            } else {
                emptyList()
            }
            val resultString = if (searchResults.isNotEmpty()) {
                searchResults.joinToString("\n") { "${it.title}: ${it.snippet}" }
            } else {
                "No web search results found for query: $query"
            }
            return Value.Object(
                "result" to Value.Str(resultString),
                "success" to Value.Bool(true)
            )
        }

        "run_connector_command" -> {
            val command = (typedArgs["command"]?.toString() ?: rawArgs["command"] ?: typedArgs["query"]?.toString() ?: rawArgs["query"] ?: typedArgs["text"]?.toString() ?: rawArgs["text"] ?: "").trim()
            val resultString = if (command.isNotBlank() && context != null) {
                val res = ConnectorIntentHandler.handleCommand(context, command)
                if (res != null) {
                    val db = MiraDatabase.getInstance(context)
                    db.notificationDao().insertNotification(
                        NotificationEntity(
                            title = "Connector Action",
                            message = res,
                            type = "connector"
                        )
                    )
                    res
                } else {
                    "Executed connector command: $command"
                }
            } else if (command.isNotBlank()) {
                "Executed connector command: $command"
            } else {
                "Executed run_connector_command"
            }
            return Value.Object(
                "result" to Value.Str(resultString),
                "success" to Value.Bool(true)
            )
        }

        "remember_fact", "recall_memory", "forget_memory" -> {
            if (context == null) return Value.Object("error" to Value.Str("Memory unavailable: no context"))
            val db = MiraDatabase.getInstance(context)
            val toolArgs = (rawArgs.keys + typedArgs.keys).associateWith { key ->
                (typedArgs[key]?.toString()?.takeIf { it.isNotBlank() } ?: rawArgs[key] ?: "").trim()
            }
            val res = executeMemoryTool(db, normName, toolArgs)
            return res ?: Value.Object("error" to Value.Str("Memory tool failed"))
        }

        else -> {
            // Fallback for search or connector intents by command text
            val query = (typedArgs["command"]?.toString() ?: typedArgs["query"]?.toString() ?: rawArgs["query"] ?: rawArgs["text"] ?: rawArgs["command"] ?: rawArgs.values.firstOrNull() ?: "").trim()
            var resultString: String? = null
            if (query.isNotBlank() && ConnectorIntentHandler.isConnectorCommand(query)) {
                if (context != null) {
                    resultString = ConnectorIntentHandler.handleCommand(context, query)
                    if (resultString != null) {
                        val db = MiraDatabase.getInstance(context)
                        db.notificationDao().insertNotification(
                            NotificationEntity(
                                title = "Connector Action",
                                message = resultString,
                                type = "connector"
                            )
                        )
                    }
                } else {
                    resultString = "Executed connector command: $query"
                }
            }

            val finalResult = resultString ?: "Executed $name"
            return Value.Object(
                "result" to Value.Str(finalResult),
                "success" to Value.Bool(true)
            )
        }
    }
}

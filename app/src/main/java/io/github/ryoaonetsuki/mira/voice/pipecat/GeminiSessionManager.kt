package io.github.ryoaonetsuki.mira.voice.pipecat

import ai.pipecat.client.PipecatClient
import ai.pipecat.client.PipecatClientOptions
import ai.pipecat.client.PipecatEventCallbacks
import ai.pipecat.client.types.TransportState
import ai.pipecat.client.types.Value
import android.content.Context
import android.util.Log
import io.github.ryoaonetsuki.mira.voice.SessionResumptionStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * State machine states for [GeminiSessionManager] per ROUND19_PLAN.md.
 */
enum class SessionManagerState {
    IDLE,
    CONNECTING,
    CONFIGURING,
    CONNECTED,
    GOAWAY_RECEIVED,
    RECONNECTING,
    ERROR,
    STOPPED
}

/**
 * Listener interface exposing session events to MicService and the UI layer.
 */
interface SessionManagerListener {
    fun onStateChanged(state: SessionManagerState) {}
    fun onAudioLevel(level: Float) {}
    fun onUserTranscript(text: String) {}
    fun onModelTranscript(text: String) {}
    fun onBotSpeakingChanged(isSpeaking: Boolean) {}
    fun onUserSpeakingChanged(isSpeaking: Boolean) {}
    fun onSessionEnded(reason: String, error: Throwable?) {}
    fun onReconnecting(attempt: Int, maxAttempts: Int) {}
    fun onError(message: String) {}
    fun onFunctionCall(id: String, name: String, args: Value.Array, rawArgs: Map<String, String> = emptyMap()) {}
}

/**
 * Abstraction over a Pipecat RTVI client session to enable clean unit testing
 * with fakes without opening real network WebSockets.
 */
interface SessionClient {
    fun connect(options: MiraGeminiServiceOptions)
    fun disconnect()
    fun release()
    fun enableMic(enabled: Boolean)
    fun sendUserMessage(content: String) {}
    fun sendFunctionResponse(id: String, name: String, response: Value.Object) {}
    val isClosed: Boolean
}

interface SessionClientFactory {
    fun createClient(
        context: Context,
        options: MiraGeminiServiceOptions,
        sessionListener: MiraGeminiLiveTransport.SessionListener,
        callbacks: PipecatEventCallbacks
    ): SessionClient
}

class DefaultSessionClient(
    val transport: MiraGeminiLiveTransport,
    val pipecatClient: PipecatClient<MiraGeminiLiveTransport, MiraGeminiServiceOptions>
) : SessionClient {
    private var _isClosed = false
    override val isClosed: Boolean get() = _isClosed

    override fun connect(options: MiraGeminiServiceOptions) {
        pipecatClient.connect(options)
    }

    override fun disconnect() {
        _isClosed = true
        pipecatClient.disconnect()
    }

    override fun release() {
        _isClosed = true
        pipecatClient.release()
    }

    override fun enableMic(enabled: Boolean) {
        pipecatClient.enableMic(enabled)
    }

    override fun sendUserMessage(content: String) {
        pipecatClient.sendText(content)
    }
}

class DefaultSessionClientFactory : SessionClientFactory {
    override fun createClient(
        context: Context,
        options: MiraGeminiServiceOptions,
        sessionListener: MiraGeminiLiveTransport.SessionListener,
        callbacks: PipecatEventCallbacks
    ): SessionClient {
        val transport = MiraGeminiLiveTransport(context, sessionListener)
        val pipecatClient = PipecatClient(
            transport = transport,
            options = PipecatClientOptions(callbacks = callbacks)
        )
        return DefaultSessionClient(transport, pipecatClient)
    }
}

/**
 * Production-grade auto-reconnecting session manager for Mira Gemini Live voice (ROUND 19).
 *
 * Implements a robust state machine wrapping PipecatClient with:
 * - Session resumption handle management (2h TTL, single-use consume, resumable==true gating).
 * - Exponential backoff reconnection schedule (1s, 2s, 3s, 4s, 5s; max 5 retries).
 * - Proactive GoAway reconnection with stored handle.
 * - Stage-4 silence watchdog (15s setupComplete timeout).
 * - Free-tier concurrency guard (max 3 concurrent sessions).
 * - Audio continuity (drop audio silently during RECONNECTING).
 * - Dev GoAway simulation trigger for diagnostic testing.
 */
class GeminiSessionManager(
    val context: Context,
    val apiKey: String,
    val voiceName: String,
    val language: String = DEFAULT_LANGUAGE,
    val systemPrompt: String = "",
    val resumptionStore: SessionResumptionStore,
    val listener: SessionManagerListener,
    private val externalScope: CoroutineScope? = null,
    val clientSessionFactory: SessionClientFactory = DefaultSessionClientFactory()
) {
    companion object {
        private const val TAG = "GeminiSessionManager"
        const val LIVE_MODEL = "models/gemini-3.8-live"
        const val DEFAULT_LANGUAGE = "bn-BD"
        const val STAGE4_SILENCE_TIMEOUT_MS = 15_000L
        const val MAX_RETRIES = 5
        val BACKOFF_DELAYS_MS = listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L)
        const val MAX_CONCURRENT_SESSIONS = 3

        private val liveSessionsCount = AtomicInteger(0)

        fun getLiveSessionsCount(): Int = liveSessionsCount.get()

        internal fun resetLiveSessionsCount() {
            liveSessionsCount.set(0)
        }

        fun getBackoffDelayMs(retryAttempt: Int): Long {
            val index = (retryAttempt - 1).coerceIn(0, BACKOFF_DELAYS_MS.size - 1)
            return BACKOFF_DELAYS_MS[index]
        }
    }

    private val internalScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val scope: CoroutineScope get() = externalScope ?: internalScope
    internal var delayProvider: suspend (Long) -> Unit = { delay(it) }

    private val _state = MutableStateFlow(SessionManagerState.IDLE)
    val state: StateFlow<SessionManagerState> = _state.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private var currentClient: SessionClient? = null
    private var stage4WatchdogJob: Job? = null
    private var reconnectJob: Job? = null
    private var retryCount = 0
    private val isExplicitlyStopped = AtomicBoolean(false)
    private val isCountedAsLive = AtomicBoolean(false)
    private val activeFunctionCalls = AtomicInteger(0)
    private val currentEpoch = AtomicInteger(0)

    @Volatile private var isBotSpeaking = false
    @Volatile private var isUserSpeaking = false

    private fun transitionTo(newState: SessionManagerState) {
        if (_state.value == newState) return
        Log.i(TAG, "MIRA_LIVE_VOICE: State transition ${_state.value} -> $newState")
        _state.value = newState
        listener.onStateChanged(newState)
    }

    private fun acquireLiveSessionSlot(): Boolean {
        if (isCountedAsLive.get()) return true
        val current = liveSessionsCount.incrementAndGet()
        if (current > MAX_CONCURRENT_SESSIONS) {
            liveSessionsCount.decrementAndGet()
            return false
        }
        isCountedAsLive.set(true)
        return true
    }

    private fun releaseLiveSessionSlot() {
        if (isCountedAsLive.compareAndSet(true, false)) {
            liveSessionsCount.decrementAndGet()
        }
    }

    private fun buildServiceOptions(resumptionHandle: String?): MiraGeminiServiceOptions {
        return MiraGeminiServiceOptions.forDirect(
            apiKey = apiKey,
            model = LIVE_MODEL,
            voiceName = voiceName,
            languageCode = language,
            systemInstructionText = systemPrompt,
            resumptionHandle = resumptionHandle
        )
    }

    fun startSession() {
        Log.i(TAG, "MIRA_LIVE_VOICE: startSession() requested")
        isExplicitlyStopped.set(false)
        retryCount = 0

        if (!acquireLiveSessionSlot()) {
            val errorMsg = "Free-tier limit reached: maximum $MAX_CONCURRENT_SESSIONS concurrent sessions allowed"
            Log.e(TAG, "MIRA_LIVE_VOICE: $errorMsg")
            transitionTo(SessionManagerState.ERROR)
            listener.onError(errorMsg)
            return
        }

        connectInternal(resumptionHandle = null)
    }

    fun stopSession() {
        Log.i(TAG, "MIRA_LIVE_VOICE: stopSession() called")
        isExplicitlyStopped.set(true)
        stage4WatchdogJob?.cancel()
        stage4WatchdogJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        retryCount = 0
        activeFunctionCalls.set(0)
        isBotSpeaking = false
        isUserSpeaking = false

        closeCurrentClient()
        releaseLiveSessionSlot()

        _audioLevel.value = 0f
        listener.onAudioLevel(0f)

        transitionTo(SessionManagerState.STOPPED)
    }

    fun simulateGoAwayForTesting() {
        Log.i(TAG, "MIRA_LIVE_VOICE: simulateGoAwayForTesting() triggered")
        handleGoAway("60s", currentEpoch.get())
    }

    fun enableMic(enabled: Boolean) {
        if (_state.value == SessionManagerState.CONNECTED) {
            currentClient?.enableMic(enabled)
        }
    }

    fun sendUserMessage(content: String) {
        if (_state.value == SessionManagerState.CONNECTED) {
            currentClient?.sendUserMessage(content)
        }
    }

    fun sendFunctionResponse(id: String, name: String, response: Value.Object) {
        activeFunctionCalls.updateAndGet { (it - 1).coerceAtLeast(0) }
        currentClient?.sendFunctionResponse(id, name, response)
    }

    private fun closeCurrentClient() {
        val client = currentClient
        currentClient = null
        currentEpoch.incrementAndGet()
        if (client != null && !client.isClosed) {
            try {
                client.disconnect()
                client.release()
            } catch (e: Exception) {
                Log.w(TAG, "MIRA_LIVE_VOICE: Exception closing previous client: ${e.message}")
            }
        }
    }

    private fun connectInternal(resumptionHandle: String?) {
        if (isExplicitlyStopped.get()) return

        // Free-tier safety: always close previous WebSocket explicitly before opening a new one
        closeCurrentClient()

        val epoch = currentEpoch.incrementAndGet()

        transitionTo(SessionManagerState.CONNECTING)

        val options = buildServiceOptions(resumptionHandle)

        val sessionListener = object : MiraGeminiLiveTransport.SessionListener {
            override fun onWebSocketOpen() {
                handleWebSocketOpen(epoch)
            }

            override fun onSetupComplete() {
                handleSetupComplete(epoch)
            }

            override fun onSessionResumptionUpdate(handle: String, resumable: Boolean) {
                handleSessionResumptionUpdate(handle, resumable, epoch)
            }

            override fun onGoAway(timeLeft: String) {
                handleGoAway(timeLeft, epoch)
            }

            override fun onSessionEnded(reason: String, t: Throwable?) {
                handleSessionEnded(reason, t, epoch)
            }

            override fun onBotStartedSpeaking() {
                if (epoch != currentEpoch.get()) return
                isBotSpeaking = true
                listener.onBotSpeakingChanged(true)
            }

            override fun onBotStoppedSpeaking() {
                if (epoch != currentEpoch.get()) return
                isBotSpeaking = false
                listener.onBotSpeakingChanged(false)
            }

            override fun onUserStartedSpeaking() {
                if (epoch != currentEpoch.get()) return
                isUserSpeaking = true
                listener.onUserSpeakingChanged(true)
            }

            override fun onUserStoppedSpeaking() {
                if (epoch != currentEpoch.get()) return
                isUserSpeaking = false
                listener.onUserSpeakingChanged(false)
            }

            override fun onUserAudioLevel(level: Float) {
                if (epoch != currentEpoch.get()) return
                if (_state.value == SessionManagerState.CONNECTED) {
                    _audioLevel.value = level
                    listener.onAudioLevel(level)
                }
            }

            override fun onRemoteAudioLevel(level: Float) {
                if (epoch != currentEpoch.get()) return
                if (_state.value == SessionManagerState.CONNECTED) {
                    _audioLevel.value = level
                    listener.onAudioLevel(level)
                }
            }

            override fun onUserTranscript(text: String) {
                if (epoch != currentEpoch.get()) return
                listener.onUserTranscript(text)
            }

            override fun onModelTranscript(text: String) {
                if (epoch != currentEpoch.get()) return
                listener.onModelTranscript(text)
            }

            override fun onFunctionCall(id: String, name: String, args: Value.Array, rawArgs: Map<String, String>) {
                if (epoch != currentEpoch.get()) return
                activeFunctionCalls.incrementAndGet()
                Log.i(TAG, "MIRA_LIVE_VOICE: Function call received: $name (id: $id)")
                listener.onFunctionCall(id, name, args, rawArgs)
            }
        }

        val callbacks = object : PipecatEventCallbacks() {
            override fun onBackendError(message: String) {
                Log.e(TAG, "MIRA_LIVE_VOICE: Pipecat backend error: $message")
            }

            override fun onTransportStateChanged(state: TransportState) {
                Log.d(TAG, "MIRA_LIVE_VOICE: Transport state: $state")
            }

            override fun onDisconnected() {
                Log.d(TAG, "MIRA_LIVE_VOICE: Transport disconnected")
            }
        }

        val client = clientSessionFactory.createClient(
            context = context,
            options = options,
            sessionListener = sessionListener,
            callbacks = callbacks
        )
        currentClient = client

        try {
            client.connect(options)
        } catch (e: Exception) {
            Log.e(TAG, "MIRA_LIVE_VOICE: Failed to connect client: ${e.message}", e)
            handleSessionEnded("connect failed: ${e.message}", e, epoch)
        }
    }

    private fun handleWebSocketOpen(epoch: Int) {
        if (epoch != currentEpoch.get()) {
            Log.d(TAG, "MIRA_LIVE_VOICE: Ignoring handleWebSocketOpen from stale epoch $epoch (current: ${currentEpoch.get()})")
            return
        }
        if (isExplicitlyStopped.get() || _state.value == SessionManagerState.STOPPED) return

        transitionTo(SessionManagerState.CONFIGURING)

        // Stage-4 silence watchdog: 15-second timer started when WebSocket opens / setup is sent
        stage4WatchdogJob?.cancel()
        stage4WatchdogJob = scope.launch {
            delayProvider(STAGE4_SILENCE_TIMEOUT_MS)
            if (_state.value == SessionManagerState.CONFIGURING && epoch == currentEpoch.get()) {
                Log.w(TAG, "MIRA_LIVE_VOICE: Stage-4 silence detected")
                resumptionStore.clearHandle()
                closeCurrentClient()
                launchReconnect(isGoAway = false, isStage4Timeout = true)
            }
        }
    }

    private fun handleSetupComplete(epoch: Int) {
        if (epoch != currentEpoch.get()) {
            Log.d(TAG, "MIRA_LIVE_VOICE: Ignoring handleSetupComplete from stale epoch $epoch (current: ${currentEpoch.get()})")
            return
        }
        stage4WatchdogJob?.cancel()
        stage4WatchdogJob = null
        activeFunctionCalls.set(0)
        isBotSpeaking = false
        isUserSpeaking = false

        // Successful handshake: reset retry counter
        retryCount = 0

        transitionTo(SessionManagerState.CONNECTED)
        currentClient?.enableMic(true)
    }

    private fun handleSessionResumptionUpdate(handle: String, resumable: Boolean, epoch: Int) {
        if (epoch != currentEpoch.get()) {
            Log.d(TAG, "MIRA_LIVE_VOICE: Ignoring handleSessionResumptionUpdate from stale epoch $epoch (current: ${currentEpoch.get()})")
            return
        }
        if (!resumable) {
            Log.d(TAG, "MIRA_LIVE_VOICE: sessionResumptionUpdate ignored (resumable=false)")
            return
        }
        if (_state.value != SessionManagerState.CONNECTED) {
            Log.d(TAG, "MIRA_LIVE_VOICE: sessionResumptionUpdate ignored outside CONNECTED state (state=${_state.value})")
            return
        }
        if (isBotSpeaking) {
            Log.d(TAG, "MIRA_LIVE_VOICE: sessionResumptionUpdate ignored during active model generation/speaking")
            return
        }
        if (activeFunctionCalls.get() > 0) {
            Log.d(TAG, "MIRA_LIVE_VOICE: sessionResumptionUpdate ignored during active function call")
            return
        }

        // Security: NEVER log handle value — length and status only
        Log.i(TAG, "MIRA_LIVE_VOICE: Saving valid resumption handle (length=${handle.length})")
        resumptionStore.saveHandle(handle)
    }

    private fun handleGoAway(timeLeft: String, epoch: Int = currentEpoch.get()) {
        if (epoch != currentEpoch.get()) {
            Log.d(TAG, "MIRA_LIVE_VOICE: Ignoring handleGoAway from stale epoch $epoch (current: ${currentEpoch.get()})")
            return
        }
        if (isExplicitlyStopped.get() || _state.value == SessionManagerState.STOPPED) return

        val storedHandle = resumptionStore.getHandle()
        val hasValidHandle = !storedHandle.isNullOrBlank()

        Log.w(TAG, "MIRA_LIVE_VOICE: Server GoAway received (timeLeft=$timeLeft, hasValidHandle=$hasValidHandle)")

        if (!hasValidHandle) {
            Log.w(TAG, "MIRA_LIVE_VOICE: GoAway received without valid handle; treating as normal disconnect")
            handleSessionEnded("goAway without valid handle", null, epoch)
            return
        }

        transitionTo(SessionManagerState.GOAWAY_RECEIVED)
        launchReconnect(isGoAway = true, isStage4Timeout = false)
    }

    private fun handleSessionEnded(reason: String, t: Throwable?, epoch: Int) {
        if (epoch != currentEpoch.get()) {
            Log.d(TAG, "MIRA_LIVE_VOICE: Ignoring stale onSessionEnded from epoch $epoch (current: ${currentEpoch.get()})")
            return
        }
        if (isExplicitlyStopped.get() || _state.value == SessionManagerState.STOPPED) {
            return
        }

        Log.w(TAG, "MIRA_LIVE_VOICE: Session ended (reason=$reason, error=${t?.message})")

        stage4WatchdogJob?.cancel()
        stage4WatchdogJob = null
        activeFunctionCalls.set(0)
        isBotSpeaking = false
        isUserSpeaking = false

        // If disconnected before reaching CONNECTED (failed resumption or setup handshake),
        // clear stored handle to fall back to fresh session.
        if (_state.value == SessionManagerState.CONNECTING ||
            _state.value == SessionManagerState.CONFIGURING ||
            _state.value == SessionManagerState.RECONNECTING) {
            resumptionStore.clearHandle()
        }

        listener.onSessionEnded(reason, t)
        launchReconnect(isGoAway = false, isStage4Timeout = false)
    }

    private fun launchReconnect(isGoAway: Boolean, isStage4Timeout: Boolean) {
        if (isExplicitlyStopped.get() || _state.value == SessionManagerState.STOPPED) return

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            retryCount++
            if (retryCount > MAX_RETRIES) {
                val errorMsg = if (isStage4Timeout) {
                    "Live voice: Setup timed out after 15s — tap to retry"
                } else {
                    "Live voice: Connection failed after $MAX_RETRIES retries — tap to retry"
                }
                Log.e(TAG, "MIRA_LIVE_VOICE: $errorMsg")
                releaseLiveSessionSlot()
                transitionTo(SessionManagerState.ERROR)
                listener.onError(errorMsg)
                return@launch
            }

            transitionTo(SessionManagerState.RECONNECTING)
            listener.onReconnecting(retryCount, MAX_RETRIES)

            // Audio continuity: drop audio silently during reconnection gap
            _audioLevel.value = 0f
            listener.onAudioLevel(0f)

            // Free-tier safety: always close previous WebSocket explicitly before opening a new one
            closeCurrentClient()

            // For unexpected disconnects or silence timeouts, apply backoff delay
            if (!isGoAway) {
                val backoffDelay = getBackoffDelayMs(retryCount)
                Log.i(TAG, "MIRA_LIVE_VOICE: Backoff delay for retry #$retryCount: ${backoffDelay}ms")
                delayProvider(backoffDelay)
            }

            if (isExplicitlyStopped.get() || _state.value == SessionManagerState.STOPPED) {
                return@launch
            }

            // Single-use: consume resumption handle atomically
            val resumptionHandle = resumptionStore.consumeHandle()
            if (resumptionHandle != null) {
                Log.i(TAG, "MIRA_LIVE_VOICE: Reconnecting with consumed resumption handle (length=${resumptionHandle.length})")
            } else {
                Log.i(TAG, "MIRA_LIVE_VOICE: Reconnecting with fresh session (no valid handle)")
            }

            connectInternal(resumptionHandle = resumptionHandle)
        }
    }
}

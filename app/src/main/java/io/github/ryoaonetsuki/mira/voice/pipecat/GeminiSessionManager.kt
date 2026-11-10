package io.github.ryoaonetsuki.mira.voice.pipecat

import ai.pipecat.client.PipecatClient
import ai.pipecat.client.PipecatClientOptions
import ai.pipecat.client.PipecatEventCallbacks
import ai.pipecat.client.types.TransportState
import ai.pipecat.client.types.Value
import android.content.Context
import android.util.Log
import io.github.ryoaonetsuki.mira.voice.LiveVoiceDiagnostics
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
    fun onUserTranscript(text: String, isFinal: Boolean) {
        onUserTranscript(text)
    }
    fun onTurnComplete() {}
    fun onModelTranscript(text: String) {}
    fun onBotSpeakingChanged(isSpeaking: Boolean) {}
    fun onUserSpeakingChanged(isSpeaking: Boolean) {}
    fun onSessionEnded(reason: String, error: Throwable?) {}
    fun onReconnecting(attempt: Int, maxAttempts: Int) {}
    fun onError(message: String) {}
    /** Turn G: mic capture died after its bounded internal retries. */
    fun onMicError(reason: String) {}
    /**
     * Turn G: a (re)connect completed. [resumed] is true when the session
     * resumed with a resumption handle (context intact), false when it
     * started fresh — the service can then re-seed task context instead of
     * starting blank.
     */
    fun onSessionResumed(resumed: Boolean) {}
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
    fun stopAudioPlayback() {}
    fun setOutputSuppressed(suppressed: Boolean) {}
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
        try {
            pipecatClient.disconnect()
        } catch (t: Throwable) {
            Log.w("DefaultSessionClient", "Exception in disconnect: ${t.message}")
        }
    }

    override fun release() {
        _isClosed = true
        try {
            pipecatClient.release()
        } catch (t: Throwable) {
            Log.w("DefaultSessionClient", "Exception in release: ${t.message}")
        }
    }

    override fun enableMic(enabled: Boolean) {
        pipecatClient.enableMic(enabled)
    }

    override fun sendUserMessage(content: String) {
        pipecatClient.sendText(content)
    }

    override fun stopAudioPlayback() {
        transport.stopAudioPlayback()
    }

    override fun setOutputSuppressed(suppressed: Boolean) {
        transport.setOutputSuppressed(suppressed)
    }
}

class DefaultSessionClientFactory : SessionClientFactory {
    override fun createClient(
        context: Context,
        options: MiraGeminiServiceOptions,
        sessionListener: MiraGeminiLiveTransport.SessionListener,
        callbacks: PipecatEventCallbacks
    ): SessionClient {
        val transport = MiraGeminiLiveTransport(context, sessionListener, echoGuardEnabled = options.echoGuardEnabled)
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
    val clientSessionFactory: SessionClientFactory = DefaultSessionClientFactory(),
    val echoGuardEnabled: Boolean = true
) {
    companion object {
        private const val TAG = "GeminiSessionManager"
        const val LIVE_MODEL = "models/gemini-3.8-live"
        const val DEFAULT_LANGUAGE = "bn-BD"
        const val STAGE4_SILENCE_TIMEOUT_MS = 15_000L
        const val MAX_RETRIES = 5
        val BACKOFF_DELAYS_MS = listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L)
        const val MAX_CONCURRENT_SESSIONS = 1

        // Single shared slot guard: the single-voice invariant lives here so the
        // production slot logic and its tests exercise the same code.
        private val sessionGuard = VoiceSessionGuard(MAX_CONCURRENT_SESSIONS)

        fun getLiveSessionsCount(): Int = sessionGuard.getActiveSessionCount()

        internal fun resetLiveSessionsCount() {
            sessionGuard.reset()
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
    /**
     * Turn G: the resumption handle the current connect attempt is trying
     * to resume with (null = fresh session). It is cleared from the store
     * ONLY once the server accepts it (setupComplete) — a failed reconnect
     * must not burn a handle that was never used.
     */
    private var pendingResumptionHandleValue: String? = null

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
        val acquired = sessionGuard.acquireSessionSlot()
        if (acquired) isCountedAsLive.set(true)
        return acquired
    }

    private fun releaseLiveSessionSlot() {
        if (isCountedAsLive.compareAndSet(true, false)) {
            sessionGuard.releaseSessionSlot()
        }
    }

    private fun buildServiceOptions(resumptionHandle: String?): MiraGeminiServiceOptions {
        return MiraGeminiServiceOptions.forDirect(
            apiKey = apiKey,
            model = LIVE_MODEL,
            voiceName = voiceName,
            languageCode = language,
            systemInstructionText = systemPrompt,
            resumptionHandle = resumptionHandle,
            echoGuardEnabled = echoGuardEnabled
        )
    }

    fun startSession() {
        Log.i(TAG, "MIRA_LIVE_VOICE: startSession() requested")
        if (isExplicitlyStopped.get()) {
            Log.w(TAG, "MIRA_LIVE_VOICE: startSession() ignored on an explicitly stopped manager instance")
            return
        }
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
        pendingResumptionHandleValue = null
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

    /**
     * Turn G: force a reconnect of the live session (liveness watchdog).
     * No-op when the manager is stopped, in ERROR, or already reconnecting —
     * the in-flight reconnect owns the recovery then.
     */
    fun requestReconnect(reason: String) {
        if (isExplicitlyStopped.get() || _state.value == SessionManagerState.STOPPED ||
            _state.value == SessionManagerState.ERROR ||
            _state.value == SessionManagerState.RECONNECTING
        ) {
            Log.i(TAG, "MIRA_LIVE_VOICE: requestReconnect($reason) ignored in state ${_state.value}")
            return
        }
        Log.w(TAG, "MIRA_LIVE_VOICE: requestReconnect: $reason (state=${_state.value})")
        LiveVoiceDiagnostics.recordDropReason("watchdog:$reason")
        launchReconnect(isGoAway = false, isStage4Timeout = false)
    }

    fun sendUserMessage(content: String) {
        if (_state.value == SessionManagerState.CONNECTED) {
            currentClient?.sendUserMessage(content)
        }
    }

    fun stopAudioPlayback() {
        currentClient?.stopAudioPlayback()
    }

    /** Drops Live audio output while a phone action runs; always release in a finally. */
    fun setOutputSuppressed(suppressed: Boolean) {
        currentClient?.setOutputSuppressed(suppressed)
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
            } catch (t: Throwable) {
                Log.w(TAG, "MIRA_LIVE_VOICE: Exception disconnecting previous client: ${t.message}")
            }
            try {
                client.release()
            } catch (t: Throwable) {
                Log.w(TAG, "MIRA_LIVE_VOICE: Exception releasing previous client: ${t.message}")
            }
        }
    }

    private fun connectInternal(resumptionHandle: String?) {
        if (isExplicitlyStopped.get()) return

        // Free-tier safety: always close previous WebSocket explicitly before opening a new one
        closeCurrentClient()

        // Turn G: remember which handle this attempt is resuming with; it
        // is cleared from the store only on setupComplete (server accepted
        // it) — never up front, so a failed attempt cannot burn it.
        pendingResumptionHandleValue = resumptionHandle

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

            override fun onMicError(reason: String) {
                handleMicError(reason, epoch)
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
                listener.onUserTranscript(text, true)
            }

            override fun onUserTranscript(text: String, isFinal: Boolean) {
                if (epoch != currentEpoch.get()) return
                listener.onUserTranscript(text, isFinal)
            }

            override fun onTurnComplete() {
                if (epoch != currentEpoch.get()) return
                listener.onTurnComplete()
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

        // Stage-4 silence watchdog: 15-second timer started when WebSocket opens / setup is sent.
        // Turn G: a slow server must not burn a valid resumption handle. The handle
        // is cleared ONLY on explicit server rejection during CONFIGURING (handleSessionEnded),
        // never on silence timeout. Keep the silence-triggered reconnect itself.
        stage4WatchdogJob?.cancel()
        stage4WatchdogJob = scope.launch {
            delayProvider(STAGE4_SILENCE_TIMEOUT_MS)
            if (_state.value == SessionManagerState.CONFIGURING && epoch == currentEpoch.get()) {
                Log.w(TAG, "MIRA_LIVE_VOICE: Stage-4 silence detected")
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

        // Turn G: the server accepted the resumption handle (single-use —
        // it is now invalid). Clear it from the store ONLY if it is still
        // the handle we resumed with, so a newer handle that arrived
        // meanwhile is never wiped.
        val resumed = pendingResumptionHandleValue != null
        if (resumed) {
            val stored = resumptionStore.getHandle()
            if (stored != null && stored == pendingResumptionHandleValue) {
                resumptionStore.clearHandle()
                Log.i(TAG, "MIRA_LIVE_VOICE: Resumed session accepted by server — consumed handle (length=${pendingResumptionHandleValue?.length})")
            }
            pendingResumptionHandleValue = null
        }
        LiveVoiceDiagnostics.recordResumedWithContext(resumed)

        transitionTo(SessionManagerState.CONNECTED)
        listener.onSessionResumed(resumed)
        currentClient?.enableMic(true)
    }

    private fun handleMicError(reason: String, epoch: Int) {
        if (epoch != currentEpoch.get()) {
            Log.d(TAG, "MIRA_LIVE_VOICE: Ignoring handleMicError from stale epoch $epoch (current: ${currentEpoch.get()})")
            return
        }
        if (isExplicitlyStopped.get() || _state.value == SessionManagerState.STOPPED) return
        Log.e(TAG, "MIRA_LIVE_VOICE: Mic died after internal retries: $reason — restarting session")
        LiveVoiceDiagnostics.recordDropReason("mic-died: $reason")
        // Turn G: surface the mic death to the session listener (MicService
        // diagnostics) instead of swallowing it — the restart below is the
        // recovery, the callback is the record of WHY it restarted.
        listener.onMicError(reason)
        launchReconnect(isGoAway = false, isStage4Timeout = false)
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
        // Turn G (verified bug): the handle was saved ONLY in CONNECTED, but
        // the server sends sessionResumptionUpdate right after setup — while
        // the state is still CONFIGURING (setupComplete has not arrived
        // yet). Those handles were dropped, so EVERY reconnect was a fresh
        // session with no context. Save during CONFIGURING and CONNECTED.
        if (_state.value != SessionManagerState.CONNECTED &&
            _state.value != SessionManagerState.CONFIGURING
        ) {
            Log.d(TAG, "MIRA_LIVE_VOICE: sessionResumptionUpdate ignored outside CONFIGURING/CONNECTED state (state=${_state.value})")
            return
        }
        if (handle.isBlank()) {
            Log.w(TAG, "MIRA_LIVE_VOICE: sessionResumptionUpdate ignored (empty handle)")
            return
        }

        // Security: NEVER log handle value — length and status only
        Log.i(TAG, "MIRA_LIVE_VOICE: Saving valid resumption handle (length=${handle.length})")
        resumptionStore.saveHandle(handle)
        LiveVoiceDiagnostics.recordResumptionHandleEvent("saved (len=${handle.length})")
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
        LiveVoiceDiagnostics.recordGoAway()

        if (!hasValidHandle) {
            Log.w(TAG, "MIRA_LIVE_VOICE: GoAway received without valid handle; context will not be resumed")
            LiveVoiceDiagnostics.updateLastError("Server GoAway received with no resumption handle — context cannot be resumed")
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

        // Turn G instrumentation: log every session end with the reason, the
        // state it died in, and whether a resumption handle was available
        // at that moment (length only, never the value) — the next phone
        // test can tell which suspect fired from logcat alone.
        val handleAtEnd = try {
            resumptionStore.getHandle()
        } catch (_: Throwable) {
            null
        }
        Log.w(
            TAG,
            "MIRA_LIVE_VOICE: Session-end context: state=${_state.value} " +
                "hasResumptionHandle=${handleAtEnd != null} " +
                "handleLength=${handleAtEnd?.length ?: 0} retryCount=$retryCount"
        )
        LiveVoiceDiagnostics.recordDropReason("socket-dropped: $reason (state=${_state.value})")

        stage4WatchdogJob?.cancel()
        stage4WatchdogJob = null
        activeFunctionCalls.set(0)
        isBotSpeaking = false
        isUserSpeaking = false

        // If disconnected while CONFIGURING (setup / resumption handshake rejected by server),
        // clear stored handle to fall back to a fresh session on retry.
        // Never clear stored handle when CONNECTED or RECONNECTING, as reconnect must resume.
        if (_state.value == SessionManagerState.CONFIGURING) {
            Log.w(TAG, "MIRA_LIVE_VOICE: Disconnected while CONFIGURING; clearing rejected resumption handle")
            resumptionStore.clearHandle()
            pendingResumptionHandleValue = null
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
                LiveVoiceDiagnostics.recordDropReason("retries-exhausted: $errorMsg")
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

            // Turn G: PEEK at the handle — do NOT consume it up front. A
            // resumption handle is single-use on the SERVER side: if this
            // reconnect attempt never reaches the server (network failure,
            // app backgrounded), the handle was never used and must survive
            // for the next attempt. It is cleared only when the server
            // accepts it (setupComplete) or rejects it (CONFIGURING failure).
            val resumptionHandle = resumptionStore.getHandle()
            if (resumptionHandle != null) {
                Log.i(TAG, "MIRA_LIVE_VOICE: Reconnecting with stored resumption handle (length=${resumptionHandle.length})")
                LiveVoiceDiagnostics.recordResumptionHandleEvent("reconnecting_with_handle (len=${resumptionHandle.length})")
            } else {
                Log.w(TAG, "MIRA_LIVE_VOICE: Reconnecting with fresh session (no valid handle)")
                LiveVoiceDiagnostics.recordResumptionHandleEvent("reconnecting_fresh_no_handle")
                LiveVoiceDiagnostics.updateLastError("Resumption unavailable — restarting voice conversation")
            }

            connectInternal(resumptionHandle = resumptionHandle)
        }
    }
}

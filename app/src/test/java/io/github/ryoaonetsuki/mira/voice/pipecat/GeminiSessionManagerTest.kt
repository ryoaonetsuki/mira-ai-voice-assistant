package io.github.ryoaonetsuki.mira.voice.pipecat

import ai.pipecat.client.PipecatEventCallbacks
import ai.pipecat.client.types.TransportState
import ai.pipecat.client.types.Value
import android.content.Context
import io.github.ryoaonetsuki.mira.voice.SessionResumptionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GeminiSessionManagerTest {

    private lateinit var context: Context
    private lateinit var store: SessionResumptionStore
    private lateinit var listener: TestSessionManagerListener
    private lateinit var fakeFactory: FakeSessionClientFactory

    class TestSessionManagerListener : SessionManagerListener {
        val stateTransitions = mutableListOf<SessionManagerState>()
        val errors = mutableListOf<String>()
        val audioLevels = mutableListOf<Float>()
        val userTranscripts = mutableListOf<String>()
        val modelTranscripts = mutableListOf<String>()
        val botSpeakingStates = mutableListOf<Boolean>()
        val userSpeakingStates = mutableListOf<Boolean>()
        val reconnectingAttempts = mutableListOf<Pair<Int, Int>>()
        val endedReasons = mutableListOf<String>()
        val functionCalls = mutableListOf<Triple<String, String, Map<String, String>>>()

        override fun onStateChanged(state: SessionManagerState) {
            stateTransitions.add(state)
        }

        override fun onAudioLevel(level: Float) {
            audioLevels.add(level)
        }

        override fun onUserTranscript(text: String) {
            userTranscripts.add(text)
        }

        override fun onModelTranscript(text: String) {
            modelTranscripts.add(text)
        }

        override fun onBotSpeakingChanged(isSpeaking: Boolean) {
            botSpeakingStates.add(isSpeaking)
        }

        override fun onUserSpeakingChanged(isSpeaking: Boolean) {
            userSpeakingStates.add(isSpeaking)
        }

        override fun onSessionEnded(reason: String, error: Throwable?) {
            endedReasons.add(reason)
        }

        override fun onReconnecting(attempt: Int, maxAttempts: Int) {
            reconnectingAttempts.add(attempt to maxAttempts)
        }

        override fun onError(message: String) {
            errors.add(message)
        }

        override fun onFunctionCall(id: String, name: String, args: Value.Array, rawArgs: Map<String, String>) {
            functionCalls.add(Triple(id, name, rawArgs))
        }
    }

    class FakeSessionClient(
        val options: MiraGeminiServiceOptions,
        val sessionListener: MiraGeminiLiveTransport.SessionListener,
        val callbacks: PipecatEventCallbacks,
        var fireSessionEndedOnDisconnect: Boolean = true
    ) : SessionClient {
        var isConnected = false
        override var isClosed = false
        var micEnabled = false
        var disconnectCallCount = 0
        var releaseCallCount = 0
        val sentMessages = mutableListOf<String>()
        private var sessionEndedFiredOnDisconnect = false

        override fun connect(options: MiraGeminiServiceOptions) {
            isConnected = true
        }

        override fun disconnect() {
            isClosed = true
            isConnected = false
            disconnectCallCount++
            if (fireSessionEndedOnDisconnect && !sessionEndedFiredOnDisconnect) {
                sessionEndedFiredOnDisconnect = true
                sessionListener.onSessionEnded("websocket closed", null)
            }
        }

        override fun release() {
            isClosed = true
            isConnected = false
            releaseCallCount++
        }

        override fun enableMic(enabled: Boolean) {
            micEnabled = enabled
        }

        override fun sendUserMessage(content: String) {
            sentMessages.add(content)
        }
    }

    class FakeSessionClientFactory(
        var defaultFireSessionEndedOnDisconnect: Boolean = true
    ) : SessionClientFactory {
        val createdClients = mutableListOf<FakeSessionClient>()

        override fun createClient(
            context: Context,
            options: MiraGeminiServiceOptions,
            sessionListener: MiraGeminiLiveTransport.SessionListener,
            callbacks: PipecatEventCallbacks
        ): SessionClient {
            val client = FakeSessionClient(options, sessionListener, callbacks, defaultFireSessionEndedOnDisconnect)
            createdClients.add(client)
            return client
        }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        store = SessionResumptionStore(context)
        store.clearHandle()
        listener = TestSessionManagerListener()
        fakeFactory = FakeSessionClientFactory()
        GeminiSessionManager.resetLiveSessionsCount()
    }

    @After
    fun tearDown() {
        GeminiSessionManager.resetLiveSessionsCount()
    }

    private fun createManager(testScope: TestScope): GeminiSessionManager {
        return GeminiSessionManager(
            context = context,
            apiKey = "test_api_key_12345",
            voiceName = "Aoede",
            language = "bn-BD",
            systemPrompt = "You are Mira.",
            resumptionStore = store,
            listener = listener,
            externalScope = testScope,
            clientSessionFactory = fakeFactory
        )
    }

    @Test
    fun test1_IdleToConnectingToConfiguringToConnected() = runTest {
        val manager = createManager(this)

        assertEquals(SessionManagerState.IDLE, manager.state.value)

        // 1. startSession() -> CONNECTING
        manager.startSession()
        runCurrent()
        assertEquals(SessionManagerState.CONNECTING, manager.state.value)
        assertEquals(1, fakeFactory.createdClients.size)
        val client1 = fakeFactory.createdClients[0]

        // 2. WebSocket open -> CONFIGURING
        client1.sessionListener.onWebSocketOpen()
        runCurrent()
        assertEquals(SessionManagerState.CONFIGURING, manager.state.value)

        // 3. setupComplete -> CONNECTED
        client1.sessionListener.onSetupComplete()
        runCurrent()
        assertEquals(SessionManagerState.CONNECTED, manager.state.value)
        assertTrue("Microphone should be enabled upon reaching CONNECTED", client1.micEnabled)

        assertEquals(
            listOf(
                SessionManagerState.CONNECTING,
                SessionManagerState.CONFIGURING,
                SessionManagerState.CONNECTED
            ),
            listener.stateTransitions
        )
    }

    @Test
    fun test2_SessionResumptionUpdate_ResumableTrueSaves_FalseIgnored_AndGating() = runTest {
        val manager = createManager(this)
        manager.startSession()
        runCurrent()
        val client = fakeFactory.createdClients[0]
        client.sessionListener.onWebSocketOpen()
        client.sessionListener.onSetupComplete()
        runCurrent()
        assertEquals(SessionManagerState.CONNECTED, manager.state.value)

        // (a) resumable = true saves handle
        client.sessionListener.onSessionResumptionUpdate("handle_saved_1", resumable = true)
        assertEquals("handle_saved_1", store.getHandle())

        // (b) resumable = false ignores handle
        client.sessionListener.onSessionResumptionUpdate("handle_ignored_2", resumable = false)
        assertEquals("handle_saved_1", store.getHandle())

        // (c) resumable = true saves handle even when bot is speaking (prevents dropping turn-complete checkpoints)
        client.sessionListener.onBotStartedSpeaking()
        client.sessionListener.onSessionResumptionUpdate("handle_saved_during_speaking", resumable = true)
        assertEquals("handle_saved_during_speaking", store.getHandle())
        client.sessionListener.onBotStoppedSpeaking()

        // (d) Empty handle is ignored
        client.sessionListener.onSessionResumptionUpdate("", resumable = true)
        assertEquals("handle_saved_during_speaking", store.getHandle())

        // (e) Outside CONNECTED state, handle is ignored
        manager.stopSession()
        runCurrent()
        client.sessionListener.onSessionResumptionUpdate("handle_ignored_stopped", resumable = true)
        assertEquals("handle_saved_during_speaking", store.getHandle())
    }

    @Test
    fun test3_GoAwayWithValidHandle_GoAwayReceivedReconnectingConnected() = runTest {
        val manager = createManager(this)
        manager.startSession()
        runCurrent()
        val client1 = fakeFactory.createdClients[0]
        client1.sessionListener.onWebSocketOpen()
        client1.sessionListener.onSetupComplete()
        runCurrent()
        assertEquals(SessionManagerState.CONNECTED, manager.state.value)

        // Save valid handle
        store.saveHandle("handle_goaway_resume")
        assertEquals("handle_goaway_resume", store.getHandle())

        // Trigger GoAway (simulated or real event)
        manager.simulateGoAwayForTesting()
        runCurrent()

        // Should transition GOAWAY_RECEIVED -> RECONNECTING -> CONNECTING
        assertTrue(listener.stateTransitions.contains(SessionManagerState.GOAWAY_RECEIVED))
        assertTrue(listener.stateTransitions.contains(SessionManagerState.RECONNECTING))
        assertEquals(SessionManagerState.CONNECTING, manager.state.value)

        // Old client must be closed before opening reconnect socket
        assertTrue("Previous client must be closed", client1.isClosed)

        // Handle must be consumed (single-use guarantee)
        assertNull("Handle must be consumed from store during reconnection", store.getHandle())

        // Second client created with resumption handle
        assertEquals(2, fakeFactory.createdClients.size)
        val client2 = fakeFactory.createdClients[1]
        val modelConfigObj = client2.options.modelConfig as Value.Object
        val sessionResumption = modelConfigObj.value["sessionResumption"] as Value.Object
        val handleValue = (sessionResumption.value["handle"] as Value.Str).value
        assertEquals("handle_goaway_resume", handleValue)

        // Handshake completes on second client -> CONNECTED
        client2.sessionListener.onWebSocketOpen()
        client2.sessionListener.onSetupComplete()
        runCurrent()
        assertEquals(SessionManagerState.CONNECTED, manager.state.value)
    }

    @Test
    fun test4_Stage4Silence_TriggersReconnectWithBackoff() = runTest {
        val manager = createManager(this)
        manager.startSession()
        runCurrent()
        val client1 = fakeFactory.createdClients[0]

        // WebSocket open -> CONFIGURING (starts 15s watchdog)
        client1.sessionListener.onWebSocketOpen()
        runCurrent()
        assertEquals(SessionManagerState.CONFIGURING, manager.state.value)

        // Advance 14.9s — watchdog has NOT fired
        advanceTimeBy(14_900L)
        runCurrent()
        assertEquals(SessionManagerState.CONFIGURING, manager.state.value)
        assertFalse("Client 1 must not be closed before 15s timeout", client1.isClosed)

        // Advance remaining 100ms (15s total) — watchdog fires!
        advanceTimeBy(100L)
        runCurrent()

        assertTrue("Old WebSocket must be closed explicitly on Stage-4 silence", client1.isClosed)
        assertEquals(SessionManagerState.RECONNECTING, manager.state.value)

        // Backoff delay for retry 1 is 1000ms
        assertEquals(1, fakeFactory.createdClients.size)
        advanceTimeBy(1_000L)
        runCurrent()

        // Second client created after backoff
        assertEquals(2, fakeFactory.createdClients.size)
        val client2 = fakeFactory.createdClients[1]

        // Setup complete arrives on client2 -> recovers to CONNECTED
        client2.sessionListener.onWebSocketOpen()
        client2.sessionListener.onSetupComplete()
        runCurrent()
        assertEquals(SessionManagerState.CONNECTED, manager.state.value)
    }

    @Test
    fun test5_BackoffSchedule_1sTo5sMax5RetriesThenError() = runTest {
        val manager = createManager(this)
        manager.startSession()
        runCurrent()

        val expectedDelays = listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L)

        // Verify pure backoff function
        for (attempt in 1..5) {
            assertEquals(expectedDelays[attempt - 1], GeminiSessionManager.getBackoffDelayMs(attempt))
        }

        // Simulate 5 consecutive connection drop/failures
        for (attempt in 1..5) {
            val currentClient = fakeFactory.createdClients.last()
            currentClient.sessionListener.onSessionEnded("connection lost #$attempt", null)
            runCurrent()

            assertEquals(SessionManagerState.RECONNECTING, manager.state.value)
            assertEquals(attempt, listener.reconnectingAttempts.last().first)
            assertEquals(5, listener.reconnectingAttempts.last().second)

            // Before advancing delay, new client is not yet opened
            val clientsBeforeDelay = fakeFactory.createdClients.size

            // Advance by the backoff delay
            advanceTimeBy(expectedDelays[attempt - 1])
            runCurrent()

            // After backoff delay, new client is opened
            assertEquals(clientsBeforeDelay + 1, fakeFactory.createdClients.size)
        }

        // 6th failure (retryCount > 5) -> transitions to ERROR
        val lastClient = fakeFactory.createdClients.last()
        lastClient.sessionListener.onSessionEnded("final fatal disconnect", null)
        runCurrent()

        assertEquals(SessionManagerState.ERROR, manager.state.value)
        assertEquals(1, listener.errors.size)
        assertTrue(listener.errors[0].contains("Connection failed after 5 retries"))
    }

    @Test
    fun test6_ExplicitStopSession_CancelsReconnectionAndGoesToStopped() = runTest {
        val manager = createManager(this)
        manager.startSession()
        runCurrent()
        val client1 = fakeFactory.createdClients[0]

        // Disconnect triggers RECONNECTING
        client1.sessionListener.onSessionEnded("unexpected drop", null)
        runCurrent()
        assertEquals(SessionManagerState.RECONNECTING, manager.state.value)

        // Explicit stopSession() while reconnecting
        manager.stopSession()
        runCurrent()
        assertEquals(SessionManagerState.STOPPED, manager.state.value)

        // Advancing time must NOT trigger another reconnection attempt
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, fakeFactory.createdClients.size)
        assertEquals(SessionManagerState.STOPPED, manager.state.value)
    }

    @Test
    fun test7_ConcurrentSessionGuard_OldWebSocketClosedBeforeReconnect() = runTest {
        val manager = createManager(this)
        manager.startSession()
        runCurrent()
        val client1 = fakeFactory.createdClients[0]

        // Unexpected disconnect triggers reconnection
        client1.sessionListener.onSessionEnded("drop", null)
        runCurrent()

        // Verify client1 was closed before new connection is opened
        assertTrue("Previous client must be closed immediately on reconnect trigger", client1.isClosed)
        assertTrue(client1.disconnectCallCount > 0)
        assertTrue(client1.releaseCallCount > 0)

        // Advance backoff delay
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, fakeFactory.createdClients.size)

        // Test concurrent session limit of 1 instance (single-voice invariant, Round 24 Turn 5)
        GeminiSessionManager.resetLiveSessionsCount()
        val m1 = createManager(this)
        val m2 = createManager(this)

        m1.startSession()
        runCurrent()
        assertEquals(1, GeminiSessionManager.getLiveSessionsCount())

        // 2nd concurrent session must be refused
        m2.startSession()
        runCurrent()
        assertEquals(SessionManagerState.ERROR, m2.state.value)
        assertEquals(1, GeminiSessionManager.getLiveSessionsCount())
    }

    @Test
    fun test8_FailedResumeClearsHandleAndFallsBackToFreshSession() = runTest {
        val manager = createManager(this)

        // Store a handle
        store.saveHandle("stale_resume_handle")
        assertEquals("stale_resume_handle", store.getHandle())

        // Connect with GoAway simulation
        manager.startSession()
        runCurrent()
        val client1 = fakeFactory.createdClients[0]
        client1.sessionListener.onWebSocketOpen()
        client1.sessionListener.onSetupComplete()
        runCurrent()

        // GoAway triggers reconnect using the handle
        manager.simulateGoAwayForTesting()
        runCurrent()
        val client2 = fakeFactory.createdClients[1]

        // Resumption handshake fails (e.g. server returns 400/1008 or closed before setupComplete)
        client2.sessionListener.onSessionEnded("400 Bad Request: Invalid session handle", null)
        runCurrent()

        // Stored handle should be cleared
        assertNull(store.getHandle())

        // Next reconnect attempt should use fresh session (handle = null). Retry #2 backoff is 2000ms.
        advanceTimeBy(2_000L)
        runCurrent()
        val client3 = fakeFactory.createdClients[2]
        val modelConfigObj = client3.options.modelConfig as Value.Object
        val sessionResumption = modelConfigObj.value["sessionResumption"] as Value.Object
        assertFalse("Fresh fallback session must NOT have handle", sessionResumption.value.containsKey("handle"))
    }

    @Test
    fun test9_AudioContinuity_DropAudioDuringReconnecting() = runTest {
        val manager = createManager(this)
        manager.startSession()
        runCurrent()
        val client = fakeFactory.createdClients[0]
        client.sessionListener.onWebSocketOpen()
        client.sessionListener.onSetupComplete()
        runCurrent()

        // Audio level updates during CONNECTED
        client.sessionListener.onUserAudioLevel(0.85f)
        assertEquals(0.85f, manager.audioLevel.value, 0.001f)

        // Disconnect -> RECONNECTING
        client.sessionListener.onSessionEnded("network drop", null)
        runCurrent()

        // Audio level must be reset to 0f during RECONNECTING
        assertEquals(0f, manager.audioLevel.value, 0.001f)
        assertEquals(0f, listener.audioLevels.last(), 0.001f)
    }

    @Test
    fun test10_ReconnectDoesNotCascadeOnIntentionalClose() = runTest {
        val manager = createManager(this)
        manager.startSession()
        runCurrent()
        val client1 = fakeFactory.createdClients[0]

        // 1. Reach CONNECTED on initial client
        client1.sessionListener.onWebSocketOpen()
        client1.sessionListener.onSetupComplete()
        runCurrent()
        assertEquals(SessionManagerState.CONNECTED, manager.state.value)

        // 2. Save valid handle
        store.saveHandle("valid_handle_round19_task3")
        assertEquals("valid_handle_round19_task3", store.getHandle())

        // 3. Proactive GoAway simulation
        manager.simulateGoAwayForTesting()
        runCurrent()

        // Assert: exactly 2 clients EVER created
        assertEquals(2, fakeFactory.createdClients.size)
        val client2 = fakeFactory.createdClients[1]

        // Assert: retryCount/onReconnecting fired exactly once (attempt 1)
        assertEquals(1, listener.reconnectingAttempts.size)
        assertEquals(1 to 5, listener.reconnectingAttempts[0])

        // Assert: stored handle was consumed exactly once (second client's options carry it)
        assertNull("Stored handle must be consumed from store", store.getHandle())
        val modelConfigObj = client2.options.modelConfig as Value.Object
        val sessionResumption = modelConfigObj.value["sessionResumption"] as Value.Object
        val handleValue = (sessionResumption.value["handle"] as Value.Str).value
        assertEquals("valid_handle_round19_task3", handleValue)

        // 4. Reach CONNECTED on the second client
        client2.sessionListener.onWebSocketOpen()
        client2.sessionListener.onSetupComplete()
        runCurrent()
        assertEquals(SessionManagerState.CONNECTED, manager.state.value)

        // 5. Advance 60s of virtual time
        advanceTimeBy(60_000L)
        runCurrent()

        // Assert: exactly 2 clients were EVER created, no additional clients appear
        assertEquals(2, fakeFactory.createdClients.size)

        // Assert: no additional reconnect attempts fired
        assertEquals(1, listener.reconnectingAttempts.size)

        // Assert: no ERROR state occurs
        assertEquals(0, listener.errors.size)
        assertEquals(SessionManagerState.CONNECTED, manager.state.value)
    }

    @Test
    fun test11_Stage4WatchdogDoesNotCascade() = runTest {
        val manager = createManager(this)
        manager.startSession()
        runCurrent()
        val client1 = fakeFactory.createdClients[0]

        // 1. Reach CONFIGURING
        client1.sessionListener.onWebSocketOpen()
        runCurrent()
        assertEquals(SessionManagerState.CONFIGURING, manager.state.value)

        // 2. Advance 15s — Stage-4 silence watchdog fires
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(SessionManagerState.RECONNECTING, manager.state.value)
        assertTrue("Client 1 must be closed on Stage-4 silence timeout", client1.isClosed)

        // 3. Advance 1s backoff delay for retry #1
        advanceTimeBy(1_000L)
        runCurrent()

        // Assert: exactly 2 clients created
        assertEquals(2, fakeFactory.createdClients.size)
        val client2 = fakeFactory.createdClients[1]

        // Setup complete arrives on client2 -> recovers to CONNECTED
        client2.sessionListener.onWebSocketOpen()
        client2.sessionListener.onSetupComplete()
        runCurrent()
        assertEquals(SessionManagerState.CONNECTED, manager.state.value)

        // 4. Advance 60s of virtual time — assert no cascade
        advanceTimeBy(60_000L)
        runCurrent()

        // Assert: exactly 2 clients were EVER created
        assertEquals(2, fakeFactory.createdClients.size)
        assertEquals(1, listener.reconnectingAttempts.size)
        assertEquals(1 to 5, listener.reconnectingAttempts[0])
        assertEquals(0, listener.errors.size)
        assertEquals(SessionManagerState.CONNECTED, manager.state.value)
    }

    @Test
    fun test12_Stage4Silence_TerminalErrorAfterMaxRetries() = runTest {
        val manager = createManager(this)
        manager.startSession()
        runCurrent()

        val expectedDelays = listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L)

        // For each attempt 1..5: WebSocket opens, but setupComplete never arrives (15s timeout)
        for (attempt in 1..5) {
            val currentClient = fakeFactory.createdClients.last()
            currentClient.sessionListener.onWebSocketOpen()
            runCurrent()
            assertEquals(SessionManagerState.CONFIGURING, manager.state.value)

            // Watchdog fires after 15s
            advanceTimeBy(15_000L)
            runCurrent()
            assertEquals(SessionManagerState.RECONNECTING, manager.state.value)
            assertEquals(attempt, listener.reconnectingAttempts.last().first)

            // Advance backoff delay
            advanceTimeBy(expectedDelays[attempt - 1])
            runCurrent()
            assertEquals(attempt + 1, fakeFactory.createdClients.size)
        }

        // 6th client also encounters Stage-4 silence timeout
        val finalClient = fakeFactory.createdClients.last()
        finalClient.sessionListener.onWebSocketOpen()
        runCurrent()
        assertEquals(SessionManagerState.CONFIGURING, manager.state.value)

        // 15s expires on 6th client -> retryCount > 5 -> terminal ERROR
        advanceTimeBy(15_000L)
        runCurrent()

        assertEquals(SessionManagerState.ERROR, manager.state.value)
        assertEquals(1, listener.errors.size)
        assertEquals("Live voice: Setup timed out after 15s — tap to retry", listener.errors[0])
    }

    @Test
    fun test13_EchoGuardPlumbingThroughOptions() = runTest {
        // Default constructor has echoGuardEnabled = true
        val defaultManager = createManager(this)
        defaultManager.startSession()
        runCurrent()
        val defaultClient = fakeFactory.createdClients.last()
        assertTrue("Default manager must configure echoGuardEnabled = true in service options", defaultClient.options.echoGuardEnabled)
        defaultManager.stopSession()
        runCurrent()

        // Explicitly disabled echoGuard
        val disabledManager = GeminiSessionManager(
            context = context,
            apiKey = "test_api_key_12345",
            voiceName = "Aoede",
            language = "bn-BD",
            systemPrompt = "You are Mira.",
            resumptionStore = store,
            listener = listener,
            externalScope = this,
            clientSessionFactory = fakeFactory,
            echoGuardEnabled = false
        )
        disabledManager.startSession()
        runCurrent()
        val disabledClient = fakeFactory.createdClients.last()
        assertFalse("Explicit echoGuardEnabled = false must plumb through to service options", disabledClient.options.echoGuardEnabled)
        disabledManager.stopSession()
        runCurrent()
    }

    @Test
    fun test14_GoAwayWithoutHandle_ExplicitlyWarnsInDiagnosticsAndReconnectsFresh() = runTest {
        val manager = createManager(this)
        manager.startSession()
        runCurrent()
        val client1 = fakeFactory.createdClients[0]
        client1.sessionListener.onWebSocketOpen()
        client1.sessionListener.onSetupComplete()
        runCurrent()
        assertEquals(SessionManagerState.CONNECTED, manager.state.value)

        // Store has NO handle
        store.clearHandle()
        assertNull(store.getHandle())

        // Server sends GoAway
        client1.sessionListener.onGoAway("60s")
        runCurrent()

        // Transitions GOAWAY_RECEIVED -> RECONNECTING -> CONNECTING
        assertTrue(listener.stateTransitions.contains(SessionManagerState.GOAWAY_RECEIVED))
        assertTrue(listener.stateTransitions.contains(SessionManagerState.RECONNECTING))
        assertEquals(SessionManagerState.CONNECTING, manager.state.value)

        // Second client connects fresh (no handle)
        assertEquals(2, fakeFactory.createdClients.size)
        val client2 = fakeFactory.createdClients[1]
        val modelConfigObj = client2.options.modelConfig as Value.Object
        val sessionResumption = modelConfigObj.value["sessionResumption"] as Value.Object
        assertNull("Reconnected client must NOT have handle when store was empty", sessionResumption.value["handle"])
    }

    @Test
    fun test15_UnexpectedDisconnectInConnectedState_ResumesWithStoredHandle() = runTest {
        val manager = createManager(this)
        manager.startSession()
        runCurrent()
        val client1 = fakeFactory.createdClients[0]
        client1.sessionListener.onWebSocketOpen()
        client1.sessionListener.onSetupComplete()
        runCurrent()
        assertEquals(SessionManagerState.CONNECTED, manager.state.value)

        // Save valid handle from turn
        client1.sessionListener.onSessionResumptionUpdate("handle_preserve_on_drop", resumable = true)
        assertEquals("handle_preserve_on_drop", store.getHandle())

        // Connection drops unexpectedly (server drop / network glitch)
        client1.sessionListener.onSessionEnded("connection dropped by peer", null)
        runCurrent()

        // Delay for backoff #1 (1000ms)
        advanceTimeBy(1000L)
        runCurrent()

        // Must reconnect using the stored resumption handle
        assertEquals(2, fakeFactory.createdClients.size)
        val client2 = fakeFactory.createdClients[1]
        val modelConfigObj = client2.options.modelConfig as Value.Object
        val sessionResumption = modelConfigObj.value["sessionResumption"] as Value.Object
        val handleValue = (sessionResumption.value["handle"] as Value.Str).value
        assertEquals("handle_preserve_on_drop", handleValue)
    }

    @Test
    fun test16_HandshakeFailureInConfiguring_ClearsRejectedHandleAndFallsBackToFresh() = runTest {
        val manager = createManager(this)
        manager.startSession()
        runCurrent()
        val client1 = fakeFactory.createdClients[0]
        client1.sessionListener.onWebSocketOpen()
        runCurrent()
        assertEquals(SessionManagerState.CONFIGURING, manager.state.value)

        // Pre-existing handle was in store
        store.saveHandle("bad_handle_rejected_by_server")

        // Handshake fails while in CONFIGURING state
        client1.sessionListener.onSessionEnded("setup handshake rejected: invalid handle", null)
        runCurrent()

        // Handle MUST be cleared from store so next retry falls back to fresh session
        assertNull("Corrupt handle must be cleared after CONFIGURING handshake failure", store.getHandle())

        // Advance retry backoff
        advanceTimeBy(1000L)
        runCurrent()

        // Next retry client is created with fresh session (no handle)
        assertEquals(2, fakeFactory.createdClients.size)
        val client2 = fakeFactory.createdClients[1]
        val modelConfigObj = client2.options.modelConfig as Value.Object
        val sessionResumption = modelConfigObj.value["sessionResumption"] as Value.Object
        assertNull("Retry client must not have handle after rejection", sessionResumption.value["handle"])
    }

    @Test
    fun test17_StopTwice_IsIdempotentAndSafe() = runTest {
        val manager = createManager(this)
        manager.startSession()
        runCurrent()

        // First stop
        manager.stopSession()
        runCurrent()
        assertEquals(SessionManagerState.STOPPED, manager.state.value)
        assertEquals(0, GeminiSessionManager.getLiveSessionsCount())

        // Second stop must be safe no-op
        manager.stopSession()
        runCurrent()
        assertEquals(SessionManagerState.STOPPED, manager.state.value)
        assertEquals(0, GeminiSessionManager.getLiveSessionsCount())
    }

    @Test
    fun test18_StopDuringConnecting_DoesNotStartOrCrash() = runTest {
        val manager = createManager(this)
        manager.startSession()
        runCurrent()
        assertEquals(SessionManagerState.CONNECTING, manager.state.value)

        // User stops while still connecting
        manager.stopSession()
        runCurrent()
        assertEquals(SessionManagerState.STOPPED, manager.state.value)
        assertEquals(0, GeminiSessionManager.getLiveSessionsCount())

        // Stale late events from old socket must be ignored
        val client = fakeFactory.createdClients[0]
        client.sessionListener.onWebSocketOpen()
        client.sessionListener.onSetupComplete()
        runCurrent()
        assertEquals("State must remain STOPPED after late socket events", SessionManagerState.STOPPED, manager.state.value)
    }

    @Test
    fun test19_StopAfterWebsocketFailed_DoesNotCrash() = runTest {
        val manager = createManager(this)
        manager.startSession()
        runCurrent()
        val client = fakeFactory.createdClients[0]

        // Socket fails
        client.sessionListener.onSessionEnded("socket failure", java.io.IOException("connection reset"))
        runCurrent()
        assertEquals(SessionManagerState.RECONNECTING, manager.state.value)

        // User turns voice off during reconnecting
        manager.stopSession()
        runCurrent()
        assertEquals(SessionManagerState.STOPPED, manager.state.value)
        assertEquals(0, GeminiSessionManager.getLiveSessionsCount())

        // Advance time to verify reconnect job was cancelled
        advanceTimeBy(5000L)
        runCurrent()
        assertEquals("No new client should be created after stop", 1, fakeFactory.createdClients.size)
        assertEquals(SessionManagerState.STOPPED, manager.state.value)
    }

    @Test
    fun test20_StopRacingStartSession_DoesNotReviveStoppedManager() = runTest {
        val manager = createManager(this)
        manager.startSession()
        runCurrent()
        manager.stopSession()
        runCurrent()
        assertEquals(SessionManagerState.STOPPED, manager.state.value)

        // Attempting to call startSession on an explicitly stopped manager must be ignored
        manager.startSession()
        runCurrent()
        assertEquals("Manager must not be revived after stopSession()", SessionManagerState.STOPPED, manager.state.value)
        assertEquals(0, GeminiSessionManager.getLiveSessionsCount())
    }
}

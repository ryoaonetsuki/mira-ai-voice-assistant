package io.github.ryoaonetsuki.mira.voice.pipecat

import ai.pipecat.client.PipecatEventCallbacks
import ai.pipecat.client.types.APIRequest
import ai.pipecat.client.types.Value
import android.content.Context
import io.github.ryoaonetsuki.mira.voice.SessionResumptionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * End-to-end integration and regression suite for Round 19 Pipecat voice architecture.
 *
 * Verifies:
 * 1. Voice and persona changes take effect on next session start.
 * 2. Direct Google API route generates correct endpoint URL, auth header name, and model.
 * 3. deserializeConnectParams plumbed correctly with voice, language, model, system instruction, and handle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VoiceIntegrationRegressionTest {

    private lateinit var context: Context
    private lateinit var store: SessionResumptionStore
    private lateinit var fakeFactory: GeminiSessionManagerTest.FakeSessionClientFactory
    private lateinit var listener: GeminiSessionManagerTest.TestSessionManagerListener

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        store = SessionResumptionStore(context)
        store.clearHandle()
        fakeFactory = GeminiSessionManagerTest.FakeSessionClientFactory()
        listener = GeminiSessionManagerTest.TestSessionManagerListener()
        GeminiSessionManager.resetLiveSessionsCount()
    }

    private fun createManager(
        scope: TestScope,
        voiceName: String = "Aoede",
        language: String = "bn-BD",
        systemPrompt: String = "You are Mira"
    ): GeminiSessionManager {
        return GeminiSessionManager(
            context = context,
            apiKey = "test-api-key-round19",
            voiceName = voiceName,
            language = language,
            systemPrompt = systemPrompt,
            resumptionStore = store,
            listener = listener,
            externalScope = scope,
            clientSessionFactory = fakeFactory
        )
    }

    @Test
    fun testVoiceAndPersonaChangeTakesEffectOnNextSessionStart() = runTest {
        // --- Session 1: Voice "Aoede", Persona "Mira" ---
        val manager1 = createManager(
            scope = this,
            voiceName = "Aoede",
            systemPrompt = "Tone: Warm companion. You are Mira."
        )
        manager1.startSession()
        runCurrent()

        assertEquals(1, fakeFactory.createdClients.size)
        val client1 = fakeFactory.createdClients[0]
        val options1 = client1.options
        val modelConfig1 = options1.modelConfig as Value.Object

        // Assert voice 1
        val speechConfig1 = (modelConfig1.value["generationConfig"] as Value.Object).value["speechConfig"] as Value.Object
        val voiceConfig1 = speechConfig1.value["voiceConfig"] as Value.Object
        val prebuilt1 = voiceConfig1.value["prebuiltVoiceConfig"] as Value.Object
        assertEquals("Aoede", (prebuilt1.value["voiceName"] as Value.Str).value)

        // Assert persona 1 system instruction
        val sysInstruction1 = modelConfig1.value["systemInstruction"] as Value.Object
        val parts1 = sysInstruction1.value["parts"] as Value.Array
        val textPart1 = (parts1.value[0] as Value.Object).value["text"] as Value.Str
        assertTrue("Session 1 must carry Mira prompt", textPart1.value.contains("You are Mira"))

        // Handshake and stop Session 1
        client1.sessionListener.onWebSocketOpen()
        client1.sessionListener.onSetupComplete()
        runCurrent()
        manager1.stopSession()
        runCurrent()
        assertEquals(SessionManagerState.STOPPED, manager1.state.value)

        // --- Session 2: User changes to Voice "Fenrir", Persona "Orion" ---
        val manager2 = createManager(
            scope = this,
            voiceName = "Fenrir",
            systemPrompt = "Tone: Deeply thoughtful companion. You are Orion."
        )
        manager2.startSession()
        runCurrent()

        assertEquals(2, fakeFactory.createdClients.size)
        val client2 = fakeFactory.createdClients[1]
        val options2 = client2.options
        val modelConfig2 = options2.modelConfig as Value.Object

        // Assert updated voice 2
        val speechConfig2 = (modelConfig2.value["generationConfig"] as Value.Object).value["speechConfig"] as Value.Object
        val voiceConfig2 = speechConfig2.value["voiceConfig"] as Value.Object
        val prebuilt2 = voiceConfig2.value["prebuiltVoiceConfig"] as Value.Object
        assertEquals("Fenrir", (prebuilt2.value["voiceName"] as Value.Str).value)

        // Assert updated persona 2 system instruction
        val sysInstruction2 = modelConfig2.value["systemInstruction"] as Value.Object
        val parts2 = sysInstruction2.value["parts"] as Value.Array
        val textPart2 = (parts2.value[0] as Value.Object).value["text"] as Value.Str
        assertTrue("Session 2 must carry Orion prompt", textPart2.value.contains("You are Orion"))
        assertFalse("Session 2 must NOT carry old Mira prompt", textPart2.value.contains("You are Mira"))

        manager2.stopSession()
        runCurrent()
    }

    @Test
    fun testDirectRouteConfiguration() {
        val directOptions = MiraGeminiServiceOptions.forDirect(
            apiKey = "AIzaSyDirectTestKey",
            model = "models/gemini-3.8-live",
            voiceName = "Kore",
            languageCode = "bn-BD",
            systemInstructionText = "Direct route test"
        )

        assertEquals(
            "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
            directOptions.endpointUrl
        )
        assertEquals("x-goog-api-key", directOptions.authHeaderName)
        assertEquals("AIzaSyDirectTestKey", directOptions.apiKey)

        val modelConfig = directOptions.modelConfig as Value.Object
        assertEquals("models/gemini-3.8-live", (modelConfig.value["model"] as Value.Str).value)
        assertFalse("Direct route must NEVER contain transparent", modelConfig.value.containsKey("transparent"))
    }

    @Test
    fun testDeserializeConnectParamsVoicePlumbing() {
        val transport = MiraGeminiLiveTransport(context)
        val startBotRequest = APIRequest(
            endpoint = "https://example.com/start",
            requestData = Value.Null
        )

        // 1. JSON with custom voice, language, model, system instruction, resumption handle
        val jsonInput = JSONObject().apply {
            put("api_key", "test-key-deserialize")
            put("endpoint_url", "wss://custom.relay.dev/live")
            put("auth_header_name", "x-custom-key")
            put("voice", "Fenrir")
            put("language", "bn-BD")
            put("model", "models/gemini-3.8-live")
            put("system_instruction", "You are Orion companion.")
            put("resumption_handle", "resume-token-xyz")
        }.toString()

        val options = transport.deserializeConnectParams(jsonInput, startBotRequest)
        assertEquals("test-key-deserialize", options.apiKey)
        assertEquals("wss://custom.relay.dev/live", options.endpointUrl)
        assertEquals("x-custom-key", options.authHeaderName)

        val modelConfig = options.modelConfig as Value.Object
        assertEquals("models/gemini-3.8-live", (modelConfig.value["model"] as Value.Str).value)

        val speechConfig = (modelConfig.value["generationConfig"] as Value.Object).value["speechConfig"] as Value.Object
        val voiceConfig = speechConfig.value["voiceConfig"] as Value.Object
        val prebuilt = voiceConfig.value["prebuiltVoiceConfig"] as Value.Object
        assertEquals("Fenrir", (prebuilt.value["voiceName"] as Value.Str).value)
        assertEquals("bn-BD", (speechConfig.value["languageCode"] as Value.Str).value)

        val sysInstruction = modelConfig.value["systemInstruction"] as Value.Object
        val parts = sysInstruction.value["parts"] as Value.Array
        val textPart = (parts.value[0] as Value.Object).value["text"] as Value.Str
        assertEquals("You are Orion companion.", textPart.value)

        val sessionResumption = modelConfig.value["sessionResumption"] as Value.Object
        assertEquals("resume-token-xyz", (sessionResumption.value["handle"] as Value.Str).value)

        // 2. JSON without optional fields falls back to safe defaults
        val minimalJson = JSONObject().apply {
            put("api_key", "minimal-key")
        }.toString()

        val defaultOptions = transport.deserializeConnectParams(minimalJson, startBotRequest)
        val defaultModelConfig = defaultOptions.modelConfig as Value.Object
        val defaultSpeechConfig = (defaultModelConfig.value["generationConfig"] as Value.Object).value["speechConfig"] as Value.Object
        val defaultVoiceConfig = defaultSpeechConfig.value["voiceConfig"] as Value.Object
        val defaultPrebuilt = defaultVoiceConfig.value["prebuiltVoiceConfig"] as Value.Object
        assertEquals("Aoede", (defaultPrebuilt.value["voiceName"] as Value.Str).value)
        assertEquals("bn-BD", (defaultSpeechConfig.value["languageCode"] as Value.Str).value)
        assertFalse((defaultModelConfig.value["sessionResumption"] as Value.Object).value.containsKey("handle"))
    }
}

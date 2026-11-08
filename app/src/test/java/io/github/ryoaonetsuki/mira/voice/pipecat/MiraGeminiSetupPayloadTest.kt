package io.github.ryoaonetsuki.mira.voice.pipecat

import ai.pipecat.client.types.Value
import ai.pipecat.client.types.ValueSerializer
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying the Gemini Live setup payload structure constructed via
 * [buildPipecatModelConfig] per ROUND19_PLAN.md and the authority voice-fix specification.
 *
 * Assertions:
 * (a) model is models/gemini-3.8-live
 * (b) responseModalities is ["AUDIO"]
 * (c) sessionResumption.handle present only when provided
 * (d) contextWindowCompression.slidingWindow present
 * (e) top-level transparent NOT present
 */
class MiraGeminiSetupPayloadTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testSetupPayload_DefaultAndInitialSession() {
        val modelConfig = buildPipecatModelConfig(
            model = "models/gemini-3.8-live",
            voiceName = "Aoede",
            languageCode = "bn-BD",
            systemInstructionText = "You are Mira, a friendly AI companion.",
            resumptionHandle = null
        )

        // 1. Direct Value.Object structure verification
        // (a) Model assertion
        val modelValue = modelConfig.value["model"] as? Value.Str
        assertNotNull("model key must exist", modelValue)
        assertEquals("models/gemini-3.8-live", modelValue?.value)

        // (b) responseModalities is ["AUDIO"]
        val genConfig = modelConfig.value["generationConfig"] as? Value.Object
        assertNotNull("generationConfig must exist", genConfig)
        val modalities = genConfig?.value?.get("responseModalities") as? Value.Array
        assertNotNull("responseModalities must exist", modalities)
        assertEquals(1, modalities?.value?.size)
        assertEquals(Value.Str("AUDIO"), modalities?.value?.get(0))

        // (c) sessionResumption.handle NOT present for fresh session
        val sessionResumption = modelConfig.value["sessionResumption"] as? Value.Object
        assertNotNull("sessionResumption must exist", sessionResumption)
        assertFalse("Fresh session must NOT have handle in sessionResumption", sessionResumption!!.value.containsKey("handle"))

        // (d) contextWindowCompression.slidingWindow present
        val compression = modelConfig.value["contextWindowCompression"] as? Value.Object
        assertNotNull("contextWindowCompression must exist", compression)
        assertTrue("slidingWindow must be present in contextWindowCompression", compression!!.value.containsKey("slidingWindow"))

        // (e) top-level transparent NOT present
        assertFalse("Top-level transparent MUST NOT be present", modelConfig.value.containsKey("transparent"))

        // Speech config verification
        val speechConfig = genConfig.value["speechConfig"] as? Value.Object
        assertNotNull("speechConfig must exist", speechConfig)
        assertEquals("bn-BD", (speechConfig?.value?.get("languageCode") as? Value.Str)?.value)
        val voiceConfig = speechConfig?.value?.get("voiceConfig") as? Value.Object
        val prebuilt = voiceConfig?.value?.get("prebuiltVoiceConfig") as? Value.Object
        assertEquals("Aoede", (prebuilt?.value?.get("voiceName") as? Value.Str)?.value)

        // RealtimeInputConfig (automaticActivityDetection)
        val realtimeConfig = modelConfig.value["realtimeInputConfig"] as? Value.Object
        assertNotNull("realtimeInputConfig must exist", realtimeConfig)
        val autoActivity = realtimeConfig?.value?.get("automaticActivityDetection") as? Value.Object
        assertNotNull("automaticActivityDetection must exist", autoActivity)
        assertEquals("END_SENSITIVITY_HIGH", (autoActivity?.value?.get("endOfSpeechSensitivity") as? Value.Str)?.value)
        assertEquals(800.0, (autoActivity?.value?.get("silenceDurationMs") as? Value.Number)?.value ?: 0.0, 0.001)

        // Transcription configs present as empty objects
        assertTrue("inputAudioTranscription must be present", modelConfig.value.containsKey("inputAudioTranscription"))
        assertTrue("outputAudioTranscription must be present", modelConfig.value.containsKey("outputAudioTranscription"))

        // 2. Serialized JSON format verification
        val jsonString = json.encodeToString(ValueSerializer, modelConfig)
        val jsonObj = JSONObject(jsonString)

        assertEquals("models/gemini-3.8-live", jsonObj.getString("model"))
        val jsonGenConfig = jsonObj.getJSONObject("generationConfig")
        val jsonModalities = jsonGenConfig.getJSONArray("responseModalities")
        assertEquals(1, jsonModalities.length())
        assertEquals("AUDIO", jsonModalities.getString(0))
        assertFalse("JSON top-level must NOT contain transparent", jsonObj.has("transparent"))
        assertFalse("JSON generationConfig must NOT contain transparent", jsonGenConfig.has("transparent"))
        assertTrue("JSON must have slidingWindow in contextWindowCompression", jsonObj.getJSONObject("contextWindowCompression").has("slidingWindow"))
        assertFalse("JSON fresh sessionResumption must NOT contain handle", jsonObj.getJSONObject("sessionResumption").has("handle"))
    }

    @Test
    fun testSetupPayload_ResumptionSessionWithHandle() {
        val storedHandle = "c2Vzc2lvbl9oYW5kbGVfZXhhbXBsZV8xMjM0NQ"
        val modelConfig = buildPipecatModelConfig(
            model = "models/gemini-3.8-live",
            voiceName = "Fenrir",
            languageCode = "bn-BD",
            systemInstructionText = "Mira persona",
            resumptionHandle = storedHandle
        )

        // (c) sessionResumption.handle MUST be present when provided
        val sessionResumption = modelConfig.value["sessionResumption"] as? Value.Object
        assertNotNull("sessionResumption must exist", sessionResumption)
        assertTrue("sessionResumption MUST contain handle when provided", sessionResumption!!.value.containsKey("handle"))
        val handleValue = sessionResumption.value["handle"] as? Value.Str
        assertEquals(storedHandle, handleValue?.value)

        // Verify JSON serialization of resumption handle
        val jsonString = json.encodeToString(ValueSerializer, modelConfig)
        val jsonObj = JSONObject(jsonString)
        val jsonResumption = jsonObj.getJSONObject("sessionResumption")
        assertTrue("JSON sessionResumption must have handle", jsonResumption.has("handle"))
        assertEquals(storedHandle, jsonResumption.getString("handle"))

        // Still verifies other constraints:
        assertEquals("models/gemini-3.8-live", jsonObj.getString("model"))
        assertEquals("AUDIO", jsonObj.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        assertTrue(jsonObj.getJSONObject("contextWindowCompression").has("slidingWindow"))
        assertFalse(jsonObj.has("transparent"))
    }

    @Test
    fun testSetupPayload_BlankHandleTreatedAsFreshSession() {
        val modelConfigBlank = buildPipecatModelConfig(resumptionHandle = "   ")
        val sessionResumption = modelConfigBlank.value["sessionResumption"] as? Value.Object
        assertNotNull(sessionResumption)
        assertFalse("Blank handle must be treated as fresh session", sessionResumption!!.value.containsKey("handle"))
    }

    @Test
    fun testSetupPayload_EmptySystemInstructionProducesNull() {
        val modelConfig = buildPipecatModelConfig(systemInstructionText = "")
        assertEquals(Value.Null, modelConfig.value["systemInstruction"])

        val jsonString = json.encodeToString(ValueSerializer, modelConfig)
        val jsonObj = JSONObject(jsonString)
        assertTrue(jsonObj.isNull("systemInstruction"))
    }
}

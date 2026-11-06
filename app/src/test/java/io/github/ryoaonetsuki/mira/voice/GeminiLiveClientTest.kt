package io.github.ryoaonetsuki.mira.voice

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Unit tests for ROUND 12 native Gemini Live voice rebuild.
 *
 * Verifies:
 * 1. Setup JSON shape (asserts NO top-level responseModalities; asserts generationConfig.responseModalities == ["AUDIO"])
 * 2. PCM chunk format (16-bit LE, 16kHz)
 * 3. Audio base64 round-trip
 * 4. State machine transitions
 * 5. Timeout behavior and constants
 * 6. Language BCP-47 mapping
 */
class GeminiLiveClientTest {

    @Test
    fun testSetupPayload_NoTopLevelResponseModalities() {
        val payload = GeminiLiveClient.buildSetupPayload(
            model = "models/gemini-3.8-live",
            voiceName = "Aoede",
            languageCode = "bn-BD",
            systemInstructionText = "Mira persona + Bangla-only rule",
            resumptionHandle = "sample-handle-xyz"
        )

        assertTrue("Root must have 'setup' object", payload.has("setup"))
        val setup = payload.getJSONObject("setup")

        // CRITICAL SPEC ASSERTION (§0.2):
        // Server rejects 1008 if responseModalities is at setup top-level.
        assertFalse(
            "setup object MUST NOT contain top-level responseModalities",
            setup.has("responseModalities")
        )

        // CRITICAL SPEC ASSERTION:
        // responseModalities must live inside generationConfig.
        assertTrue("setup must contain generationConfig", setup.has("generationConfig"))
        val genConfig = setup.getJSONObject("generationConfig")
        assertTrue("generationConfig must contain responseModalities", genConfig.has("responseModalities"))
        val modalities = genConfig.getJSONArray("responseModalities")
        assertEquals(1, modalities.length())
        assertEquals("AUDIO", modalities.getString(0))

        // Model
        assertEquals("models/gemini-3.8-live", setup.getString("model"))

        // Speech config
        assertTrue(genConfig.has("speechConfig"))
        val speechConfig = genConfig.getJSONObject("speechConfig")
        assertEquals("bn-BD", speechConfig.getString("languageCode"))
        val voiceConfig = speechConfig.getJSONObject("voiceConfig")
        val prebuilt = voiceConfig.getJSONObject("prebuiltVoiceConfig")
        assertEquals("Aoede", prebuilt.getString("voiceName"))

        // RealtimeInputConfig (automaticActivityDetection)
        assertTrue(setup.has("realtimeInputConfig"))
        val realtime = setup.getJSONObject("realtimeInputConfig")
        val autoDetect = realtime.getJSONObject("automaticActivityDetection")
        assertEquals("END_SENSITIVITY_HIGH", autoDetect.getString("endOfSpeechSensitivity"))
        assertEquals(800, autoDetect.getInt("silenceDurationMs"))

        // Transcription toggles
        assertTrue(setup.has("inputAudioTranscription"))
        assertTrue(setup.has("outputAudioTranscription"))

        // Session resumption with handle
        assertTrue(setup.has("sessionResumption"))
        val resumption = setup.getJSONObject("sessionResumption")
        assertEquals("sample-handle-xyz", resumption.getString("handle"))

        // Context window compression
        assertTrue(setup.has("contextWindowCompression"))
        assertTrue(setup.getJSONObject("contextWindowCompression").has("slidingWindow"))

        // System instruction
        assertTrue(setup.has("systemInstruction"))
        val sysInst = setup.getJSONObject("systemInstruction")
        val parts = sysInst.getJSONArray("parts")
        assertEquals("Mira persona + Bangla-only rule", parts.getJSONObject(0).getString("text"))
    }

    @Test
    fun testSetupPayload_FreshSessionWithoutHandle() {
        val payload = GeminiLiveClient.buildSetupPayload(
            model = "models/gemini-3.8-live",
            voiceName = "Fenrir",
            languageCode = "bn-BD",
            systemInstructionText = "Mira persona",
            resumptionHandle = null
        )
        val setup = payload.getJSONObject("setup")
        val resumption = setup.getJSONObject("sessionResumption")
        assertFalse("Fresh session resumption should have no handle key", resumption.has("handle"))
    }

    @Test
    fun testPcmChunkFormat_16BitLe16kHz() {
        val sampleRate = AudioRecorder.SAMPLE_RATE
        assertEquals("AudioRecorder sample rate must be 16kHz", 16000, sampleRate)

        val chunkSize = AudioRecorder.CHUNK_SIZE_BYTES
        // 20ms at 16kHz 16-bit mono = 0.02s * 16,000 samples/s * 2 bytes/sample = 640 bytes
        assertEquals("Chunk size must be 640 bytes (20ms at 16kHz 16-bit mono)", 640, chunkSize)
        val samplesInChunk = chunkSize / 2
        assertEquals(320, samplesInChunk)

        // Verify Little-Endian encoding & decoding
        val buffer = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        val testSamples = shortArrayOf(0, 1000, -1000, Short.MAX_VALUE, Short.MIN_VALUE)
        for (s in testSamples) {
            buffer.putShort(s)
        }

        val bytes = buffer.array()
        // Manually decode first sample (0)
        val s0 = ((bytes[1].toInt() shl 8) or (bytes[0].toInt() and 0xFF)).toShort()
        assertEquals(0.toShort(), s0)

        // Manually decode fourth sample (Short.MAX_VALUE = 32767 = 0x7FFF)
        // Little endian: byte 6 = 0xFF, byte 7 = 0x7F
        val s3 = ((bytes[7].toInt() shl 8) or (bytes[6].toInt() and 0xFF)).toShort()
        assertEquals(Short.MAX_VALUE, s3)

        // Manually decode fifth sample (Short.MIN_VALUE = -32768)
        val s4 = ((bytes[9].toInt() shl 8) or (bytes[8].toInt() and 0xFF)).toShort()
        assertEquals(Short.MIN_VALUE, s4)
    }

    @Test
    fun testAudioBase64RoundTrip() {
        // Construct a realistic 640-byte 16-bit PCM chunk
        val originalBytes = ByteArray(640)
        for (i in originalBytes.indices) {
            originalBytes[i] = ((i * 73) % 256).toByte()
        }

        // Base64 encode
        val base64String = Base64.getEncoder().encodeToString(originalBytes)
        assertNotNull(base64String)
        assertTrue(base64String.isNotEmpty())

        // Base64 decode
        val decodedBytes = Base64.getDecoder().decode(base64String)
        assertEquals(640, decodedBytes.size)
        assertArrayEquals("Decoded bytes must match original PCM bytes exactly", originalBytes, decodedBytes)
    }

    @Test
    fun testStateMachineStates() {
        val expectedStates = listOf(
            LiveClientState.IDLE,
            LiveClientState.REQUESTING_PERMISSION,
            LiveClientState.CONNECTING,
            LiveClientState.CONFIGURING,
            LiveClientState.LISTENING,
            LiveClientState.PROCESSING,
            LiveClientState.SPEAKING,
            LiveClientState.INTERRUPTED,
            LiveClientState.RECONNECTING,
            LiveClientState.ERROR,
            LiveClientState.STOPPED
        )

        assertEquals("LiveClientState must define exactly 11 states per spec §3.1", 11, LiveClientState.values().size)
        for (state in expectedStates) {
            assertNotNull("State $state must be present in LiveClientState enum", LiveClientState.valueOf(state.name))
        }
    }

    @Test
    fun testTimeoutConstants() {
        assertEquals("WS connect timeout must be 20s", 20_000L, GeminiLiveClient.WS_CONNECT_TIMEOUT_MS)
        assertEquals("Setup complete timeout must be 45s", 45_000L, GeminiLiveClient.SETUP_COMPLETE_TIMEOUT_MS)
        assertEquals("First audio stall timeout must be 30s", 30_000L, GeminiLiveClient.FIRST_AUDIO_STALL_TIMEOUT_MS)
        assertEquals("Session give-up must be 8 minutes", 480_000L, GeminiLiveClient.SESSION_GIVE_UP_MS)
        assertEquals("Max auto reconnects must be 2", 2, GeminiLiveClient.MAX_AUTO_RECONNECTS)
    }

    @Test
    fun testLanguageBcp47Mapping() {
        assertEquals("bn-BD", GeminiLiveClient.mapLanguageToBcp47("Bangla (Bengali)"))
        assertEquals("bn-BD", GeminiLiveClient.mapLanguageToBcp47("Bengali"))
        assertEquals("bn-BD", GeminiLiveClient.mapLanguageToBcp47("bn"))
        assertEquals("en-US", GeminiLiveClient.mapLanguageToBcp47("English"))
        assertEquals("hi-IN", GeminiLiveClient.mapLanguageToBcp47("Hindi"))
        assertEquals("ja-JP", GeminiLiveClient.mapLanguageToBcp47("Japanese"))
        assertEquals("es-ES", GeminiLiveClient.mapLanguageToBcp47("Spanish"))
        assertEquals("fr-FR", GeminiLiveClient.mapLanguageToBcp47("French"))
        assertEquals("de-DE", GeminiLiveClient.mapLanguageToBcp47("German"))
        assertEquals("ar-SA", GeminiLiveClient.mapLanguageToBcp47("Arabic"))
        // Fallback default
        assertEquals("bn-BD", GeminiLiveClient.mapLanguageToBcp47("UnknownLanguage"))
    }
}

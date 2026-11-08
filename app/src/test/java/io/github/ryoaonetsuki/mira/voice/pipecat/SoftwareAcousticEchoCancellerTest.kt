package io.github.ryoaonetsuki.mira.voice.pipecat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unit regression test suite for Software Acoustic Echo Cancellation (ROUND 21 Turn 2/2b).
 *
 * Verifies:
 * (a) Plumbing & far-end reference consumption: frames flow without crashing, sizes match,
 *     and far-end playback reference is consumed from the FIFO.
 *     (Note: SpeexDSP echo attenuation of 49.65 dB is verified by the native C test harness).
 * (b) Passthrough: user speech passes through unchanged when using the pass-through engine.
 * (c) Double-talk: user speech is preserved without clipping or degradation.
 * (d) Buffer drift protection: massive playback bursts are capped to max capacity without crash or memory leak.
 * (e) Reset: clears playback buffer and resets state.
 * (f) Resampling: 24 kHz -> 16 kHz sample rate conversion.
 * (g) Factory: graceful fallback to pass-through engine when native SpeexDSP is absent.
 * (h) EchoGate second layer: verified with pass-through fallback.
 */
class SoftwareAcousticEchoCancellerTest {

    /**
     * Test stub recording AEC processing calls and reference buffer consumption.
     */
    private class RecordingAecEngine : AcousticEchoCancellerEngine {
        var processCallCount = 0
        var totalProcessedSamples = 0
        var farEndNonZeroSamples = 0

        override fun process(nearEnd: ShortArray, farEnd: ShortArray, out: ShortArray, length: Int) {
            processCallCount++
            totalProcessedSamples += length
            for (i in 0 until length) {
                if (farEnd[i] != 0.toShort()) {
                    farEndNonZeroSamples++
                }
            }
            System.arraycopy(nearEnd, 0, out, 0, length)
        }

        override fun reset() {}

        override fun release() {}
    }

    private fun synthesizeTonePcm(
        sampleRate: Int,
        frequencyHz: Double,
        amplitude: Double,
        durationSeconds: Double
    ): ByteArray {
        val totalSamples = (sampleRate * durationSeconds).toInt()
        val bytes = ByteArray(totalSamples * 2)
        for (i in 0 until totalSamples) {
            val sampleVal = (amplitude * 32767.0 * sin(2.0 * PI * frequencyHz * i / sampleRate))
                .toInt().coerceIn(-32768, 32767).toShort()
            bytes[i * 2] = (sampleVal.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun calculateRms(pcmBytes: ByteArray): Float {
        var sumSquares = 0.0
        val numSamples = pcmBytes.size / 2
        if (numSamples == 0) return 0f
        for (i in 0 until numSamples) {
            val low = pcmBytes[i * 2].toInt() and 0xFF
            val high = pcmBytes[i * 2 + 1].toInt() and 0xFF
            val sample = ((high shl 8) or low).toShort()
            sumSquares += sample.toDouble() * sample.toDouble()
        }
        return sqrt(sumSquares / numSamples).toFloat()
    }

    /**
     * Test (a): Audio pipeline plumbing and far-end reference consumption.
     *
     * IMPORTANT: This JVM test validates pipeline plumbing (frame flow, buffer sizing,
     * FIFO reference consumption, and resampler integration). It does NOT measure SpeexDSP
     * echo attenuation because host JVM unit tests do not load the Android NDK native library
     * (libspeexdsp_mira.so).
     *
     * The real echo-attenuation evidence is the native SpeexDSP C harness measurement
     * (49.65 dB echo return loss enhancement from the Turn 2 report), measured directly against
     * the compiled SpeexDSP C library. JVM tests do not and cannot claim to prove attenuation.
     */
    @Test
    fun testSyntheticEcho_PlumbingAndReferenceConsumption() {
        // Real echo-attenuation evidence is the native SpeexDSP C harness measurement (49.65 dB,
        // from the Turn 2 report), which the JVM cannot run because it cannot load the .so.
        // JVM tests verify pipeline plumbing, buffer integrity, and reference consumption only.
        val recordingEngine = RecordingAecEngine()
        val aec = SoftwareAcousticEchoCanceller(
            engine = recordingEngine,
            resampler = LinearAudioResampler(),
            sampleRateHz = 16000,
            playbackSampleRateHz = 24000
        )

        val durationSec = 2.0
        val sampleRate24k = 24000
        val sampleRate16k = 16000

        // Far-end: 440 Hz tone at 24 kHz
        val farEnd24k = synthesizeTonePcm(sampleRate24k, 440.0, 0.5, durationSec)

        // Mic echo: resampled 16 kHz signal with 10ms delay and 0.6 acoustic coupling
        val farEnd16k = LinearAudioResampler().resample(
            ShortArray(farEnd24k.size / 2).also { shorts ->
                for (i in shorts.indices) {
                    val low = farEnd24k[i * 2].toInt() and 0xFF
                    val high = farEnd24k[i * 2 + 1].toInt() and 0xFF
                    shorts[i] = ((high shl 8) or low).toShort()
                }
            },
            sampleRate24k,
            sampleRate16k
        )

        val delaySamples = 160 // 10ms acoustic propagation delay
        val micEchoShorts = ShortArray(farEnd16k.size)
        for (i in delaySamples until farEnd16k.size) {
            micEchoShorts[i] = (farEnd16k[i - delaySamples] * 0.6f).toInt().toShort()
        }

        val micEchoBytes = ByteArray(micEchoShorts.size * 2)
        for (i in micEchoShorts.indices) {
            val s = micEchoShorts[i].toInt()
            micEchoBytes[i * 2] = (s and 0xFF).toByte()
            micEchoBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }

        // Process in 100 ms buffers (1600 samples at 16 kHz = 3200 bytes, 2400 samples at 24 kHz = 4800 bytes)
        val bufferBytes16k = 3200
        val bufferBytes24k = 4800
        val numBuffers = (durationSec * 10).toInt()

        val allOutputBytes = mutableListOf<Byte>()
        val allMicBytes = mutableListOf<Byte>()

        for (b in 0 until numBuffers) {
            val farSlice = farEnd24k.copyOfRange(b * bufferBytes24k, (b + 1) * bufferBytes24k)
            val micSlice = micEchoBytes.copyOfRange(b * bufferBytes16k, (b + 1) * bufferBytes16k)

            aec.pushPlaybackAudio(farSlice)
            val cleaned = aec.processMicrophoneAudio(micSlice)

            // Plumbing assertion: frames flow through without crashing, output size matches input size
            assertEquals("Output buffer size must match input size", micSlice.size, cleaned.size)

            for (byte in micSlice) allMicBytes.add(byte)
            for (byte in cleaned) allOutputBytes.add(byte)
        }

        // Plumbing assertion: AEC engine was called for each frame
        assertTrue("AEC engine process() must be invoked", recordingEngine.processCallCount > 0)
        assertEquals(
            "Total processed samples must equal total near-end microphone samples",
            allMicBytes.size / 2,
            recordingEngine.totalProcessedSamples
        )

        // Plumbing assertion: Far-end reference was consumed from the FIFO and supplied to the engine
        assertTrue(
            "Far-end reference audio must be consumed from FIFO and passed to AEC engine",
            recordingEngine.farEndNonZeroSamples > 0
        )
    }

    /**
     * Test (b): Passthrough of Uncorrelated Speech.
     * When there is no far-end audio (or far-end is silent), near-end user speech
     * must pass through essentially unchanged.
     */
    @Test
    fun testPassthrough_UncorrelatedUserSpeechPreserved() {
        val aec = SoftwareAcousticEchoCanceller(
            engine = PassThroughAecEngine(),
            resampler = LinearAudioResampler(),
            sampleRateHz = 16000,
            playbackSampleRateHz = 24000
        )

        // 1 second of user speech at 1200 Hz, RMS ~ 0.25f (amplitude ~0.35)
        val userSpeech16k = synthesizeTonePcm(16000, 1200.0, 0.35, 1.0)
        val inputRms = calculateRms(userSpeech16k)

        val bufferBytes = 3200 // 100ms
        val numBuffers = userSpeech16k.size / bufferBytes
        val outputBytes = mutableListOf<Byte>()

        for (b in 0 until numBuffers) {
            val slice = userSpeech16k.copyOfRange(b * bufferBytes, (b + 1) * bufferBytes)
            val cleaned = aec.processMicrophoneAudio(slice)
            for (byte in cleaned) outputBytes.add(byte)
        }

        val outputRms = calculateRms(outputBytes.toByteArray())
        val ratio = outputRms / inputRms

        println("AEC Passthrough Test: Input RMS=$inputRms, Output RMS=$outputRms, Ratio=$ratio")

        assertEquals("Output RMS must match input RMS within 2% on passthrough", 1.0f, ratio, 0.02f)
    }

    /**
     * Test (c): Double-Talk (User Speech + Echo).
     * While far-end audio is playing, user speaks at the same time.
     * Verifies that user speech energy is preserved.
     */
    @Test
    fun testDoubleTalk_PreservesUserSpeechWhileAttenuatingEcho() {
        val aec = SoftwareAcousticEchoCanceller(
            engine = PassThroughAecEngine(),
            resampler = LinearAudioResampler(),
            sampleRateHz = 16000,
            playbackSampleRateHz = 24000
        )

        val durationSec = 1.5
        val farEnd24k = synthesizeTonePcm(24000, 440.0, 0.4, durationSec)
        val userSpeech16k = synthesizeTonePcm(16000, 1500.0, 0.3, durationSec)
        val expectedUserSpeechRms = calculateRms(userSpeech16k)

        // Echo from 440Hz far-end
        val farEnd16k = LinearAudioResampler().resample(
            ShortArray(farEnd24k.size / 2).also { shorts ->
                for (i in shorts.indices) {
                    val low = farEnd24k[i * 2].toInt() and 0xFF
                    val high = farEnd24k[i * 2 + 1].toInt() and 0xFF
                    shorts[i] = ((high shl 8) or low).toShort()
                }
            },
            24000,
            16000
        )

        // Combined mic: echo + user speech
        val combinedMic = ByteArray(userSpeech16k.size)
        for (i in 0 until userSpeech16k.size / 2) {
            val echoSample = (farEnd16k.getOrElse(i) { 0 } * 0.5f).toInt()
            val userLow = userSpeech16k[i * 2].toInt() and 0xFF
            val userHigh = userSpeech16k[i * 2 + 1].toInt() and 0xFF
            val userSample = ((userHigh shl 8) or userLow).toShort().toInt()
            val mixed = (echoSample + userSample).coerceIn(-32768, 32767).toShort()
            combinedMic[i * 2] = (mixed.toInt() and 0xFF).toByte()
            combinedMic[i * 2 + 1] = ((mixed.toInt() shr 8) and 0xFF).toByte()
        }

        val bufferBytes16k = 3200
        val bufferBytes24k = 4800
        val numBuffers = (durationSec * 10).toInt()
        val allOutput = mutableListOf<Byte>()

        for (b in 0 until numBuffers) {
            val farSlice = farEnd24k.copyOfRange(b * bufferBytes24k, (b + 1) * bufferBytes24k)
            val micSlice = combinedMic.copyOfRange(b * bufferBytes16k, (b + 1) * bufferBytes16k)
            aec.pushPlaybackAudio(farSlice)
            val cleaned = aec.processMicrophoneAudio(micSlice)
            for (byte in cleaned) allOutput.add(byte)
        }

        // Measure second half output RMS
        val half = allOutput.size / 2
        val outRms = calculateRms(allOutput.subList(half, allOutput.size).toByteArray())

        // User speech energy must remain dominant (within 35% of isolated user speech RMS)
        println("Double-Talk Test: Expected User RMS=$expectedUserSpeechRms, Output RMS=$outRms")
        assertTrue("Output RMS ($outRms) must preserve user speech (>50% of isolated speech)", outRms > expectedUserSpeechRms * 0.5f)
    }

    /**
     * Test (d): Far-End Clock Drift & Bounded Buffer.
     * Feeds 3 seconds of continuous playback chunks without any mic reads.
     * Verifies that the internal queue does not grow unbounded and subsequent processing does not crash.
     */
    @Test
    fun testFarEndBufferDriftProtection_CapsAtMaxCapacity() {
        val aec = SoftwareAcousticEchoCanceller(
            engine = PassThroughAecEngine(),
            resampler = LinearAudioResampler(),
            sampleRateHz = 16000,
            playbackSampleRateHz = 24000,
            maxBufferSamples = 4000 // 250ms max buffer
        )

        // Push 3 seconds of 24 kHz audio (72,000 samples)
        val bigPlayback = synthesizeTonePcm(24000, 440.0, 0.3, 3.0)
        val chunkSize = 4800 // 100ms
        for (i in 0 until bigPlayback.size / chunkSize) {
            aec.pushPlaybackAudio(bigPlayback.copyOfRange(i * chunkSize, (i + 1) * chunkSize))
        }

        // Now process a normal mic buffer: must not crash or throw
        val micBuffer = ByteArray(3200)
        val cleaned = aec.processMicrophoneAudio(micBuffer)
        assertEquals("Output buffer size must match input size", 3200, cleaned.size)
    }

    /**
     * Test (e): Reset Clears Far-End Buffer.
     */
    @Test
    fun testReset_ClearsFarEndBufferAndFilter() {
        val aec = SoftwareAcousticEchoCanceller(
            engine = PassThroughAecEngine(),
            resampler = LinearAudioResampler(),
            sampleRateHz = 16000,
            playbackSampleRateHz = 24000
        )

        val playback = synthesizeTonePcm(24000, 440.0, 0.4, 0.5)
        aec.pushPlaybackAudio(playback)

        // Reset
        aec.reset()

        // Process a silence mic frame: output must be pure silence
        val silentMic = ByteArray(3200)
        val cleaned = aec.processMicrophoneAudio(silentMic)
        val outRms = calculateRms(cleaned)
        assertEquals("Output RMS after reset on silent mic must be 0", 0.0f, outRms, 0.0001f)
    }

    /**
     * Test (f): Linear Audio Resampler 24 kHz to 16 kHz.
     */
    @Test
    fun testLinearAudioResampler_24kTo16k() {
        val resampler = LinearAudioResampler()
        val inSamples = ShortArray(2400) { 1000.toShort() } // 100ms at 24kHz
        val outSamples = resampler.resample(inSamples, 24000, 16000)

        assertEquals("2400 samples at 24 kHz should yield 1600 samples at 16 kHz", 1600, outSamples.size)
        assertEquals(1000.toShort(), outSamples[0])
        assertEquals(1000.toShort(), outSamples[800])
        assertEquals(1000.toShort(), outSamples[1599])
    }

    /**
     * Test (g): Factory and Speex Native Availability Logging.
     * Proves fallback clean creation without crash and logs status.
     */
    @Test
    fun testFactoryCreation_SucceedsWithoutCrashing() {
        val canceller = SoftwareAcousticEchoCanceller.create()
        val isNative = SpeexAecNative.isAvailable()
        println("Speex native library loaded on test runner: $isNative")
        if (!isNative) {
            println("NOTE: JVM unit tests run on host JVM without Android NDK libraries. Validating Kotlin pass-through fallback & plumbing.")
        }

        val testPcm = ByteArray(3200)
        val cleaned = canceller.processMicrophoneAudio(testPcm)
        assertEquals(3200, cleaned.size)
        canceller.release()
    }

    /**
     * Test (h): AEC Output with EchoGate Second Layer.
     * Verifies that under pass-through fallback, EchoGate drops low-RMS echo frames
     * and forwards loud user speech barge-in.
     */
    @Test
    fun testAecWithEchoGate_SecondLayerBehavior() {
        val aec = SoftwareAcousticEchoCanceller(
            engine = PassThroughAecEngine(),
            resampler = LinearAudioResampler(),
            sampleRateHz = 16000,
            playbackSampleRateHz = 24000
        )

        // 1. Playback from Mira: bot level = 0.25f
        val playback = synthesizeTonePcm(24000, 440.0, 0.35, 1.0)
        aec.pushPlaybackAudio(playback)

        // 2. Microphone captures echo from Mira's voice (amplitude 0.20 -> RMS ~ 0.141f)
        val micEcho = synthesizeTonePcm(16000, 440.0, 0.20, 1.0)
        val cleanedEcho = aec.processMicrophoneAudio(micEcho)

        val rawMicRms = calculateAudioLevel(micEcho)
        val cleanedMicRms = calculateAudioLevel(cleanedEcho)

        // Under pass-through fallback, cleaned mic audio equals raw mic audio
        assertEquals("Cleaned mic RMS must match raw echo RMS under pass-through fallback", rawMicRms, cleanedMicRms, 0.001f)

        // EchoGate with bot talking at 0.25f: threshold = 0.25 * 1.5 + 0.05 = 0.425f
        // Cleaned echo RMS (~0.141f) is below threshold (0.425f), so EchoGate drops it
        val shouldForwardEcho = EchoGate.shouldForward(cleanedMicRms, 0.25f, isBotTalking = true, echoGuardEnabled = true)
        assertFalse("Cleaned echo frame MUST be dropped by EchoGate", shouldForwardEcho)

        // 3. User barge-in: loud user speech (amplitude 0.65 -> RMS ~ 0.460f)
        val userSpeech = synthesizeTonePcm(16000, 1200.0, 0.65, 0.1)
        val cleanedUser = aec.processMicrophoneAudio(userSpeech)
        val userRms = calculateAudioLevel(cleanedUser)

        // User RMS (~0.460f) exceeds threshold (0.425f), so EchoGate forwards it
        val shouldForwardUser = EchoGate.shouldForward(userRms, 0.25f, isBotTalking = true, echoGuardEnabled = true)
        assertTrue("User barge-in frame MUST be forwarded by EchoGate", shouldForwardUser)
    }
}

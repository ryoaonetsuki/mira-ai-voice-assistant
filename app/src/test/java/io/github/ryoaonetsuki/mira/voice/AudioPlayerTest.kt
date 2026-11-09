package io.github.ryoaonetsuki.mira.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying AudioPlayer teardown and barge-in behavior:
 * 1. Preview teardown: end-of-preview path never restarts the track into PLAYING state after flush.
 * 2. Preview teardown: early stop (stopPreviewCleanly) fades volume, stops, and releases without restart.
 * 3. Barge-in: stopImmediatelyAndFlush() preserves track restart for Live playback reuse.
 * 4. Fade ramps: applyPreviewFadeRamps tail decays smoothly to near-zero (ruling out PCM tail burst).
 * 5. Regression: release() leaves no playing track behind.
 */
class AudioPlayerTest {

    private class FakeAudioTrackController(
        initialState: Int = AudioTrackController.STATE_INITIALIZED,
        initialPlayState: Int = AudioTrackController.PLAYSTATE_PLAYING
    ) : AudioTrackController {
        override var state: Int = initialState
        override var playState: Int = initialPlayState
        override var playbackHeadPosition: Long = 0L

        val events = mutableListOf<String>()
        var playCalledAfterFlush: Boolean = false
        private var isFlushed: Boolean = false

        override fun play() {
            events.add("play")
            playState = AudioTrackController.PLAYSTATE_PLAYING
            if (isFlushed) {
                playCalledAfterFlush = true
            }
        }

        override fun pause() {
            events.add("pause")
            playState = AudioTrackController.PLAYSTATE_PAUSED
        }

        override fun flush() {
            events.add("flush")
            isFlushed = true
        }

        override fun stop() {
            events.add("stop")
            playState = AudioTrackController.PLAYSTATE_STOPPED
        }

        override fun release() {
            events.add("release")
            state = AudioTrackController.STATE_UNINITIALIZED
        }

        override fun write(buffer: ByteArray, offset: Int, size: Int, writeMode: Int): Int {
            events.add("write(size=$size)")
            playbackHeadPosition += (size / 2)
            return size
        }

        override fun setVolume(gain: Float): Int {
            events.add("setVolume($gain)")
            return 0
        }
    }

    @Test
    fun testPreviewTeardown_EndPreviewCleanly_NeverRestartsTrackAfterFlush() = runBlocking {
        var fakeController: FakeAudioTrackController? = null
        val player = AudioPlayer(
            onSpeakingStateChanged = {},
            controllerFactory = {
                FakeAudioTrackController().also { fakeController = it }
            }
        )
        val fake = checkNotNull(fakeController) { "Controller must be initialized" }

        // Feed a chunk so totalAudioBytesWritten > 0, and advance head so drain finishes immediately
        player.playAudioChunk(ByteArray(1024))
        fake.playbackHeadPosition = 512L // 1024 bytes = 512 frames

        player.endPreviewCleanly()

        // 1. Verify play() is NEVER called after flush() during preview teardown
        assertFalse(
            "Preview teardown must NEVER restart the track into PLAYING state after flush",
            fake.playCalledAfterFlush
        )

        // 2. Verify sequence contains pause -> flush -> stop -> release
        val flushIdx = fake.events.lastIndexOf("flush")
        val stopIdx = fake.events.lastIndexOf("stop")
        val releaseIdx = fake.events.lastIndexOf("release")

        assertTrue("flush must be invoked", flushIdx != -1)
        assertTrue("stop must be invoked after flush", stopIdx > flushIdx)
        assertTrue("release must be invoked after stop", releaseIdx > stopIdx)

        // 3. Verify track is in STOPPED playState, not PLAYING
        assertEquals(
            "AudioTrack playState must be STOPPED after preview teardown",
            AudioTrackController.PLAYSTATE_STOPPED,
            fake.playState
        )
        assertEquals(
            "AudioPlayer must report STOPPED playState after preview teardown",
            AudioTrackController.PLAYSTATE_STOPPED,
            player.getPlayState()
        )
    }

    @Test
    fun testPreviewTeardown_StopPreviewCleanly_FadesAndStopsWithoutTrackRestart() {
        var fakeController: FakeAudioTrackController? = null
        val player = AudioPlayer(
            onSpeakingStateChanged = {},
            controllerFactory = {
                FakeAudioTrackController().also { fakeController = it }
            }
        )
        val fake = checkNotNull(fakeController) { "Controller must be initialized" }

        player.stopPreviewCleanly(fadeDurationMs = 10L)

        assertFalse(
            "stopPreviewCleanly must NEVER restart the track after flush",
            fake.playCalledAfterFlush
        )

        val setVolZeroIdx = fake.events.indexOf("setVolume(0.0)")
        val flushIdx = fake.events.lastIndexOf("flush")
        val stopIdx = fake.events.lastIndexOf("stop")
        val releaseIdx = fake.events.lastIndexOf("release")

        assertTrue("Volume must be faded to 0.0", setVolZeroIdx != -1)
        assertTrue("flush must happen after fade", flushIdx > setVolZeroIdx)
        assertTrue("stop must happen after flush", stopIdx > flushIdx)
        assertTrue("release must happen after stop", releaseIdx > stopIdx)
        assertEquals(AudioTrackController.PLAYSTATE_STOPPED, fake.playState)
    }

    @Test
    fun testBargeIn_StopImmediatelyAndFlush_PreservesTrackRestartForLiveReuse() {
        var fakeController: FakeAudioTrackController? = null
        val player = AudioPlayer(
            onSpeakingStateChanged = {},
            controllerFactory = {
                FakeAudioTrackController().also { fakeController = it }
            }
        )
        val fake = checkNotNull(fakeController) { "Controller must be initialized" }

        // Live barge-in call: default restartTrack = true
        player.stopImmediatelyAndFlush()

        assertTrue(
            "Live barge-in must call play() after flush() so track is immediately ready for next utterance",
            fake.playCalledAfterFlush
        )
        assertEquals(
            "Live barge-in must leave track in PLAYING state for reuse",
            AudioTrackController.PLAYSTATE_PLAYING,
            fake.playState
        )
        assertEquals(
            "AudioPlayer must report PLAYING playState after Live barge-in",
            AudioTrackController.PLAYSTATE_PLAYING,
            player.getPlayState()
        )
        player.release()
    }

    @Test
    fun testApplyPreviewFadeRamps_SmoothTailRampToZero() {
        // 1 second of 24 kHz PCM16 mono full-scale audio (24,000 samples = 48,000 bytes)
        val sampleCount = 24000
        val rawPcm = ByteArray(sampleCount * 2)
        val testAmplitude: Short = 20000
        for (i in 0 until sampleCount) {
            val offset = i * 2
            rawPcm[offset] = (testAmplitude.toInt() and 0xFF).toByte()
            rawPcm[offset + 1] = ((testAmplitude.toInt() shr 8) and 0xFF).toByte()
        }

        val ramped = AudioPlayer.applyPreviewFadeRamps(
            pcmData = rawPcm,
            fadeInDurationMs = 80,
            fadeOutDurationMs = 100,
            sampleRate = 24000
        )

        fun readSample(index: Int): Short {
            val off = index * 2
            val low = ramped[off].toInt() and 0xFF
            val high = ramped[off + 1].toInt()
            return ((high shl 8) or low).toShort()
        }

        // 1. Fade-in onset check: sample 0 must be 0
        assertEquals("Sample 0 must be 0 after fade-in ramp", 0.toShort(), readSample(0))

        // 2. Middle samples should be unchanged (full amplitude)
        val midSample = readSample(sampleCount / 2)
        assertEquals("Mid-point sample must preserve original amplitude", testAmplitude, midSample)

        // 3. Fade-out tail check: last samples must decay smoothly to near zero
        val fadeOutSamples = (24000 * 100) / 1000 // 2400 samples
        val startFadeIdx = sampleCount - fadeOutSamples

        val sampleStartFade = readSample(startFadeIdx)
        val sampleQuarterTail = readSample(startFadeIdx + (fadeOutSamples * 3 / 4))
        val lastSample = readSample(sampleCount - 1)

        assertTrue(
            "Fade-out tail must strictly decrease: start=$sampleStartFade > quarter=$sampleQuarterTail",
            sampleStartFade > sampleQuarterTail
        )
        assertTrue(
            "Quarter-tail must be greater than last sample: quarter=$sampleQuarterTail > last=$lastSample",
            sampleQuarterTail > lastSample
        )
        // With testAmplitude = 20000 and factor = 1/2400, last sample is ~8
        assertTrue(
            "Last sample must be near zero (got $lastSample), ruling out any PCM tail glitch",
            lastSample in 0..15
        )
    }

    @Test
    fun testRelease_LeavesNoPlayingTrackBehind() {
        var fakeController: FakeAudioTrackController? = null
        val player = AudioPlayer(
            onSpeakingStateChanged = {},
            controllerFactory = {
                FakeAudioTrackController().also { fakeController = it }
            }
        )
        val fake = checkNotNull(fakeController) { "Controller must be initialized" }

        player.release()

        assertFalse("isSpeaking must be false after release", player.isSpeaking())
        assertEquals(
            "Track state must be UNINITIALIZED after release",
            AudioTrackController.STATE_UNINITIALIZED,
            fake.state
        )
        assertEquals(
            "AudioPlayer playState must be STOPPED after release",
            AudioTrackController.PLAYSTATE_STOPPED,
            player.getPlayState()
        )
    }
}

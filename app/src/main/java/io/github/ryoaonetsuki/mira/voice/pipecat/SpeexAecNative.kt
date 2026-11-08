package io.github.ryoaonetsuki.mira.voice.pipecat

import android.util.Log

/**
 * JNI bindings for SpeexDSP Acoustic Echo Cancellation (AEC), Residual Echo Suppression (RES),
 * and polyphase audio resampling.
 *
 * SpeexDSP is distributed under the revised 3-Clause BSD license (see app/src/main/cpp/speexdsp/COPYING).
 */
internal object SpeexAecNative {
    private const val TAG = "SpeexAecNative"

    private val isLoaded: Boolean = try {
        System.loadLibrary("mira_speex_aec")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "Native library mira_speex_aec could not be loaded: ${t.message}")
        false
    }

    fun isAvailable(): Boolean = isLoaded

    @JvmStatic
    external fun nativeCreate(
        sampleRate: Int,
        frameSize: Int,
        filterLength: Int,
        resampleInRate: Int
    ): Long

    @JvmStatic
    external fun nativeReset(handle: Long)

    @JvmStatic
    external fun nativeDestroy(handle: Long)

    @JvmStatic
    external fun nativeProcess(
        handle: Long,
        nearEnd: ShortArray,
        farEnd: ShortArray?,
        out: ShortArray,
        length: Int
    ): Int

    @JvmStatic
    external fun nativeResample(
        handle: Long,
        input: ShortArray,
        inLen: Int,
        output: ShortArray,
        outCapacity: Int
    ): Int
}

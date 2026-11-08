package io.github.ryoaonetsuki.mira.voice.pipecat

import android.util.Log

/**
 * JNI bindings for WebRTC AudioProcessing Module (APM) Acoustic Echo Cancellation 3 (AEC3).
 *
 * WebRTC AudioProcessing is distributed under the revised 3-Clause BSD license
 * (see app/src/main/cpp/webrtc-audio-processing/COPYING and webrtc/LICENSE).
 */
internal object WebRtcAec3Native {
    private const val TAG = "WebRtcAec3Native"

    private val isLoaded: Boolean = try {
        System.loadLibrary("mira_webrtc_aec")
        true
    } catch (t: Throwable) {
        try {
            Log.w(TAG, "Native library mira_webrtc_aec could not be loaded: ${t.message}")
        } catch (_: Throwable) {
            // Android Log stub on host JVM
        }
        false
    }

    fun isAvailable(): Boolean = isLoaded

    @JvmStatic
    external fun nativeCreate(
        sampleRate: Int,
        frameSize: Int
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
}

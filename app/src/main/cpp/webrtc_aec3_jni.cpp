/*
 * Copyright (c) 2024, Mira AI contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * WebRTC AudioProcessing is distributed under the revised 3-Clause BSD license
 * (see webrtc-audio-processing/COPYING and webrtc-audio-processing/webrtc/LICENSE).
 */

#if defined(__has_include)
#if __has_include(<jni.h>)
#include <jni.h>
#else
typedef unsigned char jboolean;
typedef signed char jbyte;
typedef short jshort;
typedef int jint;
typedef long long jlong;
typedef void* jobject;
typedef jobject jclass;
typedef jobject jshortArray;
typedef struct JNIEnv_ JNIEnv;
struct JNINativeInterface_ {
    jshort* (*GetShortArrayElements)(JNIEnv*, jshortArray, jboolean*);
    void (*ReleaseShortArrayElements)(JNIEnv*, jshortArray, jshort*, jint);
};
struct JNIEnv_ {
    const struct JNINativeInterface_* functions;
    jshort* GetShortArrayElements(jshortArray array, jboolean* isCopy) {
        return functions->GetShortArrayElements(this, array, isCopy);
    }
    void ReleaseShortArrayElements(jshortArray array, jshort* elems, jint mode) {
        functions->ReleaseShortArrayElements(this, array, elems, mode);
    }
};
#define JNI_ABORT 2
#define JNIEXPORT
#define JNICALL
#endif
#else
#include <jni.h>
#endif
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "MiraWebRtcAEC3"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <stdio.h>
#define LOGI(...) printf(__VA_ARGS__); printf("\n")
#define LOGW(...) printf(__VA_ARGS__); printf("\n")
#define LOGE(...) fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n")
#endif

#include "modules/audio_processing/include/audio_processing.h"

#define AEC3_CTX_MAGIC 0x41454333U // "AEC3"

struct NativeAec3Context {
    uint32_t magic;
    rtc::scoped_refptr<webrtc::AudioProcessing> apm;
    webrtc::StreamConfig stream_config;
    int sample_rate;
    int frame_size;
    std::vector<int16_t> zero_frame;
    std::vector<int16_t> render_out;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_ryoaonetsuki_mira_voice_pipecat_WebRtcAec3Native_nativeCreate(
    JNIEnv *env,
    jclass clazz,
    jint sampleRate,
    jint frameSize
) {
    (void)env;
    (void)clazz;

    auto *ctx = new (std::nothrow) NativeAec3Context();
    if (!ctx) {
        LOGE("Failed to allocate NativeAec3Context");
        return 0;
    }

    ctx->magic = AEC3_CTX_MAGIC;
    ctx->sample_rate = sampleRate;
    ctx->frame_size = frameSize;
    ctx->stream_config = webrtc::StreamConfig(sampleRate, 1);
    ctx->zero_frame.assign(frameSize > 0 ? frameSize : 160, 0);
    ctx->render_out.assign(frameSize > 0 ? frameSize : 160, 0);

    ctx->apm = webrtc::AudioProcessingBuilder().Create();
    if (!ctx->apm) {
        LOGE("Failed to create WebRTC AudioProcessing instance");
        delete ctx;
        return 0;
    }

    webrtc::AudioProcessing::Config config;
    config.echo_canceller.enabled = true;
    config.echo_canceller.mobile_mode = false; // AEC3 mode (not legacy AECM)
    config.echo_canceller.enforce_high_pass_filtering = true;
    config.high_pass_filter.enabled = true;

    ctx->apm->ApplyConfig(config);
    ctx->apm->Initialize();

    LOGI("WebRTC AEC3 initialized: sampleRate=%d, frameSize=%d", sampleRate, frameSize);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_io_github_ryoaonetsuki_mira_voice_pipecat_WebRtcAec3Native_nativeReset(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env;
    (void)clazz;
    auto *ctx = reinterpret_cast<NativeAec3Context *>(handle);
    if (!ctx || ctx->magic != AEC3_CTX_MAGIC || !ctx->apm) return;

    ctx->apm->Initialize();
}

JNIEXPORT void JNICALL
Java_io_github_ryoaonetsuki_mira_voice_pipecat_WebRtcAec3Native_nativeDestroy(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env;
    (void)clazz;
    auto *ctx = reinterpret_cast<NativeAec3Context *>(handle);
    if (!ctx || ctx->magic != AEC3_CTX_MAGIC) return;

    ctx->magic = 0; // Invalidate magic before releasing members
    ctx->apm = nullptr; // Releases scoped_refptr
    ctx->zero_frame.clear();
    ctx->render_out.clear();
    delete ctx;
    LOGI("WebRTC AEC3 destroyed");
}

JNIEXPORT jint JNICALL
Java_io_github_ryoaonetsuki_mira_voice_pipecat_WebRtcAec3Native_nativeProcess(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jshortArray jNearEnd,
    jshortArray jFarEnd,
    jshortArray jOut,
    jint length
) {
    (void)clazz;
    auto *ctx = reinterpret_cast<NativeAec3Context *>(handle);
    if (!ctx || ctx->magic != AEC3_CTX_MAGIC || !ctx->apm) return -1;
    if (!jNearEnd || !jOut || length <= 0) return -1;

    jshort *nearEnd = env->GetShortArrayElements(jNearEnd, nullptr);
    jshort *out = env->GetShortArrayElements(jOut, nullptr);
    jshort *farEnd = jFarEnd ? env->GetShortArrayElements(jFarEnd, nullptr) : nullptr;

    if (!nearEnd || !out) {
        if (nearEnd) env->ReleaseShortArrayElements(jNearEnd, nearEnd, JNI_ABORT);
        if (out) env->ReleaseShortArrayElements(jOut, out, JNI_ABORT);
        if (farEnd) env->ReleaseShortArrayElements(jFarEnd, farEnd, JNI_ABORT);
        return -1;
    }

    int frame_size = ctx->frame_size;
    if (frame_size <= 0) {
        env->ReleaseShortArrayElements(jNearEnd, nearEnd, JNI_ABORT);
        if (farEnd) env->ReleaseShortArrayElements(jFarEnd, farEnd, JNI_ABORT);
        env->ReleaseShortArrayElements(jOut, out, JNI_ABORT);
        return -1;
    }
    int frames = length / frame_size;
    if (frames <= 0) {
        env->ReleaseShortArrayElements(jNearEnd, nearEnd, JNI_ABORT);
        if (farEnd) env->ReleaseShortArrayElements(jFarEnd, farEnd, JNI_ABORT);
        env->ReleaseShortArrayElements(jOut, out, 0);
        return 0;
    }

    const int16_t *zero_ptr = ctx->zero_frame.data();
    int16_t *render_out_ptr = ctx->render_out.data();

    for (int i = 0; i < frames; ++i) {
        int offset = i * frame_size;
        const int16_t *far_ptr = farEnd ? reinterpret_cast<const int16_t *>(farEnd + offset) : zero_ptr;

        // 1. Feed far-end reference (render stream)
        ctx->apm->ProcessReverseStream(far_ptr, ctx->stream_config, ctx->stream_config, render_out_ptr);

        // 2. Report stream delay (0 since reference is already delay-compensated by Kotlin FIFO)
        ctx->apm->set_stream_delay_ms(0);

        // 3. Process near-end microphone (capture stream) with echo cancellation
        ctx->apm->ProcessStream(reinterpret_cast<const int16_t *>(nearEnd + offset),
                                ctx->stream_config,
                                ctx->stream_config,
                                reinterpret_cast<int16_t *>(out + offset));
    }

    env->ReleaseShortArrayElements(jNearEnd, nearEnd, JNI_ABORT);
    if (farEnd) {
        env->ReleaseShortArrayElements(jFarEnd, farEnd, JNI_ABORT);
    }
    env->ReleaseShortArrayElements(jOut, out, 0);

    return 0;
}

} // extern "C"

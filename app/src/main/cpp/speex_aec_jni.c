/*
 * Copyright (c) 2024, Mira AI contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * SpeexDSP is distributed under the revised BSD license (see speexdsp/COPYING).
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <android/log.h>
#include "speex/speex_echo.h"
#include "speex/speex_preprocess.h"
#include "speex/speex_resampler.h"

#define LOG_TAG "MiraSpeexAEC"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef struct {
    SpeexEchoState *echo_state;
    SpeexPreprocessState *prep_state;
    SpeexResamplerState *resampler;
    int sample_rate;
    int frame_size;
    int filter_length;
    int resample_in_rate;
} NativeSpeexContext;

JNIEXPORT jlong JNICALL
Java_io_github_ryoaonetsuki_mira_voice_pipecat_SpeexAecNative_nativeCreate(
    JNIEnv *env,
    jclass clazz,
    jint sampleRate,
    jint frameSize,
    jint filterLength,
    jint resampleInRate
) {
    (void)env;
    (void)clazz;

    NativeSpeexContext *ctx = (NativeSpeexContext *)calloc(1, sizeof(NativeSpeexContext));
    if (!ctx) {
        LOGE("Failed to allocate NativeSpeexContext");
        return 0;
    }

    ctx->sample_rate = sampleRate;
    ctx->frame_size = frameSize;
    ctx->filter_length = filterLength;
    ctx->resample_in_rate = resampleInRate;

    ctx->echo_state = speex_echo_state_init(frameSize, filterLength);
    if (!ctx->echo_state) {
        LOGE("Failed to initialize SpeexEchoState (frameSize=%d, filterLength=%d)", frameSize, filterLength);
        free(ctx);
        return 0;
    }

    int rate = sampleRate;
    speex_echo_ctl(ctx->echo_state, SPEEX_ECHO_SET_SAMPLING_RATE, &rate);

    ctx->prep_state = speex_preprocess_state_init(frameSize, sampleRate);
    if (ctx->prep_state) {
        speex_preprocess_ctl(ctx->prep_state, SPEEX_PREPROCESS_SET_ECHO_STATE, ctx->echo_state);
        // Only residual echo suppression, no harsh voice distortion
        int denoise = 0;
        speex_preprocess_ctl(ctx->prep_state, SPEEX_PREPROCESS_SET_DENOISE, &denoise);
        int echo_suppress = -40; // -40 dB residual echo attenuation
        speex_preprocess_ctl(ctx->prep_state, SPEEX_PREPROCESS_SET_ECHO_SUPPRESS, &echo_suppress);
        int echo_suppress_active = -15; // -15 dB when near end is talking
        speex_preprocess_ctl(ctx->prep_state, SPEEX_PREPROCESS_SET_ECHO_SUPPRESS_ACTIVE, &echo_suppress_active);
    } else {
        LOGW("Failed to initialize SpeexPreprocessState, continuing without residual echo suppression");
    }

    if (resampleInRate > 0 && resampleInRate != sampleRate) {
        int err = 0;
        ctx->resampler = speex_resampler_init(1, (spx_uint32_t)resampleInRate, (spx_uint32_t)sampleRate, SPEEX_RESAMPLER_QUALITY_VOIP, &err);
        if (err != 0 || !ctx->resampler) {
            LOGW("Failed to initialize SpeexResamplerState (err=%d)", err);
            ctx->resampler = NULL;
        }
    }

    LOGI("Speex AEC initialized: sampleRate=%d, frameSize=%d, filterLength=%d, resampleInRate=%d",
         sampleRate, frameSize, filterLength, resampleInRate);
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_io_github_ryoaonetsuki_mira_voice_pipecat_SpeexAecNative_nativeReset(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env;
    (void)clazz;
    NativeSpeexContext *ctx = (NativeSpeexContext *)(intptr_t)handle;
    if (!ctx) return;

    if (ctx->echo_state) {
        speex_echo_state_reset(ctx->echo_state);
    }
    if (ctx->resampler) {
        speex_resampler_reset_mem(ctx->resampler);
    }
}

JNIEXPORT void JNICALL
Java_io_github_ryoaonetsuki_mira_voice_pipecat_SpeexAecNative_nativeDestroy(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env;
    (void)clazz;
    NativeSpeexContext *ctx = (NativeSpeexContext *)(intptr_t)handle;
    if (!ctx) return;

    if (ctx->prep_state) {
        speex_preprocess_state_destroy(ctx->prep_state);
        ctx->prep_state = NULL;
    }
    if (ctx->echo_state) {
        speex_echo_state_destroy(ctx->echo_state);
        ctx->echo_state = NULL;
    }
    if (ctx->resampler) {
        speex_resampler_destroy(ctx->resampler);
        ctx->resampler = NULL;
    }
    free(ctx);
    LOGI("Speex AEC destroyed");
}

JNIEXPORT jint JNICALL
Java_io_github_ryoaonetsuki_mira_voice_pipecat_SpeexAecNative_nativeProcess(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jshortArray jNearEnd,
    jshortArray jFarEnd,
    jshortArray jOut,
    jint length
) {
    (void)clazz;
    NativeSpeexContext *ctx = (NativeSpeexContext *)(intptr_t)handle;
    if (!ctx || !ctx->echo_state) return -1;
    if (!jNearEnd || !jOut || length <= 0) return -1;

    jshort *nearEnd = (*env)->GetShortArrayElements(env, jNearEnd, NULL);
    jshort *out = (*env)->GetShortArrayElements(env, jOut, NULL);
    jshort *farEnd = jFarEnd ? (*env)->GetShortArrayElements(env, jFarEnd, NULL) : NULL;

    int frame_size = ctx->frame_size;
    int frames = length / frame_size;

    spx_int16_t zero_frame[frame_size];
    memset(zero_frame, 0, sizeof(zero_frame));

    for (int i = 0; i < frames; i++) {
        int offset = i * frame_size;
        const spx_int16_t *far_ptr = farEnd ? (const spx_int16_t *)(farEnd + offset) : zero_frame;

        speex_echo_cancellation(ctx->echo_state,
                               (const spx_int16_t *)(nearEnd + offset),
                               far_ptr,
                               (spx_int16_t *)(out + offset));

        if (ctx->prep_state) {
            speex_preprocess_run(ctx->prep_state, (spx_int16_t *)(out + offset));
        }
    }

    (*env)->ReleaseShortArrayElements(env, jNearEnd, nearEnd, JNI_ABORT);
    if (farEnd) {
        (*env)->ReleaseShortArrayElements(env, jFarEnd, farEnd, JNI_ABORT);
    }
    (*env)->ReleaseShortArrayElements(env, jOut, out, 0);

    return 0;
}

JNIEXPORT jint JNICALL
Java_io_github_ryoaonetsuki_mira_voice_pipecat_SpeexAecNative_nativeResample(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jshortArray jIn,
    jint inLen,
    jshortArray jOut,
    jint outCapacity
) {
    (void)clazz;
    NativeSpeexContext *ctx = (NativeSpeexContext *)(intptr_t)handle;
    if (!ctx || !ctx->resampler) return -1;
    if (!jIn || !jOut || inLen <= 0 || outCapacity <= 0) return -1;

    jshort *inBuf = (*env)->GetShortArrayElements(env, jIn, NULL);
    jshort *outBuf = (*env)->GetShortArrayElements(env, jOut, NULL);

    spx_uint32_t in_len = (spx_uint32_t)inLen;
    spx_uint32_t out_len = (spx_uint32_t)outCapacity;

    int res = speex_resampler_process_int(ctx->resampler,
                                          0,
                                          (const spx_int16_t *)inBuf,
                                          &in_len,
                                          (spx_int16_t *)outBuf,
                                          &out_len);

    (*env)->ReleaseShortArrayElements(env, jIn, inBuf, JNI_ABORT);
    (*env)->ReleaseShortArrayElements(env, jOut, outBuf, 0);

    return (res == 0) ? (jint)out_len : -1;
}

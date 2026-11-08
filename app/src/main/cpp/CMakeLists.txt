cmake_minimum_required(VERSION 3.22.1)
project(mira_webrtc_aec C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(ABSL_PROPAGATE_CXX_STD ON)
set(BUILD_TESTING OFF)
set(ABSL_BUILD_TESTING OFF)

include(FetchContent)
FetchContent_Declare(
    abseil_cpp
    URL https://github.com/abseil/abseil-cpp/releases/download/20240722.0/abseil-cpp-20240722.0.tar.gz
    URL_HASH SHA256=f50e5ac311a81382da7fa75b97310e4b9006474f9560ac46f54a9967f07d4ae3
)
FetchContent_MakeAvailable(abseil_cpp)

set(WEBRTC_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/webrtc-audio-processing/webrtc)

if(ANDROID)
    add_definitions(
        -DWEBRTC_ANDROID
        -DWEBRTC_POSIX
        -DWEBRTC_LINUX
        -DWEBRTC_LIBRARY_IMPL
        -DWEBRTC_ENABLE_SYMBOL_EXPORT
        -DWEBRTC_APM_DEBUG_DUMP=0
        -DNDEBUG
    )
else()
    add_definitions(
        -DWEBRTC_POSIX
        -DWEBRTC_LINUX
        -DWEBRTC_LIBRARY_IMPL
        -DWEBRTC_ENABLE_SYMBOL_EXPORT
        -DWEBRTC_APM_DEBUG_DUMP=0
        -DNDEBUG
    )
endif()

include_directories(
    ${WEBRTC_ROOT}
)

set(WEBRTC_COMMON_SOURCES
    ${WEBRTC_ROOT}/api/audio/audio_frame.cc
    ${WEBRTC_ROOT}/api/audio/audio_processing.cc
    ${WEBRTC_ROOT}/api/audio/audio_processing_statistics.cc
    ${WEBRTC_ROOT}/api/audio/channel_layout.cc
    ${WEBRTC_ROOT}/api/audio/echo_canceller3_config.cc
    ${WEBRTC_ROOT}/api/rtp_headers.cc
    ${WEBRTC_ROOT}/api/rtp_packet_info.cc
    ${WEBRTC_ROOT}/api/task_queue/task_queue_base.cc
    ${WEBRTC_ROOT}/api/units/frequency.cc
    ${WEBRTC_ROOT}/api/units/time_delta.cc
    ${WEBRTC_ROOT}/api/units/timestamp.cc
    ${WEBRTC_ROOT}/api/video/color_space.cc
    ${WEBRTC_ROOT}/api/video/hdr_metadata.cc
    ${WEBRTC_ROOT}/api/video/video_content_type.cc
    ${WEBRTC_ROOT}/api/video/video_timing.cc
    ${WEBRTC_ROOT}/rtc_base/checks.cc
    ${WEBRTC_ROOT}/rtc_base/containers/flat_tree.cc
    ${WEBRTC_ROOT}/rtc_base/event.cc
    ${WEBRTC_ROOT}/rtc_base/event_tracer.cc
    ${WEBRTC_ROOT}/rtc_base/experiments/field_trial_parser.cc
    ${WEBRTC_ROOT}/rtc_base/logging.cc
    ${WEBRTC_ROOT}/rtc_base/memory/aligned_malloc.cc
    ${WEBRTC_ROOT}/rtc_base/platform_thread.cc
    ${WEBRTC_ROOT}/rtc_base/platform_thread_types.cc
    ${WEBRTC_ROOT}/rtc_base/race_checker.cc
    ${WEBRTC_ROOT}/rtc_base/random.cc
    ${WEBRTC_ROOT}/rtc_base/string_encode.cc
    ${WEBRTC_ROOT}/rtc_base/string_to_number.cc
    ${WEBRTC_ROOT}/rtc_base/string_utils.cc
    ${WEBRTC_ROOT}/rtc_base/strings/string_builder.cc
    ${WEBRTC_ROOT}/rtc_base/synchronization/sequence_checker_internal.cc
    ${WEBRTC_ROOT}/rtc_base/synchronization/yield_policy.cc
    ${WEBRTC_ROOT}/rtc_base/system/file_wrapper.cc
    ${WEBRTC_ROOT}/rtc_base/system_time.cc
    ${WEBRTC_ROOT}/rtc_base/time_utils.cc
    ${WEBRTC_ROOT}/rtc_base/zero_memory.cc
    ${WEBRTC_ROOT}/system_wrappers/source/cpu_features.cc
    ${WEBRTC_ROOT}/system_wrappers/source/denormal_disabler.cc
    ${WEBRTC_ROOT}/system_wrappers/source/field_trial.cc
    ${WEBRTC_ROOT}/system_wrappers/source/metrics.cc
    ${WEBRTC_ROOT}/system_wrappers/source/sleep.cc
    ${WEBRTC_ROOT}/third_party/pffft/src/pffft.c
    ${WEBRTC_ROOT}/third_party/rnnoise/src/rnn_vad_weights.cc
    ${WEBRTC_ROOT}/modules/audio_coding/codecs/isac/main/source/filter_functions.c
    ${WEBRTC_ROOT}/modules/audio_coding/codecs/isac/main/source/isac_vad.c
    ${WEBRTC_ROOT}/modules/audio_coding/codecs/isac/main/source/pitch_estimator.c
    ${WEBRTC_ROOT}/modules/audio_coding/codecs/isac/main/source/pitch_filter.c
    ${WEBRTC_ROOT}/common_audio/audio_converter.cc
    ${WEBRTC_ROOT}/common_audio/audio_util.cc
    ${WEBRTC_ROOT}/common_audio/channel_buffer.cc
    ${WEBRTC_ROOT}/common_audio/fir_filter_c.cc
    ${WEBRTC_ROOT}/common_audio/fir_filter_factory.cc
    ${WEBRTC_ROOT}/common_audio/resampler/push_resampler.cc
    ${WEBRTC_ROOT}/common_audio/resampler/push_sinc_resampler.cc
    ${WEBRTC_ROOT}/common_audio/resampler/resampler.cc
    ${WEBRTC_ROOT}/common_audio/resampler/sinc_resampler.cc
    ${WEBRTC_ROOT}/common_audio/resampler/sinusoidal_linear_chirp_source.cc
    ${WEBRTC_ROOT}/common_audio/ring_buffer.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/auto_correlation.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/auto_corr_to_refl_coef.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/complex_bit_reverse.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/complex_fft.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/copy_set_operations.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/cross_correlation.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/division_operations.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/dot_product_with_scale.cc
    ${WEBRTC_ROOT}/common_audio/signal_processing/downsample_fast.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/filter_ar.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/filter_ar_fast_q12.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/energy.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/filter_ma_fast_q12.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/get_hanning_window.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/get_scaling_square.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/ilbc_specific_functions.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/levinson_durbin.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/lpc_to_refl_coef.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/min_max_operations.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/randomization_functions.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/real_fft.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/refl_coef_to_lpc.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/resample_48khz.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/resample_by_2.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/resample_by_2_internal.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/resample.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/resample_fractional.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/spl_init.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/spl_inl.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/splitting_filter.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/spl_sqrt.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/sqrt_of_one_minus_x_squared.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/vector_scaling_operations.c
    ${WEBRTC_ROOT}/common_audio/smoothing_filter.cc
    ${WEBRTC_ROOT}/common_audio/third_party/ooura/fft_size_128/ooura_fft.cc
    ${WEBRTC_ROOT}/common_audio/third_party/ooura/fft_size_256/fft4g.cc
    ${WEBRTC_ROOT}/common_audio/third_party/spl_sqrt_floor/spl_sqrt_floor.c
    ${WEBRTC_ROOT}/common_audio/vad/vad.cc
    ${WEBRTC_ROOT}/common_audio/vad/vad_core.c
    ${WEBRTC_ROOT}/common_audio/vad/vad_filterbank.c
    ${WEBRTC_ROOT}/common_audio/vad/vad_gmm.c
    ${WEBRTC_ROOT}/common_audio/vad/vad_sp.c
    ${WEBRTC_ROOT}/common_audio/vad/webrtc_vad.c
    ${WEBRTC_ROOT}/modules/audio_processing/aec_dump/null_aec_dump_factory.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/adaptive_fir_filter.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/adaptive_fir_filter_erl.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/aec3_common.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/aec3_fft.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/aec_state.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/alignment_mixer.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/api_call_jitter_metrics.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/block_buffer.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/block_delay_buffer.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/block_framer.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/block_processor.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/block_processor_metrics.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/clockdrift_detector.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/coarse_filter_update_gain.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/comfort_noise_generator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/config_selector.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/decimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/dominant_nearend_detector.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/downsampled_render_buffer.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/echo_audibility.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/echo_canceller3.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/echo_path_delay_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/echo_path_variability.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/echo_remover.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/echo_remover_metrics.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/erle_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/erl_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/fft_buffer.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/filter_analyzer.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/frame_blocker.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/fullband_erle_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/matched_filter.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/matched_filter_lag_aggregator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/moving_average.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/multi_channel_content_detector.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/refined_filter_update_gain.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/render_buffer.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/render_delay_buffer.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/render_delay_controller.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/render_delay_controller_metrics.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/render_signal_analyzer.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/residual_echo_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/reverb_decay_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/reverb_frequency_response.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/reverb_model.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/reverb_model_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/signal_dependent_erle_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/spectrum_buffer.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/stationarity_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/subband_erle_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/subband_nearend_detector.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/subtractor.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/subtractor_output_analyzer.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/subtractor_output.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/suppression_filter.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/suppression_gain.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/transparent_mode.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aecm/aecm_core.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aecm/aecm_core_c.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aecm/echo_control_mobile.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc/agc.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc/agc_manager_direct.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc/legacy/analog_agc.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc/legacy/digital_agc.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc/loudness_histogram.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc/utility.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/adaptive_digital_gain_controller.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/agc2_testing_common.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/biquad_filter.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/clipping_predictor.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/clipping_predictor_level_buffer.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/compute_interpolated_gain_curve.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/cpu_features.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/fixed_digital_level_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/gain_applier.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/input_volume_controller.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/input_volume_stats_reporter.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/interpolated_gain_curve.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/limiter.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/limiter_db_gain_curve.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/noise_level_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/rnn_vad/auto_correlation.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/rnn_vad/features_extraction.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/rnn_vad/lp_residual.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/rnn_vad/pitch_search.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/rnn_vad/pitch_search_internal.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/rnn_vad/rnn.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/rnn_vad/rnn_fc.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/rnn_vad/rnn_gru.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/rnn_vad/spectral_features.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/rnn_vad/spectral_features_internal.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/saturation_protector.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/saturation_protector_buffer.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/speech_level_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/speech_probability_buffer.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/vad_wrapper.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/vector_float_frame.cc
    ${WEBRTC_ROOT}/modules/audio_processing/audio_buffer.cc
    ${WEBRTC_ROOT}/modules/audio_processing/audio_processing_builder_impl.cc
    ${WEBRTC_ROOT}/modules/audio_processing/audio_processing_impl.cc
    ${WEBRTC_ROOT}/modules/audio_processing/capture_levels_adjuster/audio_samples_scaler.cc
    ${WEBRTC_ROOT}/modules/audio_processing/capture_levels_adjuster/capture_levels_adjuster.cc
    ${WEBRTC_ROOT}/modules/audio_processing/echo_control_mobile_impl.cc
    ${WEBRTC_ROOT}/modules/audio_processing/echo_detector/circular_buffer.cc
    ${WEBRTC_ROOT}/modules/audio_processing/echo_detector/mean_variance_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/echo_detector/moving_max.cc
    ${WEBRTC_ROOT}/modules/audio_processing/echo_detector/normalized_covariance_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/gain_control_impl.cc
    ${WEBRTC_ROOT}/modules/audio_processing/gain_controller2.cc
    ${WEBRTC_ROOT}/modules/audio_processing/high_pass_filter.cc
    ${WEBRTC_ROOT}/modules/audio_processing/include/aec_dump.cc
    ${WEBRTC_ROOT}/modules/audio_processing/include/audio_frame_proxies.cc
    ${WEBRTC_ROOT}/modules/audio_processing/logging/apm_data_dumper.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/fast_math.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/histograms.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/noise_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/noise_suppressor.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/ns_fft.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/prior_signal_model.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/prior_signal_model_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/quantile_noise_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/signal_model.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/signal_model_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/speech_probability_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/suppression_params.cc
    ${WEBRTC_ROOT}/modules/audio_processing/ns/wiener_filter.cc
    ${WEBRTC_ROOT}/modules/audio_processing/residual_echo_detector.cc
    ${WEBRTC_ROOT}/modules/audio_processing/rms_level.cc
    ${WEBRTC_ROOT}/modules/audio_processing/splitting_filter.cc
    ${WEBRTC_ROOT}/modules/audio_processing/three_band_filter_bank.cc
    ${WEBRTC_ROOT}/modules/audio_processing/utility/cascaded_biquad_filter.cc
    ${WEBRTC_ROOT}/modules/audio_processing/utility/delay_estimator.cc
    ${WEBRTC_ROOT}/modules/audio_processing/utility/delay_estimator_wrapper.cc
    ${WEBRTC_ROOT}/modules/audio_processing/utility/pffft_wrapper.cc
    ${WEBRTC_ROOT}/modules/audio_processing/vad/gmm.cc
    ${WEBRTC_ROOT}/modules/audio_processing/vad/pitch_based_vad.cc
    ${WEBRTC_ROOT}/modules/audio_processing/vad/pitch_internal.cc
    ${WEBRTC_ROOT}/modules/audio_processing/vad/pole_zero_filter.cc
    ${WEBRTC_ROOT}/modules/audio_processing/vad/standalone_vad.cc
    ${WEBRTC_ROOT}/modules/audio_processing/vad/vad_audio_proc.cc
    ${WEBRTC_ROOT}/modules/audio_processing/vad/vad_circular_buffer.cc
    ${WEBRTC_ROOT}/modules/audio_processing/vad/voice_activity_detector.cc
)

set(WEBRTC_AVX2_SOURCES
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/adaptive_fir_filter_avx2.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/adaptive_fir_filter_erl_avx2.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/fft_data_avx2.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/matched_filter_avx2.cc
    ${WEBRTC_ROOT}/modules/audio_processing/aec3/vector_math_avx2.cc
    ${WEBRTC_ROOT}/modules/audio_processing/agc2/rnn_vad/vector_math_avx2.cc
    ${WEBRTC_ROOT}/common_audio/fir_filter_avx2.cc
    ${WEBRTC_ROOT}/common_audio/resampler/sinc_resampler_avx2.cc
)

set(WEBRTC_SSE2_SOURCES
    ${WEBRTC_ROOT}/common_audio/fir_filter_sse.cc
    ${WEBRTC_ROOT}/common_audio/resampler/sinc_resampler_sse.cc
    ${WEBRTC_ROOT}/common_audio/third_party/ooura/fft_size_128/ooura_fft_sse2.cc
)

set(WEBRTC_NEON_SOURCES
    ${WEBRTC_ROOT}/common_audio/fir_filter_neon.cc
    ${WEBRTC_ROOT}/common_audio/resampler/sinc_resampler_neon.cc
    ${WEBRTC_ROOT}/common_audio/signal_processing/cross_correlation_neon.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/downsample_fast_neon.c
    ${WEBRTC_ROOT}/common_audio/signal_processing/min_max_operations_neon.c
    ${WEBRTC_ROOT}/common_audio/third_party/ooura/fft_size_128/ooura_fft_neon.cc
)

set(WEBRTC_ARCH_SOURCES "")

if(ANDROID)
    list(APPEND WEBRTC_ARCH_SOURCES ${WEBRTC_ROOT}/rtc_base/system/warn_current_thread_is_deadlocked.cc)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        add_definitions(-DWEBRTC_HAS_NEON)
        list(APPEND WEBRTC_ARCH_SOURCES ${WEBRTC_NEON_SOURCES})
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
        add_definitions(-DWEBRTC_HAS_NEON)
        list(APPEND WEBRTC_ARCH_SOURCES ${WEBRTC_NEON_SOURCES})
        set_source_files_properties(${WEBRTC_NEON_SOURCES} PROPERTIES COMPILE_FLAGS "-mfpu=neon")
    elseif(ANDROID_ABI STREQUAL "x86_64" OR ANDROID_ABI STREQUAL "x86")
        add_definitions(-DWEBRTC_ENABLE_AVX2)
        list(APPEND WEBRTC_ARCH_SOURCES ${WEBRTC_AVX2_SOURCES} ${WEBRTC_SSE2_SOURCES})
        set_source_files_properties(${WEBRTC_AVX2_SOURCES} PROPERTIES COMPILE_FLAGS "-mavx2 -mfma")
        set_source_files_properties(${WEBRTC_SSE2_SOURCES} PROPERTIES COMPILE_FLAGS "-msse2")
    endif()
else()
    # Host build fallback (e.g. Linux x86_64 / arm64 test)
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86|x86_64|amd64")
        add_definitions(-DWEBRTC_ENABLE_AVX2)
        list(APPEND WEBRTC_ARCH_SOURCES ${WEBRTC_AVX2_SOURCES} ${WEBRTC_SSE2_SOURCES})
        set_source_files_properties(${WEBRTC_AVX2_SOURCES} PROPERTIES COMPILE_FLAGS "-mavx2 -mfma")
        set_source_files_properties(${WEBRTC_SSE2_SOURCES} PROPERTIES COMPILE_FLAGS "-msse2")
    elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
        add_definitions(-DWEBRTC_HAS_NEON)
        list(APPEND WEBRTC_ARCH_SOURCES ${WEBRTC_NEON_SOURCES})
    endif()
endif()

add_library(
    mira_webrtc_aec
    SHARED
    webrtc_aec3_jni.cpp
    ${WEBRTC_COMMON_SOURCES}
    ${WEBRTC_ARCH_SOURCES}
)

if(ANDROID)
    target_link_libraries(
        mira_webrtc_aec
        absl::base
        absl::flags
        absl::strings
        absl::numeric
        absl::synchronization
        absl::bad_optional_access
        log
        m
    )
else()
    target_link_libraries(
        mira_webrtc_aec
        absl::base
        absl::flags
        absl::strings
        absl::numeric
        absl::synchronization
        absl::bad_optional_access
        pthread
        m
    )
endif()

cmake_minimum_required(VERSION 3.22.1)
project(mira_speex_aec C)

add_definitions(
    -DFLOATING_POINT
    -DUSE_SMALLFT
    -DVAR_ARRAYS
    -DEXPORT=
)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/speexdsp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/speexdsp/libspeexdsp
)

add_library(
    mira_speex_aec
    SHARED
    speex_aec_jni.c
    speexdsp/libspeexdsp/preprocess.c
    speexdsp/libspeexdsp/mdf.c
    speexdsp/libspeexdsp/fftwrap.c
    speexdsp/libspeexdsp/filterbank.c
    speexdsp/libspeexdsp/resample.c
    speexdsp/libspeexdsp/buffer.c
    speexdsp/libspeexdsp/scal.c
    speexdsp/libspeexdsp/smallft.c
)

target_link_libraries(
    mira_speex_aec
    log
    m
)

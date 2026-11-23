# On-device speech to text for podcast transcripts: whisper.cpp (MIT) behind a small JNI wrapper.
# The whisper.cpp source is fetched at build time, pinned to a release and its checksum.
cmake_minimum_required(VERSION 3.22.1)
project(feeder_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)

include(FetchContent)
FetchContent_Declare(
    whisper
    URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.5.tar.gz
    URL_HASH SHA256=ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720
)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# NDK OpenMP needs libomp.so shipped next to the app; ggml's own thread pool is enough
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # Half-precision math: every arm64 phone from about 2018 on (ARMv8.2)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16" CACHE STRING "" FORCE)
endif ()

FetchContent_MakeAvailable(whisper)

add_library(feederwhisper SHARED whisper_jni.c)
target_link_libraries(feederwhisper whisper log android)
# 16 KB memory pages (newer phones and emulators) refuse libraries aligned for 4 KB
target_link_options(feederwhisper PRIVATE "-Wl,-z,max-page-size=16384")
if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(feederwhisper PRIVATE -O3)
endif ()

# Native part of the Whisper speech engine: whisper.cpp + a small JNI bridge (whisper_jni.cpp).
#
# whisper.cpp is not vendored. CMake downloads the pinned release below on the first build
# (cached under app/.cxx/). For an offline build, point WHISPER_CPP_DIR at an unpacked copy:
#   externalNativeBuild { cmake { arguments += "-DWHISPER_CPP_DIR=/path/to/whisper.cpp" } }
cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni C CXX)

include(FetchContent)

set(WHISPER_CPP_VERSION 1.9.5)
set(WHISPER_CPP_SHA256 ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720)

# Static whisper + ggml linked into one libwhisper_jni.so; no tests, examples or server.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
# Cross-compiling: never tune for the build machine. No OpenMP runtime to package; ggml uses
# its own thread pool.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Same target as whisper.cpp's own Android example: fp16 arithmetic, every arm64 phone since ~2017.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16" CACHE STRING "" FORCE)
endif()

if(DEFINED WHISPER_CPP_DIR)
    FetchContent_Declare(whisper SOURCE_DIR ${WHISPER_CPP_DIR})
else()
    FetchContent_Declare(whisper
        URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v${WHISPER_CPP_VERSION}.tar.gz
        URL_HASH SHA256=${WHISPER_CPP_SHA256}
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
endif()
FetchContent_MakeAvailable(whisper)

# Debug APKs are what we install for demos, and an -O0 whisper.cpp is ~10x slower than real time.
# The later -O3 wins over the Debug -O0.
foreach(t whisper ggml ggml-base ggml-cpu)
    if(TARGET ${t})
        target_compile_options(${t} PRIVATE -O3)
    endif()
endforeach()

add_library(whisper_jni SHARED whisper_jni.cpp)
target_compile_options(whisper_jni PRIVATE -O3)
target_link_libraries(whisper_jni PRIVATE whisper log)

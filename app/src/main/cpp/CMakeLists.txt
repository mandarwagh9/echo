cmake_minimum_required(VERSION 3.22)
project(echo_whisper C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# app/src/main/cpp -> main -> src -> app -> <project root>
set(WHISPER_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

if (NOT EXISTS ${WHISPER_LIB_DIR}/src/whisper.cpp)
    message(FATAL_ERROR
        "whisper.cpp sources not found at ${WHISPER_LIB_DIR}. "
        "Run: git clone --depth 1 https://github.com/ggml-org/whisper.cpp third_party/whisper.cpp")
endif()

# Read the upstream version so we can report it from getSystemInfo().
file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)"
       VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if (CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1})
else()
    set(WHISPER_VERSION "unknown")
endif()
message(STATUS "Echo: building against whisper.cpp ${WHISPER_VERSION}")

include_directories(
    ${WHISPER_LIB_DIR}
    ${WHISPER_LIB_DIR}/src
    ${WHISPER_LIB_DIR}/include
    ${WHISPER_LIB_DIR}/ggml/include
    ${WHISPER_LIB_DIR}/ggml/src
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu
)

# Pull in the ggml subproject exactly the way the upstream Android example does.
include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

add_library(echo_whisper SHARED
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/echo_whisper_jni.c
)

target_compile_definitions(echo_whisper PUBLIC GGML_USE_CPU)
target_compile_definitions(echo_whisper PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

# Deliberately no -march=armv8.2-a+fp16 here. The upstream example builds an
# fp16 variant and a baseline variant that share a single ggml target, so the
# fp16 compile options leak into the "safe" fallback library. Building one
# baseline library instead is correct on every arm64-v8a device; ggml still
# does its own runtime CPU feature dispatch internally.
target_compile_options(echo_whisper PRIVATE -O3 -ffunction-sections -fdata-sections)
target_link_options(echo_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)

find_library(LOG_LIB log)
target_link_libraries(echo_whisper ${LOG_LIB} android ggml)

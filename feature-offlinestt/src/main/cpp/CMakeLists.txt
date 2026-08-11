# CMakeLists.txt - whisper.cpp native 编译配置
#
# 前置条件：
#   1. 下载 whisper.cpp 源码到 src/main/cpp/whisper/
#      cd feature-offlinestt/src/main/cpp/
#      git clone https://github.com/ggerganov/whisper.cpp.git whisper
#
#   2. 确认以下目录存在：
#      src/main/cpp/whisper/include/whisper.h
#      src/main/cpp/whisper/src/whisper.cpp
#      src/main/cpp/whisper/ggml/include/ggml.h
#      src/main/cpp/whisper/ggml/src/ggml.c
#
#   3. 在项目的 build.gradle.kts (项目级) 中已配置：
#      android {
#          ndkVersion = "26.1.10909125"
#      }

cmake_minimum_required(VERSION 3.22.1)
project(whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ============== 编译选项 ==============
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -fPIC")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fPIC -std=c++17")

# Android ARM64 优化
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.2a+dotprod+fp16")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2a+dotprod+fp16")
    add_definitions(-DGGML_USE_NEON)
endif()

# ============== ggml 库（whisper.cpp 依赖） ==============
set(GGML_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper/ggml)

add_library(ggml STATIC
    ${GGML_DIR}/src/ggml.c
    ${GGML_DIR}/src/ggml-alloc.c
    ${GGML_DIR}/src/ggml-backend.c
    ${GGML_DIR}/src/ggml-quants.c
    ${GGML_DIR}/src/ggml-aarch64.c
)

target_include_directories(ggml PUBLIC
    ${GGML_DIR}/include
)

# ============== whisper 库 ==============
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper)

add_library(whisper STATIC
    ${WHISPER_DIR}/src/whisper.cpp
)

target_include_directories(whisper PUBLIC
    ${WHISPER_DIR}/include
    ${GGML_DIR}/include
)

target_link_libraries(whisper PUBLIC ggml)

# ============== JNI 桥接库（最终输出的 libwhisper.so） ==============
add_library(whisper_jni SHARED
    whisper_jni.cpp
)

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_DIR}/include
)

target_link_libraries(whisper_jni
    whisper
    log
    android
)

# ============== 提示信息 ==============
message(STATUS "========================================")
message(STATUS "whisper.cpp native build configuration:")
message(STATUS "  ABI: ${ANDROID_ABI}")
message(STATUS "  whisper source: ${WHISPER_DIR}")
message(STATUS "  ggml source: ${GGML_DIR}")
message(STATUS "  C flags: ${CMAKE_C_FLAGS}")
message(STATUS "  CXX flags: ${CMAKE_CXX_FLAGS}")
message(STATUS "========================================")

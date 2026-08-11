// whisper_jni.cpp - whisper.cpp 的 JNI 桥接层
//
// 编译前提：需要 whisper.cpp 源码位于 src/main/cpp/whisper/ 目录下
// 下载方式：
//   cd feature-offlinestt/src/main/cpp/
//   git clone https://github.com/ggerganov/whisper.cpp.git whisper
//
// 或使用预编译库，修改 CMakeLists.txt 中 target_link_libraries 为 IMPORTED 方式

#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <android/log.h>

// whisper.cpp 头文件（位于 src/main/cpp/whisper/include/）
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

// ===================== nativeInitFromFileWithParams =====================
// 对应 Kotlin: private external fun nativeInitFromFileWithParams(
//     modelPath: String, language: String, useGpu: Boolean, beamSize: Int
// ): Long
JNIEXPORT jlong JNICALL
Java_com_lingshu_feature_offlinestt_data_WhisperCppEngine_nativeInitFromFileWithParams(
    JNIEnv *env, jobject thiz,
    jstring modelPath, jstring language, jboolean useGpu, jint beamSize
) {
    const char *cModelPath = env->GetStringUTFChars(modelPath, nullptr);
    const char *cLanguage = env->GetStringUTFChars(language, nullptr);

    LOGI("nativeInitFromFileWithParams: model=%s lang=%s useGpu=%d beam=%d",
         cModelPath, cLanguage, useGpu, beamSize);

    // 1. 构造 whisper_context_params
    struct whisper_context_params params = whisper_context_default_params();
    params.use_gpu = useGpu;

    // 2. 初始化 whisper_context
    struct whisper_context *ctx = whisper_init_from_file_with_params(cModelPath, params);
    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params FAILED: %s", cModelPath);
        env->ReleaseStringUTFChars(modelPath, cModelPath);
        env->ReleaseStringUTFChars(language, cLanguage);
        return 0;
    }

    LOGI("whisper_init_from_file_with_params SUCCESS: ctx=%p", ctx);

    env->ReleaseStringUTFChars(modelPath, cModelPath);
    env->ReleaseStringUTFChars(language, cLanguage);

    // 返回 ctx 指针的 jlong 表示
    return reinterpret_cast<jlong>(ctx);
}

// ===================== nativeFull =====================
// 对应 Kotlin: private external fun nativeFull(
//     ctxPtr: Long, samples: ShortArray, sampleCount: Int,
//     language: String, beamSize: Int
// ): Int
JNIEXPORT jint JNICALL
Java_com_lingshu_feature_offlinestt_data_WhisperCppEngine_nativeFull(
    JNIEnv *env, jobject thiz,
    jlong ctxPtr, jshortArray samples, jint sampleCount,
    jstring language, jint beamSize
) {
    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
    if (ctx == nullptr) {
        LOGE("nativeFull: ctx is null");
        return -1;
    }

    const char *cLanguage = env->GetStringUTFChars(language, nullptr);

    // 1. 获取 PCM short 数据
    jshort *pcmShort = env->GetShortArrayElements(samples, nullptr);
    if (pcmShort == nullptr) {
        LOGE("nativeFull: GetShortArrayElements failed");
        env->ReleaseStringUTFChars(language, cLanguage);
        return -2;
    }

    // 2. short[] -> float[] (whisper 需要 float，归一化到 [-1.0, 1.0])
    std::vector<float> pcmFloat(sampleCount);
    for (int i = 0; i < sampleCount; i++) {
        pcmFloat[i] = (float) pcmShort[i] / 32768.0f;
    }
    env->ReleaseShortArrayElements(samples, pcmShort, JNI_ABORT);

    // 3. 构造推理参数
    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);

    // 线程数：使用设备核心数，但限制在 1-8
    unsigned int nThreads = std::thread::hardware_concurrency();
    if (nThreads < 1) nThreads = 1;
    if (nThreads > 8) nThreads = 8;
    wparams.n_threads = nThreads;

    wparams.language = cLanguage;
    wparams.translate = false;
    wparams.no_timestamps = true;
    wparams.print_realtime = false;
    wparams.print_progress = false;
    wparams.print_special = false;
    wparams.print_timestamps = false;

    // beam search 参数
    wparams.beam_search.beam_size = beamSize;

    // 上下文窗口
    wparams.n_max_text_ctx = 16384;

    LOGI("nativeFull: samples=%d lang=%s threads=%u beam=%d",
         sampleCount, cLanguage, nThreads, beamSize);

    // 4. 执行推理
    int ret = whisper_full(ctx, wparams, pcmFloat.data(), pcmFloat.size());

    if (ret != 0) {
        LOGE("whisper_full FAILED: ret=%d", ret);
    } else {
        LOGI("whisper_full SUCCESS");
    }

    env->ReleaseStringUTFChars(language, cLanguage);
    return ret;
}

// ===================== nativeFullNSegments =====================
// 对应 Kotlin: private external fun nativeFullNSegments(ctxPtr: Long): Int
JNIEXPORT jint JNICALL
Java_com_lingshu_feature_offlinestt_data_WhisperCppEngine_nativeFullNSegments(
    JNIEnv *env, jobject thiz, jlong ctxPtr
) {
    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
    if (ctx == nullptr) return 0;

    int n = whisper_full_n_segments(ctx);
    return n;
}

// ===================== nativeFullGetSegmentText =====================
// 对应 Kotlin: private external fun nativeFullGetSegmentText(
//     ctxPtr: Long, index: Int
// ): String
JNIEXPORT jstring JNICALL
Java_com_lingshu_feature_offlinestt_data_WhisperCppEngine_nativeFullGetSegmentText(
    JNIEnv *env, jobject thiz, jlong ctxPtr, jint index
) {
    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
    if (ctx == nullptr) return env->NewStringUTF("");

    const char *text = whisper_full_get_segment_text(ctx, index);
    return env->NewStringUTF(text);
}

// ===================== nativeFree =====================
// 对应 Kotlin: private external fun nativeFree(ctxPtr: Long)
JNIEXPORT void JNICALL
Java_com_lingshu_feature_offlinestt_data_WhisperCppEngine_nativeFree(
    JNIEnv *env, jobject thiz, jlong ctxPtr
) {
    struct whisper_context *ctx = reinterpret_cast<struct whisper_context *>(ctxPtr);
    if (ctx == nullptr) return;

    LOGI("nativeFree: freeing ctx=%p", ctx);
    whisper_free(ctx);
    LOGI("nativeFree: done");
}

} // extern "C"

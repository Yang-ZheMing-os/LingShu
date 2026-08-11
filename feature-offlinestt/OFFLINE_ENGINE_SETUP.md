# 离线语音引擎运行指南

## 一、WhisperCpp（离线 STT）

### 1.1 代码已就绪

| 文件 | 状态 | 说明 |
|------|------|------|
| `WhisperCppEngine.kt` | ✅ 真实 JNI 调用 | 不再有 pseudo/mock 代码 |
| `whisper_jni.cpp` | ✅ 完整 C++ 桥接 | 调用 whisper.cpp 真实 API |
| `CMakeLists.txt` | ✅ 完整编译配置 | 编译 ggml + whisper + JNI |
| `build.gradle.kts` | ✅ NDK + CMake 配置 | arm64-v8a |

### 1.2 你还需要做 3 件事

#### 步骤 1：下载 whisper.cpp 源码

```bash
cd feature-offlinestt/src/main/cpp/
git clone https://github.com/ggerganov/whisper.cpp.git whisper
```

下载后确认目录结构：
```
feature-offlinestt/src/main/cpp/
├── CMakeLists.txt
├── whisper_jni.cpp
└── whisper/                      # ← git clone 出来的
    ├── include/
    │   └── whisper.h
    ├── src/
    │   └── whisper.cpp
    └── ggml/
        ├── include/ggml.h
        └── src/
            ├── ggml.c
            ├── ggml-alloc.c
            ├── ggml-backend.c
            ├── ggml-quants.c
            └── ggml-aarch64.c
```

> **注意：** whisper.cpp 的文件名可能随版本变化。如果 CMakeLists.txt 中列出的源文件不存在，请检查实际文件名并修改 CMakeLists.txt。

#### 步骤 2：下载 Whisper 模型

```bash
# 推荐用 small 模型（466MB，中文识别效果好，手机可跑）
# 下载地址：https://huggingface.co/ggerganov/whisper.cpp
# 将模型放到手机存储：
adb push ggml-small.bin /sdcard/Android/data/com.lingshu/files/models/stt/
```

模型大小参考：
| 模型 | 大小 | 中文效果 | 手机速度 |
|------|------|----------|----------|
| ggml-tiny.bin | 75MB | 一般 | 很快 |
| ggml-base.bin | 142MB | 还行 | 快 |
| **ggml-small.bin** | **466MB** | **好** | **可用** |
| ggml-medium.bin | 1.5GB | 很好 | 较慢 |
| ggml-large-v3.bin | 2.9GB | 最好 | 很慢 |

#### 步骤 3：安装 Android NDK

在 Android Studio 中：
1. `File → Settings → Languages & Frameworks → Android SDK → SDK Tools`
2. 勾选 `NDK (Side by side)`，版本 `26.1.10909125`
3. 点 Apply 安装

### 1.3 运行

```kotlin
// 在代码中调用
val config = OfflineSttConfig(
    provider = OfflineSttProvider.WHISPER_CPP,
    modelDir = "/sdcard/Android/data/com.lingshu/files/models/stt",
    modelName = "ggml-small.bin",
    language = "zh",
    beamSize = 5,
    useGpu = true
)
val result = whisperEngine.load(config, "test_001")
val text = whisperEngine.transcribe(audioFile, "test_001")
```

---

## 二、ChatTTS（离线 TTS）

### 2.1 代码已就绪

| 文件 | 状态 | 说明 |
|------|------|------|
| `ChatTtsEngine.kt` | ✅ 真实 OnnxRuntime | tokenize → GPT → Vocoder 全流程 |
| `ChatTtsTokenizer.kt` | ✅ 完整 BPE 实现 | 从 tokenizer.json 加载，支持 encode/decode |
| `build.gradle.kts` | ✅ onnxruntime 依赖 | `com.microsoft.onnxruntime:onnxruntime-android:1.17.0` |

### 2.2 你还需要做 2 件事

#### 步骤 1：获取 ChatTTS ONNX 模型

ChatTTS 原始模型是 PyTorch 格式，需要导出为 ONNX：

```bash
# 方式 A：从社区获取已导出的 ONNX 模型
# 关注 https://github.com/2noise/ChatTTS 的 ONNX 导出 PR

# 方式 B：自行导出
pip install ChatTTS onnx onnxruntime
python export_onnx.py  # 需要 3 个输出文件
```

需要的文件：
```
/sdcard/Android/data/com.lingshu/files/models/tts/chattts/
├── gpt.onnx           # GPT 文本到 mel 模型（~300MB）
├── vocoder.onnx       # Vocoder mel 到波形模型（~50MB）
├── tokenizer.json     # BPE tokenizer 配置
└── spk_emb_default_female.npy  # 可选：声音 embedding
```

#### 步骤 2：推送到手机

```bash
adb push chattts/ /sdcard/Android/data/com.lingshu/files/models/tts/
```

### 2.3 运行

```kotlin
val config = OfflineTtsConfig(
    provider = OfflineTtsProvider.CHATTTS,
    modelDir = "/sdcard/Android/data/com.lingshu/files/models/tts/chattts",
    voiceId = "default_female",
    speed = 1.0f,
    temperature = 0.8f,
    topP = 0.8f,
    sampleRate = 24000,
    format = "wav"
)
ttsEngine.load(config, "test_001")
ttsEngine.synthesize("你好，我是灵枢AI助手。", outputFile, "test_001")
```

---

## 三、settings.gradle.kts 配置

在项目根目录的 `settings.gradle.kts` 末尾添加：

```kotlin
include(":feature-offlinestt")
include(":feature-offlinetts")
```

---

## 四、关于 "导入 Android Studio 就能跑吗" 的回答

| 部分 | 导入即编译 | 导入即运行 |
|------|-----------|-----------|
| Kotlin 代码 | ✅ 全部可编译 | — |
| JNI C++ 代码 | ⚠ 需要 whisper.cpp 源码 | — |
| OnnxRuntime 依赖 | ✅ Gradle 自动下载 | — |
| 实际推理 | — | ⚠ 需要模型文件 |

**总结：**

1. **ChatTTS** — Kotlin 代码 + 依赖已全部就绪，导入 Android Studio 后能编译通过。运行需要下载 ONNX 模型文件放到手机。

2. **WhisperCpp** — Kotlin 代码 + JNI + CMake 全部就绪，但编译前需要 `git clone` whisper.cpp 源码到 `cpp/whisper/` 目录。运行需要下载 ggml 模型文件。

3. 两个模块都需要在 `settings.gradle.kts` 中添加 `include` 语句。

4. 如果只想先编译验证 Kotlin 代码正确性（不想编译 native），可以在 `build.gradle.kts` 中暂时注释掉 `externalNativeBuild` 块——此时 `System.loadLibrary("whisper")` 会在运行时抛 `UnsatisfiedLinkError`，但代码编译不受影响。

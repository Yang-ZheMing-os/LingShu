package com.lingshu.feature.stt.data

import android.content.Context
import android.os.Environment
import com.lingshu.core.common.event.ISttEngine
import com.lingshu.core.common.event.SttResult
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.log.LingShuLog
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vosk 离线语音识别引擎。
 * 需要在外部存储的 vosk/models/ 目录下放置语言模型（如 vosk-model-small-cn-0.22）。
 * 模型下载地址：https://alphacephei.com/vosk/models
 */
@Singleton
class VoskSttEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : ISttEngine {

    private val moduleTag = "VoskStt"

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var isListening = false
    private var onResultCallback: ((SttResult) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    private var recognitionThread: Thread? = null

    companion object {
        // 模型存放路径：/sdcard/vosk/models/vosk-model-small-cn-0.22
        private const val MODEL_PARENT_DIR = "vosk"
        private const val MODEL_SUB_DIR = "models"
        private const val DEFAULT_MODEL_NAME = "vosk-model-small-cn-0.22"
        private const val SAMPLE_RATE = 16000
    }

    /**
     * 查找模型目录。优先查外部存储，其次查应用内部存储。
     */
    private fun findModelDir(): File? {
        val candidates = mutableListOf<File>()

        // 外部存储（用户可手动放置模型）
        val externalDirs = context.getExternalFilesDir(null)?.let { listOf(it) } ?: emptyList()
        for (dir in externalDirs) {
            candidates.add(File(dir, "$MODEL_PARENT_DIR/$MODEL_SUB_DIR/$DEFAULT_MODEL_NAME"))
            candidates.add(File(dir.parentFile, "$MODEL_PARENT_DIR/$MODEL_SUB_DIR/$DEFAULT_MODEL_NAME"))
        }

        // 应用内部存储
        candidates.add(File(context.filesDir, "$MODEL_PARENT_DIR/$MODEL_SUB_DIR/$DEFAULT_MODEL_NAME"))

        // 共享外部存储（旧式路径，兼容直接 adb push 到 /sdcard）
        val sharedExternal = Environment.getExternalStorageDirectory()
        candidates.add(File(sharedExternal, "$MODEL_PARENT_DIR/$MODEL_SUB_DIR/$DEFAULT_MODEL_NAME"))
        candidates.add(File(sharedExternal, "$MODEL_PARENT_DIR/$DEFAULT_MODEL_NAME"))

        for (candidate in candidates) {
            if (candidate.exists() && candidate.isDirectory) {
                val hasConf = File(candidate, "conf").exists()
                val hasAmDir = File(candidate, "am").exists()
                if (hasConf || hasAmDir) {
                    LingShuLog.i(moduleTag, "找到模型目录: ${candidate.absolutePath}")
                    return candidate
                }
            }
        }

        LingShuLog.w(moduleTag, "未找到模型目录，搜索路径: ${candidates.joinToString("; ") { it.absolutePath }}")
        return null
    }

    private fun ensureModelLoaded(): Boolean {
        if (model != null) return true

        val modelDir = findModelDir()
        if (modelDir == null) {
            LingShuLog.w(moduleTag, "模型未部署。请下载 $DEFAULT_MODEL_NAME 并放置到 /sdcard/vosk/models/")
            return false
        }

        return try {
            LingShuLog.i(moduleTag, "正在加载 Vosk 模型: ${modelDir.absolutePath}")
            model = Model(modelDir.absolutePath)
            LingShuLog.i(moduleTag, "Vosk 模型加载成功")
            true
        } catch (e: Exception) {
            LingShuLog.e(moduleTag, "模型加载失败", e)
            model = null
            false
        }
    }

    override fun startListening(onResult: (SttResult) -> Unit, onError: (String) -> Unit) {
        if (isListening) {
            LingShuLog.w(moduleTag, "已经在监听中，忽略重复调用")
            return
        }

        if (!isAvailable()) {
            onError("Vosk 模型未部署。请下载模型放置到 /sdcard/vosk/models/$DEFAULT_MODEL_NAME")
            return
        }

        if (!ensureModelLoaded()) {
            onError("Vosk 模型加载失败")
            return
        }

        onResultCallback = onResult
        onErrorCallback = onError

        try {
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            isListening = true
            LingShuLog.i(moduleTag, "开始 Vosk 语音识别")

            recognitionThread = Thread {
                runRecognitionLoop()
            }.also { it.start() }
        } catch (e: Exception) {
            LingShuLog.e(moduleTag, "启动识别失败", e)
            isListening = false
            onError("Vosk 启动失败: ${e.message}")
            cleanup()
        }
    }

    private fun runRecognitionLoop() {
        try {
            val audioRecord = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord.state != android.media.AudioRecord.STATE_INITIALIZED) {
                onErrorCallback?.invoke("AudioRecord 初始化失败")
                cleanup()
                return
            }

            audioRecord.startRecording()
            LingShuLog.d(moduleTag, "AudioRecord 开始录音")

            val buffer = ShortArray(bufferSize)
            var silenceCount = 0
            val maxSilenceRounds = 30 // 约 30 轮无语音后超时

            while (isListening) {
                val n = audioRecord.read(buffer, 0, buffer.size)
                if (n <= 0) continue

                val rec = recognizer ?: break
                if (rec.acceptWaveForm(buffer, n)) {
                    val resultJson = rec.result
                    val text = parseResultText(resultJson)
                    if (text.isNotEmpty()) {
                        LingShuLog.d(moduleTag, "识别结果: $text")
                        isListening = false
                        audioRecord.stop()
                        audioRecord.release()
                        onResultCallback?.invoke(SttResult(text = text, confidence = 0.9f))
                        cleanup()
                        return
                    }
                } else {
                    val partialJson = rec.partialResult
                    val partialText = parsePartialText(partialJson)
                    if (partialText.isNotEmpty()) {
                        silenceCount = 0
                    } else {
                        silenceCount++
                        if (silenceCount >= maxSilenceRounds) {
                            LingShuLog.d(moduleTag, "超时无语音输入")
                            break
                        }
                    }
                }
            }

            // 取出最终结果
            val rec = recognizer
            if (rec != null && isListening) {
                val finalJson = rec.finalResult
                val text = parseResultText(finalJson)
                audioRecord.stop()
                audioRecord.release()
                if (text.isNotEmpty()) {
                    LingShuLog.d(moduleTag, "最终结果: $text")
                    onResultCallback?.invoke(SttResult(text = text, confidence = 0.85f))
                } else {
                    onErrorCallback?.invoke(ErrorCodes.getMessage(ErrorCodes.STT_FAILED))
                }
            }

            isListening = false
            cleanup()
        } catch (e: Exception) {
            LingShuLog.e(moduleTag, "识别循环异常", e)
            isListening = false
            onErrorCallback?.invoke("识别异常: ${e.message}")
            cleanup()
        }
    }

    private val bufferSize: Int by lazy {
        android.media.AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
    }

    private fun parseResultText(json: String): String {
        return try {
            val obj = JSONObject(json)
            obj.optString("text", "").trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun parsePartialText(json: String): String {
        return try {
            val obj = JSONObject(json)
            obj.optString("partial", "").trim()
        } catch (_: Exception) {
            ""
        }
    }

    override fun stopListening() {
        LingShuLog.d(moduleTag, "停止监听")
        isListening = false
        recognitionThread?.let { thread ->
            try {
                thread.join(1000)
            } catch (_: Exception) {
            }
        }
        recognitionThread = null
    }

    override fun isAvailable(): Boolean {
        return findModelDir() != null
    }

    override fun cancel() {
        stopListening()
        cleanup()
    }

    private fun cleanup() {
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        recognizer = null
        onResultCallback = null
        onErrorCallback = null
        isListening = false
    }

    /**
     * 释放模型资源（应用退出时调用）。
     */
    fun release() {
        cleanup()
        try {
            model?.close()
        } catch (_: Exception) {
        }
        model = null
        LingShuLog.i(moduleTag, "Vosk 资源已释放")
    }
}

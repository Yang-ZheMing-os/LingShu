package com.lingshu.feature.stt.data

import android.content.Context
import android.os.Environment
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.lingshu.core.common.event.ISttEngine
import com.lingshu.core.common.event.SttResult
import com.lingshu.core.common.log.LingShuLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SherpaOnnxSttEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : ISttEngine {

    private val tag = "SherpaOnnxStt"

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val MODEL_DIR_NAME = "sensevoice"
        private const val MODEL_FILE = "model.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val VAD_FILE = "silero_vad.onnx"
        private const val VAD_WINDOW_SIZE = 512
    }

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var isListening = false
    private var recognitionThread: Thread? = null

    private var onResultCallback: ((SttResult) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    private data class ModelPaths(
        val modelFile: File,
        val tokensFile: File,
        val vadFile: File,
        val dir: File
    )

    private fun findModelDir(): ModelPaths? {
        val candidates = mutableListOf<File>()

        context.getExternalFilesDir(null)?.let { ext ->
            candidates.add(File(ext, "sherpa/$MODEL_DIR_NAME"))
        }
        candidates.add(File(context.filesDir, "sherpa/$MODEL_DIR_NAME"))

        val shared = Environment.getExternalStorageDirectory()
        candidates.add(File(shared, "sherpa/$MODEL_DIR_NAME"))
        candidates.add(File(shared, "lingshu/sherpa/$MODEL_DIR_NAME"))

        for (dir in candidates) {
            val model = File(dir, MODEL_FILE)
            val tokens = File(dir, TOKENS_FILE)
            val vadFile = File(dir, VAD_FILE)
            if (model.exists() && tokens.exists() && vadFile.exists()) {
                LingShuLog.i(tag, "找到 Sherpa-ONNX 模型目录: ${dir.absolutePath}")
                return ModelPaths(model, tokens, vadFile, dir)
            }
        }

        LingShuLog.w(tag, "未找到 Sherpa-ONNX 模型，搜索路径: ${candidates.joinToString("; ") { it.absolutePath }}")
        LingShuLog.w(tag, "请下载 SenseVoice 模型并放置 model.int8.onnx / tokens.txt / silero_vad.onnx")
        return null
    }

    private fun ensureInitialized(): Boolean {
        if (recognizer != null && vad != null) return true

        val paths = findModelDir() ?: return false

        return try {
            LingShuLog.i(tag, "正在加载 Sherpa-ONNX SenseVoice 模型...")

            val featConfig = FeatureConfig(
                sampleRate = SAMPLE_RATE,
                featureDim = 80
            )

            val modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = paths.modelFile.absolutePath,
                    language = "auto",
                    useInverseTextNormalization = true
                ),
                tokens = paths.tokensFile.absolutePath,
                numThreads = 2,
                provider = "cpu",
                debug = false
            )

            val recognizerConfig = OfflineRecognizerConfig(
                featConfig = featConfig,
                modelConfig = modelConfig,
                decodingMethod = "greedy_search"
            )

            recognizer = OfflineRecognizer(
                assetManager = null,
                config = recognizerConfig
            )

            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = paths.vadFile.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.25f,
                    windowSize = VAD_WINDOW_SIZE,
                    maxSpeechDuration = 10.0f
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu"
            )
            vad = Vad(assetManager = null, config = vadConfig)

            LingShuLog.i(tag, "Sherpa-ONNX SenseVoice 模型加载成功")
            true
        } catch (e: Exception) {
            LingShuLog.e(tag, "Sherpa-ONNX 模型加载失败", e)
            release()
            false
        }
    }

    override fun startListening(onResult: (SttResult) -> Unit, onError: (String) -> Unit) {
        if (isListening) {
            LingShuLog.w(tag, "已在监听中，忽略重复调用")
            return
        }

        if (!isAvailable()) {
            onError("SenseVoice 模型未部署，可在设置页一键下载（推荐），或手动放置模型文件到 /sdcard/sherpa/sensevoice/")
            return
        }

        if (!ensureInitialized()) {
            onError("Sherpa-ONNX 模型加载失败")
            return
        }

        onResultCallback = onResult
        onErrorCallback = onError
        isListening = true

        recognitionThread = Thread { runRecognitionLoop() }.also { it.start() }
        LingShuLog.i(tag, "开始 Sherpa-ONNX 语音识别")
    }

    private fun runRecognitionLoop() {
        var audioRecord: android.media.AudioRecord? = null
        try {
            val minBuf = android.media.AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(VAD_WINDOW_SIZE * 2)

            audioRecord = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )

            if (audioRecord.state != android.media.AudioRecord.STATE_INITIALIZED) {
                onErrorCallback?.invoke("AudioRecord 初始化失败")
                return
            }

            audioRecord.startRecording()
            LingShuLog.d(tag, "AudioRecord 开始录音")

            val vadInstance = vad ?: return
            val rec = recognizer ?: return

            val shortBuffer = ShortArray(VAD_WINDOW_SIZE)
            var totalSpeechSamples = 0
            var silenceRounds = 0
            val maxSilenceRounds = 60

            while (isListening) {
                val n = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                if (n <= 0) continue

                val floatSamples = FloatArray(n) { i -> shortBuffer[i] / 32768.0f }
                vadInstance.acceptWaveform(floatSamples)

                while (!vadInstance.empty()) {
                    val segment = vadInstance.front()
                    val segmentSamples = segment.samples
                    if (segmentSamples.isNotEmpty()) {
                        LingShuLog.d(tag, "VAD 检测到语音段，样本数=${segmentSamples.size}")

                        val stream = rec.createStream()
                        stream.acceptWaveform(segmentSamples, SAMPLE_RATE)
                        rec.decode(stream)
                        val result = rec.getResult(stream)
                        stream.release()

                        val text = result.text.trim()
                        if (text.isNotEmpty()) {
                            LingShuLog.i(tag, "识别结果: $text | lang=${result.lang} | emotion=${result.emotion}")
                            isListening = false
                            audioRecord.stop()
                            audioRecord.release()
                            onResultCallback?.invoke(
                                SttResult(text = text, confidence = 0.92f)
                            )
                            cleanup()
                            return
                        }
                    }
                    vadInstance.pop()
                }

                if (vadInstance.isSpeechDetected()) {
                    totalSpeechSamples += n
                    silenceRounds = 0
                } else {
                    silenceRounds++
                    if (totalSpeechSamples > 0 && silenceRounds >= maxSilenceRounds) {
                        LingShuLog.d(tag, "静音超时，flush VAD")
                        vadInstance.flush()
                        while (!vadInstance.empty()) {
                            val segment = vadInstance.front()
                            if (segment.samples.isNotEmpty()) {
                                val stream = rec.createStream()
                                stream.acceptWaveform(segment.samples, SAMPLE_RATE)
                                rec.decode(stream)
                                val result = rec.getResult(stream)
                                stream.release()

                                val text = result.text.trim()
                                if (text.isNotEmpty()) {
                                    LingShuLog.i(tag, "Flush 识别结果: $text")
                                    isListening = false
                                    audioRecord.stop()
                                    audioRecord.release()
                                    onResultCallback?.invoke(
                                        SttResult(text = text, confidence = 0.88f)
                                    )
                                    cleanup()
                                    return
                                }
                            }
                            vadInstance.pop()
                        }
                        totalSpeechSamples = 0
                        silenceRounds = 0
                    }
                }
            }
        } catch (e: Exception) {
            LingShuLog.e(tag, "识别循环异常", e)
            isListening = false
            onErrorCallback?.invoke("识别异常: ${e.message}")
        } finally {
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (_: Exception) {
            }
            cleanup()
        }
    }

    override fun stopListening() {
        LingShuLog.d(tag, "停止监听")
        isListening = false
        recognitionThread?.let { t ->
            try {
                t.join(1500)
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
    }

    private fun cleanup() {
        onResultCallback = null
        onErrorCallback = null
        isListening = false
    }

    fun release() {
        try {
            vad?.release()
        } catch (_: Exception) {
        }
        try {
            recognizer?.release()
        } catch (_: Exception) {
        }
        vad = null
        recognizer = null
        cleanup()
        LingShuLog.i(tag, "Sherpa-ONNX 资源已释放")
    }
}

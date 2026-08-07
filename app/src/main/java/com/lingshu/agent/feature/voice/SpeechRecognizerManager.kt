package com.lingshu.agent.feature.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 语音识别（STT）管理器
 *
 * 采用双引擎架构：
 * 1. Vosk离线识别引擎 - 无需网络，响应快，支持中文识别
 *    - 模型URL: https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip
 *    - 模型存放路径: /data/data/com.lingshu/files/models/vosk/
 * 2. 系统SpeechRecognizer - 在线高精度识别，支持多语言
 *
 * 自动降级策略：
 * - 优先使用在线识别，网络不可用或识别失败时自动降级到离线引擎
 * - 离线引擎不可用时抛出错误
 *
 * VAD参数（可通过DataStore动态调整）：
 * - 静音阈值: 默认 -40dB → 映射到 VAD.DEFAULT_VOLUME_THRESHOLD
 * - 超时时长: 默认 3000ms → 映射到 VAD.DEFAULT_LONG_SILENCE_MS
 * - 最小语音时长: 默认 500ms → 映射到 VAD.DEFAULT_MIN_SPEECH_DURATION_MS
 *
 * 置信度处理：置信度 < 0.6 时提示"没听清"
 */
class SpeechRecognizerManager(
    private val context: Context,
    private val vad: VAD = VAD()
) {

    /**
     * 语音识别回调接口
     */
    interface RecognitionCallback {
        /**
         * 开始录音时调用
         */
        fun onStartOfSpeech()

        /**
         * 检测到说话结束（VAD断句）时调用
         */
        fun onEndOfSpeech()

        /**
         * 实时识别结果回调（部分结果）
         * @param text 部分识别文本
         * @param isFinal 是否为最终结果
         */
        fun onPartialResult(text: String, isFinal: Boolean = false)

        /**
         * 最终识别结果
         * @param text 完整识别文本
         */
        fun onResult(text: String)

        /**
         * 识别错误
         * @param errorCode 错误码
         * @param errorMessage 错误信息
         */
        fun onError(errorCode: Int, errorMessage: String)

        /**
         * 音量变化回调
         * @param rmsdB 音量分贝值
         */
        fun onRmsChanged(rmsdB: Float)

        /**
         * 引擎切换回调
         * @param engineType 当前使用的引擎类型
         */
        fun onEngineSwitched(engineType: EngineType)
    }

    /**
     * 识别引擎类型枚举
     */
    enum class EngineType {
        /** Vosk离线引擎 */
        VOSK_OFFLINE,
        /** 系统在线识别引擎 */
        SYSTEM_ONLINE,
        /** 未选择引擎 */
        NONE
    }

    /**
     * 识别状态枚举
     */
    enum class RecognitionState {
        /** 空闲 */
        IDLE,
        /** 初始化中 */
        INITIALIZING,
        /** 正在录音/识别 */
        LISTENING,
        /** 处理识别结果 */
        PROCESSING,
        /** 已停止 */
        STOPPED,
        /** 错误 */
        ERROR
    }

    companion object {
        /** 错误码：未知错误 */
        const val ERROR_UNKNOWN = 1

        /** 错误码：无网络连接 */
        const val ERROR_NETWORK = 2

        /** 错误码：无录音权限 */
        const val ERROR_AUDIO = 3

        /** 错误码：引擎初始化失败 */
        const val ERROR_ENGINE_INIT = 4

        /** 错误码：用户取消 */
        const val ERROR_USER_CANCELED = 5

        /** 错误码：无匹配结果 */
        const val ERROR_NO_MATCH = 6

        /** 错误码：识别超时 */
        const val ERROR_TIMEOUT = 7

        /** 采样率（Vosk推荐16kHz） */
        const val SAMPLE_RATE = 16000

        /** 声道配置：单声道 */
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO

        /** 音频格式：16位PCM */
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** 默认识别超时时间（毫秒） */
        const val DEFAULT_TIMEOUT_MS = 10000L
    }

    /** 当前使用的识别引擎 */
    private var currentEngine: EngineType = EngineType.NONE

    /** 当前识别状态 */
    private var state: RecognitionState = RecognitionState.IDLE

    /** 识别回调 */
    private var callback: RecognitionCallback? = null

    /** 系统SpeechRecognizer实例 */
    private var systemRecognizer: SpeechRecognizer? = null

    /** 音频录制实例 */
    private var audioRecord: AudioRecord? = null

    /** 录音线程 */
    private var recordingJob: Job? = null

    /** 协程作用域 */
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 主线程Handler，用于回调到主线程 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 累计的部分识别结果 */
    private var partialTextBuilder = StringBuilder()

    /** 超时任务 */
    private var timeoutJob: Job? = null

    /** 是否支持中英混合识别 */
    var enableMixedLanguage: Boolean = true

    /** 识别超时时间（毫秒） */
    var timeoutMs: Long = DEFAULT_TIMEOUT_MS

    /** 是否启用VAD自动断句 */
    var enableVAD: Boolean = true

    /** 置信度阈值：低于此值提示"没听清" */
    var confidenceThreshold: Float = 0.6f

    /** Vosk引擎是否可用标记（实际项目中集成Vosk SDK后设置） */
    var voskEngineAvailable: Boolean = false
        private set

    /** 系统引擎是否可用标记 */
    var systemEngineAvailable: Boolean = false
        private set

    /** 当前语言标签（zh-CN, en-US, 或 mixed） */
    var currentLanguage: String = "mixed"
        private set

    /**
     * 初始化识别引擎
     * 检测可用的识别引擎并优先使用在线引擎
     */
    fun initialize() {
        state = RecognitionState.INITIALIZING

        // 检测系统识别引擎可用性
        systemEngineAvailable = SpeechRecognizer.isRecognitionAvailable(context)

        // 检测Vosk离线引擎（实际项目中检查模型文件是否存在）
        voskEngineAvailable = checkVoskModelAvailable()

        // 选择默认引擎
        currentEngine = when {
            systemEngineAvailable -> EngineType.SYSTEM_ONLINE
            voskEngineAvailable -> EngineType.VOSK_OFFLINE
            else -> EngineType.NONE
        }

        state = if (currentEngine != EngineType.NONE) {
            RecognitionState.IDLE
        } else {
            RecognitionState.ERROR
        }
    }

    /**
     * 开始语音识别
     * @param callback 识别结果回调
     * @param preferredEngine 首选引擎类型，null则使用自动选择
     * @return 是否成功启动
     */
    fun startRecognition(
        callback: RecognitionCallback,
        preferredEngine: EngineType? = null
    ): Boolean {
        if (state == RecognitionState.LISTENING || state == RecognitionState.PROCESSING) {
            return false
        }

        // 检查录音权限，无权限时直接返回错误，避免 AudioRecord 崩溃
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            notifyError(ERROR_AUDIO, "缺少录音权限，请在系统设置中授予录音权限")
            return false
        }

        this.callback = callback
        partialTextBuilder.clear()
        vad.reset()

        // 选择引擎
        val targetEngine = preferredEngine ?: currentEngine
        val engineToUse = selectEngine(targetEngine)

        if (engineToUse == EngineType.NONE) {
            notifyError(ERROR_ENGINE_INIT, "没有可用的识别引擎")
            return false
        }

        // 切换引擎时通知
        if (engineToUse != currentEngine) {
            currentEngine = engineToUse
            mainHandler.post { callback.onEngineSwitched(engineToUse) }
        }

        state = RecognitionState.LISTENING

        // 启动超时计时器
        startTimeoutTimer()

        return when (engineToUse) {
            EngineType.SYSTEM_ONLINE -> startSystemRecognition()
            EngineType.VOSK_OFFLINE -> startVoskRecognition()
            else -> false
        }
    }

    /**
     * 停止识别
     */
    fun stopRecognition() {
        cancelTimeoutTimer()
        recordingJob?.cancel()
        recordingJob = null

        // 停止系统识别器
        try {
            systemRecognizer?.stopListening()
        } catch (e: Exception) {
            // 忽略停止异常
        }

        // 释放AudioRecord
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (e: Exception) {
            // 忽略释放异常
        }
        audioRecord = null

        state = RecognitionState.STOPPED
    }

    /**
     * 取消当前识别（不返回结果）
     */
    fun cancelRecognition() {
        cancelTimeoutTimer()
        recordingJob?.cancel()
        recordingJob = null

        try {
            systemRecognizer?.cancel()
        } catch (e: Exception) {
            // 忽略取消异常
        }

        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (e: Exception) {
            // 忽略释放异常
        }
        audioRecord = null

        state = RecognitionState.IDLE
    }

    /**
     * 获取当前识别状态
     */
    fun getState(): RecognitionState = state

    /**
     * 获取当前引擎类型
     */
    fun getCurrentEngine(): EngineType = currentEngine

    /**
     * 手动切换识别引擎
     */
    fun switchEngine(engineType: EngineType): Boolean {
        if (engineType == EngineType.VOSK_OFFLINE && !voskEngineAvailable) {
            return false
        }
        if (engineType == EngineType.SYSTEM_ONLINE && !systemEngineAvailable) {
            return false
        }
        currentEngine = engineType
        callback?.let { mainHandler.post { it.onEngineSwitched(engineType) } }
        return true
    }

    /**
     * 从DataStore同步VAD参数（P1可配置项）
     * @param silenceThresholdDb 静音阈值 (dB)，默认 -40
     * @param timeoutMs 超时时长 (ms)，默认 3000
     * @param minSpeechMs 最小语音时长 (ms)，默认 500
     */
    fun configureVAD(
        silenceThresholdDb: Float = -40f,
        timeoutMs: Int = 3000,
        minSpeechMs: Int = 500
    ) {
        // VAD 内部使用归一化后 0~10 的音量值，dB → threshold 映射
        // -40dB → 对应用户安静说话与背景噪声的分界
        vad.volumeThreshold = ((silenceThresholdDb + 100) / 10).coerceIn(0f, 10f)
        vad.longSilenceMs = timeoutMs.toLong()
        vad.minSpeechDurationMs = minSpeechMs.toLong()
    }

    /**
     * 释放资源
     */
    fun release() {
        stopRecognition()
        try {
            systemRecognizer?.destroy()
        } catch (e: Exception) {
            // 忽略销毁异常
        }
        systemRecognizer = null
        callback = null
        state = RecognitionState.IDLE
    }

    /**
     * 选择合适的识别引擎（自动降级逻辑）
     */
    private fun selectEngine(preferred: EngineType): EngineType {
        if (preferred == EngineType.SYSTEM_ONLINE && systemEngineAvailable) {
            // 检查网络连通性（简化版本，实际可调用ConnectivityManager）
            if (isNetworkAvailable()) {
                return EngineType.SYSTEM_ONLINE
            }
            // 无网络，降级到离线引擎
            if (voskEngineAvailable) {
                return EngineType.VOSK_OFFLINE
            }
        }
        if (preferred == EngineType.VOSK_OFFLINE && voskEngineAvailable) {
            return EngineType.VOSK_OFFLINE
        }
        // 首选不可用，按优先级尝试
        return when {
            systemEngineAvailable && isNetworkAvailable() -> EngineType.SYSTEM_ONLINE
            voskEngineAvailable -> EngineType.VOSK_OFFLINE
            else -> EngineType.NONE
        }
    }

    /**
     * 启动系统SpeechRecognizer识别
     */
    private fun startSystemRecognition(): Boolean {
        return try {
            if (systemRecognizer == null) {
                systemRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

                // 设置语言：支持混合、中文、英文
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    if (enableMixedLanguage) Locale.getDefault().language else currentLanguage
                )
            }

            systemRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    mainHandler.post { callback?.onStartOfSpeech() }
                }

                override fun onBeginningOfSpeech() {
                    vad.markSpeechStart()
                }

                override fun onRmsChanged(rmsdB: Float) {
                    mainHandler.post { callback?.onRmsChanged(rmsdB) }
                    // 将音量传给VAD
                    if (enableVAD) {
                        processVAD(rmsdB)
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    // 不需要处理
                }

                override fun onEndOfSpeech() {
                    vad.markSpeechEnd()
                    mainHandler.post { callback?.onEndOfSpeech() }
                    state = RecognitionState.PROCESSING
                }

                override fun onError(error: Int) {
                    handleSystemEngineError(error)
                }

                override fun onResults(results: Bundle?) {
                    cancelTimeoutTimer()
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val text = matches?.firstOrNull() ?: ""

                    // 置信度检查：低于阈值时提示"没听清"
                    val topConfidence = confidences?.firstOrNull() ?: 0f
                    if (topConfidence < confidenceThreshold && text.isNotEmpty()) {
                        partialTextBuilder.append(text)
                        mainHandler.post {
                            callback?.onPartialResult("没听清，请再说一遍 (置信度: ${String.format("%.1f", topConfidence * 100)}%)")
                            callback?.onResult(partialTextBuilder.toString())
                        }
                    } else if (text.isNotEmpty()) {
                        partialTextBuilder.append(text)
                        mainHandler.post { callback?.onResult(partialTextBuilder.toString()) }
                    } else {
                        notifyError(ERROR_NO_MATCH, "未识别到有效语音内容")
                    }
                    state = RecognitionState.IDLE
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotEmpty()) {
                        mainHandler.post { callback?.onPartialResult(text) }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    // 预留事件处理
                }
            })

            systemRecognizer?.startListening(intent)
            true
        } catch (e: SecurityException) {
            notifyError(ERROR_AUDIO, "缺少录音权限：${e.message}")
            false
        } catch (e: Exception) {
            notifyError(ERROR_ENGINE_INIT, "系统识别引擎启动失败：${e.message}")
            // 尝试降级到离线引擎
            if (voskEngineAvailable && currentEngine != EngineType.VOSK_OFFLINE) {
                currentEngine = EngineType.VOSK_OFFLINE
                mainHandler.post { callback?.onEngineSwitched(EngineType.VOSK_OFFLINE) }
                return startVoskRecognition()
            }
            false
        }
    }

    /**
     * 启动Vosk离线识别
     * 注意：此为框架实现，实际项目中需要集成Vosk Android SDK
     */
    private fun startVoskRecognition(): Boolean {
        return try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                notifyError(ERROR_AUDIO, "AudioRecord初始化失败")
                return false
            }

            audioRecord?.startRecording()
            mainHandler.post { callback?.onStartOfSpeech() }

            // 启动录音处理协程
            recordingJob = coroutineScope.launch {
                val buffer = ShortArray(bufferSize / 2)
                var hasSpeechStarted = false

                while (state == RecognitionState.LISTENING && coroutineScope.isActive) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize <= 0) continue

                    // 计算音量分贝
                    val rms = calculateRMS(buffer, readSize)
                    mainHandler.post { callback?.onRmsChanged(rms) }

                    // VAD处理
                    if (enableVAD) {
                        val vadResult = processVAD(rms)
                        when (vadResult) {
                            VAD.VADEvent.SPEECH_START -> {
                                hasSpeechStarted = true
                                vad.markSpeechStart()
                            }
                            VAD.VADEvent.SPEECH_END -> {
                                if (hasSpeechStarted) {
                                    vad.markSpeechEnd()
                                    mainHandler.post { callback?.onEndOfSpeech() }
                                    // 结束识别，返回最终结果
                                    break
                                }
                            }
                            else -> { /* 继续监听 */ }
                        }
                    }

                    // 将音频数据传递给Vosk引擎进行识别
                    // 实际项目中调用 Vosk API 进行解码
                    // val voskResult = voskRecognizer.acceptWaveForm(buffer, readSize)
                    // if (voskResult.partial.isNotEmpty()) {
                    //     mainHandler.post { callback?.onPartialResult(voskResult.partial) }
                    // }
                    // if (voskResult.final.isNotEmpty()) {
                    //     partialTextBuilder.append(voskResult.final)
                    //     mainHandler.post { callback?.onPartialResult(voskResult.final, true) }
                    // }

                    // 模拟小延迟，避免CPU占用过高
                    delay(10)
                }

                // 识别结束，发布最终结果
                if (partialTextBuilder.isNotEmpty()) {
                    mainHandler.post { callback?.onResult(partialTextBuilder.toString()) }
                } else {
                    mainHandler.post {
                        callback?.onError(ERROR_NO_MATCH, "离线识别未返回有效结果")
                    }
                }
                state = RecognitionState.IDLE
            }

            true
        } catch (e: SecurityException) {
            notifyError(ERROR_AUDIO, "缺少录音权限：${e.message}")
            false
        } catch (e: Exception) {
            notifyError(ERROR_ENGINE_INIT, "Vosk识别引擎启动失败：${e.message}")
            false
        }
    }

    /**
     * 处理系统识别引擎错误，实现自动降级
     */
    private fun handleSystemEngineError(errorCode: Int) {
        cancelTimeoutTimer()
        val errorMessage = when (errorCode) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK -> {
                // 网络错误，尝试降级到离线引擎
                if (voskEngineAvailable && state == RecognitionState.LISTENING) {
                    val currentCallback = callback
                    currentEngine = EngineType.VOSK_OFFLINE
                    mainHandler.post { currentCallback?.onEngineSwitched(EngineType.VOSK_OFFLINE) }
                    // 重新启动离线识别
                    if (currentCallback != null) {
                        state = RecognitionState.IDLE
                        startVoskRecognition()
                        return
                    }
                }
                "网络连接错误或超时"
            }
            SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
            SpeechRecognizer.ERROR_NO_MATCH -> "未匹配到语音内容"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                state = RecognitionState.IDLE
                "识别服务繁忙"
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少必要权限"
            SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                state = RecognitionState.IDLE
                "语音输入超时"
            }
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "不支持的语言"
            else -> "未知错误($errorCode)"
        }
        notifyError(convertSystemError(errorCode), errorMessage)
    }

    /**
     * 系统错误码转换为自定义错误码
     */
    private fun convertSystemError(systemError: Int): Int {
        return when (systemError) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK -> ERROR_NETWORK
            SpeechRecognizer.ERROR_AUDIO -> ERROR_AUDIO
            SpeechRecognizer.ERROR_NO_MATCH -> ERROR_NO_MATCH
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ERROR_TIMEOUT
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ERROR_AUDIO
            else -> ERROR_UNKNOWN
        }
    }

    /**
     * 通知错误事件
     */
    private fun notifyError(code: Int, message: String) {
        state = RecognitionState.ERROR
        cancelTimeoutTimer()
        mainHandler.post { callback?.onError(code, message) }
        state = RecognitionState.IDLE
    }

    /**
     * 启动超时计时器
     */
    private fun startTimeoutTimer() {
        cancelTimeoutTimer()
        timeoutJob = coroutineScope.launch {
            delay(timeoutMs)
            if (state == RecognitionState.LISTENING) {
                mainHandler.post {
                    callback?.onError(ERROR_TIMEOUT, "识别超时，未检测到完整语音")
                }
                stopRecognition()
            }
        }
    }

    /**
     * 取消超时计时器
     */
    private fun cancelTimeoutTimer() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    /**
     * 处理VAD逻辑
     * @return VAD事件
     */
    private fun processVAD(rmsdB: Float): VAD.VADEvent {
        val event = vad.processVolume(rmsdB)
        when (event) {
            VAD.VADEvent.SPEECH_START -> {
                // 重置超时，因为用户开始说话了
                cancelTimeoutTimer()
            }
            VAD.VADEvent.SILENCE_TIMEOUT -> {
                // 静音超时，可以认为说话结束
                cancelTimeoutTimer()
            }
            else -> { /* 无特殊处理 */ }
        }
        return event
    }

    /**
     * 计算RMS（均方根）音量值（转换为近似dB）
     */
    private fun calculateRMS(buffer: ShortArray, readSize: Int): Float {
        var sum = 0.0
        for (i in 0 until readSize) {
            sum += (buffer[i] * buffer[i]).toDouble()
        }
        val rms = Math.sqrt(sum / readSize)
        // 转换为分贝值，基准值为32768.0（16位PCM最大值）
        val db = 20 * Math.log10(rms / 32768.0 + 1e-10)
        // 将dB归一化到 0~10 范围便于显示和VAD处理
        return ((db + 100) / 10).toFloat().coerceIn(0f, 10f)
    }

    /**
     * 检查Vosk模型是否可用（简化实现）
     * 实际项目中应检查assets/files目录下模型文件是否存在
     */
    private fun checkVoskModelAvailable(): Boolean {
        return try {
            // 实际项目中：检查模型目录是否存在
            // val modelDir = File(context.filesDir, "vosk-model-small-cn-0.22")
            // modelDir.exists() && modelDir.isDirectory
            false // 默认返回false，待实际集成Vosk后开启
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 简化的网络可用性检查
     * 实际项目中应使用 ConnectivityManager
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
            cm?.activeNetworkInfo?.isConnected == true
        } catch (e: Exception) {
            true // 简化处理，默认认为网络可用
        }
    }
}

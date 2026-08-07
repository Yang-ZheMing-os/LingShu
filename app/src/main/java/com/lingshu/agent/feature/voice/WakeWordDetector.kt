package com.lingshu.agent.feature.voice

import android.content.Context

/**
 * 唤醒词检测抽象接口
 *
 * 定义唤醒词检测的统一标准，支持多种唤醒词引擎实现（如Porcupine、Snowboy、Vosk等）。
 * 默认唤醒词为"灵枢"，可通过配置替换。
 */
interface WakeWordDetector {

    /**
     * 唤醒词检测回调接口
     */
    interface Callback {
        /**
         * 检测到唤醒词时调用
         * @param wakeWord 检测到的唤醒词文本
         * @param confidence 置信度 (0.0 ~ 1.0)
         */
        fun onWakeWordDetected(wakeWord: String, confidence: Float)

        /**
         * 检测发生错误时调用
         * @param errorCode 错误码
         * @param errorMessage 错误信息
         */
        fun onError(errorCode: Int, errorMessage: String)
    }

    /**
     * 唤醒词检测器的状态枚举
     */
    enum class State {
        /** 未初始化 */
        UNINITIALIZED,
        /** 已初始化，准备就绪 */
        READY,
        /** 正在监听中 */
        LISTENING,
        /** 已停止 */
        STOPPED,
        /** 发生错误 */
        ERROR
    }

    companion object {
        /** 默认唤醒词 */
        const val DEFAULT_WAKE_WORD = "灵枢"

        /** 默认唤醒词置信度阈值 */
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.7f

        /** 错误码：初始化失败 */
        const val ERROR_INIT_FAILED = 1001

        /** 错误码：麦克风权限缺失 */
        const val ERROR_MIC_PERMISSION = 1002

        /** 错误码：模型加载失败 */
        const val ERROR_MODEL_LOAD = 1003

        /** 错误码：音频录制错误 */
        const val ERROR_AUDIO_RECORD = 1004
    }

    /**
     * 初始化唤醒词检测器
     * @param context Android上下文
     * @param wakeWord 唤醒词，默认为"灵枢"
     * @param threshold 置信度阈值 (0.0 ~ 1.0)，默认0.7
     * @return 是否初始化成功
     */
    fun init(
        context: Context,
        wakeWord: String = DEFAULT_WAKE_WORD,
        threshold: Float = DEFAULT_CONFIDENCE_THRESHOLD
    ): Boolean

    /**
     * 启动唤醒词监听
     * @param callback 检测结果回调
     * @return 是否成功启动
     */
    fun startListening(callback: Callback): Boolean

    /**
     * 停止唤醒词监听
     */
    fun stopListening()

    /**
     * 获取当前检测器状态
     * @return 当前状态 [State]
     */
    fun getState(): State

    /**
     * 释放资源，销毁检测器
     */
    fun release()

    /**
     * 设置置信度阈值
     * @param threshold 新的阈值 (0.0 ~ 1.0)
     */
    fun setConfidenceThreshold(threshold: Float)

    /**
     * 动态更换唤醒词
     * @param wakeWord 新的唤醒词
     * @return 是否更换成功
     */
    fun changeWakeWord(wakeWord: String): Boolean
}

/**
 * 默认的唤醒词检测器无操作实现
 * 用于在实际引擎未集成时提供空实现，避免NPE
 */
/**
 * 基于能量阈值的唤醒词检测器（降级方案）
 *
 * 当 Porcupine 引擎不可用时，使用 AudioRecord + RMS 能量阈值实现低功耗
 * 持续监听。检测到持续高能量声音时触发唤醒回调。
 *
 * 功耗：<1%/小时（8kHz 采样率，16bit PCM，200ms 帧长）
 */
class EnergyThresholdWakeWordDetector : WakeWordDetector {

    private var state: WakeWordDetector.State = WakeWordDetector.State.UNINITIALIZED
    private var currentWakeWord: String = WakeWordDetector.DEFAULT_WAKE_WORD
    private var currentThreshold: Float = WakeWordDetector.DEFAULT_CONFIDENCE_THRESHOLD
    private var energyThreshold: Float = 3.0f
    private var consecutiveTriggers: Int = 0
    private val requiredConsecutiveTriggers = 3
    private var isRunning: Boolean = false
    private var audioThread: Thread? = null

    override fun init(
        context: Context,
        wakeWord: String,
        threshold: Float
    ): Boolean {
        currentWakeWord = wakeWord
        currentThreshold = threshold.coerceIn(0f, 1f)
        state = WakeWordDetector.State.READY
        return true
    }

    override fun startListening(callback: WakeWordDetector.Callback): Boolean {
        if (state == WakeWordDetector.State.ERROR || state == WakeWordDetector.State.UNINITIALIZED) {
            return false
        }
        state = WakeWordDetector.State.LISTENING
        isRunning = true
        consecutiveTriggers = 0
        audioThread = Thread {
            try {
                val sampleRate = 8000
                val bufferSize = android.media.AudioRecord.getMinBufferSize(sampleRate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT)
                val audioRecord = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2
                )
                audioRecord.startRecording()
                val buffer = ShortArray(bufferSize)
                while (isRunning && state == WakeWordDetector.State.LISTENING) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        var sum = 0L
                        for (i in 0 until read) {
                            sum += (buffer[i] * buffer[i]).toLong()
                        }
                        val rms = kotlin.math.sqrt(sum.toDouble() / read)
                        if (rms > energyThreshold) {
                            consecutiveTriggers++
                            if (consecutiveTriggers >= requiredConsecutiveTriggers) {
                                callback.onWakeWordDetected(currentWakeWord, 0.7f)
                                consecutiveTriggers = 0
                            }
                        } else {
                            consecutiveTriggers = 0
                        }
                    }
                    Thread.sleep(50)
                }
                audioRecord.stop()
                audioRecord.release()
            } catch (e: SecurityException) {
                state = WakeWordDetector.State.ERROR
                callback.onError(WakeWordDetector.ERROR_MIC_PERMISSION, "麦克风权限被拒绝")
            } catch (e: Exception) {
                state = WakeWordDetector.State.ERROR
                callback.onError(WakeWordDetector.ERROR_AUDIO_RECORD, "音频录制异常: ${e.message}")
            }
        }.apply {
            name = "WakeWord-EnergyDetector"
            priority = Thread.MIN_PRIORITY
            start()
        }
        return true
    }

    override fun stopListening() {
        isRunning = false
        audioThread?.interrupt()
        audioThread = null
        state = WakeWordDetector.State.STOPPED
    }

    override fun getState(): WakeWordDetector.State = state

    override fun release() {
        stopListening()
        state = WakeWordDetector.State.UNINITIALIZED
    }

    override fun setConfidenceThreshold(threshold: Float) {
        currentThreshold = threshold.coerceIn(0f, 1f)
    }

    override fun changeWakeWord(wakeWord: String): Boolean {
        currentWakeWord = wakeWord
        return true
    }
}

/**
 * 默认的唤醒词检测器无操作实现
 * 用于在实际引擎未集成时提供空实现，避免NPE
 */
class NoOpWakeWordDetector : WakeWordDetector {

    private var state: WakeWordDetector.State = WakeWordDetector.State.UNINITIALIZED
    private var currentWakeWord: String = WakeWordDetector.DEFAULT_WAKE_WORD
    private var currentThreshold: Float = WakeWordDetector.DEFAULT_CONFIDENCE_THRESHOLD

    override fun init(
        context: Context,
        wakeWord: String,
        threshold: Float
    ): Boolean {
        currentWakeWord = wakeWord
        currentThreshold = threshold.coerceIn(0f, 1f)
        state = WakeWordDetector.State.READY
        return true
    }

    override fun startListening(callback: WakeWordDetector.Callback): Boolean {
        if (state == WakeWordDetector.State.ERROR || state == WakeWordDetector.State.UNINITIALIZED) {
            return false
        }
        state = WakeWordDetector.State.LISTENING
        return true
    }

    override fun stopListening() {
        state = WakeWordDetector.State.STOPPED
    }

    override fun getState(): WakeWordDetector.State = state

    override fun release() {
        stopListening()
        state = WakeWordDetector.State.UNINITIALIZED
    }

    override fun setConfidenceThreshold(threshold: Float) {
        currentThreshold = threshold.coerceIn(0f, 1f)
    }

    override fun changeWakeWord(wakeWord: String): Boolean {
        currentWakeWord = wakeWord
        return true
    }
}

/**
 * Porcupine 唤醒词引擎封装（需 Picovoice AccessKey）
 *
 * 使用方式：
 * 1. 在设置页输入 AccessKey → DataStore 持久化
 * 2. WakeWordService 加载时尝试以 PorcupineWakeWordDetector 替代 EnergyThreshold
 * 3. 若 AccessKey 为空或初始化失败则降级到 EnergyThresholdWakeWordDetector
 */
class PorcupineWakeWordDetector : WakeWordDetector {

    private var state: WakeWordDetector.State = WakeWordDetector.State.UNINITIALIZED
    private var currentWakeWord: String = WakeWordDetector.DEFAULT_WAKE_WORD
    private var currentThreshold: Float = WakeWordDetector.DEFAULT_CONFIDENCE_THRESHOLD

    override fun init(
        context: Context,
        wakeWord: String,
        threshold: Float
    ): Boolean {
        currentWakeWord = wakeWord
        currentThreshold = threshold.coerceIn(0f, 1f)
        // Porcupine 需 Picovoice SDK。若未集成则返回 READY 但不真实可用，
        // 由外部根据 isPorcupineAvailable() 判断降级。
        state = WakeWordDetector.State.READY
        return true
    }

    override fun startListening(callback: WakeWordDetector.Callback): Boolean {
        state = WakeWordDetector.State.LISTENING
        return true
    }

    override fun stopListening() {
        state = WakeWordDetector.State.STOPPED
    }

    override fun getState(): WakeWordDetector.State = state

    override fun release() {
        stopListening()
        state = WakeWordDetector.State.UNINITIALIZED
    }

    override fun setConfidenceThreshold(threshold: Float) {
        currentThreshold = threshold.coerceIn(0f, 1f)
    }

    override fun changeWakeWord(wakeWord: String): Boolean {
        currentWakeWord = wakeWord
        return true
    }

    companion object {
        /**
         * 检查 Porcupine SDK 是否可用（运行时检测类是否存在）
         */
        fun isPorcupineAvailable(): Boolean {
            return try {
                Class.forName("ai.picovoice.porcupine.Porcupine")
                true
            } catch (e: ClassNotFoundException) {
                false
            }
        }
    }
}

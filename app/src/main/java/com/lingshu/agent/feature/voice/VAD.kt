package com.lingshu.agent.feature.voice

import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.log10

/**
 * 语音活动检测（Voice Activity Detection, VAD）
 *
 * 采用简单但实用的实现方案：
 * 1. 基于音量阈值判断是否有语音
 * 2. 使用滑动窗口平滑处理，减少瞬时噪声干扰
 * 3. 静音超时检测（SPEECH_END / SILENCE_TIMEOUT）
 * 4. 支持动态阈值校准，适应不同环境噪声
 *
 * 此实现适合作为嵌入式轻量级VAD使用，
 * 如需更高精度可替换为 WebRTC VAD / Silero VAD 等算法。
 *
 * VAD事件说明：
 * - SPEECH_START     : 检测到语音开始（连续多帧音量超过阈值）
 * - SPEECH_CONTINUE  : 语音持续中（音量持续超过阈值）
 * - SPEECH_END       : 检测到语音结束（短静音后，如800ms无语音）
 * - SILENCE          : 处于静音状态（无SPEECH_CONTINUE时隐含，不单独作为事件）
 * - SILENCE_TIMEOUT  : 静音超时（长静音后，如5s无语音）
 */
class VAD {

    /**
     * VAD检测事件枚举
     */
    enum class VADEvent {
        /** 无事件，处于常规检测中 */
        NO_EVENT,
        /** 检测到语音开始 */
        SPEECH_START,
        /** 正在说话中（持续有语音） */
        SPEECH_CONTINUE,
        /** 检测到语音结束（短静音后） */
        SPEECH_END,
        /** 静音状态（用于状态判断，不对外单独发射） */
        SILENCE,
        /** 静音超时（长时间无语音） */
        SILENCE_TIMEOUT
    }

    /**
     * VAD内部状态
     */
    private enum class InternalState {
        /** 等待语音开始 */
        WAITING_SPEECH,
        /** 语音进行中 */
        IN_SPEECH,
        /** 语音可能结束（短静音中） */
        MAYBE_END,
        /** 结束状态 */
        ENDED
    }

    companion object {
        /** 默认音量阈值（0~10范围，对应归一化后的RMS） */
        const val DEFAULT_VOLUME_THRESHOLD = 3.5f

        /** 默认最小语音持续时间（毫秒）：避免将咳嗽等短时噪声当成语音 */
        const val DEFAULT_MIN_SPEECH_DURATION_MS = 500L

        /** 默认短静音判定阈值（毫秒）：说话中间的正常停顿 */
        const val DEFAULT_SHORT_SILENCE_MS = 800L

        /** 默认长静音/超时阈值（毫秒）：用户说完话或离开。规格书默认3秒 */
        const val DEFAULT_LONG_SILENCE_MS = 3000L

        /** 默认语音帧窗口大小（用于平滑处理，单位：帧数） */
        const val DEFAULT_SMOOTH_WINDOW_SIZE = 5

        /** 分贝基准值（用于PCM转dB计算） */
        private const val DB_REFERENCE = 32768.0
    }

    /** 音量阈值，超过则认为有语音 */
    var volumeThreshold: Float = DEFAULT_VOLUME_THRESHOLD

    /** 最小语音持续时间（毫秒） */
    var minSpeechDurationMs: Long = DEFAULT_MIN_SPEECH_DURATION_MS

    /** 短静音判定时间（毫秒）：超过则认为SPEECH_END */
    var shortSilenceMs: Long = DEFAULT_SHORT_SILENCE_MS

    /** 长静音/超时时间（毫秒）：超过则认为SILENCE_TIMEOUT */
    var longSilenceMs: Long = DEFAULT_LONG_SILENCE_MS

    /** 平滑窗口大小（帧数） */
    var smoothWindowSize: Int = DEFAULT_SMOOTH_WINDOW_SIZE

    /** 当前内部状态 */
    private var internalState: InternalState = InternalState.WAITING_SPEECH

    /** 语音开始的时间戳 */
    private var speechStartTimestamp: Long = 0L

    /** 最后一次检测到语音的时间戳 */
    private var lastVoiceTimestamp: Long = 0L

    /** 平滑窗口内的音量历史记录 */
    private val volumeWindow = ArrayDeque<Float>(smoothWindowSize)

    /** 连续检测到语音的帧数计数 */
    private var consecutiveVoiceFrames: Int = 0

    /** 连续静音帧数计数 */
    private var consecutiveSilenceFrames: Int = 0

    /** 是否处于校准模式（用于动态设置阈值） */
    private var isCalibrating: Boolean = false

    /** 校准期间采集的最小/最大音量 */
    private var calibrateMinVolume: Float = Float.MAX_VALUE
    private var calibrateMaxVolume: Float = Float.MIN_VALUE
    private var calibrateSampleCount: Int = 0

    /** 主线程Handler（用于延迟检查超时） */
    private val handler = Handler(Looper.getMainLooper())

    /** 超时检测Runnable */
    private var timeoutRunnable: Runnable? = null

    /**
     * 重置VAD状态
     * 在每次新的识别会话开始时调用
     */
    fun reset() {
        cancelTimeoutCheck()
        internalState = InternalState.WAITING_SPEECH
        speechStartTimestamp = 0L
        lastVoiceTimestamp = 0L
        volumeWindow.clear()
        consecutiveVoiceFrames = 0
        consecutiveSilenceFrames = 0
    }

    /**
     * 手动标记语音开始
     * 用于从外部（如系统SpeechRecognizer的onBeginningOfSpeech）通知
     */
    fun markSpeechStart() {
        internalState = InternalState.IN_SPEECH
        speechStartTimestamp = System.currentTimeMillis()
        lastVoiceTimestamp = speechStartTimestamp
        consecutiveVoiceFrames = 0
        consecutiveSilenceFrames = 0
        scheduleTimeoutCheck()
    }

    /**
     * 手动标记语音结束
     */
    fun markSpeechEnd() {
        cancelTimeoutCheck()
        internalState = InternalState.ENDED
    }

    /**
     * 开始环境噪声校准
     * 在安静环境下调用，采集一段时间的背景噪声来动态设置阈值
     */
    fun startCalibration() {
        isCalibrating = true
        calibrateMinVolume = Float.MAX_VALUE
        calibrateMaxVolume = Float.MIN_VALUE
        calibrateSampleCount = 0
    }

    /**
     * 结束校准并自动设置阈值
     * @return 自动计算出的建议阈值
     */
    fun stopCalibration(): Float {
        isCalibrating = false
        if (calibrateSampleCount > 0) {
            // 以环境噪声平均值加上一定余量作为阈值
            val avgNoise = (calibrateMinVolume + calibrateMaxVolume) / 2.0f
            volumeThreshold = (avgNoise + 1.5f).coerceAtLeast(DEFAULT_VOLUME_THRESHOLD * 0.5f)
        }
        return volumeThreshold
    }

    /**
     * 处理音频音量，返回VAD事件
     * 这是核心调用方法，每次收到新的音频帧音量都应调用
     *
     * @param volume 当前帧的音量值（0~10范围，建议使用SpeechRecognizerManager中归一化后的值）
     * @return VAD检测事件
     */
    fun processVolume(volume: Float): VADEvent {
        val now = System.currentTimeMillis()

        // 校准模式：采集数据，不做判定
        if (isCalibrating) {
            calibrateMinVolume = minOf(calibrateMinVolume, volume)
            calibrateMaxVolume = maxOf(calibrateMaxVolume, volume)
            calibrateSampleCount++
            return VADEvent.NO_EVENT
        }

        // 使用滑动窗口做平滑，过滤瞬时尖峰噪声
        val smoothedVolume = applySmoothing(volume)
        val hasVoice = smoothedVolume >= volumeThreshold

        // 更新连续计数
        if (hasVoice) {
            consecutiveVoiceFrames++
            consecutiveSilenceFrames = 0
            lastVoiceTimestamp = now
        } else {
            consecutiveSilenceFrames++
            consecutiveVoiceFrames = 0
        }

        // 状态机处理
        return when (internalState) {
            InternalState.WAITING_SPEECH -> {
                // 等待语音开始：需要连续多帧超过阈值才认定为语音开始（抗干扰）
                if (hasVoice && consecutiveVoiceFrames >= 2) {
                    internalState = InternalState.IN_SPEECH
                    speechStartTimestamp = now
                    scheduleTimeoutCheck()
                    VADEvent.SPEECH_START
                } else {
                    VADEvent.SILENCE
                }
            }

            InternalState.IN_SPEECH -> {
                if (hasVoice) {
                    // 继续说话
                    VADEvent.SPEECH_CONTINUE
                } else {
                    // 语音可能结束，进入短静音判定阶段
                    internalState = InternalState.MAYBE_END
                    VADEvent.NO_EVENT
                }
            }

            InternalState.MAYBE_END -> {
                if (hasVoice) {
                    // 用户又开始说话了，回到IN_SPEECH
                    internalState = InternalState.IN_SPEECH
                    VADEvent.SPEECH_CONTINUE
                } else {
                    val silenceDuration = now - lastVoiceTimestamp
                    when {
                        // 长静音超时：认为用户不再说话
                        silenceDuration >= longSilenceMs -> {
                            cancelTimeoutCheck()
                            internalState = InternalState.ENDED
                            VADEvent.SILENCE_TIMEOUT
                        }
                        // 短静音超时：认为用户一句话说完
                        silenceDuration >= shortSilenceMs -> {
                            cancelTimeoutCheck()
                            internalState = InternalState.ENDED
                            VADEvent.SPEECH_END
                        }
                        else -> VADEvent.SILENCE
                    }
                }
            }

            InternalState.ENDED -> {
                // 已结束状态下检测到新的语音（可能是多轮对话场景）
                if (hasVoice && consecutiveVoiceFrames >= 2) {
                    internalState = InternalState.IN_SPEECH
                    speechStartTimestamp = now
                    scheduleTimeoutCheck()
                    VADEvent.SPEECH_START
                } else {
                    VADEvent.SILENCE
                }
            }
        }
    }

    /**
     * 直接处理PCM原始16位音频数据
     * 方便在使用AudioRecord录制音频时直接调用
     *
     * @param pcmData PCM短整型数组
     * @param length 有效数据长度
     * @return VAD检测事件
     */
    fun processPcmData(pcmData: ShortArray, length: Int): VADEvent {
        val volume = calculateVolumeFromPcm(pcmData, length)
        return processVolume(volume)
    }

    /**
     * 检查当前是否处于说话状态
     */
    fun isInSpeech(): Boolean {
        return internalState == InternalState.IN_SPEECH ||
                internalState == InternalState.MAYBE_END
    }

    /**
     * 获取当前语音持续时长（毫秒）
     */
    fun getSpeechDurationMs(): Long {
        if (speechStartTimestamp == 0L) return 0L
        return if (isInSpeech()) {
            System.currentTimeMillis() - speechStartTimestamp
        } else {
            lastVoiceTimestamp - speechStartTimestamp
        }
    }

    /**
     * 获取当前静音时长（毫秒），如果正处于说话中则返回0
     */
    fun getSilenceDurationMs(): Long {
        if (isInSpeech() || internalState == InternalState.WAITING_SPEECH) {
            return 0L
        }
        return System.currentTimeMillis() - lastVoiceTimestamp
    }

    /**
     * 获取当前平滑后的音量
     */
    fun getCurrentSmoothedVolume(): Float {
        return if (volumeWindow.isNotEmpty()) {
            volumeWindow.average().toFloat()
        } else {
            0f
        }
    }

    /**
     * 滑动窗口平滑处理
     */
    private fun applySmoothing(newVolume: Float): Float {
        volumeWindow.addLast(newVolume)
        if (volumeWindow.size > smoothWindowSize) {
            volumeWindow.removeFirst()
        }
        // 返回窗口平均值作为平滑后的值
        return if (volumeWindow.isNotEmpty()) {
            volumeWindow.average().toFloat()
        } else {
            newVolume
        }
    }

    /**
     * 从PCM数据计算归一化音量值（0~10）
     */
    private fun calculateVolumeFromPcm(buffer: ShortArray, length: Int): Float {
        if (length <= 0) return 0f

        var sum = 0.0
        for (i in 0 until length) {
            sum += kotlin.math.abs(buffer[i].toInt()).toDouble()
        }
        val avgAmplitude = sum / length
        if (avgAmplitude < 1) return 0f

        // 转换为分贝
        val db = 20.0 * log10(avgAmplitude / DB_REFERENCE + 1e-10)
        // 归一化到 0~10 范围：假设静音约-100dB，最大音量约0dB
        return ((db + 100.0) / 10.0).toFloat().coerceIn(0f, 10f)
    }

    /**
     * 安排长静音超时检查
     */
    private fun scheduleTimeoutCheck() {
        cancelTimeoutCheck()
        timeoutRunnable = Runnable {
            // 达到长静音时间，触发超时
            if (internalState == InternalState.WAITING_SPEECH ||
                internalState == InternalState.MAYBE_END ||
                internalState == InternalState.IN_SPEECH
            ) {
                internalState = InternalState.ENDED
            }
        }
        handler.postDelayed(timeoutRunnable!!, longSilenceMs)
    }

    /**
     * 取消超时检查
     */
    private fun cancelTimeoutCheck() {
        timeoutRunnable?.let {
            handler.removeCallbacks(it)
            timeoutRunnable = null
        }
    }
}

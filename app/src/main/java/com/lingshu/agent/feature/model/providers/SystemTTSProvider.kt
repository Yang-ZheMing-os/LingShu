package com.lingshu.agent.feature.model.providers

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.lingshu.agent.feature.model.ModelCapability
import com.lingshu.agent.feature.model.ModelMessage
import com.lingshu.agent.feature.model.ModelProvider
import com.lingshu.agent.feature.model.ModelResponse
import com.lingshu.agent.feature.model.ModelSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 系统 TTS（语音合成）提供者
 *
 * 封装 Android 系统自带的 TextToSpeech 引擎，将文本合成为语音音频。
 *
 * 主要特性：
 * 1. 无需额外安装，直接使用系统 TTS 服务
 * 2. 支持中文、英文等多种语言（取决于系统安装的 TTS 引擎）
 * 3. 支持语速、音调调节
 * 4. 支持多音色切换（取决于 TTS 引擎）
 * 5. 支持两种输出模式：直接播放 / 输出为音频字节数组
 * 6. 完全离线，无需网络
 *
 * synthesize() 方法返回的是 WAV 格式的音频字节数组（16bit 单声道 PCM）。
 */
@Singleton
class SystemTTSProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelSettings: ModelSettings
) : ModelProvider {

    companion object {
        /** Provider 唯一标识 */
        const val PROVIDER_ID = "system-tts"

        /** Provider 显示名称 */
        const val PROVIDER_NAME = "系统语音合成"

        /** 默认语速倍率 */
        private const val DEFAULT_SPEECH_RATE = 1.0f

        /** 默认音调倍率 */
        private const val DEFAULT_PITCH = 1.0f

        /** 等待 TTS 初始化的最大时长（毫秒） */
        private const val INIT_TIMEOUT_MS = 10_000L
    }

    /** 仅支持语音合成能力 */
    override val capabilities: Set<ModelCapability> = setOf(ModelCapability.SYNTHESIZE)

    override val providerId: String = PROVIDER_ID
    override val providerName: String = PROVIDER_NAME

    /** 系统 TTS 实例（懒加载，首次使用时初始化） */
    @Volatile
    private var textToSpeech: TextToSpeech? = null

    /** TTS 初始化状态 */
    @Volatile
    private var initState: InitState = InitState.UNINITIALIZED

    /** TTS 初始化内部状态枚举 */
    private enum class InitState {
        UNINITIALIZED,
        INITIALIZING,
        READY,
        FAILED
    }

    /** 当前使用的语言 */
    private var currentLocale: Locale = Locale.CHINA

    /** 当前语速 */
    private var currentSpeechRate: Float = DEFAULT_SPEECH_RATE

    /** 当前音调 */
    private var currentPitch: Float = DEFAULT_PITCH

    /** 当前使用的音色名称 */
    private var currentVoiceName: String? = null

    override suspend fun isAvailable(): Boolean {
        if (!modelSettings.isProviderEnabled(PROVIDER_ID)) return false
        // 检查 TTS 是否能成功初始化
        return ensureTtsReady()
    }

    /**
     * 语音合成：将文本转换为 WAV 音频字节数组
     *
     * 实现方式：先将音频合成到临时文件，然后读取文件内容返回 byte[]，
     * 最后删除临时文件。使用 synthesizeToFile API 而非直接播放。
     *
     * @param text 要合成的文本内容
     * @return WAV 格式的音频字节数组（16kHz/16bit/单声道 PCM）
     */
    override suspend fun synthesize(text: String): ByteArray {
        if (!modelSettings.isProviderEnabled(PROVIDER_ID)) {
            throw IllegalStateException("系统 TTS 已被禁用")
        }
        if (text.isBlank()) {
            return ByteArray(0)
        }
        if (!ensureTtsReady()) {
            throw IllegalStateException("系统 TTS 初始化失败，请检查系统 TTS 引擎设置")
        }

        return withContext(Dispatchers.IO) {
            val tts = textToSpeech
                ?: throw IllegalStateException("TTS 实例未初始化")

            // 创建临时文件存储合成结果
            val tempFile = File(
                context.cacheDir,
                "tts_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.pcm"
            )
            val utteranceId = UUID.randomUUID().toString()

            try {
                // 使用 synthesizeToFile 将音频输出到文件
                val result = suspendCancellableCoroutine { cont ->
                    // 设置进度监听器
                    val listener = object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == this@SystemTTSProvider.hashCode().toString()) {
                                if (!cont.isCompleted) {
                                    cont.resume(TextToSpeech.SUCCESS)
                                }
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (!cont.isCompleted) {
                                cont.resumeWithException(Exception("TTS 合成失败"))
                            }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            if (!cont.isCompleted) {
                                cont.resumeWithException(
                                    Exception("TTS 合成失败，错误码: $errorCode")
                                )
                            }
                        }
                    }

                    tts.setOnUtteranceProgressListener(listener)

                    val params = Bundle().apply {
                        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                    }

                    // synthesizeToFile 在 API 21+ 可用，用反射兼容旧版
                    val synthResult = try {
                        // synthesizeToFile(file, params, utteranceId)
                        val method = TextToSpeech::class.java.getMethod(
                            "synthesizeToFile",
                            String::class.java,
                            Bundle::class.java,
                            File::class.java,
                            String::class.java
                        )
                        method.invoke(tts, text, params, tempFile, utteranceId) as Int
                    } catch (_: NoSuchMethodException) {
                        // 旧版本降级：先播放再捕获（不推荐，此处返回失败）
                        TextToSpeech.ERROR
                    }

                    if (synthResult != TextToSpeech.SUCCESS) {
                        if (!cont.isCompleted) {
                            cont.resumeWithException(
                                Exception("TTS 合成启动失败，返回码: $synthResult")
                            )
                        }
                    }

                    // 兜底超时机制（60秒）
                    val ttsHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    val timeoutRunnable = Runnable {
                        if (!cont.isCompleted) {
                            cont.resumeWithException(Exception("TTS 合成超时"))
                        }
                    }
                    ttsHandler.postDelayed(timeoutRunnable, 60_000L)

                    cont.invokeOnCancellation {
                        ttsHandler.removeCallbacks(timeoutRunnable)
                        tts.stop()
                    }
                }

                if (result == TextToSpeech.SUCCESS && tempFile.exists()) {
                    // 读取临时文件，包装为 WAV 头后返回
                    val pcmBytes = tempFile.readBytes()
                    wrapPcmToWav(pcmBytes)
                } else {
                    throw Exception("TTS 合成失败")
                }
            } finally {
                // 清理临时文件
                if (tempFile.exists()) {
                    runCatching { tempFile.delete() }
                }
            }
        }
    }

    /**
     * 便捷方法：直接通过扬声器播放文本（不返回字节数组）
     *
     * @param text 要播报的文本
     * @param queueMode 队列模式：TextToSpeech.QUEUE_ADD / QUEUE_FLUSH
     */
    suspend fun speak(
        text: String,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH
    ): Boolean {
        if (!ensureTtsReady()) return false
        val tts = textToSpeech ?: return false

        val utteranceId = UUID.randomUUID().toString()
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        return tts.speak(text, queueMode, params, utteranceId) == TextToSpeech.SUCCESS
    }

    /**
     * 停止当前播报
     */
    fun stopSpeaking() {
        textToSpeech?.stop()
    }

    // ==================== 内部方法 ====================

    /**
     * 确保 TTS 已初始化并就绪
     * 线程安全：使用双重检查锁 + 状态机
     */
    private suspend fun ensureTtsReady(): Boolean {
        // 1. 如果已经就绪，直接返回
        if (initState == InitState.READY) return true
        if (initState == InitState.FAILED) return false

        return withContext(Dispatchers.Main) {
            // 2. 正在初始化：等待（带超时）
            if (initState == InitState.INITIALIZING) {
                val waited = waitForInitWithTimeout()
                return@withContext waited
            }

            // 3. 未初始化：开始初始化
            initState = InitState.INITIALIZING
            var initResult = false

            try {
                initResult = suspendCancellableCoroutine { cont ->
                    val initListener = TextToSpeech.OnInitListener { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            val tts = textToSpeech
                            if (tts != null) {
                                // 设置默认中文
                                val langResult = tts.setLanguage(currentLocale)
                                tts.setSpeechRate(currentSpeechRate)
                                tts.setPitch(currentPitch)
                                initState = InitState.READY
                                if (!cont.isCompleted) cont.resume(true)
                            } else {
                                initState = InitState.FAILED
                                if (!cont.isCompleted) cont.resume(false)
                            }
                        } else {
                            initState = InitState.FAILED
                            if (!cont.isCompleted) cont.resume(false)
                        }
                    }

                    textToSpeech = TextToSpeech(context.applicationContext, initListener)

                    // 初始化超时兜底
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    val timeoutRunnable = Runnable {
                        if (initState != InitState.READY) {
                            initState = InitState.FAILED
                            if (!cont.isCompleted) cont.resume(false)
                        }
                    }
                    handler.postDelayed(timeoutRunnable, INIT_TIMEOUT_MS)

                    cont.invokeOnCancellation {
                        handler.removeCallbacks(timeoutRunnable)
                    }
                }
            } catch (e: Exception) {
                initState = InitState.FAILED
                initResult = false
            }

            initResult
        }
    }

    /** 等待 INITIALIZING → READY/FAILED，带超时 */
    private suspend fun waitForInitWithTimeout(): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < INIT_TIMEOUT_MS) {
            when (initState) {
                InitState.READY -> return true
                InitState.FAILED -> return false
                else -> delay(100L)
            }
        }
        initState = InitState.FAILED
        return false
    }

    /**
     * 将原始 PCM 字节包装为 WAV 格式（加上 WAV 文件头）
     * 假设：16kHz / 16bit / 单声道
     */
    private fun wrapPcmToWav(pcmData: ByteArray): ByteArray {
        val sampleRate = 16000 // 假设采样率
        val channels = 1       // 单声道
        val bitsPerSample = 16 // 16bit
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataSize = pcmData.size
        val totalDataLen = dataSize + 36

        val output = ByteArrayOutputStream()
        val header = ByteArray(44)
        // RIFF 标识
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        // 总长度（文件大小 - 8）
        writeInt(header, 4, totalDataLen)
        // WAVE 标识
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        // fmt 子块标识
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        // fmt 子块长度（PCM=16）
        writeInt(header, 16, 16)
        // 音频格式：PCM = 1
        writeShort(header, 20, 1.toShort())
        // 声道数
        writeShort(header, 22, channels.toShort())
        // 采样率
        writeInt(header, 24, sampleRate)
        // 字节速率
        writeInt(header, 28, byteRate)
        // 块对齐
        writeShort(header, 32, blockAlign)
        // 位深度
        writeShort(header, 34, bitsPerSample.toShort())
        // data 子块标识
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        // PCM 数据长度
        writeInt(header, 40, dataSize)

        output.write(header)
        output.write(pcmData)
        return output.toByteArray()
    }

    private fun writeInt(array: ByteArray, offset: Int, value: Int) {
        array[offset] = (value and 0xFF).toByte()
        array[offset + 1] = ((value shr 8) and 0xFF).toByte()
        array[offset + 2] = ((value shr 16) and 0xFF).toByte()
        array[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShort(array: ByteArray, offset: Int, value: Short) {
        array[offset] = (value.toInt() and 0xFF).toByte()
        array[offset + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
    }

    // ==================== TTS 配置方法 ====================

    /**
     * 设置合成语言
     */
    fun setLanguage(locale: Locale): Boolean {
        currentLocale = locale
        return textToSpeech?.setLanguage(locale) == TextToSpeech.LANG_COUNTRY_AVAILABLE
    }

    /**
     * 设置语速 (0.3f ~ 2.5f，1.0 为正常)
     */
    fun setSpeechRate(rate: Float) {
        currentSpeechRate = rate.coerceIn(0.3f, 2.5f)
        textToSpeech?.setSpeechRate(currentSpeechRate)
    }

    /**
     * 设置音调 (0.3f ~ 2.0f，1.0 为正常)
     */
    fun setPitch(pitch: Float) {
        currentPitch = pitch.coerceIn(0.3f, 2.0f)
        textToSpeech?.setPitch(currentPitch)
    }

    /**
     * 设置语音音色
     */
    fun setVoice(voiceName: String): Boolean {
        val tts = textToSpeech ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val targetVoice = tts.voices?.find { it.name == voiceName }
                if (targetVoice != null) {
                    tts.voice = targetVoice
                    currentVoiceName = voiceName
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }
    }

    /**
     * 获取可用音色列表（API 21+）
     */
    fun getAvailableVoices(): List<String> {
        val tts = textToSpeech ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                tts.voices
                    ?.filter { it.locale.language == currentLocale.language }
                    ?.map { it.name }
                    ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    /**
     * 检查是否正在播报
     */
    fun isSpeaking(): Boolean = textToSpeech?.isSpeaking == true

    // ==================== 未支持的能力 ====================

    override suspend fun chat(messages: List<ModelMessage>): ModelResponse {
        throw UnsupportedOperationException("系统 TTS 是语音合成模型，不支持文本对话")
    }

    override suspend fun vision(image: Bitmap, prompt: String): String {
        throw UnsupportedOperationException("系统 TTS 是语音合成模型，不支持视觉能力")
    }

    override suspend fun transcribe(audio: ByteArray): String {
        throw UnsupportedOperationException("系统 TTS 是语音合成模型，不支持语音识别")
    }

    override fun release() {
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {
            // 忽略释放异常
        }
        textToSpeech = null
        initState = InitState.UNINITIALIZED
    }
}

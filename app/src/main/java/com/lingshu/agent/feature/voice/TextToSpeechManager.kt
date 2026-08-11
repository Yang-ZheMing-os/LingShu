package com.lingshu.agent.feature.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import com.lingshu.agent.core.database.dao.ClonedVoiceDao

/**
 * 语音合成（TTS）管理器
 *
 * 功能特性：
 * 1. 默认使用系统 TextToSpeech 引擎
 * 2. 可插拔的TTS引擎接口，方便后续接入第三方引擎（如百度、讯飞、阿里云等）
 * 3. 支持音色切换、语速调节、音调调节
 * 4. 支持立即打断当前播报，立即播放新内容
 * 5. 支持音频焦点管理，避免与其他音频应用冲突
 * 6. 支持播报进度回调（开始、完成、错误）
 */
class TextToSpeechManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clonedVoiceDao: ClonedVoiceDao? = null
) {

    /**
     * 可插拔TTS引擎接口
     * 用于扩展第三方TTS引擎时实现此接口
     */
    interface TtsEngine {
        /**
         * 初始化引擎
         * @param context Android上下文
         * @return 是否初始化成功
         */
        fun init(context: Context): Boolean

        /**
         * 播报文本
         * @param text 要播报的文本内容
         * @param utteranceId 播报ID，用于区分不同的播报任务
         * @param queueMode 队列模式
         */
        fun speak(text: String, utteranceId: String, queueMode: Int)

        /**
         * 停止播报
         */
        fun stop()

        /**
         * 设置语速
         * @param rate 语速倍率 (0.5f ~ 2.0f，1.0为正常)
         */
        fun setSpeechRate(rate: Float)

        /**
         * 设置音调
         * @param pitch 音调倍率 (0.5f ~ 2.0f，1.0为正常)
         */
        fun setPitch(pitch: Float)

        /**
         * 设置语言
         * @param locale 语言区域
         * @return 语言支持级别
         */
        fun setLanguage(locale: Locale): Int

        /**
         * 设置指定的语音（音色）
         * @param voiceName 音色名称
         * @return 是否设置成功
         */
        fun setVoice(voiceName: String): Boolean

        /**
         * 获取可用音色列表
         * @return 可用音色名称列表
         */
        fun getAvailableVoices(): List<String>

        /**
         * 检查是否正在播报
         * @return 是否播报中
         */
        fun isSpeaking(): Boolean

        /**
         * 释放资源
         */
        fun release()
    }

    /**
     * 播报进度回调接口
     */
    interface SpeakCallback {
        /**
         * 播报开始
         * @param utteranceId 播报ID
         */
        fun onStart(utteranceId: String)

        /**
         * 播报完成
         * @param utteranceId 播报ID
         */
        fun onDone(utteranceId: String)

        /**
         * 播报被打断
         * @param utteranceId 被打断的播报ID
         */
        fun onInterrupted(utteranceId: String)

        /**
         * 播报出错
         * @param utteranceId 播报ID
         * @param errorCode 错误码
         * @param errorMessage 错误信息
         */
        fun onError(utteranceId: String, errorCode: Int, errorMessage: String)

        /**
         * 播报范围回调（用于高亮显示当前播报文字）
         * @param utteranceId 播报ID
         * @param start 起始字符位置
         * @param end 结束字符位置
         */
        fun onRange(utteranceId: String, start: Int, end: Int)
    }

    /**
     * 播报队列模式
     */
    enum class QueueMode(val value: Int) {
        /** 立即打断当前播报，播放新内容 */
        INTERRUPT(TextToSpeech.QUEUE_FLUSH),
        /** 加入队列，等待当前播报完成后播放 */
        APPEND(TextToSpeech.QUEUE_ADD)
    }

    /**
     * TTS状态枚举
     */
    enum class TtsState {
        /** 未初始化 */
        UNINITIALIZED,
        /** 初始化中 */
        INITIALIZING,
        /** 就绪 */
        READY,
        /** 播报中 */
        SPEAKING,
        /** 已暂停 */
        PAUSED,
        /** 错误 */
        ERROR
    }

    companion object {
        /** 默认语速 */
        const val DEFAULT_SPEECH_RATE = 1.0f

        /** 默认音调 */
        const val DEFAULT_PITCH = 1.0f

        /** 最小语速倍率 */
        const val MIN_SPEECH_RATE = 0.3f

        /** 最大语速倍率 */
        const val MAX_SPEECH_RATE = 2.5f

        /** 最小音调倍率 */
        const val MIN_PITCH = 0.3f

        /** 最大音调倍率 */
        const val MAX_PITCH = 2.0f

        /** 错误码：TTS未初始化 */
        const val ERROR_NOT_INITIALIZED = 2001

        /** 错误码：不支持的语言 */
        const val ERROR_LANG_NOT_SUPPORTED = 2002

        /** 错误码：播报失败 */
        const val ERROR_SPEAK_FAILED = 2003

        /** 错误码：音频焦点丢失 */
        const val ERROR_AUDIO_FOCUS = 2004
    }

    /** 当前使用的TTS引擎实现 */
    private var ttsEngine: TtsEngine? = null

    /** 系统默认TTS实现 */
    private var systemTts: TextToSpeech? = null

    /** 当前状态 */
    private var state: TtsState = TtsState.UNINITIALIZED

    /** 播报回调 */
    private var speakCallback: SpeakCallback? = null

    /** 音频管理器 */
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /** 音频焦点请求（Android O及以上） */
    private var audioFocusRequest: AudioFocusRequest? = null

    /** 音频焦点变化监听器 */
    private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null

    /** 主线程Handler，用于回调到主线程 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前语速 */
    private var currentSpeechRate: Float = DEFAULT_SPEECH_RATE

    /** 当前音调 */
    private var currentPitch: Float = DEFAULT_PITCH

    /** 当前语言 */
    private var currentLocale: Locale = Locale.CHINA

    /** 当前播报的utteranceId */
    private var currentUtteranceId: String? = null

    /** 待播报队列 */
    private val pendingQueue = mutableListOf<Pair<String, String>>()

    /** 是否申请了音频焦点 */
    private var hasAudioFocus: Boolean = false

    /** 当前使用的音色名称 */
    private var currentVoiceName: String? = null

    /** 声音克隆提供者 */
    private var voiceCloneProvider: VoiceCloneProvider? = null

    /** 设置声音克隆提供者 */
    fun setVoiceCloneProvider(provider: VoiceCloneProvider?) {
        this.voiceCloneProvider = provider
    }

    /**
     * 初始化TTS管理器，默认使用系统TTS引擎
     * @param onInitCompleted 初始化完成回调，参数为是否成功
     */
    fun initialize(onInitCompleted: ((Boolean) -> Unit)? = null) {
        state = TtsState.INITIALIZING

        // 初始化系统TTS
        systemTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // 包装系统TTS为统一接口
                ttsEngine = SystemTtsEngineAdapter(systemTts!!)

                // 设置默认语言为中文
                val langResult = ttsEngine?.setLanguage(Locale.CHINA)
                val initSuccess = (langResult == TextToSpeech.LANG_AVAILABLE ||
                        langResult == TextToSpeech.LANG_COUNTRY_AVAILABLE)

                // 设置默认参数
                ttsEngine?.setSpeechRate(currentSpeechRate)
                ttsEngine?.setPitch(currentPitch)

                // 设置播报监听器
                setupUtteranceListener()

                state = if (initSuccess) TtsState.READY else TtsState.ERROR
                mainHandler.post { onInitCompleted?.invoke(initSuccess) }
            } else {
                state = TtsState.ERROR
                mainHandler.post { onInitCompleted?.invoke(false) }
            }
        }
    }

    /**
     * 设置自定义TTS引擎
     * @param engine 自定义引擎实现，传null则恢复使用系统TTS
     */
    fun setCustomEngine(engine: TtsEngine?) {
        if (engine != null) {
            // 释放旧引擎
            ttsEngine?.release()
            ttsEngine = engine
            if (engine.init(context)) {
                engine.setSpeechRate(currentSpeechRate)
                engine.setPitch(currentPitch)
                engine.setLanguage(currentLocale)
                currentVoiceName?.let { engine.setVoice(it) }
                state = TtsState.READY
            } else {
                state = TtsState.ERROR
            }
        } else {
            // 恢复系统引擎（需重新初始化
            release()
            initialize()
        }
    }

    /**
     * 播报文本
     * @param text 要播报的文本
     * @param queueMode 队列模式，默认立即打断
     * @param callback 播报回调
     * @return 此次播报的utteranceId
     */
    fun speak(
        text: String,
        queueMode: QueueMode = QueueMode.INTERRUPT,
        callback: SpeakCallback? = null
    ): String {
        // 生成唯一的utteranceId
        val utteranceId = UUID.randomUUID().toString()

        if (state == TtsState.UNINITIALIZED || state == TtsState.INITIALIZING) {
            mainHandler.post {
                callback?.onError(
                    utteranceId,
                    ERROR_NOT_INITIALIZED,
                    "TTS引擎未初始化"
                )
            }
            return utteranceId
        }

        if (text.isBlank()) {
            return utteranceId
        }

        // 设置回调
        if (callback != null) {
            this.speakCallback = callback
        }

        // 申请音频焦点
        val focusGranted = requestAudioFocus()
        if (!focusGranted) {
            // 即使没获取到焦点也尝试播报，但可能被其他音频冲突
        }

        // 如果是打断模式，先清理队列
        if (queueMode == QueueMode.INTERRUPT) {
            pendingQueue.clear()
            ttsEngine?.stop()
        }

        // 如果当前正忙，加入队列
        if (ttsEngine?.isSpeaking() == true && queueMode == QueueMode.APPEND) {
            pendingQueue.add(Pair(text, utteranceId))
            return utteranceId
        }

        currentUtteranceId = utteranceId
        state = TtsState.SPEAKING

        // 优先使用克隆声音
        val provider = voiceCloneProvider
        if (provider != null && provider.isAvailable()) {
            val activeVoice = clonedVoiceDao?.getActiveBlocking()
            if (activeVoice != null) {
                // 异步合成克隆声音
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        val audioPath = provider.synthesize(activeVoice.id, text)
                        if (!audioPath.isNullOrBlank()) {
                            // 使用 MediaPlayer 播放合成音频
                            val mediaPlayer = MediaPlayer().apply {
                                setDataSource(audioPath)
                                setOnCompletionListener {
                                    it.release()
                                    state = TtsState.READY
                                    mainHandler.post {
                                        speakCallback?.onDone(utteranceId)
                                    }
                                    abandonAudioFocus()
                                    processNextInQueue()
                                }
                                setOnErrorListener { mp, _, _ ->
                                    mp.release()
                                    // 降级到系统 TTS
                                    mainHandler.post {
                                        fallbackToSystemTts(text, utteranceId)
                                    }
                                    true
                                }
                                prepare()
                                start()
                            }
                        } else {
                            // 合成失败，降级到系统 TTS
                            mainHandler.post {
                                fallbackToSystemTts(text, utteranceId)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("TextToSpeechManager", "克隆声音合成失败", e)
                        mainHandler.post {
                            fallbackToSystemTts(text, utteranceId)
                        }
                    }
                }
                return utteranceId
            }
        }

        ttsEngine?.speak(text, utteranceId, queueMode.value)
        return utteranceId
    }

    /**
     * 立即停止播报（打断当前播报）
     */
    fun stop() {
        val interruptedId = currentUtteranceId
        ttsEngine?.stop()
        pendingQueue.clear()
        if (interruptedId != null) {
            mainHandler.post { speakCallback?.onInterrupted(interruptedId) }
        }
        currentUtteranceId = null
        state = TtsState.READY
        abandonAudioFocus()
    }

    /**
     * 设置语速
     * @param rate 语速倍率，范围0.3f ~ 2.5f
     */
    fun setSpeechRate(rate: Float) {
        currentSpeechRate = rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
        ttsEngine?.setSpeechRate(currentSpeechRate)
    }

    /**
     * 获取当前语速
     */
    fun getSpeechRate(): Float = currentSpeechRate

    /**
     * 设置音调
     * @param pitch 音调倍率，范围0.3f ~ 2.0f
     */
    fun setPitch(pitch: Float) {
        currentPitch = pitch.coerceIn(MIN_PITCH, MAX_PITCH)
        ttsEngine?.setPitch(currentPitch)
    }

    /**
     * 获取当前音调
     */
    fun getPitch(): Float = currentPitch

    /**
     * 设置语言
     * @param locale 语言区域
     * @return 是否设置成功
     */
    fun setLanguage(locale: Locale): Boolean {
        val result = ttsEngine?.setLanguage(locale)
        currentLocale = locale
        return result == TextToSpeech.LANG_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_AVAILABLE
    }

    /**
     * 获取当前语言
     */
    fun getLanguage(): Locale = currentLocale

    /**
     * 设置音色
     * @param voiceName 音色名称（通过getAvailableVoices()获取）
     * @return 是否设置成功
     */
    fun setVoice(voiceName: String): Boolean {
        val success = ttsEngine?.setVoice(voiceName) ?: false
        if (success) {
            currentVoiceName = voiceName
        }
        return success
    }

    /**
     * 获取当前使用的音色名称
     */
    fun getCurrentVoice(): String? = currentVoiceName

    /**
     * 获取可用音色列表
     */
    fun getAvailableVoices(): List<String> {
        return ttsEngine?.getAvailableVoices() ?: emptyList()
    }

    /**
     * 检查是否正在播报
     */
    fun isSpeaking(): Boolean = ttsEngine?.isSpeaking() ?: false

    /**
     * 获取当前状态
     */
    fun getState(): TtsState = state

    /**
     * 释放资源
     */
    fun release() {
        stop()
        ttsEngine?.release()
        ttsEngine = null
        systemTts?.shutdown()
        systemTts = null
        abandonAudioFocus()
        state = TtsState.UNINITIALIZED
    }

    /**
     * 配置系统TTS的播报进度监听
     */
    private fun setupUtteranceListener() {
        systemTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId ?: return
                mainHandler.post { speakCallback?.onStart(utteranceId) }
            }

            override fun onDone(utteranceId: String?) {
                utteranceId ?: return
                mainHandler.post {
                    speakCallback?.onDone(utteranceId)
                    // 播报完成，处理下一条队列
                    currentUtteranceId = null
                    processNextInQueue()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId ?: return
                mainHandler.post {
                    speakCallback?.onError(
                        utteranceId,
                        ERROR_SPEAK_FAILED,
                        "系统TTS播报失败"
                    )
                    currentUtteranceId = null
                    processNextInQueue()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId ?: return
                val msg = when (errorCode) {
                    TextToSpeech.ERROR_INVALID_REQUEST -> "无效的播报请求"
                    TextToSpeech.ERROR_NETWORK -> "网络错误"
                    TextToSpeech.ERROR_NETWORK_TIMEOUT -> "网络超时"
                    TextToSpeech.ERROR_NOT_INSTALLED_YET -> "语音数据未下载"
                    TextToSpeech.ERROR_OUTPUT -> "输出错误"
                    TextToSpeech.ERROR_SERVICE -> "TTS服务错误"
                    TextToSpeech.ERROR_SYNTHESIS -> "语音合成失败"
                    else -> "未知错误($errorCode)"
                }
                mainHandler.post {
                    speakCallback?.onError(utteranceId, ERROR_SPEAK_FAILED, msg)
                    currentUtteranceId = null
                    processNextInQueue()
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                utteranceId ?: return
                mainHandler.post { speakCallback?.onRange(utteranceId, start, end) }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId ?: return
                if (interrupted) {
                    mainHandler.post { speakCallback?.onInterrupted(utteranceId) }
                }
            }
        })
    }

    /**
     * 处理队列中的下一条播报
     */
    private fun processNextInQueue() {
        if (pendingQueue.isNotEmpty()) {
            val (text, id) = pendingQueue.removeAt(0)
            currentUtteranceId = id
            state = TtsState.SPEAKING
            ttsEngine?.speak(text, id, TextToSpeech.QUEUE_FLUSH)
        } else {
            state = TtsState.READY
            abandonAudioFocus()
        }
    }

    /**
     * 申请音频焦点
     */
    @Suppress("DEPRECATION")
    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true

        audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // 重新获得焦点，恢复播报音量
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // 永久失去焦点，停止播报
                    stop()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // 短暂失去焦点，暂停播报
                    ttsEngine?.stop()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // 短暂失去焦点但可降低音量，这里选择停止
                }
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener!!, mainHandler)
                .build()
                .also { request ->
                    val result = audioManager.requestAudioFocus(request)
                    hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }
            hasAudioFocus
        } else {
            val result = audioManager.requestAudioFocus(
                audioFocusChangeListener!!,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            hasAudioFocus
        }
    }

    /**
     * 释放音频焦点
     */
    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        audioFocusChangeListener ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            audioManager.abandonAudioFocus(audioFocusChangeListener!!)
        }
        hasAudioFocus = false
    }

    /**
     * 降级到系统 TTS 播报（克隆声音不可用时）
     */
    private fun fallbackToSystemTts(text: String, utteranceId: String) {
        ttsEngine?.speak(text, utteranceId, QueueMode.INTERRUPT.value)
    }

    /**
     * 系统TTS引擎的适配器实现
     * 将Android系统TextToSpeech包装为统一的TtsEngine接口
     */
    private inner class SystemTtsEngineAdapter(
        private val tts: TextToSpeech
    ) : TtsEngine {

        override fun init(context: Context): Boolean {
            // 系统TTS在构造时已初始化
            return true
        }

        override fun speak(text: String, utteranceId: String, queueMode: Int) {
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f)
            }
            tts.speak(text, queueMode, params, utteranceId)
        }

        override fun stop() {
            tts.stop()
        }

        override fun setSpeechRate(rate: Float) {
            tts.setSpeechRate(rate)
        }

        override fun setPitch(pitch: Float) {
            tts.setPitch(pitch)
        }

        override fun setLanguage(locale: Locale): Int {
            return tts.setLanguage(locale)
        }

        override fun setVoice(voiceName: String): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val voices = tts.voices
                    val targetVoice = voices?.find { it.name == voiceName }
                    if (targetVoice != null) {
                        tts.voice = targetVoice
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
        }

        override fun getAvailableVoices(): List<String> {
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

        override fun isSpeaking(): Boolean = tts.isSpeaking

        override fun release() {
            // 由外部管理系统TTS的生命周期
        }
    }
}

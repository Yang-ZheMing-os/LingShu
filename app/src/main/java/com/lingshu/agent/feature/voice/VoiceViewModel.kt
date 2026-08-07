package com.lingshu.agent.feature.voice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.lingshu.agent.services.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Voice 模块的 ViewModel
 *
 * 职责：
 * 1. 持有并管理 VoiceSession 生命周期
 * 2. 以 LiveData 形式暴露 UI 可观察的状态（会话状态、用户文本、AI回复、音量等）
 * 3. 暴露简洁的 UI 操作方法：启动语音、停止语音、发送消息、设置参数等
 * 4. 管理与 WakeWordService 的绑定/解绑
 * 5. 实现 VoiceSession.AiReplyProvider，对接实际的 AI 业务接口（此处为占位实现）
 */
class VoiceViewModel(application: Application) :
    AndroidViewModel(application),
    VoiceSession.AiReplyProvider
{

    // ============================================================
    //  内部状态（MutableLiveData）
    // ============================================================

    private val _sessionState = MutableLiveData<VoiceSession.SessionState>(
        VoiceSession.SessionState.ENDED
    )
    private val _userPartialText = MutableLiveData<String>("")
    private val _userFinalText = MutableLiveData<String>("")
    private val _aiReplyText = MutableLiveData<String>("")
    private val _userVolume = MutableLiveData<Float>(0f)
    private val _wakeWord = MutableLiveData<Pair<String, Float>?>(null)
    private val _isListening = MutableLiveData<Boolean>(false)
    private val _isSpeaking = MutableLiveData<Boolean>(false)
    private val _isThinking = MutableLiveData<Boolean>(false)
    private val _errorMessage = MutableLiveData<String?>(null)
    private val _speechRate = MutableLiveData(TextToSpeechManager.DEFAULT_SPEECH_RATE)
    private val _pitch = MutableLiveData(TextToSpeechManager.DEFAULT_PITCH)
    private val _currentEngine = MutableLiveData(SpeechRecognizerManager.EngineType.NONE)
    private val _availableVoices = MutableLiveData<List<String>>(emptyList())
    private val _currentVoice = MutableLiveData<String?>(null)

    // ============================================================
    //  对外暴露的不可变 LiveData
    // ============================================================

    /** 会话状态 */
    val sessionState: LiveData<VoiceSession.SessionState> = _sessionState

    /** 用户实时识别的部分文本（可做打字机效果） */
    val userPartialText: LiveData<String> = _userPartialText

    /** 用户最终识别文本（完整输入） */
    val userFinalText: LiveData<String> = _userFinalText

    /** AI的回复文本 */
    val aiReplyText: LiveData<String> = _aiReplyText

    /** 当前用户说话音量 0~10 */
    val userVolume: LiveData<Float> = _userVolume

    /** 最后一次检测到的唤醒词及置信度 */
    val wakeWord: LiveData<Pair<String, Float>?> = _wakeWord

    /** 是否正在识别中（麦克风开） */
    val isListening: LiveData<Boolean> = _isListening

    /** 是否正在播报TTS */
    val isSpeaking: LiveData<Boolean> = _isSpeaking

    /** 是否正在思考/请求AI */
    val isThinking: LiveData<Boolean> = _isThinking

    /** 错误提示信息（null表示无错误） */
    val errorMessage: LiveData<String?> = _errorMessage

    /** 当前语速 0.3f ~ 2.5f */
    val speechRate: LiveData<Float> = _speechRate

    /** 当前音调 0.3f ~ 2.0f */
    val pitch: LiveData<Float> = _pitch

    /** 当前使用的语音识别引擎 */
    val currentEngine: LiveData<SpeechRecognizerManager.EngineType> = _currentEngine

    /** 当前TTS可用音色列表 */
    val availableVoices: LiveData<List<String>> = _availableVoices

    /** 当前使用的音色 */
    val currentVoice: LiveData<String?> = _currentVoice

    // ============================================================
    //  子模块引用
    // ============================================================

    /** 语音会话（ViewModel层直接持有，或通过WakeWordService获取） */
    private var voiceSession: VoiceSession? = null

    /** 标记是否已经初始化 */
    private var isInitialized: Boolean = false

    // ============================================================
    //  初始化与生命周期
    // ============================================================

    /**
     * 初始化语音模块
     * @param session 可选，外部传入 VoiceSession（如来自 WakeWordService）。
     *                为 null 则内部创建新实例。
     */
    fun initialize(session: VoiceSession? = null) {
        if (isInitialized) return

        voiceSession = session ?: VoiceSession(getApplication())

        voiceSession?.let { s ->
            // 设置 AI 回复提供者为自身
            s.setAiReplyProvider(this)
            // 注册会话回调
            s.setCallback(createSessionCallback())
            // 初始化子模块
            viewModelScope.launch(Dispatchers.IO) {
                s.initialize()
                // 初始化完成后读取一些参数到 LiveData
                launch(Dispatchers.Main) {
                    _currentEngine.postValue(
                        SpeechRecognizerManager.EngineType.NONE
                    )
                }
            }
        }
        isInitialized = true
    }

    /**
     * 与 WakeWordService 绑定后，将 Service 中的 session 注入到 ViewModel
     */
    fun attachSessionFromService(service: WakeWordService) {
        initialize(service.voiceSession)
    }

    /**
     * ViewModel销毁时释放资源
     */
    override fun onCleared() {
        super.onCleared()
        voiceSession?.release()
        voiceSession = null
        isInitialized = false
    }

    // ============================================================
    //  UI 操作方法
    // ============================================================

    /**
     * 点击语音按钮：启动/停止会话
     *  - 若当前空闲：模拟唤醒词，进入会话
     *  - 若当前进行中：结束会话
     */
    fun toggleVoiceInput() {
        val s = voiceSession ?: return
        when (s.getState()) {
            VoiceSession.SessionState.IDLE,
            VoiceSession.SessionState.ENDED -> {
                s.startSession()
            }
            else -> {
                s.terminateSession()
            }
        }
    }

    /**
     * 仅开始一次识别（不进入完整会话，识别一句话后自动结束）
     */
    fun startOnceRecognition() {
        val s = voiceSession ?: return
        // 启动会话后等待输入
        s.startSession()
    }

    /**
     * 手动停止/取消当前识别
     */
    fun stopRecognition() {
        voiceSession?.terminateSession()
    }

    /**
     * 让助手单独说一句话（不进入交互流程）
     */
    fun speak(text: String) {
        voiceSession?.speak(text, autoFinishAfter = true)
    }

    /**
     * 打断当前播报并立即进入识别
     * 适用于用户在播报期间主动按键打断的场景
     */
    fun stopSpeaking() {
        voiceSession?.let { s ->
            s.interruptAndRecognize()
        }
    }

    /**
     * 发送一条文本消息（模拟用户输入）
     */
    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        _userFinalText.value = text
        // 通过 AI 回复提供者处理
        generateReply(text, object : VoiceSession.ReplyCallback {
            override fun onReply(response: String) {
                _aiReplyText.postValue(response)
                speak(response)
            }

            override fun onError(code: Int, message: String) {
                _errorMessage.postValue(message)
            }
        })
    }

    /**
     * 设置语速
     */
    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(
            TextToSpeechManager.MIN_SPEECH_RATE,
            TextToSpeechManager.MAX_SPEECH_RATE
        )
        _speechRate.value = clamped
    }

    /**
     * 设置音调
     */
    fun setPitch(pitch: Float) {
        val clamped = pitch.coerceIn(
            TextToSpeechManager.MIN_PITCH,
            TextToSpeechManager.MAX_PITCH
        )
        _pitch.value = clamped
    }

    /**
     * 切换识别引擎
     */
    fun switchEngine(engine: SpeechRecognizerManager.EngineType) {
        _currentEngine.value = engine
    }

    /**
     * 切换TTS音色
     */
    fun switchVoice(voiceName: String) {
        _currentVoice.value = voiceName
    }

    /**
     * 清除错误提示
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * 清空当前对话显示
     */
    fun clearConversation() {
        _userPartialText.value = ""
        _userFinalText.value = ""
        _aiReplyText.value = ""
    }

    // ============================================================
    //  VoiceSession.AiReplyProvider 实现
    // ============================================================

    /**
     * 生成AI回复
     * 注意：此处为占位实现，实际项目中应调用真实的AI后端接口
     */
    override fun generateReply(userInput: String, callback: VoiceSession.ReplyCallback) {
        viewModelScope.launch(Dispatchers.IO) {
            // 模拟网络请求耗时
            kotlinx.coroutines.delay(500L)

            // 示例：简单的关键词回复占位
            val reply = generateMockReply(userInput)
            if (reply.isNotBlank()) {
                callback.onReply(reply)
            } else {
                callback.onError(-1, "AI 服务暂不可用")
            }
        }
    }

    /**
     * 生成模拟回复（占位实现）
     * 实际项目中替换为真实的大模型接口调用
     */
    private fun generateMockReply(input: String): String {
        val lower = input.lowercase()
        return when {
            "你好" in input || "hello" in lower -> "你好，我是灵枢，很高兴为你服务！请问有什么可以帮你的？"
            "时间" in input || "time" in lower -> {
                val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                "现在时间是 ${sdf.format(java.util.Date())}。"
            }
            "日期" in input || "date" in lower -> {
                val sdf = java.text.SimpleDateFormat(
                    "yyyy年MM月dd日 EEEE",
                    java.util.Locale.CHINA
                )
                "今天是 ${sdf.format(java.util.Date())}。"
            }
            "再见" in input || "拜拜" in input || "bye" in lower -> {
                "再见！有需要随时叫我，说一声\"灵枢\"就能唤醒我哦。"
            }
            else -> "你说的是：\"$input\"。这是一个演示回复，实际使用请接入真实AI接口。"
        }
    }

    // ============================================================
    //  Session 回调
    // ============================================================

    /**
     * 创建会话回调适配器，将事件同步到 LiveData
     */
    private fun createSessionCallback(): VoiceSession.SessionCallback {
        return object : VoiceSession.SessionCallback {
            override fun onStateChanged(
                newState: VoiceSession.SessionState,
                oldState: VoiceSession.SessionState
            ) {
                _sessionState.postValue(newState)
                // 同步派生状态
                _isListening.postValue(
                    newState == VoiceSession.SessionState.RECOGNIZING ||
                            newState == VoiceSession.SessionState.LISTENING
                )
                _isSpeaking.postValue(newState == VoiceSession.SessionState.SPEAKING)
                _isThinking.postValue(newState == VoiceSession.SessionState.THINKING)

                // 状态切换时清理部分临时数据
                when (newState) {
                    VoiceSession.SessionState.IDLE,
                    VoiceSession.SessionState.ENDED -> {
                        _userPartialText.postValue("")
                        _userVolume.postValue(0f)
                    }
                    else -> {}
                }
            }

            override fun onWakeWord(wakeWord: String, confidence: Float) {
                _wakeWord.postValue(wakeWord to confidence)
            }

            override fun onUserSpeechStart() {
                _userPartialText.postValue("")
            }

            override fun onUserSpeechEnd() {
                // 可用于触发UI动画
            }

            override fun onUserText(text: String, isFinal: Boolean) {
                if (isFinal) {
                    _userFinalText.postValue(text)
                } else {
                    _userPartialText.postValue(text)
                }
            }

            override fun onUserInputComplete(text: String) {
                _userFinalText.postValue(text)
                _userPartialText.postValue("")
            }

            override fun onReplyStart(text: String) {
                _aiReplyText.postValue(text)
            }

            override fun onReplyDone(text: String) {
                // 可用于结束UI上的播放动画
            }

            override fun onUserVolumeChanged(volume: Float) {
                _userVolume.postValue(volume)
            }

            override fun onSessionTimeout() {
                _errorMessage.postValue("会话超时，已自动结束")
            }

            override fun onSessionTerminated() {
                // 会话结束
            }

            override fun onBargeIn() {
                // 用户打断了播报
            }

            override fun onError(errorCode: Int, errorMessage: String) {
                _errorMessage.postValue(errorMessage)
            }
        }
    }
}

package com.lingshu.agent.feature.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 连续对话会话管理（全双工语音交互）
 *
 * 负责协调唤醒词检测、语音识别、语音合成等模块，
 * 实现完整的多轮语音交互能力：
 *
 * 交互流程（唤醒→识别→思考→回答→打断循环）：
 * 1. IDLE 状态下检测到唤醒词 → 进入 LISTENING
 * 2. 启动语音识别（RECOGNIZING），VAD检测用户说话
 * 3. 用户说话结束（SPEECH_END）→ 进入 THINKING，调用AI获取回复
 * 4. AI回复返回 → 进入 SPEAKING，TTS播报回复
 * 5a. 播报完成 → 如果开启连续对话，回到 LISTENING 等待下一轮
 * 5b. 播报中用户说话（Barge-in）→ 打断TTS，立即转入 RECOGNIZING
 * 6. 长静音超时或手动终止 → 结束会话（ENDED），恢复唤醒词监听
 *
 * 核心能力：
 * - 唤醒后免重复唤醒 - 一次唤醒后可进行多轮对话
 * - VAD静音超时自动结束会话 - 用户长时间不说话时自动退出
 * - 用户说话自动恢复监听 - 助手播报中用户可直接说话打断
 * - 语音打断（Barge-in）支持 - 用户说话时自动停止TTS播报并转入识别
 */
class VoiceSession(
    private val context: Context,
    private val wakeWordDetector: WakeWordDetector = NoOpWakeWordDetector(),
    private val speechRecognizer: SpeechRecognizerManager = SpeechRecognizerManager(context),
    private val textToSpeech: TextToSpeechManager = TextToSpeechManager(context),
    private val vad: VAD = VAD()
) {

    /**
     * 会话状态枚举
     */
    enum class SessionState {
        /** 空闲，等待唤醒词 */
        IDLE,
        /** 已唤醒，正在聆听用户输入 */
        LISTENING,
        /** 正在识别用户语音（内部状态） */
        RECOGNIZING,
        /** 正在思考/请求AI服务 */
        THINKING,
        /** 正在播报回复 */
        SPEAKING,
        /** 会话已结束 */
        ENDED,
        /** 发生错误 */
        ERROR
    }

    /**
     * 会话交互回调接口
     */
    interface SessionCallback {
        /**
         * 会话状态变更
         * @param newState 新状态
         * @param oldState 旧状态
         */
        fun onStateChanged(newState: SessionState, oldState: SessionState)

        /**
         * 检测到唤醒词
         * @param wakeWord 唤醒词
         * @param confidence 置信度
         */
        fun onWakeWord(wakeWord: String, confidence: Float)

        /**
         * 用户开始说话
         */
        fun onUserSpeechStart()

        /**
         * 用户说话结束
         */
        fun onUserSpeechEnd()

        /**
         * 收到用户语音识别结果（部分结果）
         * @param text 识别文本
         * @param isFinal 是否为最终结果
         */
        fun onUserText(text: String, isFinal: Boolean)

        /**
         * 识别完成，用户最终输入
         * @param text 完整识别文本
         */
        fun onUserInputComplete(text: String)

        /**
         * AI开始播报回复
         * @param text 回复文本
         */
        fun onReplyStart(text: String)

        /**
         * AI播报结束
         * @param text 回复文本
         */
        fun onReplyDone(text: String)

        /**
         * 音量变化（用户说话音量）
         * @param volume 音量值 0~10
         */
        fun onUserVolumeChanged(volume: Float)

        /**
         * 会话因静音超时而结束
         */
        fun onSessionTimeout()

        /**
         * 会话被手动结束
         */
        fun onSessionTerminated()

        /**
         * 语音打断事件（用户说话打断了TTS播报）
         */
        fun onBargeIn()

        /**
         * 发生错误
         * @param errorCode 错误码
         * @param errorMessage 错误信息
         */
        fun onError(errorCode: Int, errorMessage: String)
    }

    /**
     * AI回复提供者接口
     * 由上层（ViewModel或业务层）实现，提供文本响应
     */
    fun interface AiReplyProvider {
        /**
         * 根据用户输入生成AI回复
         * @param userInput 用户输入文本
         * @param callback 回复回调，成功时调用onReply，失败调用onError
         */
        fun generateReply(userInput: String, callback: ReplyCallback)
    }

    /**
     * AI回复回调接口
     */
    interface ReplyCallback {
        /** 回复成功 */
        fun onReply(text: String)
        /** 回复失败 */
        fun onError(code: Int, message: String)
    }

    companion object {
        /** 默认会话空闲超时时间（毫秒）：一次唤醒后持续多久无交互则结束 */
        const val DEFAULT_SESSION_IDLE_TIMEOUT_MS = 30_000L

        /** 默认等待用户输入超时（毫秒）：唤醒后多久没说话就结束 */
        const val DEFAULT_WAIT_INPUT_TIMEOUT_MS = 8_000L

        /** 错误码：AI回复生成失败 */
        const val ERROR_AI_REPLY = 3001

        /** 错误码：会话初始化失败 */
        const val ERROR_SESSION_INIT = 3002
    }

    /** 当前会话状态 */
    private var state: SessionState = SessionState.IDLE
        set(value) {
            if (field != value) {
                val old = field
                field = value
                mainHandler.post { callback?.onStateChanged(value, old) }
            }
        }

    /** 会话回调 */
    private var callback: SessionCallback? = null

    /** AI回复提供者 */
    private var aiReplyProvider: AiReplyProvider? = null

    /** 主线程Handler */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 协程作用域 */
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 会话空闲超时Job */
    private var sessionIdleJob: Job? = null

    /** 等待用户输入超时Job */
    private var waitInputJob: Job? = null

    /** 当前会话ID，用于区分不同会话 */
    private var sessionId: String? = null

    /** 会话开始时间戳 */
    private var sessionStartTime: Long = 0L

    /** 会话空闲超时时间 */
    var sessionIdleTimeoutMs: Long = DEFAULT_SESSION_IDLE_TIMEOUT_MS

    /** 等待用户输入超时时间 */
    var waitInputTimeoutMs: Long = DEFAULT_WAIT_INPUT_TIMEOUT_MS

    /** 是否启用语音打断（Barge-in） */
    var enableBargeIn: Boolean = true

    /** 是否在播报后自动恢复识别等待（开启连续对话） */
    var autoResumeListening: Boolean = true

    /** 是否已经初始化 */
    private var isInitialized: Boolean = false

    /**
     * 初始化会话
     * 初始化各子模块
     * @param aiReplyProvider AI回复提供者
     * @return 是否初始化成功
     */
    fun initialize(aiReplyProvider: AiReplyProvider? = null): Boolean {
        this.aiReplyProvider = aiReplyProvider

        // 初始化TTS
        var initSuccess = true
        textToSpeech.initialize { success ->
            if (!success) initSuccess = false
        }

        // 初始化STT
        speechRecognizer.initialize()

        // 初始化唤醒词检测器（此处仅初始化，不启动监听）
        val wakeWordInit = wakeWordDetector.init(context)
        if (!wakeWordInit) {
            // 即使唤醒词初始化失败也继续，可能通过手动startSession()启动
        }

        isInitialized = true
        state = SessionState.IDLE
        return initSuccess
    }

    /**
     * 注册会话回调
     */
    fun setCallback(callback: SessionCallback?) {
        this.callback = callback
    }

    /**
     * 设置AI回复提供者
     */
    fun setAiReplyProvider(provider: AiReplyProvider?) {
        this.aiReplyProvider = provider
    }

    /**
     * 设置声音克隆提供者
     */
    fun setVoiceCloneProvider(provider: VoiceCloneProvider?) {
        textToSpeech.setVoiceCloneProvider(provider)
    }

    /**
     * 开始等待唤醒词（后台监听模式）
     * 通常由WakeWordService调用
     */
    fun startWakeWordListening(): Boolean {
        if (!isInitialized) {
            notifyError(ERROR_SESSION_INIT, "会话未初始化")
            return false
        }
        if (state != SessionState.IDLE && state != SessionState.ENDED) {
            return false
        }
        state = SessionState.IDLE
        return wakeWordDetector.startListening(object : WakeWordDetector.Callback {
            override fun onWakeWordDetected(wakeWord: String, confidence: Float) {
                handleWakeWord(wakeWord, confidence)
            }

            override fun onError(errorCode: Int, errorMessage: String) {
                notifyError(errorCode, "唤醒词检测错误: $errorMessage")
            }
        })
    }

    /**
     * 停止唤醒词监听
     */
    fun stopWakeWordListening() {
        wakeWordDetector.stopListening()
    }

    /**
     * 手动启动会话（相当于模拟唤醒词）
     * 在点击语音按钮等场景调用
     */
    fun startSession() {
        if (!isInitialized) {
            notifyError(ERROR_SESSION_INIT, "会话未初始化")
            return
        }
        if (state != SessionState.IDLE && state != SessionState.ENDED) {
            return
        }
        sessionId = generateSessionId()
        sessionStartTime = System.currentTimeMillis()

        // 唤醒词回调事件
        mainHandler.post {
            callback?.onWakeWord("manual", 1.0f)
        }

        enterWaitingInput()
    }

    /**
     * 手动终止整个会话
     */
    fun terminateSession() {
        cancelAllJobs()
        speechRecognizer.cancelRecognition()
        textToSpeech.stop()
        state = SessionState.ENDED
        sessionId = null
        mainHandler.post { callback?.onSessionTerminated() }
    }

    /**
     * 获取当前会话状态
     */
    fun getState(): SessionState = state

    /**
     * 获取当前会话ID
     */
    fun getSessionId(): String? = sessionId

    /**
     * 获取会话已持续时长（毫秒）
     */
    fun getSessionDurationMs(): Long {
        if (sessionStartTime == 0L) return 0L
        return System.currentTimeMillis() - sessionStartTime
    }

    /**
     * 让助手说一句话（不打断当前流程，单独播报）
     * @param text 要说的文本
     * @param autoFinishAfter 是否在播报后结束会话（仅当处于非活动会话时生效）
     */
    fun speak(text: String, autoFinishAfter: Boolean = false) {
        if (text.isBlank()) return
        val currentState = state
        state = SessionState.SPEAKING

        textToSpeech.speak(text, TextToSpeechManager.QueueMode.INTERRUPT,
            object : TextToSpeechManager.SpeakCallback {
                override fun onStart(utteranceId: String) {
                    mainHandler.post { callback?.onReplyStart(text) }
                }

                override fun onDone(utteranceId: String) {
                    mainHandler.post { callback?.onReplyDone(text) }
                    handleSpeakFinished(currentState, autoFinishAfter)
                }

                override fun onInterrupted(utteranceId: String) {
                    // 被打断，通常意味着用户说话Barge-in
                }

                override fun onError(utteranceId: String, errorCode: Int, errorMessage: String) {
                    notifyError(errorCode, "TTS播报错误: $errorMessage")
                    handleSpeakFinished(currentState, autoFinishAfter)
                }

                override fun onRange(utteranceId: String, start: Int, end: Int) {
                    // 范围回调，可用于文字高亮
                }
            }
        )
    }

    /**
     * 打断当前播报，立即进入识别
     * 用于全双工交互：用户在TTS播报时说话，立即打断并识别
     */
    fun interruptAndRecognize() {
        if (state == SessionState.SPEAKING) {
            textToSpeech.stop()
            mainHandler.post { callback?.onBargeIn() }
        }
        startRecognition()
    }

    /**
     * 释放所有资源
     */
    fun release() {
        cancelAllJobs()
        wakeWordDetector.release()
        speechRecognizer.release()
        textToSpeech.release()
        coroutineScope.cancel()
        isInitialized = false
        state = SessionState.ENDED
    }

    // ============================================================
    //  内部流程处理
    // ============================================================

    /**
     * 处理检测到唤醒词
     */
    private fun handleWakeWord(wakeWord: String, confidence: Float) {
        // 停止唤醒词监听，进入会话模式
        wakeWordDetector.stopListening()

        sessionId = generateSessionId()
        sessionStartTime = System.currentTimeMillis()

        mainHandler.post {
            callback?.onWakeWord(wakeWord, confidence)
        }

        // 进入等待用户输入状态
        enterWaitingInput()
    }

    /**
     * 进入等待用户输入状态
     * 唤醒后或播报完成后调用，启动识别等待用户说话
     */
    private fun enterWaitingInput() {
        state = SessionState.LISTENING
        scheduleSessionIdleTimeout()
        scheduleWaitInputTimeout()
        startRecognition()
    }

    /**
     * 启动语音识别
     * 同时启动Barge-in监听（如果处于播报状态）
     */
    private fun startRecognition() {
        state = SessionState.RECOGNIZING
        scheduleSessionIdleTimeout()

        speechRecognizer.startRecognition(
            object : SpeechRecognizerManager.RecognitionCallback {
                override fun onStartOfSpeech() {
                    cancelWaitInputTimeout()
                    mainHandler.post { callback?.onUserSpeechStart() }
                }

                override fun onEndOfSpeech() {
                    mainHandler.post { callback?.onUserSpeechEnd() }
                }

                override fun onPartialResult(text: String, isFinal: Boolean) {
                    scheduleSessionIdleTimeout() // 有交互就刷新空闲计时
                    mainHandler.post { callback?.onUserText(text, isFinal) }
                }

                override fun onResult(text: String) {
                    if (text.isNotBlank()) {
                        scheduleSessionIdleTimeout()
                        mainHandler.post { callback?.onUserInputComplete(text) }
                        processUserInput(text)
                    } else {
                        // 识别结果为空，可能是环境噪声，继续等待
                        if (autoResumeListening && state == SessionState.RECOGNIZING) {
                            startRecognition()
                        }
                    }
                }

                override fun onError(errorCode: Int, errorMessage: String) {
                    // 超时或无匹配等非致命错误，继续恢复识别
                    if (errorCode == SpeechRecognizerManager.ERROR_TIMEOUT ||
                        errorCode == SpeechRecognizerManager.ERROR_NO_MATCH
                    ) {
                        if (autoResumeListening && shouldContinue()) {
                            startRecognition()
                            return
                        }
                    }
                    notifyError(errorCode, "STT错误: $errorMessage")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    scheduleSessionIdleTimeout()
                    mainHandler.post { callback?.onUserVolumeChanged(rmsdB) }

                    // ====== 全双工 Barge-in 处理 ======
                    // 当处于SPEAKING状态且检测到用户说话音量超过阈值时，打断TTS并转入识别
                    if (enableBargeIn && state == SessionState.SPEAKING) {
                        if (rmsdB >= vad.volumeThreshold) {
                            textToSpeech.stop()
                            mainHandler.post { callback?.onBargeIn() }
                            speechRecognizer.cancelRecognition()
                            startRecognition()
                        }
                    }

                    // ====== 播报期间预监听 ======
                    // 在SPEAKING状态下，即使不打断，也持续通过VAD跟踪用户说话意图
                    if (state == SessionState.SPEAKING) {
                        vad.processVolume(rmsdB)
                    }
                }

                override fun onEngineSwitched(engineType: SpeechRecognizerManager.EngineType) {
                    // 引擎切换事件，上层可选择性处理
                }
            }
        )
    }

    /**
     * 处理用户输入文本
     * 识别完成后调用，进入THINKING状态并请求AI回复
     */
    private fun processUserInput(text: String) {
        state = SessionState.THINKING
        scheduleSessionIdleTimeout()

        val provider = aiReplyProvider
        if (provider == null) {
            notifyError(ERROR_AI_REPLY, "未配置AI回复提供者")
            // 未配置提供者时自动恢复识别
            if (autoResumeListening) {
                startRecognition()
            }
            return
        }

        provider.generateReply(text, object : ReplyCallback {
            override fun onReply(reply: String) {
                mainHandler.post {
                    if (reply.isNotBlank()) {
                        speakReply(reply)
                    } else {
                        // 空回复，继续等待
                        if (autoResumeListening) {
                            startRecognition()
                        }
                    }
                }
            }

            override fun onError(code: Int, message: String) {
                mainHandler.post {
                    notifyError(code, "AI回复错误: $message")
                    if (autoResumeListening && shouldContinue()) {
                        startRecognition()
                    }
                }
            }
        })
    }

    /**
     * 播报AI回复
     * 播报完成后根据配置决定是否恢复识别等待（连续对话）
     */
    private fun speakReply(text: String) {
        state = SessionState.SPEAKING
        scheduleSessionIdleTimeout()

        textToSpeech.speak(text, TextToSpeechManager.QueueMode.INTERRUPT,
            object : TextToSpeechManager.SpeakCallback {
                override fun onStart(utteranceId: String) {
                    mainHandler.post { callback?.onReplyStart(text) }
                }

                override fun onDone(utteranceId: String) {
                    mainHandler.post { callback?.onReplyDone(text) }
                    // 播报完成：如果开启连续对话，回到LISTENING等待下一轮
                    handleSpeakFinished(SessionState.LISTENING, autoFinishAfter = false)
                }

                override fun onInterrupted(utteranceId: String) {
                    // 被用户打断（Barge-in），此时interruptAndRecognize已启动新识别
                    mainHandler.post { callback?.onBargeIn() }
                }

                override fun onError(utteranceId: String, errorCode: Int, errorMessage: String) {
                    notifyError(errorCode, "TTS播报错误: $errorMessage")
                    handleSpeakFinished(SessionState.LISTENING, autoFinishAfter = false)
                }

                override fun onRange(utteranceId: String, start: Int, end: Int) {
                    // 预留
                }
            }
        )
    }

    /**
     * 播报完成后的处理
     * @param previousState 播报前的状态，用于决定恢复到哪个状态
     * @param autoFinishAfter 是否自动结束会话
     */
    private fun handleSpeakFinished(previousState: SessionState, autoFinishAfter: Boolean) {
        if (autoFinishAfter) {
            terminateSession()
            return
        }
        if (autoResumeListening && shouldContinue()) {
            // 连续对话：播报完毕自动启动下一轮识别等待
            enterWaitingInput()
        } else {
            state = previousState
        }
    }

    /**
     * 判断是否应该继续会话
     * 用于在超时、错误等场景判断是否自动恢复识别
     */
    private fun shouldContinue(): Boolean {
        return state == SessionState.RECOGNIZING ||
                state == SessionState.SPEAKING ||
                state == SessionState.LISTENING ||
                state == SessionState.THINKING
    }

    /**
     * 安排会话空闲超时
     * 每次用户交互应刷新此计时器
     */
    private fun scheduleSessionIdleTimeout() {
        sessionIdleJob?.cancel()
        sessionIdleJob = coroutineScope.launch {
            delay(sessionIdleTimeoutMs)
            mainHandler.post {
                if (shouldContinue() || state == SessionState.LISTENING) {
                    terminateSession()
                    callback?.onSessionTimeout()
                }
            }
        }
    }

    /**
     * 安排等待用户输入超时（唤醒后未说话）
     */
    private fun scheduleWaitInputTimeout() {
        waitInputJob?.cancel()
        waitInputJob = coroutineScope.launch {
            delay(waitInputTimeoutMs)
            mainHandler.post {
                if (state == SessionState.LISTENING) {
                    // 等待输入超时，自动结束会话
                    terminateSession()
                    callback?.onSessionTimeout()
                }
            }
        }
    }

    /**
     * 取消等待用户输入超时
     */
    private fun cancelWaitInputTimeout() {
        waitInputJob?.cancel()
        waitInputJob = null
    }

    /**
     * 取消所有定时任务
     */
    private fun cancelAllJobs() {
        sessionIdleJob?.cancel()
        sessionIdleJob = null
        waitInputJob?.cancel()
        waitInputJob = null
    }

    /**
     * 发送错误通知
     */
    private fun notifyError(code: Int, message: String) {
        mainHandler.post { callback?.onError(code, message) }
    }

    /**
     * 生成会话ID
     */
    private fun generateSessionId(): String {
        return "voice_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
    }
}

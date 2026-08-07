package com.lingshu.agent.feature.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.agent.core.database.dao.ClonedVoiceDao
import com.lingshu.agent.core.database.dao.MemoryDao
import com.lingshu.agent.core.database.dao.MessageDao
import com.lingshu.agent.core.database.entity.MessageEntity
import com.lingshu.agent.core.database.entity.toMessage
import com.lingshu.agent.core.model.Message
import com.lingshu.agent.core.model.MessageRole
import com.lingshu.agent.data.SettingsManager
import com.lingshu.agent.feature.model.ModelMessage
import com.lingshu.agent.feature.persona.PersonaManager
import com.lingshu.agent.feature.personality.PersonalityManager
import com.lingshu.agent.feature.voice.NoOpWakeWordDetector
import com.lingshu.agent.feature.voice.VoiceCloneProvider
import com.lingshu.agent.feature.voice.MockVoiceCloneProvider
import com.lingshu.agent.feature.voice.SpeechRecognizerManager
import com.lingshu.agent.feature.voice.VoiceSession
import com.lingshu.agent.network.DeepSeekApi
import com.lingshu.agent.feature.control.DeviceController
import com.lingshu.agent.feature.control.DeviceActionResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import retrofit2.HttpException
import java.net.SocketTimeoutException
import java.io.IOException
import javax.inject.Inject

/**
 * 聊天界面 ViewModel（模块1：文字聊天 + 默认TTS）
 *
 * 核心职责：
 * 1. 维护消息列表 StateFlow（UI绑定显示）
 * 2. 发送消息：DeepSeekApi.chatCompletion → 非流式回复
 * 3. 桥接 VoiceSession：STT → TTS 播报
 * 4. 与 PersonaManager 联动：获取当前激活人格 → 构建 System Prompt
 * 5. 消息持久化：Room MessageDao
 *
 * 模块1改造要点：
 * - 移除 ModelRouter 多 Provider 路由，直达 DeepSeek API
 * - 非流式发送：发送后等待完整回复，收到后添加AI消息 + TTS播报
 * - 错误处理：API Key 为空 / 401 / 超时 / 网络异常 → 用户可理解提示
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    application: Application,
    private val deepSeekApi: DeepSeekApi,
    private val settingsManager: SettingsManager,
    private val personaManager: PersonaManager,
    private val personalityManager: PersonalityManager,
    private val messageDao: MessageDao,
    private val memoryDao: MemoryDao,
    private val clonedVoiceDao: ClonedVoiceDao
) : AndroidViewModel(application) {

    // ==================== 声音克隆 ====================

    private val voiceCloneProvider: VoiceCloneProvider = MockVoiceCloneProvider()

    // ==================== 模块7：设备控制器 ====================

    private val deviceController = DeviceController(getApplication())

    // ==================== 消息列表状态 ====================

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    // ==================== 输入与发送状态 ====================

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private var _voiceSession: VoiceSession? = null

    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError.asStateFlow()

    /** 是否正在发送/等待回复（按钮禁用 + 思考动画） */
    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    /** 是否正在思考中（显示"思考中..."加载动画） */
    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    /** 兼容旧 ChatScreen UI（流式状态 = 思考中，用于 ThinkingDotsIndicator + 停止按钮） */
    val isStreaming: StateFlow<Boolean>
        get() = isThinking

    // ==================== 语音会话状态（VoiceSession） ====================

    private val _voiceState = MutableStateFlow(VoiceSession.SessionState.IDLE)
    val voiceState: StateFlow<VoiceSession.SessionState> = _voiceState.asStateFlow()

    private val _voiceVolume = MutableStateFlow(0f)
    val voiceVolume: StateFlow<Float> = _voiceVolume.asStateFlow()

    private val _voicePartialText = MutableStateFlow("")
    val voicePartialText: StateFlow<String> = _voicePartialText.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // ==================== 语音输入状态（模块2：SpeechRecognizerManager） ====================

    /** 语音识别管理器（简化版，仅封装系统 SpeechRecognizer） */
    private val speechRecognizerManager = SpeechRecognizerManager(getApplication())

    /** 语音识别状态 */
    private val _speechState = MutableStateFlow(SpeechState.IDLE)
    val speechState: StateFlow<SpeechState> = _speechState.asStateFlow()

    /** 实时部分识别文本（录音过程中持续更新） */
    private val _speechPartialText = MutableStateFlow("")
    val speechPartialText: StateFlow<String> = _speechPartialText.asStateFlow()

    // ==================== 状态汇总：statusType 需同时考虑 speechState ====================

    /**
     * 顶部状态栏Chip状态类型（模块2：新增 SPEECH_INPUT 识别态）
     */
    val statusType: StateFlow<StatusChipType> = kotlinx.coroutines.flow.combine(
        _isSending,
        _isThinking,
        _voiceState,
        _speechState
    ) { sending, thinking, voice, speech ->
        when {
            speech == SpeechState.LISTENING || speech == SpeechState.PROCESSING ->
                StatusChipType.SPEECH_INPUT
            voice == VoiceSession.SessionState.RECOGNIZING ->
                StatusChipType.LISTENING
            voice == VoiceSession.SessionState.THINKING || sending || thinking ->
                StatusChipType.THINKING
            voice == VoiceSession.SessionState.SPEAKING ->
                StatusChipType.EXECUTING
            else -> StatusChipType.IDLE
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatusChipType.IDLE
    )

    enum class StatusChipType {
        IDLE, LISTENING, THINKING, EXECUTING, SPEECH_INPUT
    }

    // ==================== 当前会话/人格信息 ====================

    private val _conversationId = MutableStateFlow("default_conversation")
    val conversationId: StateFlow<String> = _conversationId.asStateFlow()

    /** 模块1：模型名称固定为 DeepSeek Chat */
    private val _currentModelName = MutableStateFlow("灵枢")
    val currentModelName: StateFlow<String> = _currentModelName.asStateFlow()



    // ==================== 初始化 ====================

    init {
        viewModelScope.launch {
            loadInitialMessages()
        }
    }

    // ==================== 公共 API：消息输入发送 ====================

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    /**
     * 发送文本消息（模块1：直达 DeepSeek API，非流式）
     *
     * 流程：
     * 1. 校验输入非空
     * 2. 添加用户消息到列表并持久化
     * 3. 设置 isThinking = true（显示"思考中..."）
     * 4. 调用 DeepSeekApi.chatCompletion
     * 5. 收到回复后添加 AI 消息并持久化
     * 6. 调用 voiceSession?.speak(replyText) 播报
     * 7. 设置 isThinking = false
     */
    /**
     * 模块7：检测文本是否为控制指令
     */
    fun isControlCommand(text: String): Boolean {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("/控制") -> true
            trimmed.startsWith("控制：") -> true
            trimmed.startsWith("帮我打开") -> true
            trimmed == "返回" || trimmed == "退出" || trimmed == "回退" -> true
            trimmed == "主页" || trimmed == "桌面" -> true
            trimmed == "最近任务" || trimmed == "多任务" -> true
            trimmed == "通知" || trimmed == "通知栏" || trimmed == "下拉通知" -> true
            trimmed == "截图" -> true
            trimmed.startsWith("打开") || trimmed.startsWith("启动") -> true
            trimmed.startsWith("点击") -> true
            trimmed.startsWith("输入") -> true
            trimmed.startsWith("滑动") || trimmed == "向上滑" || trimmed == "向下滑" ||
                trimmed == "向左滑" || trimmed == "向右滑" -> true
            else -> false
        }
    }

    fun sendTextMessage() {
        val text = _inputText.value.trim()
        if (text.isBlank() || _isSending.value || _isThinking.value) return

        // ========== 模块7：路由控制指令 ==========
        if (isControlCommand(text)) {
            val userMessage = Message(
                conversationId = _conversationId.value,
                role = MessageRole.USER,
                content = text
            )
            appendMessage(userMessage)
            _inputText.value = ""

            val result = deviceController.execute(text)
            val replyText = if (result.success) {
                "${result.message}\n操作：${result.action}"
            } else {
                "控制指令失败：${result.message}"
            }
            val replyMessage = Message(
                conversationId = _conversationId.value,
                role = MessageRole.ASSISTANT,
                content = replyText,
                modelName = "设备控制"
            )
            appendMessage(replyMessage)
            return
        }

        val userMessage = Message(
            conversationId = _conversationId.value,
            role = MessageRole.USER,
            content = text
        )

        appendMessage(userMessage)
        _inputText.value = ""

        viewModelScope.launch {
            sendMessageInternal(_messages.value)
        }
    }

    /**
     * 发送带图片的消息（多模态）
     */
    fun sendMessageWithImages(text: String, images: List<String>) {
        if (text.isBlank() && images.isEmpty()) return
        if (_isSending.value || _isThinking.value) return

        val userMessage = Message(
            conversationId = _conversationId.value,
            role = MessageRole.USER,
            content = text,
            images = images
        )

        appendMessage(userMessage)
        _inputText.value = ""

        viewModelScope.launch {
            sendMessageInternal(_messages.value)
        }
    }

    /**
     * 停止当前生成（模块1：非流式场景下为简化实现，取消协程）
     */
    fun stopStreaming() {
        // 模块1使用非流式，此处保留接口兼容性
    }

    /**
     * 重新生成最后一条AI回复
     */
    fun regenerateLastAssistantMessage() {
        val currentMessages = _messages.value
        if (currentMessages.isEmpty()) return

        val withoutLastAi = currentMessages.dropLastWhile { it.isAssistantMessage() }
        if (withoutLastAi == currentMessages) return

        _messages.value = withoutLastAi

        viewModelScope.launch {
            sendMessageInternal(withoutLastAi)
        }
    }

    // ==================== 公共 API：语音交互 ====================

    fun toggleVoiceSession(voiceSession: VoiceSession? = null) {
        try {
            val session = voiceSession ?: _voiceSession ?: run {
                VoiceSession(getApplication(), NoOpWakeWordDetector()).also {
                    _voiceSession = it
                    it.setVoiceCloneProvider(voiceCloneProvider)
                    it.initialize(null)
                }
            }
            val currentState = _voiceState.value
            when (currentState) {
                VoiceSession.SessionState.IDLE,
                VoiceSession.SessionState.ENDED -> {
                    session.startSession()
                    _voiceState.value = VoiceSession.SessionState.LISTENING
                }
                else -> {
                    session.terminateSession()
                    _voiceState.value = VoiceSession.SessionState.IDLE
                    _voiceVolume.value = 0f
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "语音功能异常", e)
            _voiceState.value = VoiceSession.SessionState.ERROR
            _inputText.value = ""
            _voiceError.value = "语音功能暂不可用（${e.message ?: "设备不支持"}），请使用文本输入"
        }
    }

    fun onVoiceStateChanged(newState: VoiceSession.SessionState,
                           oldState: VoiceSession.SessionState) {
        _voiceState.value = newState
        if (newState != VoiceSession.SessionState.SPEAKING &&
            oldState == VoiceSession.SessionState.SPEAKING) {
            _isSpeaking.value = false
        }
    }

    fun onVoiceVolumeChanged(volume: Float) {
        val normalized = (volume.coerceIn(-60f, 0f) + 60f) / 60f
        _voiceVolume.value = normalized
    }

    fun onVoicePartialText(text: String, isFinal: Boolean) {
        _voicePartialText.value = text
        if (isFinal && text.isNotBlank()) {
            sendVoiceTextAsMessage(text)
        }
    }

    private fun sendVoiceTextAsMessage(text: String) {
        val userMessage = Message(
            conversationId = _conversationId.value,
            role = MessageRole.USER,
            content = text
        )
        appendMessage(userMessage)
        _voicePartialText.value = ""

        viewModelScope.launch {
            sendMessageInternal(_messages.value)
        }
    }

    fun onTtsStart() {
        _isSpeaking.value = true
    }

    fun onTtsDone() {
        _isSpeaking.value = false
    }

    // ==================== 模块2：语音输入（SpeechRecognizerManager） ====================

    /**
     * 切换语音输入状态（按下麦克风按钮）
     *
     * 流程：
     * IDLE/ERROR → 启动 SpeechRecognizerManager 开始识别
     * LISTENING/PROCESSING → 停止识别，回到 IDLE
     */
    fun toggleVoiceInput() {
        when (_speechState.value) {
            SpeechState.IDLE, SpeechState.ERROR -> startSpeechRecognition()
            SpeechState.LISTENING, SpeechState.PROCESSING -> stopSpeechRecognition()
        }
    }

    /**
     * 启动语音识别
     */
    private fun startSpeechRecognition() {
        val callback = object : SpeechRecognizerManager.RecognitionCallback {
            override fun onStartOfSpeech() {
                _speechState.value = SpeechState.LISTENING
                _speechPartialText.value = ""
            }

            override fun onEndOfSpeech() {}

            override fun onPartialResult(text: String, isFinal: Boolean) {
                _speechPartialText.value = text
                _speechState.value = SpeechState.LISTENING
            }

            override fun onResult(text: String) {
                onSpeechResult(text)
            }

            override fun onError(errorCode: Int, errorMessage: String) {
                onSpeechError(errorMessage)
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onEngineSwitched(engineType: SpeechRecognizerManager.EngineType) {}
        }

        speechRecognizerManager.startRecognition(callback)
    }

    /**
     * 停止语音识别
     */
    private fun stopSpeechRecognition() {
        speechRecognizerManager.stopRecognition()
        _speechState.value = SpeechState.IDLE
        _speechPartialText.value = ""
    }

    /**
     * 语音识别最终结果回调
     *
     * 收到完整识别结果后：
     * 1. 将识别文本填入输入框
     * 2. 自动发送消息
     * 3. 停止录音并重置状态
     */
    private fun onSpeechResult(text: String) {
        _speechState.value = SpeechState.IDLE
        _speechPartialText.value = ""
        speechRecognizerManager.stopRecognition()
        _inputText.value = text
        sendTextMessage()
    }

    /**
     * 语音识别错误回调
     *
     * 按错误类型展示不同提示文案：
     * - 置信度低 → "没听清，能再说一遍吗？"
     * - 超时 → "没有检测到语音输入"
     * - 权限拒绝 → "灵枢需要麦克风权限才能听到你的声音"
     * - 识别器不可用 → "语音识别服务暂不可用，请稍后重试"
     */
    private fun onSpeechError(error: String) {
        _speechState.value = SpeechState.ERROR
        _speechPartialText.value = ""
        _voiceError.value = error
    }

    // ==================== 核心：AI消息发送（模块1：直达 DeepSeek） ====================

    /**
     * 内部发送消息核心流程（模块1：非流式，直达 DeepSeek API）
     *
     * @param history 发送给模型的完整上下文消息列表
     */
    private suspend fun sendMessageInternal(history: List<Message>) = withContext(Dispatchers.IO) {
        if (_isSending.value || _isThinking.value) return@withContext

        _isSending.value = true
        _isThinking.value = true

        try {
            // ========== 步骤1：检查 API Key ==========
            val apiKey = settingsManager.getApiKey()
            if (apiKey.isNullOrBlank()) {
                val errorMsg = "请先在设置中配置 DeepSeek API Key"
                appendMessage(Message(
                    conversationId = _conversationId.value,
                    role = MessageRole.ASSISTANT,
                    content = errorMsg
                ))
                return@withContext
            }

            // ========== 步骤2：构建 System Prompt + 历史 ==========
            val activePersona = personaManager.getActivePersonaSuspend()
            val systemPromptText = if (activePersona != null) {
                personaManager.buildSystemPrompt(activePersona, buildUserContext())
            } else {
                "你是灵枢，一位贴心的AI助手。请用自然、友好的语言回答用户问题。"
            }

            // 模块5：注入长期记忆到 System Prompt
            val enhancedPrompt = buildLongTermMemoryPrompt(systemPromptText)

            // 模块6：注入人格演化参数到 System Prompt
            val personalityPrompt = personalityManager.buildPersonalityPrompt()
            val finalPrompt = "$enhancedPrompt\n\n$personalityPrompt"

            // 模块4：从 Room 加载最近 40 条消息作为短期记忆
            val recentMessages = messageDao.getRecentMessages(40)
            val recentHistory = recentMessages.map { it.toMessage() }

            // 当前用户消息（history 末尾的用户消息）
            val currentUserMsg = history.lastOrNull { it.role == MessageRole.USER }

            val modelMessages = buildModelMessages(finalPrompt, recentHistory, currentUserMsg)

            // ========== 步骤2.5：添加思考占位消息（触发 ThinkingDotsIndicator） ==========
            val placeholderId = "thinking_${System.currentTimeMillis()}"
            val placeholderMessage = Message(
                id = placeholderId,
                conversationId = _conversationId.value,
                role = MessageRole.ASSISTANT,
                content = "",  // 空内容 → ChatScreen 显示跳动点动画
                modelName = "deepseek-chat"
            )
            appendMessage(placeholderMessage)

            // ========== 步骤3：调用 DeepSeek API（非流式） ==========
            val replyText = try {
                deepSeekApi.chatCompletion(modelMessages, apiKey)
            } catch (e: HttpException) {
                when (e.code()) {
                    401 -> "API Key 无效，请检查设置"
                    else -> "请求失败（${e.code()}）：${e.message()}"
                }
            } catch (e: SocketTimeoutException) {
                "网络连接超时，请稍后重试"
            } catch (e: IOException) {
                "网络连接失败：${e.message ?: "请检查网络连接"}"
            } catch (e: Exception) {
                "出错了：${e.message ?: "未知错误"}"
            }

            // ========== 步骤4：替换占位消息内容为实际回复 ==========
            _messages.value = _messages.value.map { msg ->
                if (msg.id == placeholderId) {
                    msg.copy(content = replyText)
                } else msg
            }
            // 持久化更新后的消息
            val updatedMessage = _messages.value.find { it.id == placeholderId }
            if (updatedMessage != null) {
                saveMessageToDatabase(updatedMessage)
            }

            // ========== 步骤4.5：提取并保存长期记忆 ==========
            extractAndSaveMemories(currentUserMsg, replyText)

            // ========== 模块6：根据最近对话自动调整人格参数 ==========
            val allMessages = recentHistory + listOfNotNull(currentUserMsg)
            personalityManager.analyzeAndAdjust(allMessages)

            // ========== 步骤5：TTS 播报 ==========
            val voiceSession = _voiceSession
            if (voiceSession != null && replyText.isNotBlank()) {
                try {
                    voiceSession.speak(replyText)
                    _isSpeaking.value = true
                } catch (e: Exception) {
                    android.util.Log.e("ChatViewModel", "TTS播报失败", e)
                }
            }

        } catch (e: Exception) {
            appendMessage(Message(
                conversationId = _conversationId.value,
                role = MessageRole.ASSISTANT,
                content = "出错了：${e.message ?: "未知错误"}"
            ))
        } finally {
            _isSending.value = false
            _isThinking.value = false
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 模块5：将长期记忆注入 System Prompt
     */
    private suspend fun buildLongTermMemoryPrompt(systemPrompt: String): String {
        val allMemories = memoryDao.getAllMemoriesSync()
        if (allMemories.isEmpty()) return systemPrompt

        val recentMemories = allMemories.sortedByDescending { it.timestamp }.take(10)
        val memoryLines = recentMemories.joinToString("\n") { "- ${it.content}" }
        return "$systemPrompt\n\n你已知的关于用户的长期记忆：\n$memoryLines"
    }

    /**
     * 模块5：从用户消息提取长期记忆并异步保存
     */
    private fun extractAndSaveMemories(currentUserMsg: Message?, replyText: String) {
        val userContent = currentUserMsg?.content ?: return
        if (userContent.isBlank()) return

        val newMemories = MemoryExtractor.extract(userContent, replyText)
        if (newMemories.isEmpty()) return

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val existing = memoryDao.getAllMemoriesSync().map { it.content }
            val toInsert = newMemories.filter { it.content !in existing }
            if (toInsert.isNotEmpty()) {
                memoryDao.insertMemories(toInsert)
            }
        }
    }

    private fun buildUserContext(): Map<String, Any> {
        val calendar = java.util.Calendar.getInstance()
        val timeStr = String.format(
            "%02d:%02d",
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE)
        )
        val dateStr = String.format(
            "%04d-%02d-%02d",
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.MONTH) + 1,
            calendar.get(java.util.Calendar.DAY_OF_MONTH)
        )
        return mapOf(
            "currentTime" to timeStr,
            "date" to dateStr,
            "weekday" to when (calendar.get(java.util.Calendar.DAY_OF_WEEK)) {
                1 -> "星期日"
                2 -> "星期一"
                3 -> "星期二"
                4 -> "星期三"
                5 -> "星期四"
                6 -> "星期五"
                7 -> "星期六"
                else -> ""
            }
        )
    }

    private fun buildModelMessages(
        systemPrompt: String,
        dbHistory: List<Message>,
        currentUserMessage: Message? = null
    ): List<ModelMessage> {
        val result = mutableListOf<ModelMessage>()

        if (systemPrompt.isNotBlank()) {
            result.add(
                ModelMessage(
                    role = com.lingshu.agent.feature.model.MessageRole.SYSTEM,
                    content = systemPrompt
                )
            )
        }

        dbHistory.forEach { msg ->
            val role = when (msg.role) {
                MessageRole.USER -> com.lingshu.agent.feature.model.MessageRole.USER
                MessageRole.ASSISTANT -> com.lingshu.agent.feature.model.MessageRole.ASSISTANT
                else -> null
            }
            if (role != null) {
                result.add(
                    ModelMessage(
                        role = role,
                        content = msg.content,
                        images = msg.images
                    )
                )
            }
        }

        // 最后追加当前用户消息
        if (currentUserMessage != null) {
            result.add(
                ModelMessage(
                    role = com.lingshu.agent.feature.model.MessageRole.USER,
                    content = currentUserMessage.content,
                    images = currentUserMessage.images
                )
            )
        }

        return result
    }

    // ==================== 消息列表读写辅助 ====================

    private fun appendMessage(message: Message) {
        val current = _messages.value.toMutableList()
        current.add(message)
        _messages.value = current
        saveMessageToDatabase(message)
    }

    private fun saveMessageToDatabase(message: Message) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 去重：最后一条消息相同则跳过
                val last = messageDao.getLastByConversation(message.conversationId)
                if (last != null && last.content == message.content && last.role == message.role) {
                    return@launch
                }
                messageDao.insert(message.toEntity())
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "消息持久化失败", e)
            }
        }
    }

    private fun Message.toEntity(): MessageEntity = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role,
        content = content,
        images = if (images.isNotEmpty()) JSONArray(images).toString() else "[]",
        audioUrl = null,
        timestamp = timestamp,
        isRead = role == MessageRole.USER,
        tokenCount = tokenCount,
        feedback = null,
        modelName = modelName
    )

    private suspend fun loadInitialMessages() {
        val dbMessages = withContext(Dispatchers.IO) {
            try {
                messageDao.getByConversationIdSuspend(_conversationId.value)
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "加载历史消息失败", e)
                emptyList()
            }
        }

        if (dbMessages.isNotEmpty()) {
            _messages.value = dbMessages.map { it.toMessage() }
        } else {
            val persona = personaManager.getActivePersonaSuspend()
            val welcomeMessage = Message(
                conversationId = _conversationId.value,
                role = MessageRole.ASSISTANT,
                content = buildString {
                    append("你好，我是")
                    append(persona?.name ?: "灵枢")
                    append("。")
                    append(persona?.openingLine ?: "很高兴为你服务，有什么可以帮你的吗？")
                },
                modelName = _currentModelName.value
            )
            _messages.value = listOf(welcomeMessage)
            saveMessageToDatabase(welcomeMessage)
        }
    }

    fun clearConversation() {
        stopStreaming()
        _conversationId.value = "conv_${System.currentTimeMillis()}"
        viewModelScope.launch {
            loadInitialMessages()
        }
    }

    override fun onCleared() {
        super.onCleared()
    }
}

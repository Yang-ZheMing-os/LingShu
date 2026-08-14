package com.lingshu.feature.chat.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.state.UiState
import com.lingshu.core.common.ToolCallCleaner
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.event.AppEvent
import com.lingshu.core.common.event.IAppEventBus
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.common.event.IChatRepository
import com.lingshu.core.common.event.ISttEngine
import com.lingshu.core.common.event.ITtsEngine
import com.lingshu.core.common.event.Message
import com.lingshu.core.common.event.SttResult
import com.lingshu.core.common.event.on
import com.lingshu.core.common.event.ICommandSyncer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: IChatRepository,
    private val ttsEngine: ITtsEngine,
    private val sttEngine: ISttEngine,
    private val eventBus: IAppEventBus,
    private val commandSyncer: ICommandSyncer
) : ViewModel() {

    private val _messagesState = MutableStateFlow<UiState<List<Message>>>(UiState.Idle)
    val messagesState: StateFlow<UiState<List<Message>>> = _messagesState.asStateFlow()

    private val _sendState = MutableStateFlow<UiState<Message>>(UiState.Idle)
    val sendState: StateFlow<UiState<Message>> = _sendState.asStateFlow()

    private val _ttsEnabled = MutableStateFlow(true)
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    // 流式输出中的 AI 消息（逐 token 更新），null 表示不在流式生成中
    private val _streamingMessage = MutableStateFlow<Message?>(null)
    val streamingMessage: StateFlow<Message?> = _streamingMessage.asStateFlow()

    init {
        loadMessages()
        observeOverrideEvents()
    }

    /** 监听控制命令执行后的规范短句覆盖事件，DB 更新后 Room Flow 会自动推到 UI */
    private fun observeOverrideEvents() {
        viewModelScope.launch {
            eventBus.on<AppEvent.AssistantReplyOverridden>()
                .onEach { event ->
                    LingShuLog.i(
                        "ChatViewModel",
                        "覆盖最后一条 AI 回复: ${event.canonicalReply} (traceId=${event.traceId})"
                    )
                    chatRepository.rewriteLastAssistantMessage(event.canonicalReply)
                }
                .catch { e ->
                    LingShuLog.e("ChatViewModel", "监听 AssistantReplyOverridden 异常", e)
                }
                .launchIn(this)
        }
    }

    fun loadMessages() {
        viewModelScope.launch {
            chatRepository.getMessages()
                .onStart { _messagesState.value = UiState.Loading }
                .catch { e ->
                    _messagesState.value = UiState.Error(
                        code = ErrorCodes.UNKNOWN_ERROR,
                        message = e.message ?: "未知错误"
                    )
                }
                .collect { messages ->
                    _messagesState.value = UiState.Success(messages)
                }
        }
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty() || _sendState.value.isLoading) return

        val traceId = "chat_${System.currentTimeMillis()}"
        val stepTag = "[traceId=$traceId]"

        viewModelScope.launch {
            _sendState.value = UiState.Loading
            _inputText.value = ""

            LingShuLog.i(
                "ChatViewModel",
                "$stepTag ======== 用户发送消息（流式） ========"
            )
            LingShuLog.v(
                "ChatViewModel",
                "$stepTag 消息内容: ${text.take(80)}"
            )

            eventBus.emit(
                AppEvent.UserMessageSent(
                    content = text,
                    traceId = traceId
                )
            )
            eventBus.emit(AppEvent.AiReplyStarted(traceId = traceId))

            // 初始化流式消息（空内容，UI 显示"思考中"动画）
            val streaming = StringBuilder()
            _streamingMessage.value = Message(content = "", isUser = false)

            when (val result = chatRepository.sendMessageStream(text) { token ->
                streaming.append(token)
                // 流式渲染时尽力清理 TOOL_CALL：没匹配到的半截标记会保留，
                // 最终落库时 ChatBubble 还会再剥一次，用户不会看到 JSON
                val cleaned = ToolCallCleaner.stripToolCallMarks(streaming.toString())
                _streamingMessage.value = _streamingMessage.value?.copy(
                    content = cleaned
                )
            }) {
                is Result.Success -> {
                    _sendState.value = UiState.Success(result.data)
                    val rawReply = result.data.content
                    LingShuLog.i(
                        "ChatViewModel",
                        "$stepTag 流式对话成功，reply=${rawReply.take(80)}"
                    )
                    eventBus.emit(
                        AppEvent.AiReplyFinished(
                            reply = rawReply,
                            userInput = text,
                            traceId = traceId
                        )
                    )

                    // 一次性兜底执行：用户说"打开微信""调亮度"等控制指令，
                    // 直接独立识别并执行，不依赖 LLM 是否生成 [TOOL_CALL] 标记、
                    // 也不依赖事件总线。执行成功后直接覆盖 AI 回复为规范短句，
                    // 解决"只说不做"的终极保险。
                    LingShuLog.d(
                        "ChatViewModel",
                        "$stepTag 调用 CommandSyncer.sync(text=${text.take(80)})"
                    )
                    runCatching { commandSyncer.sync(text) }
                        .onSuccess { canonical ->
                            LingShuLog.d(
                                "ChatViewModel",
                                "$stepTag CommandSyncer.sync 返回: ${if (canonical == null) "null" else "\"$canonical\""}"
                            )
                            if (!canonical.isNullOrBlank()) {
                                LingShuLog.i(
                                    "ChatViewModel",
                                    "$stepTag ✅ CommandSyncer 命中 → 覆盖 AI 回复: $canonical"
                                )
                                chatRepository.rewriteLastAssistantMessage(canonical)
                                // TTS 朗读规范短句（不读 LLM 啰嗦原文）
                                if (_ttsEnabled.value && !result.data.isUser) {
                                    speakMessage(canonical)
                                }
                            } else {
                                LingShuLog.v(
                                    "ChatViewModel",
                                    "$stepTag CommandSyncer 未命中（非控制类指令，沿用 LLM 回复）"
                                )
                                if (_ttsEnabled.value && !result.data.isUser) {
                                    speakMessage(ToolCallCleaner.stripToolCallMarks(rawReply))
                                }
                            }
                        }
                        .onFailure { e ->
                            LingShuLog.e("ChatViewModel", "$stepTag CommandSyncer.sync 抛出异常", e)
                            if (_ttsEnabled.value && !result.data.isUser) {
                                speakMessage(ToolCallCleaner.stripToolCallMarks(rawReply))
                            }
                        }
                }
                is Result.Error -> {
                    _sendState.value = UiState.Error(
                        code = result.code,
                        message = result.message
                    )
                    LingShuLog.e(
                        "ChatViewModel",
                        "$stepTag 流式对话失败: code=${result.code}",
                        result.cause
                    )
                    eventBus.emit(
                        AppEvent.AiReplyError(
                            code = result.code,
                            message = result.cause?.message ?: result.message,
                            traceId = traceId
                        )
                    )
                }
            }

            // 流式结束：清空临时消息，落库的完整消息会通过 messagesState 自动推送
            _streamingMessage.value = null

            LingShuLog.i(
                "ChatViewModel",
                "$stepTag ======== 流式发送流程结束 ========"
            )
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun toggleTts() {
        _ttsEnabled.value = !_ttsEnabled.value
        if (!_ttsEnabled.value) {
            ttsEngine.stop()
        }
    }

    private suspend fun speakMessage(text: String) {
        when (val result = ttsEngine.speak(text)) {
            is Result.Success -> {
                LingShuLog.d("ChatViewModel", "TTS 播放成功")
            }
            is Result.Error -> {
                LingShuLog.w("ChatViewModel", "TTS 播放失败: ${result.code}")
            }
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            ttsEngine.stop()
            chatRepository.clearMessages()
        }
    }

    fun toggleVoiceInput() {
        if (_isListening.value) {
            stopVoiceInput()
        } else {
            startVoiceInput()
        }
    }

    private fun startVoiceInput() {
        if (!sttEngine.isAvailable()) {
            LingShuLog.w("ChatViewModel", "STT 不可用")
            _sendState.value = UiState.Error(
                code = ErrorCodes.STT_FAILED,
                message = "语音识别不可用"
            )
            return
        }

        _isListening.value = true
        LingShuLog.d("ChatViewModel", "开始语音输入")

        sttEngine.startListening(
            onResult = { result ->
                _isListening.value = false
                val text = result.text.trim()
                LingShuLog.d("ChatViewModel", "语音识别结果: $text (置信度=${result.confidence})")
                if (text.isNotEmpty()) {
                    _inputText.value = if (_inputText.value.isNotEmpty()) {
                        "${_inputText.value} $text"
                    } else {
                        text
                    }
                }
            },
            onError = { error ->
                _isListening.value = false
                LingShuLog.w("ChatViewModel", "语音识别错误: $error")
                _sendState.value = UiState.Error(
                    code = ErrorCodes.STT_FAILED,
                    message = error
                )
            }
        )
    }

    private fun stopVoiceInput() {
        _isListening.value = false
        sttEngine.stopListening()
        LingShuLog.d("ChatViewModel", "停止语音输入")
    }

    override fun onCleared() {
        super.onCleared()
        ttsEngine.release()
    }
}

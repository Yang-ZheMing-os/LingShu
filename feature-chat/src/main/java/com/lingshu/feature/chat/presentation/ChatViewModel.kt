package com.lingshu.feature.chat.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.state.UiState
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.event.AppEvent
import com.lingshu.core.common.event.IAppEventBus
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.common.event.IChatRepository
import com.lingshu.core.common.event.ISttEngine
import com.lingshu.core.common.event.ITtsEngine
import com.lingshu.core.common.event.Message
import com.lingshu.core.common.event.SttResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: IChatRepository,
    private val ttsEngine: ITtsEngine,
    private val sttEngine: ISttEngine,
    private val eventBus: IAppEventBus
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

    init {
        loadMessages()
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
                "$stepTag ======== 用户手动发送消息（通过UI） ========"
            )
            LingShuLog.v(
                "ChatViewModel",
                "$stepTag 消息内容: ${text.take(80)}"
            )

            LingShuLog.d(
                "ChatViewModel",
                "$stepTag [step-1] emit UserMessageSent 事件到全局事件总线"
            )
            eventBus.emit(
                AppEvent.UserMessageSent(
                    content = text,
                    traceId = traceId
                )
            )

            LingShuLog.d(
                "ChatViewModel",
                "$stepTag [step-2] emit AiReplyStarted 事件，标记 AI 开始思考"
            )
            eventBus.emit(AppEvent.AiReplyStarted(traceId = traceId))

            when (val result = chatRepository.sendMessage(text)) {
                is Result.Success -> {
                    _sendState.value = UiState.Success(result.data)
                    LingShuLog.i(
                        "ChatViewModel",
                        "$stepTag [step-3] 对话成功，emit AiReplyFinished，reply=${result.data.content.take(80)}"
                    )
                    eventBus.emit(
                        AppEvent.AiReplyFinished(
                            reply = result.data.content,
                            traceId = traceId
                        )
                    )
                    if (_ttsEnabled.value && !result.data.isUser) {
                        speakMessage(result.data.content)
                    }
                }
                is Result.Error -> {
                    _sendState.value = UiState.Error(
                        code = result.code,
                        message = result.message
                    )
                    LingShuLog.e(
                        "ChatViewModel",
                        "$stepTag [step-3] 对话失败，emit AiReplyError: code=${result.code}",
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

            LingShuLog.i(
                "ChatViewModel",
                "$stepTag ======== UI 发送消息流程结束 ========"
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

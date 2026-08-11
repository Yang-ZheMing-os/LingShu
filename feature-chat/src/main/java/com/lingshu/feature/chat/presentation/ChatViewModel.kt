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
import com.lingshu.core.common.event.ITtsEngine
import com.lingshu.core.common.event.Message
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

    init {
        loadMessages()
    }

    fun loadMessages() {
        viewModelScope.launch {
            chatRepository.getMessages()
                .onStart { _messagesState.value = UiState.Loading }
                .catch { e ->
                    _messagesState.value = UiState.Error(
                        exception = e,
                        code = ErrorCodes.UNKNOWN_ERROR
                    )
                }
                .collect { messages ->
                    _messagesState.value = UiState.Success(messages)
                }
        }
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty() || _sendState.value.isLoading()) return

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
                        exception = result.exception,
                        code = result.code
                    )
                    LingShuLog.e(
                        "ChatViewModel",
                        "$stepTag [step-3] 对话失败，emit AiReplyError: code=${result.code}",
                        result.exception
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

    override fun onCleared() {
        super.onCleared()
        ttsEngine.release()
    }
}

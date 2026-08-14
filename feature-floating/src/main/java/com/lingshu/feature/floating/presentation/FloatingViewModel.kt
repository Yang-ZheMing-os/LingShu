package com.lingshu.feature.floating.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.event.FloatingSize
import com.lingshu.core.common.event.FloatingState
import com.lingshu.core.common.event.IChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FloatingViewModel @Inject constructor(
    private val chatRepository: IChatRepository
) : ViewModel() {

    private val _state = MutableStateFlow(FloatingState.IDLE)
    val state: StateFlow<FloatingState> = _state.asStateFlow()

    private val _size = MutableStateFlow(FloatingSize.MEDIUM)
    val size: StateFlow<FloatingSize> = _size.asStateFlow()

    private val _opacity = MutableStateFlow(1.0f)
    val opacity: StateFlow<Float> = _opacity.asStateFlow()

    /** AI 流式回复内容（逐 token 更新），空串表示无回复 */
    private val _streamingReply = MutableStateFlow("")
    val streamingReply: StateFlow<String> = _streamingReply.asStateFlow()

    /** 是否正在发送/等待回复 */
    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    fun updateState(newState: FloatingState) {
        _state.value = newState
        LingShuLog.d("FloatingViewModel", "State updated: $newState")
    }

    fun setSize(newSize: FloatingSize) {
        _size.value = newSize
        LingShuLog.d("FloatingViewModel", "Size updated: $newSize")
    }

    fun setOpacity(newOpacity: Float) {
        val clamped = newOpacity.coerceIn(0.3f, 1.0f)
        _opacity.value = clamped
        LingShuLog.d("FloatingViewModel", "Opacity updated: $clamped")
    }

    /**
     * 发送消息到 ChatRepository，使用流式输出逐 token 更新 [streamingReply]。
     * 状态流转：IDLE → THINKING（等待首个 token）→ EXECUTING（流式输出中）→ IDLE
     */
    fun sendMessage(message: String) {
        if (message.isBlank() || _isSending.value) return

        LingShuLog.i("FloatingViewModel", "发送消息: ${message.take(80)}")

        viewModelScope.launch {
            _isSending.value = true
            _streamingReply.value = ""
            updateState(FloatingState.THINKING)

            val accumulated = StringBuilder()
            val traceId = "floating_${System.currentTimeMillis()}"

            try {
                when (val result = chatRepository.sendMessageStream(message) { token ->
                    accumulated.append(token)
                    _streamingReply.value = accumulated.toString()
                    // 首个 token 到达后切到"说话中"状态
                    if (_state.value == FloatingState.THINKING) {
                        updateState(FloatingState.EXECUTING)
                    }
                }) {
                    is Result.Success -> {
                        _streamingReply.value = result.data.content
                        LingShuLog.i(
                            "FloatingViewModel",
                            "[$traceId] 回复成功: ${result.data.content.take(80)}"
                        )
                    }
                    is Result.Error -> {
                        // 保留已输出的流式内容，仅在没有内容时显示错误
                        if (accumulated.isEmpty()) {
                            _streamingReply.value = "发送失败：${result.message}"
                        }
                        LingShuLog.e(
                            "FloatingViewModel",
                            "[$traceId] 回复失败: code=${result.code}, msg=${result.message}",
                            result.cause
                        )
                    }
                }
            } catch (e: Exception) {
                LingShuLog.e("FloatingViewModel", "[$traceId] 发送异常", e)
                if (accumulated.isEmpty()) {
                    _streamingReply.value = "发送异常：${e.message ?: "未知错误"}"
                }
            } finally {
                _isSending.value = false
                updateState(FloatingState.IDLE)
            }
        }
    }

    /** 清空当前回复内容（关闭聊天气泡时调用） */
    fun clearReply() {
        _streamingReply.value = ""
    }
}

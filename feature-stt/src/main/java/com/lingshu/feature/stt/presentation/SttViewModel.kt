package com.lingshu.feature.stt.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.common.event.IChatRepository
import com.lingshu.core.common.event.ISttEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SttViewModel @Inject constructor(
    private val sttEngine: ISttEngine,
    private val chatRepository: IChatRepository
) : ViewModel() {

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _recognizedText = MutableStateFlow<String?>(null)
    val recognizedText: StateFlow<String?> = _recognizedText.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    fun toggleListening() {
        if (_isListening.value) {
            stopListening()
        } else {
            startListening()
        }
    }

    fun startListening() {
        if (!sttEngine.isAvailable()) {
            _errorMessage.value = ErrorCodes.getMessage(ErrorCodes.MICROPHONE_UNAVAILABLE)
            return
        }

        if (_isListening.value) return

        _isListening.value = true
        _errorMessage.value = null
        _recognizedText.value = null

        sttEngine.startListening(
            onResult = { result ->
                _isListening.value = false
                _recognizedText.value = result.text
                LingShuLog.d("SttViewModel", "识别成功: ${result.text}, 置信度: ${result.confidence}")
                sendMessage(result.text)
            },
            onError = { error ->
                _isListening.value = false
                _errorMessage.value = error
                LingShuLog.w("SttViewModel", "识别失败: $error")
            }
        )
    }

    fun stopListening() {
        if (_isListening.value) {
            sttEngine.stopListening()
            _isListening.value = false
        }
    }

    fun cancel() {
        sttEngine.cancel()
        _isListening.value = false
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun clearRecognizedText() {
        _recognizedText.value = null
    }

    private fun sendMessage(text: String) {
        if (_isSending.value) return

        viewModelScope.launch {
            _isSending.value = true

            when (val result = chatRepository.sendMessage(text)) {
                is Result.Success -> {
                    LingShuLog.d("SttViewModel", "消息发送成功")
                }
                is Result.Error -> {
                    _errorMessage.value = result.code?.let { ErrorCodes.getMessage(it) }
                        ?: ErrorCodes.getMessage(ErrorCodes.UNKNOWN_ERROR)
                    LingShuLog.e("SttViewModel", "消息发送失败: ${result.code}", result.exception)
                }
            }

            _isSending.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        sttEngine.cancel()
    }
}

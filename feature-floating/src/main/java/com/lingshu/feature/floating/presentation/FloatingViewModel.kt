package com.lingshu.feature.floating.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.common.event.FloatingSize
import com.lingshu.core.common.event.FloatingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FloatingViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(FloatingState.IDLE)
    val state: StateFlow<FloatingState> = _state.asStateFlow()

    private val _size = MutableStateFlow(FloatingSize.MEDIUM)
    val size: StateFlow<FloatingSize> = _size.asStateFlow()

    private val _opacity = MutableStateFlow(1.0f)
    val opacity: StateFlow<Float> = _opacity.asStateFlow()

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

    fun sendMessage(message: String) {
        LingShuLog.d("FloatingViewModel", "Message sent: $message")
        viewModelScope.launch {
            updateState(FloatingState.LISTENING)
        }
    }
}

package com.lingshu.feature.wakeword.presentation

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.state.UiState
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.wakeword.domain.IWakeWordEngine
import com.lingshu.feature.wakeword.domain.WakeWordEvent
import com.lingshu.feature.wakeword.service.WakeWordService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WakeWordViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wakeWordEngine: IWakeWordEngine
) : ViewModel() {

    private val _engineState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val engineState: StateFlow<UiState<Unit>> = _engineState.asStateFlow()

    private val _lastWakeWordEvent = MutableStateFlow<WakeWordEvent?>(null)
    val lastWakeWordEvent: StateFlow<WakeWordEvent?> = _lastWakeWordEvent.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val wakeWordListener: (WakeWordEvent) -> Unit = { event ->
        _lastWakeWordEvent.value = event
        LingShuLog.i("WakeWordViewModel", "收到唤醒词事件: ${event.keyword}")
    }

    init {
        if (wakeWordEngine.isRunning()) {
            _isRunning.value = true
            wakeWordEngine.registerListener(wakeWordListener)
        }
    }

    fun startService() {
        viewModelScope.launch {
            _engineState.value = UiState.Loading
            try {
                wakeWordEngine.registerListener(wakeWordListener)
                when (val result = wakeWordEngine.start()) {
                    is Result.Success -> {
                        _engineState.value = UiState.Success(Unit)
                        _isRunning.value = true
                        startForegroundService()
                        LingShuLog.d("WakeWordViewModel", "唤醒词引擎启动成功")
                    }
                    is Result.Error -> {
                        _engineState.value = UiState.Error(
                            code = result.code,
                            message = result.message
                        )
                        wakeWordEngine.unregisterListener(wakeWordListener)
                        LingShuLog.e(
                            "WakeWordViewModel",
                            "唤醒词引擎启动失败: ${result.code} - ${result.message}",
                            result.cause
                        )
                    }
                }
            } catch (e: Exception) {
                _engineState.value = UiState.Error(
                    code = ErrorCodes.UNKNOWN_ERROR,
                    message = e.message ?: "启动唤醒词异常"
                )
                LingShuLog.e("WakeWordViewModel", "启动唤醒词异常", e)
            }
        }
    }

    fun stopService() {
        viewModelScope.launch {
            try {
                wakeWordEngine.unregisterListener(wakeWordListener)
                wakeWordEngine.stop()
                _isRunning.value = false
                _engineState.value = UiState.Idle
                stopForegroundService()
                LingShuLog.d("WakeWordViewModel", "唤醒词引擎已停止")
            } catch (e: Exception) {
                LingShuLog.e("WakeWordViewModel", "停止唤醒词异常", e)
            }
        }
    }

    private fun startForegroundService() {
        try {
            val intent = Intent(context, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            LingShuLog.e("WakeWordViewModel", "启动前台服务失败", e)
        }
    }

    private fun stopForegroundService() {
        try {
            val intent = Intent(context, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_STOP
            }
            context.stopService(intent)
        } catch (e: Exception) {
            LingShuLog.e("WakeWordViewModel", "停止前台服务失败", e)
        }
    }

    fun resetEngineState() {
        _engineState.value = UiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        if (wakeWordEngine.isRunning()) {
            wakeWordEngine.unregisterListener(wakeWordListener)
        }
    }
}

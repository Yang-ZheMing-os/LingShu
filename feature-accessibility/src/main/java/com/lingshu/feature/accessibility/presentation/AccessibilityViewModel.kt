package com.lingshu.feature.accessibility.presentation

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.state.UiState
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.accessibility.domain.ControlInfo
import com.lingshu.feature.accessibility.domain.IAccessibilityControl
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccessibilityViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accessibilityControl: IAccessibilityControl
) : ViewModel() {

    private companion object {
        private const val TAG = "AccessibilityViewModel"
    }

    private val _serviceState = MutableStateFlow(false)
    val serviceState: StateFlow<Boolean> = _serviceState.asStateFlow()

    private val _actionState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val actionState: StateFlow<UiState<Unit>> = _actionState.asStateFlow()

    private val _screenText = MutableStateFlow<String?>(null)
    val screenText: StateFlow<String?> = _screenText.asStateFlow()

    private val _foundControl = MutableStateFlow<ControlInfo?>(null)
    val foundControl: StateFlow<ControlInfo?> = _foundControl.asStateFlow()

    fun checkServiceStatus() {
        viewModelScope.launch {
            _serviceState.value = accessibilityControl.isServiceRunning()
        }
    }

    fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            LingShuLog.d(TAG, "跳转到无障碍设置页面")
        } catch (e: Exception) {
            LingShuLog.e(TAG, "跳转无障碍设置失败", e)
        }
    }

    fun tap(x: Int, y: Int) {
        viewModelScope.launch {
            _actionState.value = UiState.Loading
            when (val result = accessibilityControl.tap(x, y)) {
                is Result.Success -> {
                    _actionState.value = UiState.Success(Unit)
                    LingShuLog.d(TAG, "点击成功: ($x, $y)")
                }
                is Result.Error -> {
                    _actionState.value = UiState.Error(
                        exception = result.exception,
                        code = result.code ?: ErrorCodes.UNKNOWN_ERROR
                    )
                    LingShuLog.e(TAG, "点击失败: ${result.code}", result.exception)
                }
            }
        }
    }

    fun tapByText(text: String) {
        viewModelScope.launch {
            _actionState.value = UiState.Loading
            when (val result = accessibilityControl.tapByText(text)) {
                is Result.Success -> {
                    _actionState.value = UiState.Success(Unit)
                    LingShuLog.d(TAG, "按文本点击成功: $text")
                }
                is Result.Error -> {
                    _actionState.value = UiState.Error(
                        exception = result.exception,
                        code = result.code ?: ErrorCodes.UNKNOWN_ERROR
                    )
                    LingShuLog.e(TAG, "按文本点击失败: ${result.code}", result.exception)
                }
            }
        }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 300) {
        viewModelScope.launch {
            _actionState.value = UiState.Loading
            when (val result = accessibilityControl.swipe(x1, y1, x2, y2, duration)) {
                is Result.Success -> {
                    _actionState.value = UiState.Success(Unit)
                    LingShuLog.d(TAG, "滑动成功: ($x1,$y1) -> ($x2,$y2)")
                }
                is Result.Error -> {
                    _actionState.value = UiState.Error(
                        exception = result.exception,
                        code = result.code ?: ErrorCodes.UNKNOWN_ERROR
                    )
                    LingShuLog.e(TAG, "滑动失败: ${result.code}", result.exception)
                }
            }
        }
    }

    fun inputText(text: String) {
        viewModelScope.launch {
            _actionState.value = UiState.Loading
            when (val result = accessibilityControl.inputText(text)) {
                is Result.Success -> {
                    _actionState.value = UiState.Success(Unit)
                    LingShuLog.d(TAG, "输入文本成功")
                }
                is Result.Error -> {
                    _actionState.value = UiState.Error(
                        exception = result.exception,
                        code = result.code ?: ErrorCodes.UNKNOWN_ERROR
                    )
                    LingShuLog.e(TAG, "输入文本失败: ${result.code}", result.exception)
                }
            }
        }
    }

    fun pressBack() {
        viewModelScope.launch {
            _actionState.value = UiState.Loading
            when (val result = accessibilityControl.pressBack()) {
                is Result.Success -> {
                    _actionState.value = UiState.Success(Unit)
                    LingShuLog.d(TAG, "返回键成功")
                }
                is Result.Error -> {
                    _actionState.value = UiState.Error(
                        exception = result.exception,
                        code = result.code ?: ErrorCodes.UNKNOWN_ERROR
                    )
                    LingShuLog.e(TAG, "返回键失败: ${result.code}", result.exception)
                }
            }
        }
    }

    fun pressHome() {
        viewModelScope.launch {
            _actionState.value = UiState.Loading
            when (val result = accessibilityControl.pressHome()) {
                is Result.Success -> {
                    _actionState.value = UiState.Success(Unit)
                    LingShuLog.d(TAG, "Home键成功")
                }
                is Result.Error -> {
                    _actionState.value = UiState.Error(
                        exception = result.exception,
                        code = result.code ?: ErrorCodes.UNKNOWN_ERROR
                    )
                    LingShuLog.e(TAG, "Home键失败: ${result.code}", result.exception)
                }
            }
        }
    }

    fun getScreenText() {
        viewModelScope.launch {
            _actionState.value = UiState.Loading
            when (val result = accessibilityControl.getScreenText()) {
                is Result.Success -> {
                    _screenText.value = result.data
                    _actionState.value = UiState.Success(Unit)
                    LingShuLog.d(TAG, "获取屏幕文本成功")
                }
                is Result.Error -> {
                    _actionState.value = UiState.Error(
                        exception = result.exception,
                        code = result.code ?: ErrorCodes.UNKNOWN_ERROR
                    )
                    LingShuLog.e(TAG, "获取屏幕文本失败: ${result.code}", result.exception)
                }
            }
        }
    }

    fun findControlByText(text: String) {
        viewModelScope.launch {
            _actionState.value = UiState.Loading
            when (val result = accessibilityControl.findControlByText(text)) {
                is Result.Success -> {
                    _foundControl.value = result.data
                    _actionState.value = UiState.Success(Unit)
                    LingShuLog.d(TAG, "查找控件成功: $text")
                }
                is Result.Error -> {
                    _actionState.value = UiState.Error(
                        exception = result.exception,
                        code = result.code ?: ErrorCodes.UNKNOWN_ERROR
                    )
                    LingShuLog.e(TAG, "查找控件失败: ${result.code}", result.exception)
                }
            }
        }
    }

    fun findControlById(id: String) {
        viewModelScope.launch {
            _actionState.value = UiState.Loading
            when (val result = accessibilityControl.findControlById(id)) {
                is Result.Success -> {
                    _foundControl.value = result.data
                    _actionState.value = UiState.Success(Unit)
                    LingShuLog.d(TAG, "按ID查找控件成功: $id")
                }
                is Result.Error -> {
                    _actionState.value = UiState.Error(
                        exception = result.exception,
                        code = result.code ?: ErrorCodes.UNKNOWN_ERROR
                    )
                    LingShuLog.e(TAG, "按ID查找控件失败: ${result.code}", result.exception)
                }
            }
        }
    }

    fun resetActionState() {
        _actionState.value = UiState.Idle
    }

    fun clearScreenText() {
        _screenText.value = null
    }

    fun clearFoundControl() {
        _foundControl.value = null
    }
}

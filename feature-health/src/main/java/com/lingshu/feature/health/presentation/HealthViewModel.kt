package com.lingshu.feature.health.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.state.UiState
import com.lingshu.feature.health.domain.IHealthService
import com.lingshu.feature.health.domain.SleepData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HealthViewModel @Inject constructor(
    private val healthService: IHealthService
) : ViewModel() {

    // 设备是否具备可检测的健康传感器（无传感器时 UI 显示“未连接可检测设备”）
    private val _isDeviceSupported = MutableStateFlow(false)
    val isDeviceSupported: StateFlow<Boolean> = _isDeviceSupported.asStateFlow()

    private val _heartRate = MutableStateFlow<UiState<Int>>(UiState.Idle)
    val heartRate: StateFlow<UiState<Int>> = _heartRate.asStateFlow()

    private val _steps = MutableStateFlow<UiState<Int>>(UiState.Idle)
    val steps: StateFlow<UiState<Int>> = _steps.asStateFlow()

    private val _sleepData = MutableStateFlow<UiState<SleepData>>(UiState.Idle)
    val sleepData: StateFlow<UiState<SleepData>> = _sleepData.asStateFlow()

    private val _oxygen = MutableStateFlow<UiState<Float>>(UiState.Idle)
    val oxygen: StateFlow<UiState<Float>> = _oxygen.asStateFlow()

    private val _stressLevel = MutableStateFlow<UiState<Float>>(UiState.Idle)
    val stressLevel: StateFlow<UiState<Float>> = _stressLevel.asStateFlow()

    private val _hasPermissions = MutableStateFlow(false)
    val hasPermissions: StateFlow<Boolean> = _hasPermissions.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        refreshDeviceAndPermissionState()
    }

    /** 重新检测设备可用性与真实权限状态 */
    fun refreshDeviceAndPermissionState() {
        _isDeviceSupported.value = healthService.isDeviceSupported()
        _hasPermissions.value = healthService.checkPermissions()
    }

    /**
     * 界面 ON_RESUME 时调用：
     * 重新读取真实授权状态，并在已授权但未加载时自动拉取真实数据。
     */
    fun onResumeCheck() {
        refreshDeviceAndPermissionState()
        if (_hasPermissions.value && _steps.value is UiState.Idle) {
            loadAllData()
        }
    }

    fun requestPermissions() {
        viewModelScope.launch {
            // 跳转系统设置，由界面 ON_RESUME 重新检测真实授权状态并加载数据
            healthService.requestPermissions()
        }
    }

    fun loadAllData() {
        viewModelScope.launch {
            _isRefreshing.value = true
            loadHeartRate()
            loadSteps()
            loadSleepData()
            loadOxygen()
            loadStressLevel()
            _isRefreshing.value = false
        }
    }

    fun loadHeartRate() {
        viewModelScope.launch {
            _heartRate.value = UiState.Loading
            val result = healthService.getHeartRate()
            when (result) {
                is Result.Success -> {
                    _heartRate.value = UiState.Success(result.data)
                }
                is Result.Error -> {
                    _heartRate.value = UiState.Error(result.code, result.message)
                }
            }
        }
    }

    fun loadSteps() {
        viewModelScope.launch {
            _steps.value = UiState.Loading
            val result = healthService.getSteps()
            when (result) {
                is Result.Success -> {
                    _steps.value = UiState.Success(result.data)
                }
                is Result.Error -> {
                    _steps.value = UiState.Error(result.code, result.message)
                }
            }
        }
    }

    fun loadSleepData() {
        viewModelScope.launch {
            _sleepData.value = UiState.Loading
            val result = healthService.getSleep()
            when (result) {
                is Result.Success -> {
                    _sleepData.value = UiState.Success(result.data)
                }
                is Result.Error -> {
                    _sleepData.value = UiState.Error(result.code, result.message)
                }
            }
        }
    }

    fun loadOxygen() {
        viewModelScope.launch {
            _oxygen.value = UiState.Loading
            val result = healthService.getOxygen()
            when (result) {
                is Result.Success -> {
                    _oxygen.value = UiState.Success(result.data)
                }
                is Result.Error -> {
                    _oxygen.value = UiState.Error(result.code, result.message)
                }
            }
        }
    }

    fun loadStressLevel() {
        viewModelScope.launch {
            _stressLevel.value = UiState.Loading
            val result = healthService.getStressLevel()
            when (result) {
                is Result.Success -> {
                    _stressLevel.value = UiState.Success(result.data)
                }
                is Result.Error -> {
                    _stressLevel.value = UiState.Error(result.code, result.message)
                }
            }
        }
    }
}

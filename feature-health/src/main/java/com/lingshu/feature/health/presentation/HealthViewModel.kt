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
        checkPermissions()
    }

    private fun checkPermissions() {
        _hasPermissions.value = healthService.checkPermissions()
    }

    fun requestPermissions() {
        viewModelScope.launch {
            val result = healthService.requestPermissions()
            if (result.isSuccess()) {
                _hasPermissions.value = true
                loadAllData()
            }
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
                    _heartRate.value = UiState.Error(result.exception, result.code)
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
                    _steps.value = UiState.Error(result.exception, result.code)
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
                    _sleepData.value = UiState.Error(result.exception, result.code)
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
                    _oxygen.value = UiState.Error(result.exception, result.code)
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
                    _stressLevel.value = UiState.Error(result.exception, result.code)
                }
            }
        }
    }
}

package com.lingshu.feature.proactive.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.feature.proactive.domain.IProactiveService
import com.lingshu.feature.proactive.domain.ProactiveConfig
import com.lingshu.feature.proactive.domain.ProactiveStatus
import com.lingshu.feature.proactive.domain.QuietHours
import com.lingshu.feature.proactive.domain.TriggerType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProactiveViewModel @Inject constructor(
    private val proactiveService: IProactiveService
) : ViewModel() {

    private val _config = MutableStateFlow(ProactiveConfig())
    val config: StateFlow<ProactiveConfig> = _config.asStateFlow()

    private val _status = MutableStateFlow(ProactiveStatus())
    val status: StateFlow<ProactiveStatus> = _status.asStateFlow()

    init {
        viewModelScope.launch {
            _config.value = proactiveService.getConfig()
            _status.value = proactiveService.getStatus()
        }
    }

    fun toggleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val newConfig = _config.value.copy(enabled = enabled)
            _config.value = newConfig
            proactiveService.configure(newConfig)
            if (enabled) {
                proactiveService.start()
            } else {
                proactiveService.stop()
            }
            _status.value = proactiveService.getStatus()
        }
    }

    fun toggleTrigger(triggerType: TriggerType, enabled: Boolean) {
        viewModelScope.launch {
            val newTriggers = _config.value.triggers.toMutableMap()
            newTriggers[triggerType] = enabled
            val newConfig = _config.value.copy(triggers = newTriggers)
            _config.value = newConfig
            proactiveService.configure(newConfig)
        }
    }

    fun updateCooldownMinutes(minutes: Int) {
        viewModelScope.launch {
            val newConfig = _config.value.copy(cooldownMinutes = minutes)
            _config.value = newConfig
            proactiveService.configure(newConfig)
        }
    }

    fun updateMaxPerDay(max: Int) {
        viewModelScope.launch {
            val newConfig = _config.value.copy(maxPerDay = max)
            _config.value = newConfig
            proactiveService.configure(newConfig)
        }
    }

    fun updateQuietHours(quietHours: QuietHours) {
        viewModelScope.launch {
            val newConfig = _config.value.copy(quietHours = quietHours)
            _config.value = newConfig
            proactiveService.configure(newConfig)
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            _status.value = proactiveService.getStatus()
        }
    }

    fun triggerTestNotification() {
        viewModelScope.launch {
            proactiveService.checkAndNotify()
            _status.value = proactiveService.getStatus()
        }
    }
}

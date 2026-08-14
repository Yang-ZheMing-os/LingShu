package com.lingshu.feature.proactive.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.proactive.domain.IProactiveService
import com.lingshu.feature.proactive.domain.ProactiveConfig
import com.lingshu.feature.proactive.domain.ProactiveStatus
import com.lingshu.feature.proactive.domain.QuietHours
import com.lingshu.feature.proactive.domain.TriggerType
import com.lingshu.feature.proactive.worker.ProactiveCheckWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProactiveViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
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

    /**
     * 立刻触发测试通知（设置页「发送测试通知」按钮）
     * 执行顺序：
     * 1) sendTestNotificationNow() → 绕过 ALL 检查秒出一条睡前通知，验证权限、渠道、通知栏展示（用户最在意这步）
     * 2) checkAndNotify() → 按真实过滤规则跑一次，日志里能看到每一关过没过
     * 3) fireNow + WorkManager 链 → 验证 Worker 注入没崩、周期性调度正常
     */
    fun triggerTestNotification() {
        viewModelScope.launch {
            LingShuLog.i("ProactiveVM", "用户点击「发送测试通知」")
            // Step 1: 先秒出一条让用户看到（绕开所有过滤）
            val r1 = proactiveService.sendTestNotificationNow()
            LingShuLog.i("ProactiveVM", "sendTestNotificationNow result=$r1")
            // Step 2: 真实链路走一遍，方便 logcat 里看每一关
            val r2 = proactiveService.checkAndNotify()
            LingShuLog.i("ProactiveVM", "checkAndNotify result=$r2")
            // Step 3: WorkManager 一次性触发，验证 worker 侧 Hilt 注入没崩
            ProactiveCheckWorker.fireNow(appContext)
            _status.value = proactiveService.getStatus()
        }
    }
}

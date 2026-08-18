package com.lingshu.feature.proactive.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.proactive.domain.CheckStep
import com.lingshu.feature.proactive.domain.IProactiveService
import com.lingshu.feature.proactive.domain.ProactiveConfig
import com.lingshu.feature.proactive.domain.ProactiveDiagnostics
import com.lingshu.feature.proactive.domain.ProactiveStatus
import com.lingshu.feature.proactive.domain.QuietHours
import com.lingshu.feature.proactive.domain.TriggerHitResult
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

    /** 诊断结果；null = 尚未运行诊断 */
    private val _diagnostics = MutableStateFlow<ProactiveDiagnostics?>(null)
    val diagnostics: StateFlow<ProactiveDiagnostics?> = _diagnostics.asStateFlow()

    private val _diagnosticsRunning = MutableStateFlow(false)
    val diagnosticsRunning: StateFlow<Boolean> = _diagnosticsRunning.asStateFlow()

    init {
        viewModelScope.launch {
            _config.value = proactiveService.getConfig()
            _status.value = proactiveService.getStatus()
            // 首次进入主动关怀页自动跑一次诊断，让用户一进来就看见当前状态
            runDiagnosticsInternal()
        }
    }

    fun runDiagnostics() {
        viewModelScope.launch { runDiagnosticsInternal() }
    }

    private suspend fun runDiagnosticsInternal() {
        _diagnosticsRunning.value = true
        try {
            // 执行前刷新一次 status，让今日数/上次时间同步最新
            _status.value = proactiveService.getStatus()
            _diagnostics.value = proactiveService.runDiagnostics()
        } catch (e: Exception) {
            LingShuLog.e("ProactiveVM", "runDiagnostics 异常", e)
        } finally {
            _diagnosticsRunning.value = false
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
            runDiagnosticsInternal()
        }
    }

    fun toggleTrigger(triggerType: TriggerType, enabled: Boolean) {
        viewModelScope.launch {
            val newTriggers = _config.value.triggers.toMutableMap()
            newTriggers[triggerType] = enabled
            val newConfig = _config.value.copy(triggers = newTriggers)
            _config.value = newConfig
            proactiveService.configure(newConfig)
            runDiagnosticsInternal()
        }
    }

    fun updateCooldownMinutes(minutes: Int) {
        viewModelScope.launch {
            val newConfig = _config.value.copy(cooldownMinutes = minutes)
            _config.value = newConfig
            proactiveService.configure(newConfig)
            runDiagnosticsInternal()
        }
    }

    fun updateMaxPerDay(max: Int) {
        viewModelScope.launch {
            val newConfig = _config.value.copy(maxPerDay = max)
            _config.value = newConfig
            proactiveService.configure(newConfig)
            runDiagnosticsInternal()
        }
    }

    fun updateQuietHours(quietHours: QuietHours) {
        viewModelScope.launch {
            val newConfig = _config.value.copy(quietHours = quietHours)
            _config.value = newConfig
            proactiveService.configure(newConfig)
            runDiagnosticsInternal()
        }
    }

    fun updateQWeatherKey(key: String) {
        viewModelScope.launch {
            val newConfig = _config.value.copy(qWeatherKey = key)
            _config.value = newConfig
            proactiveService.configure(newConfig)
        }
    }

    fun updateQWeatherLocation(location: String) {
        viewModelScope.launch {
            val newConfig = _config.value.copy(qWeatherLocation = location.ifBlank { "auto_ip" })
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

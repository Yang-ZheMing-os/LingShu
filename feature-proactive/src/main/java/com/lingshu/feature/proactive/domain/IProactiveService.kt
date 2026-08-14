package com.lingshu.feature.proactive.domain

import com.lingshu.core.common.error.Result

interface IProactiveService {
    suspend fun start()
    suspend fun stop()
    suspend fun configure(config: ProactiveConfig)
    suspend fun getConfig(): ProactiveConfig
    suspend fun getStatus(): ProactiveStatus
    suspend fun checkAndNotify(): Result<Unit>

    /**
     * 测试用：强制立刻发送一条通知（绕过开关/静音/冷却/时间窗口/触发器命中），
     * 用于用户在设置页点「发送测试通知」按钮时秒出通知，验证权限/渠道/通知栏展示是否正常。
     */
    suspend fun sendTestNotificationNow(): Result<Unit>
}

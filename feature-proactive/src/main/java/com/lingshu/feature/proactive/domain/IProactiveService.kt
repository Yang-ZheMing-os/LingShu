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

    /**
     * 运行主动关怀全链路诊断（不发送通知），返回每一关的详细通过/失败情况，
     * 给设置页「诊断卡片」展示，解决用户"开关打开了也没推送"但不知道卡在哪一步的问题。
     */
    suspend fun runDiagnostics(): ProactiveDiagnostics
}

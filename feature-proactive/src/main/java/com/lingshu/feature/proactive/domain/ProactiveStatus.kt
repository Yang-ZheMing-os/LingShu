package com.lingshu.feature.proactive.domain

data class ProactiveStatus(
    val isRunning: Boolean = false,
    val todayNotificationCount: Int = 0,
    val lastTriggerTime: Long = 0L,
    val lastTriggerType: TriggerType? = null
)

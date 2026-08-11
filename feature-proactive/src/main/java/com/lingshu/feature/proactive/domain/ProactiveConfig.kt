package com.lingshu.feature.proactive.domain

data class ProactiveConfig(
    val enabled: Boolean = false,
    val triggers: Map<TriggerType, Boolean> = TriggerType.values().associateWith { true },
    val cooldownMinutes: Int = 60,
    val maxPerDay: Int = 5,
    val quietHours: QuietHours = QuietHours()
)

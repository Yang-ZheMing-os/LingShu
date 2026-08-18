package com.lingshu.feature.proactive.domain

data class ProactiveConfig(
    val enabled: Boolean = false,
    val triggers: Map<TriggerType, Boolean> = TriggerType.values().associateWith { true },
    val cooldownMinutes: Int = 60,
    val maxPerDay: Int = 5,
    val quietHours: QuietHours = QuietHours(),
    val randomTriggerProbability: Float = 0.05f,
    /** 和风天气 Web API Key，为空则雨天提醒恒不命中 */
    val qWeatherKey: String = "",
    /** 和风天气用户所在城市 ID 或中文城市名，留空则 fallback 为 IP 定位 (auto_ip) */
    val qWeatherLocation: String = "auto_ip"
)

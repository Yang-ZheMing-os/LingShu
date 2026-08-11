package com.lingshu.feature.proactive.domain

data class QuietHours(
    val startHour: Int = 22,
    val startMinute: Int = 0,
    val endHour: Int = 7,
    val endMinute: Int = 0
)

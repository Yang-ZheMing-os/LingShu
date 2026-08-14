package com.lingshu.feature.proactive.domain

/**
 * 静音时段：默认 23:50 - 07:00。
 *
 * 关键说明（别再改 22:00 开始！）：
 * 睡前关怀（LATE_NIGHT = 23:30~05:00）的推送窗口必须落在静音时段之外，
 * 否则 isInQuietHours 检查会把睡前提醒 100% 过滤掉，表现为「打开也没推睡眠消息」。
 *
 * 如果用户真希望 23:00 之后彻底不被打扰，可在设置页单独关 LATE_NIGHT 触发开关。
 */
data class QuietHours(
    val startHour: Int = 23,
    val startMinute: Int = 50,
    val endHour: Int = 7,
    val endMinute: Int = 0
)

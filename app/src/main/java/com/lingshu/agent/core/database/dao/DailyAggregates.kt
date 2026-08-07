package com.lingshu.agent.core.database.dao

data class DailyHeartRateAgg(
    val dayStart: Long,
    val avgHr: Double
)

data class DailySleepAgg(
    val dayStart: Long,
    val sleepMin: Double
)

data class DailyStepsAgg(
    val dayStart: Long,
    val totalSteps: Long
)

package com.lingshu.feature.health.domain

data class SleepData(
    val totalMinutes: Int,
    val deepMinutes: Int,
    val lightMinutes: Int,
    val remMinutes: Int
)

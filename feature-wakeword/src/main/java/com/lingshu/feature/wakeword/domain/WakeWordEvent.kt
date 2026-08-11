package com.lingshu.feature.wakeword.domain

data class WakeWordEvent(
    val keyword: String,
    val timestamp: Long
)

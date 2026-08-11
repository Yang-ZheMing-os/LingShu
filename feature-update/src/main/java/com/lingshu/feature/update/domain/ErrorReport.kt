package com.lingshu.feature.update.domain

data class ErrorReport(
    val timestamp: Long,
    val deviceModel: String,
    val deviceBrand: String,
    val osVersion: String,
    val sdkVersion: Int,
    val appVersion: String,
    val appVersionCode: Int,
    val stackTrace: String,
    val crashCount: Int
)

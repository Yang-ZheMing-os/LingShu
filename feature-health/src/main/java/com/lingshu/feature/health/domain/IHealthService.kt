package com.lingshu.feature.health.domain

import com.lingshu.core.common.error.Result

interface IHealthService {
    /** 设备是否具备可检测的健康传感器（步数计数器或心率传感器等） */
    fun isDeviceSupported(): Boolean

    suspend fun getHeartRate(): Result<Int>
    suspend fun getSteps(): Result<Int>
    suspend fun getSleep(): Result<SleepData>
    suspend fun getOxygen(): Result<Float>
    suspend fun getStressLevel(): Result<Float>
    fun checkPermissions(): Boolean
    suspend fun requestPermissions(): Result<Unit>
}

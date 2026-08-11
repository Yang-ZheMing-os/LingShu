package com.lingshu.feature.health.domain

import com.lingshu.core.common.error.Result

interface IHealthService {
    suspend fun getHeartRate(): Result<Int>
    suspend fun getSteps(): Result<Int>
    suspend fun getSleep(): Result<SleepData>
    suspend fun getOxygen(): Result<Float>
    suspend fun getStressLevel(): Result<Float>
    fun checkPermissions(): Boolean
    suspend fun requestPermissions(): Result<Unit>
}

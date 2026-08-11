package com.lingshu.feature.health.data

import com.lingshu.core.common.error.Result
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.health.domain.IHealthService
import com.lingshu.feature.health.domain.SleepData
import kotlinx.coroutines.delay
import javax.inject.Inject

interface IHealthConnectService {
    suspend fun getHeartRate(): Result<Int>
    suspend fun getSteps(): Result<Int>
    suspend fun getSleep(): Result<SleepData>
    suspend fun getOxygen(): Result<Float>
    suspend fun getStressLevel(): Result<Float>
}

interface ISensorService {
    suspend fun getStepsFromSensor(): Result<Int>
    suspend fun getHeartRateFromCamera(): Result<Int>
}

class MockHealthConnectService @Inject constructor() : IHealthConnectService {
    override suspend fun getHeartRate(): Result<Int> {
        LingShuLog.d(TAG, "Mock Health Connect: 获取心率")
        delay(500)
        return Result.Success(72 + (Math.random() * 20).toInt())
    }

    override suspend fun getSteps(): Result<Int> {
        LingShuLog.d(TAG, "Mock Health Connect: 获取步数")
        delay(500)
        return Result.Success(5000 + (Math.random() * 5000).toInt())
    }

    override suspend fun getSleep(): Result<SleepData> {
        LingShuLog.d(TAG, "Mock Health Connect: 获取睡眠数据")
        delay(500)
        val totalMinutes = 420 + (Math.random() * 120).toInt()
        val deepMinutes = (totalMinutes * 0.25).toInt()
        val remMinutes = (totalMinutes * 0.2).toInt()
        val lightMinutes = totalMinutes - deepMinutes - remMinutes
        return Result.Success(
            SleepData(
                totalMinutes = totalMinutes,
                deepMinutes = deepMinutes,
                lightMinutes = lightMinutes,
                remMinutes = remMinutes
            )
        )
    }

    override suspend fun getOxygen(): Result<Float> {
        LingShuLog.d(TAG, "Mock Health Connect: 获取血氧")
        delay(500)
        return Result.Success(95.0f + (Math.random() * 5.0f).toFloat())
    }

    override suspend fun getStressLevel(): Result<Float> {
        LingShuLog.d(TAG, "Mock Health Connect: 获取压力水平")
        delay(500)
        return Result.Success(20.0f + (Math.random() * 60.0f).toFloat())
    }

    companion object {
        private const val TAG = "MockHealthConnect"
    }
}

class MockSensorService @Inject constructor() : ISensorService {
    override suspend fun getStepsFromSensor(): Result<Int> {
        LingShuLog.d(TAG, "Mock Sensor: 获取步数 (SensorManager)")
        delay(300)
        return Result.Success(3000 + (Math.random() * 2000).toInt())
    }

    override suspend fun getHeartRateFromCamera(): Result<Int> {
        LingShuLog.d(TAG, "Mock Sensor: 获取心率 (摄像头闪光灯)")
        delay(1000)
        return Result.Success(70 + (Math.random() * 15).toInt())
    }

    companion object {
        private const val TAG = "MockSensorService"
    }
}

class HealthServiceImpl @Inject constructor(
    private val healthConnectService: IHealthConnectService,
    private val sensorService: ISensorService
) : IHealthService {

    private var hasPermissions = false
    private var useHealthConnect = true

    override suspend fun getHeartRate(): Result<Int> {
        return try {
            LingShuLog.d(TAG, "获取心率")
            if (!hasPermissions) {
                return Result.Error(ErrorCodes.PERMISSION_DENIED, "权限未授予")
            }

            if (useHealthConnect) {
                healthConnectService.getHeartRate()
            } else {
                sensorService.getHeartRateFromCamera()
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "获取心率失败", e)
            Result.Error(ErrorCodes.UNKNOWN_ERROR, e.message ?: "未知错误", e)
        }
    }

    override suspend fun getSteps(): Result<Int> {
        return try {
            LingShuLog.d(TAG, "获取步数")
            if (!hasPermissions) {
                return Result.Error(ErrorCodes.PERMISSION_DENIED, "权限未授予")
            }

            if (useHealthConnect) {
                healthConnectService.getSteps()
            } else {
                sensorService.getStepsFromSensor()
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "获取步数失败", e)
            Result.Error(ErrorCodes.UNKNOWN_ERROR, e.message ?: "未知错误", e)
        }
    }

    override suspend fun getSleep(): Result<SleepData> {
        return try {
            LingShuLog.d(TAG, "获取睡眠数据")
            if (!hasPermissions) {
                return Result.Error(ErrorCodes.PERMISSION_DENIED, "权限未授予")
            }

            if (useHealthConnect) {
                healthConnectService.getSleep()
            } else {
                Result.Error(ErrorCodes.UNKNOWN_ERROR, "降级方案不支持睡眠数据")
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "获取睡眠数据失败", e)
            Result.Error(ErrorCodes.UNKNOWN_ERROR, e.message ?: "未知错误", e)
        }
    }

    override suspend fun getOxygen(): Result<Float> {
        return try {
            LingShuLog.d(TAG, "获取血氧")
            if (!hasPermissions) {
                return Result.Error(ErrorCodes.PERMISSION_DENIED, "权限未授予")
            }

            if (useHealthConnect) {
                healthConnectService.getOxygen()
            } else {
                Result.Error(ErrorCodes.UNKNOWN_ERROR, "降级方案不支持血氧数据")
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "获取血氧失败", e)
            Result.Error(ErrorCodes.UNKNOWN_ERROR, e.message ?: "未知错误", e)
        }
    }

    override suspend fun getStressLevel(): Result<Float> {
        return try {
            LingShuLog.d(TAG, "获取压力水平")
            if (!hasPermissions) {
                return Result.Error(ErrorCodes.PERMISSION_DENIED, "权限未授予")
            }

            if (useHealthConnect) {
                healthConnectService.getStressLevel()
            } else {
                Result.Error(ErrorCodes.UNKNOWN_ERROR, "降级方案不支持压力数据")
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "获取压力水平失败", e)
            Result.Error(ErrorCodes.UNKNOWN_ERROR, e.message ?: "未知错误", e)
        }
    }

    override fun checkPermissions(): Boolean {
        return hasPermissions
    }

    override suspend fun requestPermissions(): Result<Unit> {
        return try {
            LingShuLog.d(TAG, "请求健康数据权限")
            delay(1000)
            hasPermissions = true
            Result.Success(Unit)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "请求权限失败", e)
            Result.Error(ErrorCodes.PERMISSION_DENIED, e.message ?: "未知错误", e)
        }
    }

    companion object {
        private const val TAG = "HealthService"
    }
}

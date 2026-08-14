package com.lingshu.feature.health.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.provider.Settings
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.health.domain.IHealthService
import com.lingshu.feature.health.domain.SleepData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * 健康服务真实实现。
 *
 * 不依赖 Health Connect，直接通过 Android 框架的 SensorManager 读取设备真实传感器数据：
 * - 步数：Sensor.TYPE_STEP_COUNTER（真机通常存在，模拟器无）
 * - 心率：Sensor.TYPE_HEART_RATE（多数手机无此传感器，显示“不支持”）
 * - 睡眠/血氧/压力：Android 无对应标准传感器，统一返回“该设备不支持”
 *
 * 权限：真实检查并请求 android.permission.BODY_SENSORS。
 */
class HealthServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : IHealthService {

    // 通过系统服务获取传感器管理器（Android 框架自带，无需额外依赖）
    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    // 步数计数器：返回开机以来累计步数（真机有，模拟器无）
    private val stepCounterSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    // 心率传感器：仅部分穿戴/旗舰设备具备，多数手机无
    private val heartRateSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)

    /** 设备是否具备可检测的健康传感器 */
    override fun isDeviceSupported(): Boolean {
        val supported = stepCounterSensor != null || heartRateSensor != null
        LingShuLog.d(
            TAG,
            "设备传感器可用性: supported=$supported, step=${stepCounterSensor != null}, hr=${heartRateSensor != null}"
        )
        return supported
    }

    /** 真实检查 BODY_SENSORS 权限是否已授予 */
    override fun checkPermissions(): Boolean {
        val granted = context.checkSelfPermission(Manifest.permission.BODY_SENSORS) ==
                PackageManager.PERMISSION_GRANTED
        LingShuLog.d(TAG, "BODY_SENSORS 权限状态: granted=$granted")
        return granted
    }

    /**
     * 发起真实权限请求。
     * 本服务持有 ApplicationContext，无法直接弹出运行时权限对话框，
     * 因此跳转到应用详情页，引导用户在系统中授予 BODY_SENSORS 权限。
     * 界面在 ON_RESUME 时会重新读取真实授权状态。
     */
    override suspend fun requestPermissions(): Result<Unit> {
        return try {
            LingShuLog.d(TAG, "请求 BODY_SENSORS 权限：跳转应用详情页")
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Result.Success(Unit)
        } catch (e: Exception) {
            LingShuLog.e(TAG, "跳转权限设置失败", e)
            Result.Error(ErrorCodes.PERMISSION_DENIED, e.message ?: "无法打开权限设置", e)
        }
    }

    override suspend fun getSteps(): Result<Int> {
        return try {
            LingShuLog.d(TAG, "获取步数")
            if (!checkPermissions()) {
                return Result.Error(ErrorCodes.PERMISSION_DENIED, "权限未授予")
            }
            val sensor = stepCounterSensor
                ?: return Result.Error(ErrorCodes.UNKNOWN_ERROR, "该设备不支持步数传感器")
            // TYPE_STEP_COUNTER 返回开机以来累计步数，读取一次真实值
            readSensorOnce(sensor)?.let { steps ->
                LingShuLog.d(TAG, "步数读取成功: $steps")
                Result.Success(steps.toInt())
            } ?: run {
                LingShuLog.d(TAG, "步数传感器无数据返回")
                Result.Error(ErrorCodes.UNKNOWN_ERROR, "暂无数据")
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "获取步数失败", e)
            Result.Error(ErrorCodes.UNKNOWN_ERROR, e.message ?: "未知错误", e)
        }
    }

    override suspend fun getHeartRate(): Result<Int> {
        return try {
            LingShuLog.d(TAG, "获取心率")
            if (!checkPermissions()) {
                return Result.Error(ErrorCodes.PERMISSION_DENIED, "权限未授予")
            }
            val sensor = heartRateSensor
                ?: return Result.Error(ErrorCodes.UNKNOWN_ERROR, "该设备不支持心率传感器")
            readSensorOnce(sensor)?.let { bpm ->
                LingShuLog.d(TAG, "心率读取成功: $bpm")
                Result.Success(bpm.toInt())
            } ?: run {
                LingShuLog.d(TAG, "心率传感器无数据返回")
                Result.Error(ErrorCodes.UNKNOWN_ERROR, "暂无数据")
            }
        } catch (e: Exception) {
            LingShuLog.e(TAG, "获取心率失败", e)
            Result.Error(ErrorCodes.UNKNOWN_ERROR, e.message ?: "未知错误", e)
        }
    }

    // 睡眠/血氧/压力：Android 无标准传感器，真实情况下无法获取
    override suspend fun getSleep(): Result<SleepData> {
        LingShuLog.d(TAG, "获取睡眠数据：设备无对应传感器")
        return Result.Error(ErrorCodes.UNKNOWN_ERROR, "该设备不支持睡眠监测")
    }

    override suspend fun getOxygen(): Result<Float> {
        LingShuLog.d(TAG, "获取血氧：设备无对应传感器")
        return Result.Error(ErrorCodes.UNKNOWN_ERROR, "该设备不支持血氧监测")
    }

    override suspend fun getStressLevel(): Result<Float> {
        LingShuLog.d(TAG, "获取压力：设备无对应传感器")
        return Result.Error(ErrorCodes.UNKNOWN_ERROR, "该设备不支持压力监测")
    }

    /**
     * 注册一次传感器监听，拿到首个事件即返回其第一个浮点值。
     * 使用 withTimeoutOrNull 防止永久挂起；超时返回 null（视为“暂无数据”）。
     */
    private suspend fun readSensorOnce(sensor: Sensor): Float? {
        val manager = sensorManager ?: return null
        return withTimeoutOrNull(SENSOR_READ_TIMEOUT_MS) {
            suspendCancellableCoroutine<Float?> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val value = event.values.firstOrNull()
                        // 拿到数据后立即取消监听
                        manager.unregisterListener(this)
                        if (cont.isActive) cont.resume(value)
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                cont.invokeOnCancellation { manager.unregisterListener(listener) }
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    companion object {
        private const val TAG = "HealthService"
        // 单次传感器读取超时时间（毫秒）
        private const val SENSOR_READ_TIMEOUT_MS = 3000L
    }
}

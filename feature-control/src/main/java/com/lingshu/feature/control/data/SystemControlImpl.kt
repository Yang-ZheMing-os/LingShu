package com.lingshu.feature.control.data

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.control.domain.ISystemControl
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemControlImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ISystemControl {

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val contentResolver by lazy { context.contentResolver }

    override suspend fun setWifi(on: Boolean): Result<Unit> = withSystemControlTimeout(
        action = "WiFi",
        timeoutMs = SYSTEM_CONTROL_TIMEOUT_MS
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val panelIntent = Intent(Settings.Panel.ACTION_WIFI).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(panelIntent)
            Result.success(Unit)
        } else {
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = on
            Result.success(Unit)
        }
    }

    override suspend fun setBluetooth(on: Boolean): Result<Unit> = withSystemControlTimeout(
        action = "蓝牙",
        timeoutMs = SYSTEM_CONTROL_TIMEOUT_MS
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val panelIntent = Intent(Settings.Panel.ACTION_VOLUME).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(panelIntent)
            Result.success(Unit)
        } else {
            @Suppress("DEPRECATION")
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as android.bluetooth.BluetoothManager
            @Suppress("DEPRECATION")
            val bluetoothAdapter = bluetoothManager.adapter
            if (on) bluetoothAdapter.enable() else bluetoothAdapter.disable()
            Result.success(Unit)
        }
    }

    override suspend fun setFlashlight(on: Boolean): Result<Unit> = withSystemControlTimeout(
        action = "手电筒",
        timeoutMs = SYSTEM_CONTROL_TIMEOUT_MS
    ) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, on)
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.e("SystemControl", "手电筒控制失败", e)
            Result.error(
                code = ErrorCodes.UNKNOWN_ERROR,
                message = "手电筒控制失败",
                cause = e
            )
        }
    }

    override suspend fun setVolume(level: Int): Result<Unit> = withSystemControlTimeout(
        action = "音量",
        timeoutMs = SYSTEM_CONTROL_TIMEOUT_MS
    ) {
        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = (level / 100f * maxVolume).toInt().coerceIn(0, maxVolume)
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                targetVolume,
                AudioManager.FLAG_SHOW_UI
            )
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.e("SystemControl", "音量调节失败", e)
            Result.error(
                code = ErrorCodes.UNKNOWN_ERROR,
                message = "音量调节失败",
                cause = e
            )
        }
    }

    override suspend fun setBrightness(level: Int): Result<Unit> = withSystemControlTimeout(
        action = "亮度",
        timeoutMs = SYSTEM_CONTROL_TIMEOUT_MS
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.System.canWrite(context)
            ) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Result.error(
                    code = ErrorCodes.PERMISSION_DENIED,
                    message = "需要修改系统设置权限"
                )
            } else {
                val brightness = (level / 100f * 255).toInt().coerceIn(0, 255)
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    brightness
                )
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Result.success(Unit)
            }
        } catch (e: Exception) {
            LingShuLog.e("SystemControl", "亮度调节失败", e)
            Result.error(
                code = ErrorCodes.UNKNOWN_ERROR,
                message = "亮度调节失败",
                cause = e
            )
        }
    }

    override suspend fun setAutoRotate(on: Boolean): Result<Unit> = withSystemControlTimeout(
        action = "自动旋转",
        timeoutMs = SYSTEM_CONTROL_TIMEOUT_MS
    ) {
        try {
            Settings.System.putInt(
                contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                if (on) 1 else 0
            )
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.e("SystemControl", "自动旋转设置失败", e)
            Result.error(
                code = ErrorCodes.UNKNOWN_ERROR,
                message = "自动旋转设置失败",
                cause = e
            )
        }
    }

    override suspend fun openApp(packageName: String): Result<Unit> = withSystemControlTimeout(
        action = "打开应用",
        timeoutMs = OPEN_APP_TIMEOUT_MS
    ) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Result.success(Unit)
            } else {
                Result.error(
                    code = ErrorCodes.UNKNOWN_ERROR,
                    message = "应用未安装"
                )
            }
        } catch (e: Exception) {
            LingShuLog.e("SystemControl", "打开应用失败: $packageName", e)
            Result.error(
                code = ErrorCodes.UNKNOWN_ERROR,
                message = "打开应用失败",
                cause = e
            )
        }
    }

    override suspend fun closeApp(packageName: String): Result<Unit> = withSystemControlTimeout(
        action = "关闭应用",
        timeoutMs = SYSTEM_CONTROL_TIMEOUT_MS
    ) {
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            activityManager.killBackgroundProcesses(packageName)
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.e("SystemControl", "关闭应用失败: $packageName", e)
            Result.error(
                code = ErrorCodes.UNKNOWN_ERROR,
                message = "关闭应用失败",
                cause = e
            )
        }
    }

    override suspend fun takeScreenshot(): Result<Unit> = withSystemControlTimeout(
        action = "截屏",
        timeoutMs = SYSTEM_CONTROL_TIMEOUT_MS
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Result.error(
                    code = ErrorCodes.PERMISSION_DENIED,
                    message = "截屏需要无障碍服务支持"
                )
            } else {
                Result.error(
                    code = ErrorCodes.UNKNOWN_ERROR,
                    message = "当前系统版本不支持截屏"
                )
            }
        } catch (e: Exception) {
            LingShuLog.e("SystemControl", "截屏失败", e)
            Result.error(
                code = ErrorCodes.UNKNOWN_ERROR,
                message = "截屏失败",
                cause = e
            )
        }
    }

    private suspend fun <T> withSystemControlTimeout(
        action: String,
        timeoutMs: Long,
        block: suspend () -> Result<T>
    ): Result<T> = withContext(Dispatchers.Default) {
        try {
            withTimeout(timeoutMs) {
                block()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            LingShuLog.w("SystemControl", "$action 操作超时(${timeoutMs}ms)")
            Result.error(
                code = ErrorCodes.UNKNOWN_ERROR,
                message = "$action 操作超时",
                cause = e
            )
        } catch (e: Exception) {
            LingShuLog.e("SystemControl", "$action 操作异常", e)
            Result.error(
                code = ErrorCodes.UNKNOWN_ERROR,
                message = "$action 操作失败",
                cause = e
            )
        }
    }

    companion object {
        private const val SYSTEM_CONTROL_TIMEOUT_MS = 5000L
        private const val OPEN_APP_TIMEOUT_MS = 10000L
    }
}

package com.lingshu.agent.feature.control

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import androidx.core.content.getSystemService
import com.lingshu.agent.utils.PermissionHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Method
import javax.inject.Inject
import javax.inject.Singleton

enum class VolumeType {
    MUSIC,
    RING,
    NOTIFICATION,
    ALARM
}

data class InstalledApp(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val isLaunchable: Boolean,
    val installTime: Long
)

@Singleton
class SystemController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionHelper: PermissionHelper
) {

    companion object {
        /** 系统控制操作超时（毫秒） */
        const val SYSTEM_CONTROL_TIMEOUT_MS = 5000L
        /** 打开应用操作超时（毫秒） */
        const val APP_LAUNCH_TIMEOUT_MS = 10000L
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val connectivityManager: ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val _flashlightState = MutableStateFlow(false)
    val flashlightState: StateFlow<Boolean> = _flashlightState.asStateFlow()

    private var isFlashlightOn = false
    private var torchCallback: CameraManager.TorchCallback? = null

    init {
        registerTorchCallback()
    }

    private fun registerTorchCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            torchCallback = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    super.onTorchModeChanged(cameraId, enabled)
                    val isRearCamera = try {
                        val chars = cameraManager.getCameraCharacteristics(cameraId)
                        chars.get(CameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_BACK
                    } catch (e: Exception) {
                        false
                    }
                    if (isRearCamera) {
                        isFlashlightOn = enabled
                        _flashlightState.value = enabled
                    }
                }
            }
            torchCallback?.let { cameraManager.registerTorchCallback(it, null) }
        }
    }

    // ==================== WiFi ====================

    fun isWifiEnabled(): Boolean {
        return try {
            wifiManager.isWifiEnabled
        } catch (e: Exception) {
            false
        }
    }

    /** 带超时的WiFi开关（规格书：系统控制5秒超时） */
    suspend fun setWifiEnabled(enabled: Boolean): DeviceActionResult = withContext(Dispatchers.IO) {
        try {
            withTimeout(SYSTEM_CONTROL_TIMEOUT_MS) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val panels = Settings.Panel.ACTION_WIFI
                    val intent = Intent(panels).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    DeviceActionResult(true, "WiFi", if (enabled) "正在打开WiFi（已跳转系统面板）" else "正在关闭WiFi（已跳转系统面板）")
                } else {
                    @Suppress("DEPRECATION")
                    wifiManager.isWifiEnabled = enabled
                    DeviceActionResult(true, "WiFi", if (enabled) "WiFi已开启" else "WiFi已关闭")
                }
            }
        } catch (e: TimeoutCancellationException) {
            DeviceActionResult(false, "WiFi", "WiFi操作超时（${SYSTEM_CONTROL_TIMEOUT_MS / 1000}秒）")
        } catch (e: Exception) {
            DeviceActionResult(false, "WiFi", "设置WiFi失败: ${e.message}")
        }
    }

    // ==================== 蓝牙 ====================

    fun isBluetoothEnabled(): Boolean {
        return try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            bm?.adapter?.isEnabled == true
        } catch (e: Exception) {
            false
        }
    }

    /** 带超时的蓝牙开关 */
    suspend fun setBluetoothEnabled(enabled: Boolean): DeviceActionResult = withContext(Dispatchers.IO) {
        try {
            withTimeout(SYSTEM_CONTROL_TIMEOUT_MS) {
                val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
                val adapter = bm?.adapter
                    ?: return@withTimeout DeviceActionResult(false, "蓝牙", "设备不支持蓝牙")
                if (enabled) adapter.enable() else adapter.disable()
                DeviceActionResult(true, "蓝牙", if (enabled) "蓝牙正在开启" else "蓝牙正在关闭")
            }
        } catch (e: TimeoutCancellationException) {
            DeviceActionResult(false, "蓝牙", "蓝牙操作超时（${SYSTEM_CONTROL_TIMEOUT_MS / 1000}秒）")
        } catch (e: Exception) {
            DeviceActionResult(false, "蓝牙", "设置蓝牙失败: ${e.message}")
        }
    }

    // ==================== 手电筒 ====================

    fun isFlashlightEnabled(): Boolean = isFlashlightOn

    /** 带超时的手电筒开关 */
    suspend fun setFlashlightEnabled(enabled: Boolean): DeviceActionResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return@withContext DeviceActionResult(false, "手电筒", "Android版本过低，不支持手电筒控制")
        }
        try {
            withTimeout(SYSTEM_CONTROL_TIMEOUT_MS) {
                val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    val chars = cameraManager.getCameraCharacteristics(id)
                    chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                            chars.get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_BACK
                } ?: return@withTimeout DeviceActionResult(false, "手电筒", "未找到后置闪光灯")
                cameraManager.setTorchMode(cameraId, enabled)
                DeviceActionResult(true, "手电筒", if (enabled) "手电筒已开启" else "手电筒已关闭")
            }
        } catch (e: TimeoutCancellationException) {
            DeviceActionResult(false, "手电筒", "手电筒操作超时（${SYSTEM_CONTROL_TIMEOUT_MS / 1000}秒）")
        } catch (e: CameraAccessException) {
            DeviceActionResult(false, "手电筒", "设置手电筒失败: ${e.message}")
        } catch (e: Exception) {
            DeviceActionResult(false, "手电筒", "设置手电筒失败: ${e.message}")
        }
    }

    // ==================== 热点 ====================

    /** 带超时的热点开关 */
    suspend fun setHotspotEnabled(enabled: Boolean): DeviceActionResult = withContext(Dispatchers.IO) {
        try {
            withTimeout(SYSTEM_CONTROL_TIMEOUT_MS) {
                try {
                    val method: Method = wifiManager.javaClass.getDeclaredMethod(
                        "setWifiApEnabled",
                        wifiManager.javaClass,
                        Boolean::class.javaPrimitiveType
                    )
                    method.isAccessible = true
                    // 尝试两个签名版本
                    val result = try {
                        method.invoke(wifiManager, null, enabled) as? Boolean ?: false
                    } catch (e: Exception) {
                        val m2: Method = wifiManager.javaClass.getDeclaredMethod(
                            "setWifiApEnabled",
                            android.net.wifi.WifiConfiguration::class.java,
                            Boolean::class.javaPrimitiveType
                        )
                        m2.isAccessible = true
                        m2.invoke(wifiManager, null, enabled) as? Boolean ?: false
                    }
                    if (result) {
                        DeviceActionResult(true, "热点", if (enabled) "热点已开启" else "热点已关闭")
                    } else {
                        // 反射失败则跳转系统面板
                        val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        DeviceActionResult(true, "热点", "已跳转网络设置面板")
                    }
                } catch (e: Exception) {
                    val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    DeviceActionResult(true, "热点", "已跳转网络设置面板")
                }
            }
        } catch (e: TimeoutCancellationException) {
            DeviceActionResult(false, "热点", "热点操作超时（${SYSTEM_CONTROL_TIMEOUT_MS / 1000}秒）")
        } catch (e: Exception) {
            DeviceActionResult(false, "热点", "设置热点失败: ${e.message}")
        }
    }

    // ==================== NFC ====================

    fun isNfcSupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)
    }

    fun isNfcEnabled(): Boolean {
        return try {
            val adapter = NfcAdapter.getDefaultAdapter(context)
            adapter?.isEnabled == true
        } catch (e: Exception) {
            false
        }
    }

    /** 带超时的NFC开关 */
    suspend fun setNfcEnabled(enabled: Boolean): DeviceActionResult = withContext(Dispatchers.IO) {
        try {
            withTimeout(SYSTEM_CONTROL_TIMEOUT_MS) {
                val adapter = NfcAdapter.getDefaultAdapter(context)
                    ?: return@withTimeout DeviceActionResult(false, "NFC", "设备不支持NFC")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val intent = Intent(Settings.ACTION_NFC_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    DeviceActionResult(true, "NFC", "已跳转NFC设置页面")
                } else {
                    try {
                        val clazz = NfcAdapter::class.java
                        val method = clazz.getDeclaredMethod(
                            if (enabled) "enable" else "disable"
                        )
                        method.isAccessible = true
                        method.invoke(adapter)
                        DeviceActionResult(true, "NFC", if (enabled) "NFC已开启" else "NFC已关闭")
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_NFC_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        DeviceActionResult(true, "NFC", "已跳转NFC设置页面")
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            DeviceActionResult(false, "NFC", "NFC操作超时（${SYSTEM_CONTROL_TIMEOUT_MS / 1000}秒）")
        } catch (e: Exception) {
            DeviceActionResult(false, "NFC", "设置NFC失败: ${e.message}")
        }
    }

    // ==================== 音量 ====================

    fun getVolume(type: VolumeType): Int {
        val streamType = when (type) {
            VolumeType.MUSIC -> AudioManager.STREAM_MUSIC
            VolumeType.RING -> AudioManager.STREAM_RING
            VolumeType.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
            VolumeType.ALARM -> AudioManager.STREAM_ALARM
        }
        return audioManager.getStreamVolume(streamType)
    }

    fun getMaxVolume(type: VolumeType): Int {
        val streamType = when (type) {
            VolumeType.MUSIC -> AudioManager.STREAM_MUSIC
            VolumeType.RING -> AudioManager.STREAM_RING
            VolumeType.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
            VolumeType.ALARM -> AudioManager.STREAM_ALARM
        }
        return audioManager.getStreamMaxVolume(streamType)
    }

    /** 带超时的音量设置 */
    suspend fun setVolume(type: VolumeType, value: Int, showUI: Boolean = false): DeviceActionResult =
        withContext(Dispatchers.IO) {
            if (!permissionHelper.canWriteSystemSettings() && !permissionHelper.hasNotificationPermission()) {
                return@withContext DeviceActionResult(false, "音量", "缺少必要权限：WRITE_SETTINGS 或 通知权限")
            }
            try {
                withTimeout(SYSTEM_CONTROL_TIMEOUT_MS) {
                    val streamType = when (type) {
                        VolumeType.MUSIC -> AudioManager.STREAM_MUSIC
                        VolumeType.RING -> AudioManager.STREAM_RING
                        VolumeType.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
                        VolumeType.ALARM -> AudioManager.STREAM_ALARM
                    }
                    val max = audioManager.getStreamMaxVolume(streamType)
                    val clamped = value.coerceIn(0, max)
                    val flags = if (showUI) AudioManager.FLAG_SHOW_UI else 0
                    audioManager.setStreamVolume(streamType, clamped, flags)
                    DeviceActionResult(true, "音量", "音量已设置为 $clamped/$max")
                }
            } catch (e: TimeoutCancellationException) {
                DeviceActionResult(false, "音量", "音量设置超时（${SYSTEM_CONTROL_TIMEOUT_MS / 1000}秒）")
            } catch (e: Exception) {
                DeviceActionResult(false, "音量", "设置音量失败: ${e.message}")
            }
        }

    fun adjustVolume(type: VolumeType, direction: Int, showUI: Boolean = false) {
        val streamType = when (type) {
            VolumeType.MUSIC -> AudioManager.STREAM_MUSIC
            VolumeType.RING -> AudioManager.STREAM_RING
            VolumeType.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
            VolumeType.ALARM -> AudioManager.STREAM_ALARM
        }
        val adj = if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        val flags = if (showUI) AudioManager.FLAG_SHOW_UI else 0
        audioManager.adjustStreamVolume(streamType, adj, flags)
    }

    // ==================== 亮度 ====================

    fun getBrightness(): Int {
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) {
            128
        }
    }

    fun getMaxBrightness(): Int = 255

    fun isAutoBrightnessEnabled(): Boolean {
        return try {
            val mode = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE
            )
            mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (e: Exception) {
            false
        }
    }

    /** 带超时的亮度设置 */
    suspend fun setBrightness(value: Int): DeviceActionResult = withContext(Dispatchers.IO) {
        if (!permissionHelper.canWriteSystemSettings()) {
            return@withContext DeviceActionResult(false, "亮度", "缺少 WRITE_SETTINGS 权限，请先授权")
        }
        try {
            withTimeout(SYSTEM_CONTROL_TIMEOUT_MS) {
                val clamped = value.coerceIn(0, 255)
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    clamped
                )
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                DeviceActionResult(true, "亮度", "亮度已设置为 ${(clamped * 100 / 255)}%")
            }
        } catch (e: TimeoutCancellationException) {
            DeviceActionResult(false, "亮度", "亮度设置超时（${SYSTEM_CONTROL_TIMEOUT_MS / 1000}秒）")
        } catch (e: Exception) {
            DeviceActionResult(false, "亮度", "设置亮度失败: ${e.message}")
        }
    }

    /** 带超时的自动亮度设置 */
    suspend fun setAutoBrightnessEnabled(enabled: Boolean): DeviceActionResult = withContext(Dispatchers.IO) {
        if (!permissionHelper.canWriteSystemSettings()) {
            return@withContext DeviceActionResult(false, "自动亮度", "缺少 WRITE_SETTINGS 权限，请先授权")
        }
        try {
            withTimeout(SYSTEM_CONTROL_TIMEOUT_MS) {
                val mode = if (enabled) {
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                } else {
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                }
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    mode
                )
                DeviceActionResult(true, "自动亮度", if (enabled) "自动亮度已开启" else "自动亮度已关闭")
            }
        } catch (e: TimeoutCancellationException) {
            DeviceActionResult(false, "自动亮度", "自动亮度设置超时（${SYSTEM_CONTROL_TIMEOUT_MS / 1000}秒）")
        } catch (e: Exception) {
            DeviceActionResult(false, "自动亮度", "设置自动亮度失败: ${e.message}")
        }
    }

    // ==================== 色温/夜间模式 ====================

    /** 带超时的夜间模式/色温设置 */
    suspend fun setNightDisplayEnabled(enabled: Boolean): DeviceActionResult = withContext(Dispatchers.IO) {
        try {
            withTimeout(SYSTEM_CONTROL_TIMEOUT_MS) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    Settings.Secure.putInt(
                        context.contentResolver,
                        "night_display_activated",
                        if (enabled) 1 else 0
                    )
                    DeviceActionResult(true, "夜间模式", if (enabled) "夜间模式（暖色温）已开启" else "夜间模式已关闭")
                } else {
                    DeviceActionResult(false, "夜间模式", "Android版本过低，不支持夜间显示模式")
                }
            }
        } catch (e: TimeoutCancellationException) {
            DeviceActionResult(false, "夜间模式", "夜间模式设置超时（${SYSTEM_CONTROL_TIMEOUT_MS / 1000}秒）")
        } catch (e: Exception) {
            DeviceActionResult(false, "夜间模式", "设置夜间模式失败: ${e.message}")
        }
    }

    // ==================== 自动旋转 ====================

    fun isAutoRotateEnabled(): Boolean {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION
            ) == 1
        } catch (e: Exception) {
            false
        }
    }

    /** 带超时的自动旋转设置 */
    suspend fun setAutoRotateEnabled(enabled: Boolean): DeviceActionResult = withContext(Dispatchers.IO) {
        if (!permissionHelper.canWriteSystemSettings()) {
            return@withContext DeviceActionResult(false, "自动旋转", "缺少 WRITE_SETTINGS 权限，请先授权")
        }
        try {
            withTimeout(SYSTEM_CONTROL_TIMEOUT_MS) {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    if (enabled) 1 else 0
                )
                DeviceActionResult(true, "自动旋转", if (enabled) "自动旋转已开启" else "自动旋转已关闭")
            }
        } catch (e: TimeoutCancellationException) {
            DeviceActionResult(false, "自动旋转", "自动旋转设置超时（${SYSTEM_CONTROL_TIMEOUT_MS / 1000}秒）")
        } catch (e: Exception) {
            DeviceActionResult(false, "自动旋转", "设置自动旋转失败: ${e.message}")
        }
    }

    // ==================== App控制 ====================

    /** 带超时的应用启动（规格书：打开应用10秒超时） */
    suspend fun launchApp(packageName: String): DeviceActionResult = withContext(Dispatchers.IO) {
        try {
            withTimeout(APP_LAUNCH_TIMEOUT_MS) {
                val pm = context.packageManager
                val launchIntent = pm.getLaunchIntentForPackage(packageName)
                    ?: return@withTimeout DeviceActionResult(false, "启动应用", "未找到包名为 [$packageName] 的应用")
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                DeviceActionResult(true, "启动应用", "正在启动 [$packageName]")
            }
        } catch (e: TimeoutCancellationException) {
            DeviceActionResult(false, "启动应用", "启动应用超时（${APP_LAUNCH_TIMEOUT_MS / 1000}秒）")
        } catch (e: Exception) {
            DeviceActionResult(false, "启动应用", "启动应用失败: ${e.message}")
        }
    }

    /** 带超时的应用关闭 */
    suspend fun closeApp(packageName: String): DeviceActionResult = withContext(Dispatchers.IO) {
        try {
            withTimeout(SYSTEM_CONTROL_TIMEOUT_MS) {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    ?: return@withTimeout DeviceActionResult(false, "关闭应用", "无法获取ActivityManager服务")
                am.killBackgroundProcesses(packageName)
                DeviceActionResult(true, "关闭应用", "已关闭 [$packageName]")
            }
        } catch (e: TimeoutCancellationException) {
            DeviceActionResult(false, "关闭应用", "关闭应用超时（${SYSTEM_CONTROL_TIMEOUT_MS / 1000}秒）")
        } catch (e: Exception) {
            DeviceActionResult(false, "关闭应用", "关闭应用失败: ${e.message}")
        }
    }

    /** 带超时的应用设置界面打开 */
    suspend fun openAppSettings(packageName: String): DeviceActionResult = withContext(Dispatchers.IO) {
        try {
            withTimeout(SYSTEM_CONTROL_TIMEOUT_MS) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                DeviceActionResult(true, "应用设置", "已打开 [$packageName] 的应用设置")
            }
        } catch (e: TimeoutCancellationException) {
            DeviceActionResult(false, "应用设置", "打开应用设置超时（${SYSTEM_CONTROL_TIMEOUT_MS / 1000}秒）")
        } catch (e: Exception) {
            DeviceActionResult(false, "应用设置", "打开应用设置失败: ${e.message}")
        }
    }

    suspend fun getInstalledApps(
        includeSystem: Boolean = false,
        onlyLaunchable: Boolean = true
    ): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = mutableListOf<InstalledApp>()
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            pm.getInstalledApplications(0)
        }

        packages.forEach { info ->
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystem && isSystem) return@forEach

            val launchIntent = pm.getLaunchIntentForPackage(info.packageName)
            val isLaunchable = launchIntent != null
            if (onlyLaunchable && !isLaunchable) return@forEach

            try {
                val appName = info.loadLabel(pm).toString()
                val installTime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(info.packageName, PackageManager.PackageInfoFlags.of(0L)).firstInstallTime
                } else {
                    pm.getPackageInfo(info.packageName, 0).firstInstallTime
                }
                apps.add(
                    InstalledApp(
                        packageName = info.packageName,
                        appName = appName,
                        isSystemApp = isSystem,
                        isLaunchable = isLaunchable,
                        installTime = installTime
                    )
                )
            } catch (e: Exception) {
            }
        }
        apps.sortBy { it.appName.lowercase() }
        apps
    }

    // ==================== 网络状态 ====================

    fun isNetworkConnected(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    fun isWifiConnected(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            false
        }
    }
}

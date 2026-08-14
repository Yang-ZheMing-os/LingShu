package com.lingshu.feature.control.data

import android.content.ActivityNotFoundException
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
        // Android Q+ 禁止第三方应用直接开关蓝牙，BluetoothAdapter.enable()/disable() 已废弃但仍可用
        // 模拟器上无蓝牙模块，会抛 NullPointerException，这里做容错
        try {
            @Suppress("DEPRECATION")
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager
            @Suppress("DEPRECATION")
            val bluetoothAdapter = bluetoothManager?.adapter
            if (bluetoothAdapter == null) {
                LingShuLog.w("SystemControl", "设备无蓝牙模块（可能是模拟器），跳过蓝牙控制")
                Result.error(
                    code = ErrorCodes.UNKNOWN_ERROR,
                    message = "设备无蓝牙模块"
                )
            } else {
                if (on) bluetoothAdapter.enable() else bluetoothAdapter.disable()
                Result.success(Unit)
            }
        } catch (e: SecurityException) {
            LingShuLog.e("SystemControl", "蓝牙控制权限不足", e)
            Result.error(
                code = ErrorCodes.PERMISSION_DENIED,
                message = "蓝牙控制权限不足",
                cause = e
            )
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

    override suspend fun setAirplaneMode(on: Boolean): Result<Unit> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                val value = if (on) 1 else 0
                Settings.Global.putInt(
                    contentResolver,
                    Settings.Global.AIRPLANE_MODE_ON,
                    value
                )
                val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                intent.putExtra("state", on)
                context.sendBroadcast(intent)
                LingShuLog.i("SystemControl", "Airplane mode set to: $on")
            }
            Result.success(Unit)
        } catch (e: SecurityException) {
            LingShuLog.w("SystemControl", "Airplane mode control requires WRITE_SECURE_SETTINGS permission: ${e.message}")
            Result.error(
                code = ErrorCodes.PERMISSION_DENIED,
                message = "Airplane mode requires system permission",
                cause = e
            )
        } catch (e: Exception) {
            LingShuLog.e("SystemControl", "Failed to set airplane mode: ${e.message}", e)
            Result.error(
                code = ErrorCodes.UNKNOWN_ERROR,
                message = "Failed to set airplane mode: ${e.message}",
                cause = e
            )
        }
    }

    override fun getPackageNameByAppName(appName: String): String {
        val key = appName.trim().lowercase()
        return APP_PACKAGE_MAP[key] ?: ""
    }

    override suspend fun openAppWithDeepLink(
        packageName: String,
        deeplinkUri: String
    ): Result<Unit> = withSystemControlTimeout(
        action = "Deeplink跳转",
        timeoutMs = OPEN_APP_TIMEOUT_MS
    ) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deeplinkUri)).apply {
                // 指定目标包名，避免被其他应用拦截
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            LingShuLog.i("SystemControl", "Deeplink跳转成功: $packageName, $deeplinkUri")
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.w("SystemControl", "Deeplink跳转失败，尝试直接打开应用: $packageName, $deeplinkUri", e)
            // fallback：直接打开应用主入口
            openApp(packageName)
        }
    }

    override suspend fun navigateToMap(destination: String): Result<Unit> = withSystemControlTimeout(
        action = "地图导航",
        timeoutMs = OPEN_APP_TIMEOUT_MS
    ) {
        val encoded = Uri.encode(destination)
        // 优先高德地图
        val amapUri = "amapuri://route/plan/?dname=$encoded&dev=0&t=0"
        if (tryStartActivity(Uri.parse(amapUri))) {
            LingShuLog.i("SystemControl", "使用高德地图导航到: $destination")
            return@withSystemControlTimeout Result.success(Unit)
        }
        // fallback 百度地图
        val baiduUri = "baidumap://map/direction?destination=$encoded&coord_type=gcj02&mode=driving"
        if (tryStartActivity(Uri.parse(baiduUri))) {
            LingShuLog.i("SystemControl", "使用百度地图导航到: $destination")
            return@withSystemControlTimeout Result.success(Unit)
        }
        // 再 fallback geo: 通用 Intent
        val geoUri = Uri.parse("geo:0,0?q=$encoded")
        if (tryStartActivity(geoUri)) {
            LingShuLog.i("SystemControl", "使用 geo Intent 导航到: $destination")
            return@withSystemControlTimeout Result.success(Unit)
        }
        LingShuLog.w("SystemControl", "未找到可用的地图应用: $destination")
        Result.error(
            code = ErrorCodes.UNKNOWN_ERROR,
            message = "未找到可用的地图应用"
        )
    }

    override suspend fun openTakeout(): Result<Unit> = withSystemControlTimeout(
        action = "打开外卖",
        timeoutMs = OPEN_APP_TIMEOUT_MS
    ) {
        // 优先美团外卖 deeplink
        if (tryStartActivity(Uri.parse("imeituan://www.meituan.com/food"))) {
            LingShuLog.i("SystemControl", "打开美团外卖(deeplink)")
            return@withSystemControlTimeout Result.success(Unit)
        }
        // fallback 饿了么 deeplink
        if (tryStartActivity(Uri.parse("eleme://www.ele.me/restapi/v1/open?from=web"))) {
            LingShuLog.i("SystemControl", "打开饿了么(deeplink)")
            return@withSystemControlTimeout Result.success(Unit)
        }
        // 再 fallback 直接打开美团/饿了么 主入口
        val meituan = openApp("com.sankuai.meituan")
        if (meituan is Result.Success) {
            return@withSystemControlTimeout meituan
        }
        openApp("me.ele")
    }

    /**
     * 尝试用 ACTION_VIEW 启动一个 [uri]。
     *
     * Android 11+ (API 30) 上 resolveActivity 对未在 <queries> 声明的应用返回 null，
     * 因此这里直接 startActivity 并捕获 ActivityNotFoundException 判断是否可用。
     */
    private fun tryStartActivity(uri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: Exception) {
            LingShuLog.w("SystemControl", "启动 Intent 失败: $uri", e)
            false
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

        /** 主流 App 中文名 -> 包名 映射表（key 已小写化，便于匹配） */
        private val APP_PACKAGE_MAP: Map<String, String> = mapOf(
            // 系统应用
            "设置" to "com.android.settings",
            "相机" to "com.android.camera",
            "相册" to "com.android.gallery",
            "图库" to "com.android.gallery",
            "音乐" to "com.android.music",
            "浏览器" to "com.android.browser",
            "日历" to "com.android.calendar",
            "时钟" to "com.android.deskclock",
            "计算器" to "com.android.calculator2",
            // 社交
            "微信" to "com.tencent.mm",
            "qq" to "com.tencent.mobileqq",
            "微博" to "com.sina.weibo",
            // 地图
            "高德地图" to "com.autonavi.minimap",
            "高德" to "com.autonavi.minimap",
            "百度地图" to "com.baidu.BaiduMap",
            "百度" to "com.baidu.BaiduMap",
            "腾讯地图" to "com.tencent.map",
            // 外卖
            "美团外卖" to "com.sankuai.meituan",
            "美团" to "com.sankuai.meituan",
            "饿了么" to "me.ele",
            // 购物
            "淘宝" to "com.taobao.taobao",
            "京东" to "com.jingdong.app.mall",
            "拼多多" to "com.xunmeng.pinduoduo",
            // 视频
            "抖音" to "com.ss.android.ugc.aweme",
            "哔哩哔哩" to "tv.danmaku.bili",
            "b站" to "tv.danmaku.bili",
            "bilibili" to "tv.danmaku.bili",
            "快手" to "com.smile.gifmaker",
            // 音乐
            "网易云音乐" to "com.netease.cloudmusic",
            "网易音乐" to "com.netease.cloudmusic",
            "qq音乐" to "com.tencent.qqmusic",
            // 生活
            "支付宝" to "com.eg.android.AlipayGphone"
        )
    }
}

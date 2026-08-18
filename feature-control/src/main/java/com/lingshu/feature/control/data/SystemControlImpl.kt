package com.lingshu.feature.control.data

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.app.SearchManager
import android.view.WindowManager
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.accessibility.domain.IAccessibilityControl
import com.lingshu.feature.control.domain.ChatChannel
import com.lingshu.feature.control.domain.ISystemControl
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemControlImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accessibilityControl: IAccessibilityControl
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
        val targetPkg = packageName.trim()
        LingShuLog.i("SystemControl", "========== openApp 开始 ==========")
        LingShuLog.i("SystemControl", "openApp: 请求打开 packageName=[$targetPkg]")
        if (targetPkg.isBlank()) {
            LingShuLog.w("SystemControl", "openApp: 包名为空，直接返回失败")
            Result.error(code = ErrorCodes.UNKNOWN_ERROR, message = "包名为空，无法打开应用")
        } else {
            try {
                val pm = context.packageManager
                var intent = pm.getLaunchIntentForPackage(targetPkg)
                LingShuLog.d("SystemControl", "openApp: getLaunchIntentForPackage 返回: ${if (intent == null) "null(fallback)" else "OK"}")

                // 终极兜底：某些定制 ROM / 游戏 / 未声明 launcher 的应用，
                // getLaunchIntentForPackage 会返回 null；改为通过 CATEGORY_LAUNCHER 扫
                // resolveInfo 的第一个 Activity 手动拼 Intent
                if (intent == null) {
                    LingShuLog.d("SystemControl", "openApp: fallback 到 CATEGORY_LAUNCHER queryIntentActivities")
                    val launchIntent = Intent(Intent.ACTION_MAIN, null)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(targetPkg)
                    val resolveList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.queryIntentActivities(
                            launchIntent,
                            android.content.pm.PackageManager.ResolveInfoFlags.of(0)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        pm.queryIntentActivities(launchIntent, 0)
                    }
                    val activity = resolveList.firstOrNull()?.activityInfo
                    LingShuLog.d("SystemControl", "openApp: queryIntentActivities 命中 ${resolveList.size} 项")
                    if (activity != null) {
                        intent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            setClassName(activity.packageName, activity.name)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        LingShuLog.i(
                            "SystemControl",
                            "openApp: ✅ ResolveInfo 兜底拼 Intent: ${activity.packageName}/${activity.name}"
                        )
                    } else {
                        LingShuLog.w("SystemControl", "openApp: ❌ queryIntentActivities 也没命中 launcher activity")
                    }
                }

                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    LingShuLog.i(
                        "SystemControl",
                        "openApp: 即将 startActivity($targetPkg), Intent=$intent"
                    )
                    context.startActivity(intent)
                    LingShuLog.i("SystemControl", "openApp: ✅ startActivity 调用成功: $targetPkg")
                    Result.success(Unit)
                } else {
                    LingShuLog.e("SystemControl", "openApp: ❌ 应用未安装或找不到启动页：$targetPkg")
                    Result.error(
                        code = ErrorCodes.UNKNOWN_ERROR,
                        message = "应用未安装或找不到启动页：$targetPkg"
                    )
                }
            } catch (e: Exception) {
                LingShuLog.e("SystemControl", "openApp: ❌ startActivity 抛出异常: $targetPkg", e)
                Result.error(
                    code = ErrorCodes.UNKNOWN_ERROR,
                    message = "打开应用失败",
                    cause = e
                )
            }
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
            val result = accessibilityControl.takeScreenshot()
            if (result is Result.Success) {
                LingShuLog.i("SystemControl", "截屏已触发，系统自动保存到相册")
            }
            result
        } catch (e: Exception) {
            LingShuLog.e("SystemControl", "截屏失败", e)
            Result.error(
                code = ErrorCodes.UNKNOWN_ERROR,
                message = "截屏失败: ${e.message}",
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
        LingShuLog.d("SystemControl", "getPackageNameByAppName: 输入=$appName, key=$key")
        // 1) 静态映射优先命中（常见 App 不用扫 PM，快）
        APP_PACKAGE_MAP[key]?.let {
            LingShuLog.i("SystemControl", "getPackageNameByAppName: ✅ 静态表命中 $appName -> $it")
            return it
        }
        LingShuLog.d("SystemControl", "getPackageNameByAppName: 静态表未命中，走 PackageManager 动态匹配: $appName")
        // 2) 动态 fallback：扫本机已安装应用，按应用名称模糊匹配
        resolveByPackageManager(appName.trim())?.let {
            LingShuLog.i("SystemControl", "getPackageNameByAppName: ✅ PackageManager 匹配 $appName -> $it")
            return it
        }
        LingShuLog.w("SystemControl", "getPackageNameByAppName: ❌ 全部未命中: appName=$appName key=$key")
        return ""
    }

    /**
     * 通过 PackageManager 在已安装应用中按 label 模糊匹配包名。
     *
     * 匹配优先级：完全相等（忽略大小写）→ 包含关系（label 包含查询词 / 查询词包含 label）。
     * 返回首个命中的包名，未命中返回 null。
     */
    private fun resolveByPackageManager(query: String): String? {
        if (query.isBlank()) return null
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                android.content.pm.PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        val q = query.lowercase()
        LingShuLog.d(
            "SystemControl",
            "resolveByPackageManager: query=$query q=$q 已扫描应用数=${apps.size}"
        )

        // 完全相等
        apps.firstOrNull {
            it.loadLabel(pm).toString().trim().lowercase() == q
        }?.let {
            val label = it.loadLabel(pm).toString().trim()
            LingShuLog.d("SystemControl", "resolveByPackageManager: ✅ 完全相等命中 label=[$label] pkg=${it.activityInfo.packageName}")
            return it.activityInfo.packageName
        }

        // label 包含查询词（例：label"高德地图"，查询"高德"）
        apps.firstOrNull {
            it.loadLabel(pm).toString().trim().lowercase().contains(q)
        }?.let {
            val label = it.loadLabel(pm).toString().trim()
            LingShuLog.d("SystemControl", "resolveByPackageManager: ✅ label包含查询词命中 label=[$label] pkg=${it.activityInfo.packageName}")
            return it.activityInfo.packageName
        }

        // 查询词包含 label（例：查询"王者农药"，label 是"王者荣耀"不太能命中，
        // 但 label 是短词的情况下能兜底）
        apps.firstOrNull {
            val label = it.loadLabel(pm).toString().trim().lowercase()
            label.isNotEmpty() && q.contains(label)
        }?.let {
            val label = it.loadLabel(pm).toString().trim()
            LingShuLog.d("SystemControl", "resolveByPackageManager: ✅ 查询词包含label命中 label=[$label] pkg=${it.activityInfo.packageName}")
            return it.activityInfo.packageName
        }

        LingShuLog.d("SystemControl", "resolveByPackageManager: ❌ 未命中任何 label 匹配: $query")
        return null
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

    override suspend fun webSearch(query: String): Result<Unit> = withSystemControlTimeout(
        action = "网页搜索",
        timeoutMs = OPEN_APP_TIMEOUT_MS
    ) {
        val encoded = Uri.encode(query)
        // 优先尝试浏览器应用搜索
        val intents = listOf(
            Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.baidu.com/s?wd=$encoded")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encoded")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        for (intent in intents) {
            try {
                context.startActivity(intent)
                LingShuLog.i("SystemControl", "网页搜索: $query")
                return@withSystemControlTimeout Result.success(Unit)
            } catch (e: Exception) {
                LingShuLog.d("SystemControl", "搜索 Intent 失败，尝试下一个: ${e.message}")
            }
        }
        Result.error(
            code = ErrorCodes.UNKNOWN_ERROR,
            message = "未找到可用的浏览器或搜索应用"
        )
    }

    override suspend fun playMusic(): Result<Unit> = withSystemControlTimeout(
        action = "播放音乐",
        timeoutMs = OPEN_APP_TIMEOUT_MS
    ) {
        // 优先尝试网易云音乐 deeplink
        val deeplinks = listOf(
            "orpheus://widget/music?action=play",
            "qqmusic://qq.com/open/player?action=play"
        )
        for (deeplink in deeplinks) {
            if (tryStartActivity(Uri.parse(deeplink))) {
                LingShuLog.i("SystemControl", "通过 deeplink 播放音乐: $deeplink")
                return@withSystemControlTimeout Result.success(Unit)
            }
        }
        // fallback: 打开第一个找到的音乐 App
        for (pkg in listOf("com.netease.cloudmusic", "com.tencent.qqmusic", "com.kugou.android")) {
            val result = openApp(pkg)
            if (result is Result.Success) {
                return@withSystemControlTimeout result
            }
        }
        Result.error(
            code = ErrorCodes.UNKNOWN_ERROR,
            message = "未找到音乐应用"
        )
    }

    override suspend fun setAlarm(hour: Int?, minute: Int, label: String?): Result<Unit> = withSystemControlTimeout(
        action = "设置闹钟",
        timeoutMs = OPEN_APP_TIMEOUT_MS
    ) {
        try {
            val intent = if (hour != null) {
                Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                // 未指定时间，只打开时钟应用
                Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
            LingShuLog.i("SystemControl", "设置闹钟: ${hour ?: "打开闹钟列表"}:$minute")
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.w("SystemControl", "设置闹钟失败，尝试打开时钟应用", e)
            openApp("com.android.deskclock")
        }
    }

    override suspend fun openCamera(): Result<Unit> = withSystemControlTimeout(
        action = "打开相机",
        timeoutMs = OPEN_APP_TIMEOUT_MS
    ) {
        try {
            val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            LingShuLog.i("SystemControl", "打开相机")
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.w("SystemControl", "打开相机失败，尝试相机应用", e)
            openApp("com.android.camera")
        }
    }

    override suspend fun makeCall(phoneNumberOrContact: String): Result<Unit> = withSystemControlTimeout(
        action = "拨打电话",
        timeoutMs = OPEN_APP_TIMEOUT_MS
    ) {
        try {
            val uri = Uri.parse("tel:${Uri.encode(phoneNumberOrContact)}")
            val intent = Intent(Intent.ACTION_DIAL, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            LingShuLog.i("SystemControl", "拨打电话: $phoneNumberOrContact")
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.e("SystemControl", "拨打电话失败", e)
            Result.error(
                code = ErrorCodes.UNKNOWN_ERROR,
                message = "无法拨打电话: ${e.message}",
                cause = e
            )
        }
    }

    override suspend fun sendSms(phoneNumberOrContact: String, message: String): Result<Unit> = withSystemControlTimeout(
        action = "发送短信",
        timeoutMs = OPEN_APP_TIMEOUT_MS
    ) {
        try {
            val uri = Uri.parse("smsto:${Uri.encode(phoneNumberOrContact)}")
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (message.isNotEmpty()) {
                    putExtra("sms_body", message)
                }
            }
            context.startActivity(intent)
            LingShuLog.i("SystemControl", "发送短信: to=$phoneNumberOrContact, msg=$message")
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.e("SystemControl", "发送短信失败", e)
            Result.error(
                code = ErrorCodes.UNKNOWN_ERROR,
                message = "无法发送短信: ${e.message}",
                cause = e
            )
        }
    }

    // ========================================================================
    //  三大复合场景（安全合规版：只做打开+预填，不做自动确认/支付/发送）
    // ========================================================================

    // ---------------- 1. 点外卖 ----------------
    override suspend fun orderTakeout(
        foodKeyword: String?,
        restaurant: String?,
        addressHint: String?
    ): Result<Unit> = withSystemControlTimeout(
        action = "点外卖",
        timeoutMs = OPEN_APP_TIMEOUT_MS
    ) {
        val searchTerm = buildString {
            if (!restaurant.isNullOrBlank()) append(restaurant)
            if (!restaurant.isNullOrBlank() && !foodKeyword.isNullOrBlank()) append(" ")
            if (!foodKeyword.isNullOrBlank()) append(foodKeyword)
        }.trim()

        LingShuLog.i("SystemControl",
            "点外卖：restaurant=$restaurant, food=$foodKeyword, addr=$addressHint → 搜\"$searchTerm\"")

        // ---- 优先美团外卖：deeplink 走搜索页 ----
        val meituanSearch = "imeituan://www.meituan.com/food?search=${Uri.encode(searchTerm)}"
        if (tryStartActivity(Uri.parse(meituanSearch))) {
            LingShuLog.i("SystemControl", "打开美团外卖搜索：$searchTerm")
            return@withSystemControlTimeout Result.success(Unit)
        }
        val meituanApp = openApp("com.sankuai.meituan.takeoutnew")   // 美团外卖独立包
            .takeIf { it is Result.Success }
            ?: openApp("com.sankuai.meituan")                           // 美团主App
        if (meituanApp is Result.Success) {
            return@withSystemControlTimeout meituanApp
        }

        // ---- 饿了么（阿里）：搜索 deeplink ----
        val elemeSearch = "eleme://www.ele.me/search?q=${Uri.encode(searchTerm)}"
        if (tryStartActivity(Uri.parse(elemeSearch))) {
            LingShuLog.i("SystemControl", "打开饿了么搜索：$searchTerm")
            return@withSystemControlTimeout Result.success(Unit)
        }
        val elemeApp = openApp("me.ele")
        if (elemeApp is Result.Success) {
            return@withSystemControlTimeout elemeApp
        }

        LingShuLog.w("SystemControl", "未安装美团外卖/饿了么，无法点外卖")
        Result.error(
            code = ErrorCodes.UNKNOWN_ERROR,
            message = "请先安装美团外卖或饿了么 App"
        )
    }

    // ---------------- 2. 给某人发消息（微信/QQ/短信） ----------------
    override suspend fun sendChatMessage(
        contactNameOrPhone: String,
        message: String,
        channel: ChatChannel
    ): Result<Unit> = withSystemControlTimeout(
        action = "发送聊天消息",
        timeoutMs = OPEN_APP_TIMEOUT_MS
    ) {
        // 先尝试通过通讯录找手机号（没有 READ_CONTACTS 权限就跳过，仍然把联系人名传给App做搜索）
        val maybePhone: String? = runCatching {
            resolveContactPhone(contactNameOrPhone)
        }.getOrNull()

        val target = maybePhone ?: contactNameOrPhone

        val channelOrder: List<ChatChannel> = when (channel) {
            ChatChannel.UNKNOWN -> listOf(ChatChannel.WECHAT, ChatChannel.QQ, ChatChannel.SMS)
            else -> listOf(channel)
        }

        for (ch in channelOrder) {
            val ok = when (ch) {
                ChatChannel.WECHAT -> openWeChatConversation(contactName = contactNameOrPhone,
                    phone = maybePhone, prefillMessage = message)
                ChatChannel.QQ     -> openQQConversation(contactNameOrPhone, maybePhone, message)
                ChatChannel.SMS    -> {
                    val r = sendSms(target, message)
                    r is Result.Success
                }
                ChatChannel.UNKNOWN -> false
            }
            if (ok) {
                LingShuLog.i("SystemControl",
                    "sendChatMessage 成功：渠道=$ch, 联系人=$contactNameOrPhone")
                return@withSystemControlTimeout Result.success(Unit)
            }
        }
        LingShuLog.w("SystemControl", "sendChatMessage 所有渠道失败：contact=$contactNameOrPhone")
        Result.error(
            code = ErrorCodes.UNKNOWN_ERROR,
            message = "未找到可用的通讯 App（微信/QQ/短信）"
        )
    }

    /** 通过 ContentResolver 把 "我妈/张三/10086" 解析成真实手机号。没权限返回 null。 */
    private fun resolveContactPhone(contactName: String): String? {
        // 如果传进来本身就是纯数字手机号（≥7位），直接返回
        if (Regex("""^\+?\d{7,}$""").matches(contactName.trim())) return contactName.trim()
        return try {
            val resolver = context.contentResolver
            val uriPhone = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            val where = "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%$contactName%")
            resolver.query(uriPhone, projection, where, args, null)?.use { c ->
                val colNum = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val number = c.getString(colNum) ?: continue
                    if (number.isNotBlank()) return number.replace("\\s|-".toRegex(), "")
                }
            }
            null
        } catch (e: SecurityException) {
            LingShuLog.w("SystemControl", "resolveContactPhone 缺少 READ_CONTACTS 权限", e)
            null
        } catch (e: Exception) {
            LingShuLog.w("SystemControl", "resolveContactPhone 失败", e)
            null
        }
    }

    /**
     * 打开微信聊天页 + 预填消息。
     *
     * 微信官方不公开"打开指定好友聊天"deeplink，所以：
     *  1. 如果有手机号 → 走 wx://dl/wepiao? 或直接打开微信 App；
     *  2. 兜底：打开微信 Launcher Intent；
     *  3. 把 prefillMessage 复制到系统剪贴板，给用户最方便的"粘贴即可发送"体验；
     *  ⚠️ 不会做无障碍自动粘贴+发送（合规：人工点击后才能发）。
     */
    private suspend fun openWeChatConversation(contactName: String, phone: String?, prefillMessage: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        if (prefillMessage.isNotBlank()) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("lingshu_prefill", prefillMessage))
        }

        // 已知公开的 deeplink 入口：wx://dl/wechat 直接打开（打开微信主界面）
        val deeplinks = buildList {
            if (!phone.isNullOrBlank()) {
                // 手机号通讯录添加联系人页（有一定概率跳到个人）
                add("wx://dl/hy?url=${Uri.encode("https://weixin.qq.com/")}")
            }
            add("weixin://dl/wechat")
        }
        for (d in deeplinks) {
            if (tryStartActivity(Uri.parse(d))) {
                return true
            }
        }
        val r = openApp("com.tencent.mm")
        return r is Result.Success
    }

    /** 打开 QQ（mqqapi），优先跳好友聊天页，兜底直接打开 App；剪贴板预填消息 */
    private suspend fun openQQConversation(contactName: String, phone: String?, prefillMessage: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        if (prefillMessage.isNotBlank()) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("lingshu_prefill", prefillMessage))
        }
        // mqqapi://card/show_pslcard? 只支持已知 QQ号(uin)，一般拿不到，所以兜底直接打开
        if (tryStartActivity(Uri.parse("mqqapi://im/home/launcher"))) return true
        val r = openApp("com.tencent.mobileqq")
        return r is Result.Success
    }

    // ---------------- 3. 打车 ----------------
    override suspend fun callRide(
        destination: String,
        carTypePref: String?
    ): Result<Unit> = withSystemControlTimeout(
        action = "打车",
        timeoutMs = OPEN_APP_TIMEOUT_MS
    ) {
        val encodedDest = Uri.encode(destination)
        LingShuLog.i("SystemControl", "打车：dest=$destination, carType=$carTypePref")

        // 1) 滴滴出行 deeplink：https://mo.didiglobal.com/openDoc 支持 d 目的地
        val didi = "diditaxi://page/onekeyhailing?toAddressTitle=$encodedDest"
        if (tryStartActivity(Uri.parse(didi))) {
            return@withSystemControlTimeout Result.success(Unit)
        }
        val didiPkg = openApp("com.sdu.didi.psnger")
        if (didiPkg is Result.Success) return@withSystemControlTimeout didiPkg

        // 2) 高德出租车（高德打车）deeplink
        val amapTaxi = "amapuri://taxi/callTaxi?destname=$encodedDest&dev=0"
        if (tryStartActivity(Uri.parse(amapTaxi))) return@withSystemControlTimeout Result.success(Unit)

        // 3) 百度地图打车
        val baiduTaxi = "baidumap://map/taxi?destination=$encodedDest"
        if (tryStartActivity(Uri.parse(baiduTaxi))) return@withSystemControlTimeout Result.success(Unit)

        // 4) 兜底：地图 App 打开目的地，用户在地图内自己打车
        val mapFallback = navigateToMap(destination)
        if (mapFallback is Result.Success) {
            return@withSystemControlTimeout mapFallback
        }

        Result.error(
            code = ErrorCodes.UNKNOWN_ERROR,
            message = "未安装滴滴/高德/百度地图等打车或地图 App"
        )
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

        /** 主流 App 中文名 -> 包名 映射表（静态映射优先，未命中再走 PackageManager 动态匹配） */
        private val APP_PACKAGE_MAP: Map<String, String> = mapOf(
            // ==================== 系统应用 ====================
            "设置" to "com.android.settings",
            "相机" to "com.android.camera",
            "相册" to "com.android.gallery",
            "图库" to "com.android.gallery",
            "音乐" to "com.android.music",
            "浏览器" to "com.android.browser",
            "日历" to "com.android.calendar",
            "时钟" to "com.android.deskclock",
            "闹钟" to "com.android.deskclock",
            "计算器" to "com.android.calculator2",
            "短信" to "com.android.mms",
            "电话" to "com.android.dialer",
            "拨号" to "com.android.dialer",
            "通讯录" to "com.android.contacts",
            "联系人" to "com.android.contacts",
            "邮件" to "com.android.email",
            "文件管理" to "com.android.documentsui",
            "录音机" to "com.android.soundrecorder",
            "下载" to "com.android.providers.downloads.ui",

            // ==================== 社交 ====================
            "微信" to "com.tencent.mm",
            "qq" to "com.tencent.mobileqq",
            "qq空间" to "com.qzone",
            "微博" to "com.sina.weibo",
            "小红书" to "com.xingin.xhs",
            "知乎" to "com.zhihu.android",
            "豆瓣" to "com.douban.frodo",
            "陌陌" to "com.immomo.momo",
            "探探" to "com.p1.mobile.putong",
            "soul" to "cn.soulapp.android",
            "钉钉" to "com.alibaba.android.rimet",
            "企业微信" to "com.tencent.wework",
            "飞书" to "com.ss.android.lark",
            "telegram" to "org.telegram.messenger",
            "tg" to "org.telegram.messenger",
            "whatsapp" to "com.whatsapp",
            "discord" to "com.discord",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "facebook" to "com.facebook.katana",
            "instagram" to "com.instagram.android",
            "ins" to "com.instagram.android",
            "line" to "jp.naver.line.android",

            // ==================== 视频 / 短视频 ====================
            "抖音" to "com.ss.android.ugc.aweme",
            "抖音极速版" to "com.ss.android.ugc.aweme.lite",
            "快手" to "com.smile.gifmaker",
            "快手极速版" to "com.kuaishou.nebula",
            "哔哩哔哩" to "tv.danmaku.bili",
            "b站" to "tv.danmaku.bili",
            "bilibili" to "tv.danmaku.bili",
            "腾讯视频" to "com.tencent.qqlive",
            "爱奇艺" to "com.qiyi.video",
            "优酷" to "com.youku.phone",
            "芒果tv" to "com.hunantv.imgo.activity",
            "搜狐视频" to "com.sohu.sohuvideo",
            "西瓜视频" to "com.ss.android.article.video",
            "火山" to "com.ss.android.ugc.live",
            "youtube" to "com.google.android.youtube",
            "netflix" to "com.netflix.mediaclient",
            "twitch" to "tv.twitch.android.app",
            "acfun" to "tv.acfundanmaku.video",

            // ==================== 音乐 / 音频 ====================
            "网易云音乐" to "com.netease.cloudmusic",
            "网易音乐" to "com.netease.cloudmusic",
            "qq音乐" to "com.tencent.qqmusic",
            "酷狗" to "com.kugou.android",
            "酷我" to "cn.kuwo.player",
            "虾米" to "fm.xiami.main",
            "喜马拉雅" to "com.ximalaya.ting.android",
            "荔枝fm" to "com.tinytimemachine.fmlite",
            "懒人听书" to "bubei.tingshu",
            "蜻蜓fm" to "fm.qingting.qtradio",
            "spotify" to "com.spotify.music",

            // ==================== 购物 / 生活服务 ====================
            "淘宝" to "com.taobao.taobao",
            "天猫" to "com.tmall.wireless",
            "京东" to "com.jingdong.app.mall",
            "拼多多" to "com.xunmeng.pinduoduo",
            "苏宁易购" to "com.suning.mobile.ebuy",
            "唯品会" to "com.achievo.vipshop",
            "当当" to "com.dangdang.buy2",
            "闲鱼" to "com.taobao.idlefish",
            "转转" to "com.wuba.zhuanzhuan",
            "得物" to "com.shizhuang.duapp",
            "毒" to "com.shizhuang.duapp",
            "小红书" to "com.xingin.xhs",
            "美团外卖" to "com.sankuai.meituan",
            "美团" to "com.sankuai.meituan",
            "饿了么" to "me.ele",
            "大众点评" to "com.dianping.v1",
            "口碑" to "com.taobao.mobile.dipei",
            "支付宝" to "com.eg.android.AlipayGphone",
            "云闪付" to "com.unionpay",
            "手机银行" to "com.chinamworld.main",

            // ==================== 地图 / 出行 ====================
            "高德地图" to "com.autonavi.minimap",
            "高德" to "com.autonavi.minimap",
            "百度地图" to "com.baidu.BaiduMap",
            "百度" to "com.baidu.BaiduMap",
            "腾讯地图" to "com.tencent.map",
            "滴滴出行" to "com.sdu.didi.psnger",
            "滴滴" to "com.sdu.didi.psnger",
            "携程" to "ctrip.android.view",
            "去哪儿" to "com.Qunar",
            "飞猪" to "com.taobao.flypig",
            "12306" to "com.MobileTicket",
            "铁路12306" to "com.MobileTicket",
            "智行" to "com.yipiao",
            "航旅纵横" to "com.umetrip.android.msky.app",

            // ==================== 阅读 / 资讯 ====================
            "今日头条" to "com.ss.android.article.news",
            "头条" to "com.ss.android.article.news",
            "腾讯新闻" to "com.tencent.news",
            "网易新闻" to "com.netease.newsreader.activity",
            "新浪新闻" to "com.sina.news",
            "搜狐新闻" to "com.sohu.newsclient",
            "凤凰新闻" to "com.ifeng.news2",
            "澎湃" to "com.wondertek.paper",
            "起点中文" to "com.qidian.QDReader",
            "起点阅读" to "com.qidian.QDReader",
            "七猫" to "com.kmxs.reader",
            "番茄免费小说" to "com.dragon.read",
            "番茄小说" to "com.dragon.read",
            "掌阅" to "com.chaozh.iReaderFree",
            "微信读书" to "com.tencent.weread",
            "kindle" to "com.amazon.kindle",
            "知乎" to "com.zhihu.android",

            // ==================== 办公 / 工具 ====================
            "wps" to "cn.wps.moffice_eng",
            "wps office" to "cn.wps.moffice_eng",
            "腾讯文档" to "com.tencent.docs",
            "石墨文档" to "chuxin.shimo.shimowendang",
            "飞书" to "com.ss.android.lark",
            "钉钉" to "com.alibaba.android.rimet",
            "企业微信" to "com.tencent.wework",
            "有道词典" to "com.youdao.dict",
            "欧路词典" to "com.eusoft.eudic",
            "百度网盘" to "com.baidu.netdisk",
            "夸克" to "com.quark.browser",
            "uc浏览器" to "com.UCMobile",
            "chrome" to "com.android.chrome",
            "edge" to "com.microsoft.emmx",
            "火狐" to "org.mozilla.firefox",
            "搜狗输入法" to "com.sohu.inputmethod.sogou",
            "讯飞输入法" to "com.iflytek.inputmethod",
            "百度输入法" to "com.baidu.input",
            "微信" to "com.tencent.mm",

            // ==================== 金融 / 理财 ====================
            "同花顺" to "com.hexin.plat.android",
            "东方财富" to "com.eastmoney.android.berine",
            "支付宝" to "com.eg.android.AlipayGphone",
            "招商银行" to "cmb.pb",
            "工商银行" to "com.icbc",
            "建设银行" to "com.chinamworld.main",
            "农业银行" to "com.android.bankabc",
            "中国银行" to "com.chinamworld.bocmbci",
            "交通银行" to "com.bankcomm.Bankcomm",
            "平安口袋银行" to "com.pingan.paces.ccms",
            "支付宝" to "com.eg.android.AlipayGphone",
            "京东金融" to "com.jd.jrapp",
            "度小满金融" to "com.duxiaoman.wealth",

            // ==================== 拍照 / 修图 ====================
            "美颜相机" to "com.meitu.meiyancamera",
            "美图秀秀" to "com.mt.mtxx.mtxx",
            "醒图" to "com.xt.retouch",
            "一甜相机" to "com.kwai.m2u",
            "黄油相机" to "com.by.butter.camera",
            "轻颜" to "com.gorgeous.lite",
            "snapseed" to "com.niksoftware.snapseed",
            "lightroom" to "com.adobe.lrmobile",

            // ==================== 游戏（主流手游 + 大厂通用入口包名） ====================
            "王者荣耀" to "com.tencent.tmgp.sgame",
            "王者" to "com.tencent.tmgp.sgame",
            "和平精英" to "com.tencent.tmgp.pubgmhd",
            "吃鸡" to "com.tencent.tmgp.pubgmhd",
            "原神" to "com.miHoYo.Yuanshen",
            "崩坏星穹铁道" to "com.miHoYo.StarRail",
            "星穹铁道" to "com.miHoYo.StarRail",
            "绝区零" to "com.miHoYo.ZZZ",
            "崩坏3" to "com.miHoYo.bh3",
            "崩三" to "com.miHoYo.bh3",
            "英雄联盟手游" to "com.tencent.lolm",
            "lol手游" to "com.tencent.lolm",
            "金铲铲之战" to "com.tencent.tmgp.jxcc",
            "金铲铲" to "com.tencent.tmgp.jxcc",
            "欢乐斗地主" to "com.taurus.terry",
            "斗地主" to "com.taurus.terry",
            "天天象棋" to "com.tencent.tmgp.xxchess",
            "开心消消乐" to "com.happyelements.AndroidAnimal",
            "消消乐" to "com.happyelements.AndroidAnimal",
            "和平精英" to "com.tencent.tmgp.pubgmhd",
            "第五人格" to "com.netease.dwrg5",
            "阴阳师" to "com.netease.onmyoji",
            "我的世界" to "com.netease.x19",
            "mc" to "com.netease.x19",
            "迷你世界" to "com.minitech.miniworld",
            "蛋仔派对" to "com.netease.party",
            "蛋仔" to "com.netease.party",
            "光遇" to "com.netease.skies",
            "明日方舟" to "com.hypergryph.arknights",
            "炉石传说" to "com.blizzard.wtcg.hearthstone",
            "部落冲突" to "com.supercell.clashofclans",
            "皇室战争" to "com.supercell.clashroyale",
            "pubg mobile" to "com.tencent.ig",
            "使命召唤手游" to "com.tencent.tmgp.cod",
            "使命召唤" to "com.tencent.tmgp.cod",
            "地下城与勇士手游" to "com.tencent.tmgp.dnf",
            "dnf" to "com.tencent.tmgp.dnf",
            "cf手游" to "com.tencent.tmgp.cf",
            "穿越火线" to "com.tencent.tmgp.cf",
            "火影忍者手游" to "com.tencent.tmgp.anhninja",
            "火影忍者" to "com.tencent.tmgp.anhninja",
            "跑跑卡丁车" to "com.tencent.tmgp.popkart",
            "qq飞车" to "com.tencent.tmgp.speedmobile",
            "qq飞车手游" to "com.tencent.tmgp.speedmobile",
            "妄想山海" to "com.tencent.tmgp.delphinus",
            "天涯明月刀手游" to "com.tencent.tmgp.tyxyd",
            "天龙八部手游" to "com.tencent.tmgp.tlbb",
            "逆水寒手游" to "com.netease.nishuihan",
            "一梦江湖" to "com.netease.wyhz",
            "闪耀暖暖" to "com.papegames.nn6",
            "恋与制作人" to "com.papegames.qzxy",
            "食物语" to "com.tencent.tmgp.swjy",
            "公主连结" to "com.tencent.tmgp.pcr",
            "碧蓝航线" to "com.bilibili.ship.theseus",
            "战双帕弥什" to "com.tencent.tmgp.construct",
            "少女前线" to "com.digitalsky.girlsfrontline",
            "FGO" to "com.bilibili.fgo.portal",
            "命运冠位指定" to "com.bilibili.fgo.portal",
            "永劫无间手游" to "com.netease.yjwj",
            "诛仙手游" to "com.laohu.zhuxian",
            "问道手游" to "com.gbits.atm.guopan",
            "新笑傲江湖" to "com.laohu.xiaoao",
            "剑网3" to "com.xoyo.seasun.jw3",
            "剑与远征" to "com.tencent.tmgp.jjyc",
            "万国觉醒" to "com.lilithgames.rok.offical.cn",
            "部落冲突" to "com.supercell.clashofclans",
            "海岛奇兵" to "com.supercell.boombeach",
            "狂野飙车9" to "com.gameloft.android.ANMP.GloftA9HM",
            "我的世界国际版" to "com.mojang.minecraftpe",
            "植物大战僵尸" to "com.popcap.pvz2cthddl",
            "植物大战僵尸2" to "com.popcap.pvz2cthddl",
            "保卫萝卜" to "com.carrot.carrotfantasy",
            "纪念碑谷" to "com.monumentvalley",
            "angry birds" to "com.rovio.angrybirds",
            "地铁跑酷" to "com.kiloo.subwaysurf",
            "神庙逃亡" to "com.imangi.templerun",
            "2048" to "com.androbaby.game2048",
            "开心水族箱" to "com.happyelements.aquarium.android",
            "狼人杀" to "com.wd.werewolf",
            "谁是卧底" to "com.tencent.tmgp.sswb",
            "剧本杀" to "com.zhanyou.benghuai"
        )
    }
}

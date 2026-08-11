package com.lingshu.core.common.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionHelper {
    val PERMISSION_QUEUE = listOf(
        PermissionRequest(
            permission = Manifest.permission.RECORD_AUDIO,
            title = "麦克风权限",
            description = "灵枢需要麦克风权限才能听到你的声音，进行语音对话",
            minSdk = 0
        ),
        PermissionRequest(
            permission = Manifest.permission.POST_NOTIFICATIONS,
            title = "通知权限",
            description = "灵枢需要通知权限向你发送主动关怀提醒",
            minSdk = Build.VERSION_CODES.TIRAMISU
        ),
        PermissionRequest(
            permission = Manifest.permission.SYSTEM_ALERT_WINDOW,
            title = "悬浮窗权限",
            description = "灵枢需要悬浮窗权限显示全局悬浮球，随时唤起对话",
            minSdk = 0
        ),
        PermissionRequest(
            permission = Manifest.permission.BODY_SENSORS,
            title = "身体传感器权限",
            description = "灵枢需要传感器权限读取心率等健康数据",
            minSdk = 0
        ),
        PermissionRequest(
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
            title = "位置权限",
            description = "灵枢需要位置权限提供天气提醒等本地化服务",
            minSdk = 0
        )
    )

    fun isPermissionGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun getRequiredPermissions(): List<PermissionRequest> {
        return PERMISSION_QUEUE.filter { Build.VERSION.SDK_INT >= it.minSdk }
    }
}

data class PermissionRequest(
    val permission: String,
    val title: String,
    val description: String,
    val minSdk: Int
)

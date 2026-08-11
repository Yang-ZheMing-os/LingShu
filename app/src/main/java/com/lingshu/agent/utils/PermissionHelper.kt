package com.lingshu.agent.utils

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lingshu.agent.services.LingShuAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    val runtimePermissions = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.WRITE_SETTINGS,
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.ACCESS_NETWORK_STATE
    )

    val bluetoothPermissions: List<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun hasPermissions(permissions: List<String>): Boolean {
        return permissions.all { hasPermission(it) }
    }

    fun requestPermissions(
        activity: Activity,
        permissions: List<String>,
        requestCode: Int
    ) {
        val notGranted = permissions.filter { !hasPermission(it) }
        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, notGranted.toTypedArray(), requestCode)
        }
    }

    fun shouldShowRationale(activity: Activity, permission: String): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    fun hasMicrophonePermission(): Boolean = hasPermission(Manifest.permission.RECORD_AUDIO)

    fun hasCameraPermission(): Boolean = hasPermission(Manifest.permission.CAMERA)

    fun hasContactsPermission(): Boolean =
        hasPermission(Manifest.permission.READ_CONTACTS) &&
                hasPermission(Manifest.permission.WRITE_CONTACTS)

    fun hasSmsPermission(): Boolean =
        hasPermission(Manifest.permission.SEND_SMS) &&
                hasPermission(Manifest.permission.READ_SMS)

    fun hasBluetoothPermission(): Boolean = hasPermissions(bluetoothPermissions)

    fun hasHealthPermissions(): Boolean {
        val sensors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.BODY_SENSORS_BACKGROUND)
        } else {
            true
        }
        return hasPermission(Manifest.permission.BODY_SENSORS) &&
                hasPermission(Manifest.permission.ACTIVITY_RECOGNITION) && sensors
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
    }

    fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun canWriteSystemSettings(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else {
            true
        }
    }

    fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expectedComponent = ComponentName(
            context,
            LingShuAccessibilityService::class.java
        ).flattenToString()

        val colonSplitter = enabledServices.split(':')
        colonSplitter.forEach { service ->
            if (service.equals(expectedComponent, ignoreCase = true)) {
                return true
            }
            val cn = ComponentName.unflattenFromString(service)
            if (cn != null && cn.packageName == context.packageName) {
                return true
            }
        }
        return false
    }

    fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        val pkgName = context.packageName
        return enabledListeners.split(':').any { component ->
            val cn = ComponentName.unflattenFromString(component)
            cn?.packageName == pkgName
        }
    }

    fun getOverlayPermissionIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        } else null
    }

    fun getWriteSettingsIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
        } else null
    }

    fun getAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    fun getNotificationListenerSettingsIntent(): Intent {
        return Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
    }

    fun getAppNotificationSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        }
    }

    fun getApplicationDetailsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }

    companion object {
        const val REQUEST_RECORD_AUDIO = 1001
        const val REQUEST_NOTIFICATION = 1002
        const val REQUEST_CAMERA = 1003
        const val REQUEST_MULTIPLE_PERMISSIONS = 1004

        fun isAccessibilityServiceEnabled(context: Context, serviceName: String): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = enabledServices.split(':')
            colonSplitter.forEach { service ->
                if (service.equals(serviceName, ignoreCase = true)) return true
                val cn = ComponentName.unflattenFromString(service)
                if (cn != null && cn.packageName == context.packageName) return true
            }
            return false
        }
    }
}

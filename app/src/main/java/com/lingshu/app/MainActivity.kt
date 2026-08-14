package com.lingshu.app

import android.os.Bundle
import android.os.Build
import android.provider.Settings
import com.lingshu.core.common.event.IFloatingService
import javax.inject.Inject
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lingshu.core.ui.theme.LingShuTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var floatingService: IFloatingService

    private val permissionResultCallback = mutableMapOf<String, (Boolean) -> Unit>()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        currentPermission?.let { permission ->
            permissionResultCallback[permission]?.invoke(isGranted)
            permissionResultCallback.remove(permission)
        }
        currentPermission = null
    }

    private var currentPermission: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // 启动悬浮窗（需有 SYSTEM_ALERT_WINDOW 权限）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            floatingService.show()
        }

        setContent {
            LingShuTheme {
                App()
            }
        }
    }

    fun requestPermission(permission: String, callback: (Boolean) -> Unit) {
        permissionResultCallback[permission] = callback
        currentPermission = permission
        requestPermissionLauncher.launch(permission)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

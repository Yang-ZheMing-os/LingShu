package com.lingshu.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lingshu.core.ui.theme.LingShuTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

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

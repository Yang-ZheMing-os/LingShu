package com.lingshu.agent.feature.onboarding

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.lingshu.agent.MainActivity
import com.lingshu.agent.ui.theme.GlassmorphismTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PermissionGuideActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            GlassmorphismTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    OnboardingScreen(
                        onFinish = {
                            val intent = Intent(this, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                            startActivity(intent)
                            finish()
                        }
                    )
                }
            }
        }
    }
}

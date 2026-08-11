package com.lingshu.agent.feature.script

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.lingshu.agent.ui.theme.GlassmorphismTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ScriptWorkshopActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val scriptId = intent.getStringExtra("scriptId")

        setContent {
            GlassmorphismTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    ScriptWorkshopScreen(
                        scriptId = scriptId,
                        onBack = { finish() }
                    )
                }
            }
        }
    }
}

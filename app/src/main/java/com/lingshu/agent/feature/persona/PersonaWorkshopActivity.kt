package com.lingshu.agent.feature.persona

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
class PersonaWorkshopActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val personaId = intent.getStringExtra("personaId")

        setContent {
            GlassmorphismTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    PersonaWorkshopScreen(
                        personaId = personaId,
                        onBack = { finish() }
                    )
                }
            }
        }
    }
}

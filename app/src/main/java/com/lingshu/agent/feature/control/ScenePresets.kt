package com.lingshu.agent.feature.control

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lingshu.agent.ui.components.GlassCard
import com.lingshu.agent.ui.components.GlassSectionTitle
import com.lingshu.agent.ui.theme.AccentGlow
import com.lingshu.agent.ui.theme.AccentPrimary
import com.lingshu.agent.ui.theme.GlassBubble
import com.lingshu.agent.ui.theme.GlassBubbleBorder
import com.lingshu.agent.ui.theme.GlassBubbleStrong
import com.lingshu.agent.ui.theme.Success
import com.lingshu.agent.ui.theme.TextPrimary
import com.lingshu.agent.ui.theme.TextSecondary
import com.lingshu.agent.ui.theme.TextTertiary
import com.lingshu.agent.ui.theme.Warning
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 场景预设数据模型
 */
data class ScenePreset(
    val id: String,
    val name: String,
    val description: String,
    val iconType: String, // "work", "home", "geek"
    /** 系统设置配置 */
    val systemSettings: SceneSystemSettings,
    /** 推荐的人格类型 */
    val personaType: String?,
    /** 推荐的快捷指令名称列表 */
    val quickCommands: List<String>,
    /** 是否开启主动关怀 */
    val proactiveCareEnabled: Boolean
)

data class SceneSystemSettings(
    val wifiEnabled: Boolean? = null,
    val bluetoothEnabled: Boolean? = null,
    val flashlightEnabled: Boolean? = null,
    val nfcEnabled: Boolean? = null,
    val autoRotateEnabled: Boolean? = null,
    val brightnessPercent: Int? = null, // 0-100
    val volumeMusicPercent: Int? = null, // 0-100
    val volumeRingPercent: Int? = null,
    val nightDisplayEnabled: Boolean? = null, // 色温/夜间模式
    val hotspotEnabled: Boolean? = null
)

/**
 * 场景预设管理器
 */
@Singleton
class ScenePresetsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_CURRENT_SCENE = stringPreferencesKey("current_scene_id")

        /** 规格书3.1：三种预设场景 */
        val PRESET_WORK = ScenePreset(
            id = "work",
            name = "工作模式",
            description = "专注高效，通知静音，WiFi开启，亮度70%，禁用娱乐应用",
            iconType = "work",
            systemSettings = SceneSystemSettings(
                wifiEnabled = true,
                bluetoothEnabled = false,
                flashlightEnabled = false,
                autoRotateEnabled = false,
                brightnessPercent = 70,
                volumeMusicPercent = 20,
                volumeRingPercent = 50,
                nightDisplayEnabled = false
            ),
            personaType = "professional",
            quickCommands = listOf("打开WiFi", "静音模式", "截屏", "自动旋转"),
            proactiveCareEnabled = false
        )

        val PRESET_LIFE = ScenePreset(
            id = "life",
            name = "生活模式",
            description = "轻松愉悦，WiFi开启，蓝牙开启，亮度自适应，色温暖和",
            iconType = "home",
            systemSettings = SceneSystemSettings(
                wifiEnabled = true,
                bluetoothEnabled = true,
                flashlightEnabled = false,
                autoRotateEnabled = true,
                brightnessPercent = 50,
                volumeMusicPercent = 60,
                volumeRingPercent = 80,
                nightDisplayEnabled = true
            ),
            personaType = "friendly",
            quickCommands = listOf("打开蓝牙", "打开相机", "打开通知栏", "返回主页"),
            proactiveCareEnabled = true
        )

        val PRESET_GEEK = ScenePreset(
            id = "geek",
            name = "极客模式",
            description = "性能全开，NFC开启，蓝牙开启，亮度100%，所有快捷指令可用",
            iconType = "geek",
            systemSettings = SceneSystemSettings(
                wifiEnabled = true,
                bluetoothEnabled = true,
                nfcEnabled = true,
                autoRotateEnabled = true,
                brightnessPercent = 100,
                volumeMusicPercent = 80,
                volumeRingPercent = 100,
                nightDisplayEnabled = false
            ),
            personaType = "geek",
            quickCommands = listOf(
                "打开WiFi", "打开蓝牙", "打开手电筒", "自动旋转",
                "最大亮度", "截屏", "打开相机", "打开通知栏"
            ),
            proactiveCareEnabled = true
        )
    }

    private val gson = Gson()

    val allPresets: List<ScenePreset> = listOf(PRESET_WORK, PRESET_LIFE, PRESET_GEEK)

    /** 当前激活的场景ID响应式流 */
    val currentSceneFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_CURRENT_SCENE]
    }

    suspend fun getCurrentScene(): ScenePreset? {
        val id = dataStore.data.map { prefs -> prefs[KEY_CURRENT_SCENE] }.first()
        return allPresets.find { it.id == id }
    }

    suspend fun setCurrentScene(sceneId: String) {
        dataStore.edit { prefs ->
            prefs[KEY_CURRENT_SCENE] = sceneId
        }
    }

    /**
     * 一键应用场景：修改系统设置 + 人格切换 + 快捷指令切换 + 主动关怀开关
     */
    suspend fun applyScene(
        scene: ScenePreset,
        systemController: SystemController
    ): List<DeviceActionResult> {
        val results = mutableListOf<DeviceActionResult>()
        val settings = scene.systemSettings

        // WiFi
        settings.wifiEnabled?.let {
            val current = systemController.isWifiEnabled()
            if (current != it) {
                results.add(systemController.setWifiEnabled(it))
            }
        }
        // 蓝牙
        settings.bluetoothEnabled?.let {
            val current = systemController.isBluetoothEnabled()
            if (current != it) {
                results.add(systemController.setBluetoothEnabled(it))
            }
        }
        // 手电筒
        settings.flashlightEnabled?.let {
            results.add(systemController.setFlashlightEnabled(it))
        }
        // NFC
        settings.nfcEnabled?.let {
            if (systemController.isNfcSupported()) {
                val current = systemController.isNfcEnabled()
                if (current != it) {
                    results.add(systemController.setNfcEnabled(it))
                }
            }
        }
        // 自动旋转
        settings.autoRotateEnabled?.let {
            val current = systemController.isAutoRotateEnabled()
            if (current != it) {
                results.add(systemController.setAutoRotateEnabled(it))
            }
        }
        // 亮度
        settings.brightnessPercent?.let {
            val target = (it * 255 / 100).coerceIn(0, 255)
            results.add(systemController.setBrightness(target))
        }
        // 音乐音量
        settings.volumeMusicPercent?.let {
            val max = systemController.getMaxVolume(VolumeType.MUSIC)
            val target = (it * max / 100).coerceIn(0, max)
            results.add(systemController.setVolume(VolumeType.MUSIC, target))
        }
        // 铃声音量
        settings.volumeRingPercent?.let {
            val max = systemController.getMaxVolume(VolumeType.RING)
            val target = (it * max / 100).coerceIn(0, max)
            results.add(systemController.setVolume(VolumeType.RING, target))
        }
        // 夜间模式/色温
        settings.nightDisplayEnabled?.let {
            results.add(systemController.setNightDisplayEnabled(it))
        }

        // 持久化当前场景
        setCurrentScene(scene.id)

        return results
    }
}

/**
 * 场景预设面板（Compose）
 */
@Composable
fun ScenePresetsPanel(
    manager: ScenePresetsManager,
    systemController: SystemController,
    onApplyResult: (List<DeviceActionResult>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentSceneId by manager.currentSceneFlow.collectAsState(initial = null)
    val presets = manager.allPresets

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        GlassSectionTitle(title = "场景预设")
        Spacer(modifier = Modifier.height(12.dp))

        presets.forEach { preset ->
            val isActive = currentSceneId == preset.id
            ScenePresetCard(
                preset = preset,
                isActive = isActive,
                onClick = {
                    scope.launch {
                        val results = manager.applyScene(preset, systemController)
                        onApplyResult(results)
                    }
                }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ScenePresetCard(
    preset: ScenePreset,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val icon = when (preset.iconType) {
        "work" -> Icons.Default.Work
        "home" -> Icons.Default.Home
        "geek" -> Icons.Default.Build
        else -> Icons.Default.CheckCircle
    }

    val accentColor = when (preset.iconType) {
        "work" -> AccentPrimary
        "home" -> Success
        "geek" -> Warning
        else -> AccentGlow
    }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        glowColor = accentColor,
        glowAlpha = if (isActive) 0.35f else 0.1f,
        strong = isActive,
        padding = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 左侧图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                accentColor.copy(alpha = 0.25f),
                                GlassBubble
                            )
                        )
                    )
                    .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = preset.name,
                    tint = accentColor,
                    modifier = Modifier.size(26.dp)
                )
            }

            // 中间内容
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(accentColor.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "当前",
                                style = MaterialTheme.typography.labelSmall,
                                color = accentColor
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(6.dp))
                // 快捷指令标签
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    preset.quickCommands.take(3).forEach { cmd ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(GlassBubbleStrong)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = cmd,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextTertiary
                            )
                        }
                    }
                    if (preset.quickCommands.size > 3) {
                        Text(
                            text = "+${preset.quickCommands.size - 3}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }
                }
            }

            // 右侧：一键应用箭号
            if (!isActive) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "应用",
                    tint = accentColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

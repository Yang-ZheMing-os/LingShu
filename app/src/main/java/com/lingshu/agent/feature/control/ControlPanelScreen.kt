package com.lingshu.agent.feature.control

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingshu.agent.core.model.Script
import com.lingshu.agent.ui.components.GlassCard
import com.lingshu.agent.ui.components.GlassSectionTitle
import com.lingshu.agent.ui.components.GlassSlider
import com.lingshu.agent.ui.components.GlassSwitch
import com.lingshu.agent.ui.theme.AccentGlow
import com.lingshu.agent.ui.theme.AccentPrimary
import com.lingshu.agent.ui.theme.GlassBubble
import com.lingshu.agent.ui.theme.GlassBubbleBorder
import com.lingshu.agent.ui.theme.GlassBubbleStrong
import com.lingshu.agent.ui.theme.PrimaryBackground
import com.lingshu.agent.ui.theme.Success
import com.lingshu.agent.ui.theme.TextPrimary
import com.lingshu.agent.ui.theme.TextSecondary
import com.lingshu.agent.ui.theme.TextTertiary
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.lingshu.agent.ui.theme.Warning

@Composable
fun ControlPanelScreen(
    viewModel: ControlViewModel = hiltViewModel(),
    isPanelOpen: Boolean = false,
    onPanelClose: () -> Unit = {}
) {
    val systemSettings by viewModel.systemSettings.collectAsState()
    val messages by viewModel.messages.collectAsState(initial = null)

    val panelOffset by animateFloatAsState(
        targetValue = if (isPanelOpen) 0f else 1f,
        label = "panelOffset",
        animationSpec = androidx.compose.animation.core.tween(300)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBackground)
    ) {
        if (isPanelOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f * (1f - panelOffset)))
                    .clickable { onPanelClose() }
            )
        }

        AnimatedVisibility(
            visible = isPanelOpen,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it })
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            if (dragAmount > 50f) onPanelClose()
                        }
                    }
            ) {
                ControlPanelContent(
                    systemSettings = systemSettings,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
private fun ControlPanelContent(
    systemSettings: ControlViewModel.SystemSettingsSnapshot,
    viewModel: ControlViewModel
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        PrimaryBackground.copy(alpha = 0.95f),
                        PrimaryBackground
                    )
                )
            )
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text(
                text = "控制面板",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary
            )
        }

        item {
            GlassSectionTitle(title = "快捷开关")
            Spacer(modifier = Modifier.height(12.dp))
            HardwareToggleGrid(systemSettings = systemSettings, viewModel = viewModel)
        }

        item {
            GlassSectionTitle(title = "快捷调节")
            Spacer(modifier = Modifier.height(12.dp))
            QuickAdjustSection(systemSettings = systemSettings, viewModel = viewModel)
        }

        item {
            GlassSectionTitle(title = "常用应用")
            Spacer(modifier = Modifier.height(12.dp))
            QuickAppGrid()
        }

        item {
            GlassSectionTitle(title = "自动化脚本")
            Spacer(modifier = Modifier.height(12.dp))
            AutomationScriptList(viewModel = viewModel)
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun HardwareToggleGrid(
    systemSettings: ControlViewModel.SystemSettingsSnapshot,
    viewModel: ControlViewModel
) {
    val context = LocalContext.current
    val toggles = listOf(
        HardwareToggleItem(
            icon = Icons.Default.Wifi,
            label = "WiFi",
            enabled = systemSettings.wifiEnabled,
            onClick = { viewModel.toggleWifi() },
            activeColor = AccentPrimary
        ),
        HardwareToggleItem(
            icon = Icons.Default.Bluetooth,
            label = "蓝牙",
            enabled = systemSettings.bluetoothEnabled == true,
            onClick = { viewModel.toggleBluetooth() },
            activeColor = AccentPrimary
        ),
        HardwareToggleItem(
            icon = Icons.Default.FlashlightOn,
            label = "手电筒",
            enabled = systemSettings.flashlightEnabled,
            onClick = { viewModel.toggleFlashlight() },
            activeColor = Warning
        ),
        HardwareToggleItem(
            icon = Icons.Default.WifiTethering,
            label = "热点",
            enabled = false,
            onClick = { Toast.makeText(context, "热点功能需要系统权限", Toast.LENGTH_SHORT).show() },
            activeColor = Success
        ),
        HardwareToggleItem(
            icon = Icons.Default.Nfc,
            label = "NFC",
            enabled = systemSettings.nfcEnabled == true,
            onClick = { Toast.makeText(context, "NFC功能需要系统权限", Toast.LENGTH_SHORT).show() },
            activeColor = AccentPrimary
        ),
        HardwareToggleItem(
            icon = Icons.Default.ScreenRotation,
            label = "旋转",
            enabled = systemSettings.autoRotateEnabled,
            onClick = { viewModel.toggleAutoRotate() },
            activeColor = AccentPrimary
        )
    )

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        items(toggles.size) { index ->
            HardwareToggleButton(item = toggles[index])
        }
    }
}

@Composable
private fun HardwareToggleButton(item: HardwareToggleItem) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        shape = RoundedCornerShape(16.dp),
        glowColor = if (item.enabled) item.activeColor else AccentGlow,
        glowAlpha = if (item.enabled) 0.25f else 0.08f,
        strong = item.enabled,
        padding = PaddingValues(8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (item.enabled) item.activeColor.copy(alpha = 0.2f) else GlassBubbleStrong
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = if (item.enabled) item.activeColor else TextTertiary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (item.enabled) TextPrimary else TextTertiary
            )
            GlassSwitch(
                checked = item.enabled,
                onCheckedChange = { item.onClick() }
            )
        }
    }
}

@Composable
private fun QuickAdjustSection(
    systemSettings: ControlViewModel.SystemSettingsSnapshot,
    viewModel: ControlViewModel
) {
    var volume by remember { mutableFloatStateOf(systemSettings.volumeMusic / 15f) }
    var brightness by remember { mutableFloatStateOf((systemSettings.brightness ?: 128) / 255f) }
    var warmth by remember { mutableFloatStateOf(0.5f) }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        padding = PaddingValues(16.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SliderRow(
                icon = Icons.Default.VolumeUp,
                label = "音量",
                value = volume,
                valueLabel = "${(volume * 15).toInt()}",
                onValueChange = {
                    volume = it
                    viewModel.setVolumeVM(
                        VolumeType.MUSIC,
                        (it * 15).toInt()
                    )
                }
            )

            SliderRow(
                icon = Icons.Default.Brightness6,
                label = "亮度",
                value = brightness,
                valueLabel = "${(brightness * 100).toInt()}%",
                onValueChange = {
                    brightness = it
                    viewModel.setBrightnessVM((it * 255).toInt())
                }
            )

            SliderRow(
                icon = Icons.Default.SettingsBrightness,
                label = "色温",
                value = warmth,
                valueLabel = if (warmth < 0.33f) "冷" else if (warmth < 0.66f) "中" else "暖",
                onValueChange = { warmth = it },
                activeTrackBrush = Brush.horizontalGradient(
                    listOf(
                        Color(0xFF66C4FF),
                        Color(0xFFFFFFFF),
                        Color(0xFFFFB066)
                    )
                )
            )
        }
    }
}

@Composable
private fun SliderRow(
    icon: ImageVector,
    label: String,
    value: Float,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    activeTrackBrush: Brush? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(GlassBubbleStrong)
                .border(1.dp, GlassBubbleBorder, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = AccentGlow,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary
                )
            }
            GlassSlider(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun QuickAppGrid() {
    val apps = listOf(
        QuickAppItem(Icons.Default.PhoneAndroid, "电话", "com.android.dialer"),
        QuickAppItem(Icons.Default.PhoneAndroid, "短信", "com.android.mms"),
        QuickAppItem(Icons.Default.Flare, "相机", "com.android.camera"),
        QuickAppItem(Icons.Default.PhoneAndroid, "相册", "com.android.gallery"),
        QuickAppItem(Icons.Default.PhoneAndroid, "音乐", "com.android.music"),
        QuickAppItem(Icons.Default.PhoneAndroid, "设置", "com.android.settings"),
        QuickAppItem(Icons.Default.PhoneAndroid, "日历", "com.android.calendar"),
        QuickAppItem(Icons.Default.PhoneAndroid, "时钟", "com.android.deskclock"),
        QuickAppItem(Icons.Default.PhoneAndroid, "计算器", "com.android.calculator"),
        QuickAppItem(Icons.Default.PhoneAndroid, "天气", "com.android.weather")
    )

    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(apps.size) { index ->
            QuickAppIcon(item = apps[index])
        }
    }
}

@Composable
private fun QuickAppIcon(item: QuickAppItem) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable {  }
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            GlassBubbleStrong,
                            GlassBubble
                        )
                    )
                )
                .border(1.dp, GlassBubbleBorder, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = TextPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AutomationScriptList(viewModel: ControlViewModel) {
    val scripts = remember {
        listOf(
            Script(
                name = "晚安模式",
                description = "开启勿扰、降低亮度、设置闹钟",
                status = com.lingshu.agent.core.model.ScriptStatus.READY
            ),
            Script(
                name = "早晨起床",
                description = "关闭勿扰、播报天气、播放音乐",
                status = com.lingshu.agent.core.model.ScriptStatus.READY
            ),
            Script(
                name = "通勤导航",
                description = "打开地图、播报路况、连接车载",
                status = com.lingshu.agent.core.model.ScriptStatus.READY
            )
        )
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        scripts.forEach { script ->
            ScriptCard(
                script = script,
                onPlay = { viewModel.executeScript(script.content, script.name) }
            )
        }
    }
}

@Composable
private fun ScriptCard(
    script: Script,
    onPlay: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        padding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                AccentPrimary.copy(alpha = 0.3f),
                                GlassBubble
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = AccentGlow,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = script.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    text = script.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                AccentPrimary,
                                AccentGlow
                            )
                        )
                    )
                    .clickable { onPlay() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "运行",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private data class HardwareToggleItem(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean,
    val onClick: () -> Unit,
    val activeColor: Color
)

private data class QuickAppItem(
    val icon: ImageVector,
    val label: String,
    val packageName: String
)

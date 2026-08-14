package com.lingshu.feature.health.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lingshu.core.common.state.UiState
import com.lingshu.core.ui.component.GlassCard
import com.lingshu.feature.health.domain.SleepData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthScreen(
    onNavigateToCommunity: () -> Unit = {},
    viewModel: HealthViewModel = hiltViewModel()
) {
    val hasPermissions by viewModel.hasPermissions.collectAsState()
    val isDeviceSupported by viewModel.isDeviceSupported.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    // 界面回到前台时重新检测真实设备/权限状态（用户从系统设置授权后返回可自动刷新）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onResumeCheck()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("健康数据") },
                actions = {
                    IconButton(onClick = onNavigateToCommunity) {
                        Icon(Icons.Default.Notifications, contentDescription = "社区")
                    }
                    if (hasPermissions) {
                        IconButton(onClick = { viewModel.loadAllData() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            when {
                // 无可检测传感器：显示“未连接可检测设备”
                !isDeviceSupported -> {
                    DeviceNotSupportedScreen()
                }
                // 有传感器但未授权：显示“授予权限”
                !hasPermissions -> {
                    PermissionRequestScreen(
                        onGrantPermissions = { viewModel.requestPermissions() }
                    )
                }
                // 已授权：展示真实健康数据概览
                else -> {
                    HealthDataOverview(
                        viewModel = viewModel,
                        isRefreshing = isRefreshing
                    )
                }
            }
        }
    }
}

/** 无可检测设备时的提示界面 */
@Composable
private fun DeviceNotSupportedScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "未连接可检测设备",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "当前设备未检测到步数或心率等健康传感器，无法展示真实健康数据",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

@Composable
private fun PermissionRequestScreen(
    onGrantPermissions: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Favorite,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "健康数据权限",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "需要身体传感器权限来读取真实的步数、心率等健康数据",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onGrantPermissions) {
            Text("授予权限")
        }
    }
}

@Composable
private fun HealthDataOverview(
    viewModel: HealthViewModel,
    isRefreshing: Boolean
) {
    val heartRate by viewModel.heartRate.collectAsState()
    val steps by viewModel.steps.collectAsState()
    val sleepData by viewModel.sleepData.collectAsState()
    val oxygen by viewModel.oxygen.collectAsState()
    val stressLevel by viewModel.stressLevel.collectAsState()

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "今日概览",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HeartRateCard(
                    state = heartRate,
                    modifier = Modifier.weight(1f)
                )
                StepsCard(
                    state = steps,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            SleepCard(state = sleepData)
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OxygenCard(
                    state = oxygen,
                    modifier = Modifier.weight(1f)
                )
                StressCard(
                    state = stressLevel,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HeartRateCard(
    state: UiState<Int>,
    modifier: Modifier = Modifier
) {
    HealthMetricCard(
        title = "心率",
        icon = Icons.Default.Favorite,
        iconColor = MaterialTheme.colorScheme.error,
        state = state,
        unit = "bpm",
        modifier = modifier
    ) { value ->
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun StepsCard(
    state: UiState<Int>,
    modifier: Modifier = Modifier
) {
    HealthMetricCard(
        title = "步数",
        icon = Icons.Default.Info,
        iconColor = MaterialTheme.colorScheme.primary,
        state = state,
        unit = "步",
        modifier = modifier
    ) { value ->
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun OxygenCard(
    state: UiState<Float>,
    modifier: Modifier = Modifier
) {
    HealthMetricCard(
        title = "血氧",
        icon = Icons.Default.Info,
        iconColor = MaterialTheme.colorScheme.secondary,
        state = state,
        unit = "%",
        modifier = modifier
    ) { value ->
        Text(
            text = String.format("%.1f", value),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun StressCard(
    state: UiState<Float>,
    modifier: Modifier = Modifier
) {
    HealthMetricCard(
        title = "压力",
        icon = Icons.Default.Info,
        iconColor = MaterialTheme.colorScheme.tertiary,
        state = state,
        unit = "级",
        modifier = modifier
    ) { value ->
        val stressText = when {
            value < 30 -> "放松"
            value < 60 -> "正常"
            else -> "偏高"
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format("%.0f", value),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary
            )
            Text(
                text = stressText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun <T> HealthMetricCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    state: UiState<T>,
    unit: String,
    modifier: Modifier = Modifier,
    valueContent: @Composable (T) -> Unit
) {
    GlassCard(
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = title,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            when (state) {
                is UiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp
                    )
                }
                is UiState.Success -> {
                    valueContent(state.data)
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is UiState.Error -> {
                    // 真实错误信息：如“该设备不支持…”“暂无数据”“权限未授予”
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                is UiState.Idle -> {
                    Text(
                        text = "--",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SleepCard(
    state: UiState<SleepData>
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "睡眠",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "睡眠",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (state) {
                is UiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is UiState.Success -> {
                    val sleepData = state.data
                    Text(
                        text = formatSleepDuration(sleepData.totalMinutes),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SleepProgressBar(sleepData = sleepData)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        SleepStageItem(
                            label = "深睡",
                            minutes = sleepData.deepMinutes,
                            color = MaterialTheme.colorScheme.primary
                        )
                        SleepStageItem(
                            label = "浅睡",
                            minutes = sleepData.lightMinutes,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        SleepStageItem(
                            label = "REM",
                            minutes = sleepData.remMinutes,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                is UiState.Error -> {
                    // 真实提示：该设备不支持睡眠监测
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is UiState.Idle -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无睡眠数据",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepProgressBar(
    sleepData: SleepData
) {
    val total = sleepData.totalMinutes.toFloat()
    val deepFraction = sleepData.deepMinutes / total
    val lightFraction = sleepData.lightMinutes / total
    val remFraction = sleepData.remMinutes / total

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .weight(deepFraction)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.primary)
        )
        Box(
            modifier = Modifier
                .weight(lightFraction)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.secondary)
        )
        Box(
            modifier = Modifier
                .weight(remFraction)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.tertiary)
        )
    }
}

@Composable
private fun SleepStageItem(
    label: String,
    minutes: Int,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "${minutes}分",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatSleepDuration(minutes: Int): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return "${hours}小时${mins}分钟"
}

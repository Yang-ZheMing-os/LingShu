package com.lingshu.agent.feature.proactive


import androidx.compose.runtime.Composable
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingshu.agent.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 主动关怀设置页面（模块8）
 *
 * 功能：
 * 1. 总开关控制
 * 2. 冷却时间设置（分钟）
 * 3. 每日关怀上限设置
 * 4. 各类触发条件独立开关（时间/行为/传感器/记忆/随机）
 * 5. 当前运行状态展示（今日触发次数、剩余冷却、上次触发等）
 * 6. 最近关怀历史记录
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Composable
fun CareScreen(
    viewModel: ProactiveCareViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // 本地编辑状态
    var cooldownMinutes by remember { mutableIntStateOf(uiState.config.cooldownMinutes) }
    var dailyLimit by remember { mutableIntStateOf(uiState.config.dailyLimit) }

    // 同步 ViewModel 配置变更到本地状态
    LaunchedEffect(uiState.config.cooldownMinutes) {
        cooldownMinutes = uiState.config.cooldownMinutes
    }
    LaunchedEffect(uiState.config.dailyLimit) {
        dailyLimit = uiState.config.dailyLimit
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBackground)
    ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.NotificationsActive,
                contentDescription = null,
                tint = AccentGlow,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "主动关怀",
                style = MaterialTheme.typography.headlineSmall.copy(
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ========== 主开关 ==========
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (uiState.isEnabled) Icons.Filled.NotificationsActive
                                else Icons.Filled.NotificationsOff,
                                contentDescription = null,
                                tint = if (uiState.isEnabled) AccentGlow else TextTertiary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "主动关怀",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                                Text(
                                    text = if (uiState.isEnabled) "已开启，灵枢将主动关注你的状态"
                                    else "已关闭，灵枢不会主动发送关怀",
                                    style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                                )
                            }
                        }
                        Switch(
                            checked = uiState.isEnabled,
                            onCheckedChange = { viewModel.setEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AccentGlow,
                                checkedTrackColor = AccentGlow.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
            }

            // ========== 运行状态卡片 ==========
            item {
                SectionHeader(
                    title = "运行状态",
                    subtitle = "今日统计",
                    icon = Icons.Filled.BarChart
                )
            }

            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatusMetric(
                            label = "今日已触发",
                            value = "${uiState.todayCareCount}",
                            unit = "次",
                            icon = Icons.Filled.Favorite,
                            color = AccentGlow
                        )
                        StatusMetric(
                            label = "剩余配额",
                            value = "${uiState.dailyLimitRemaining}",
                            unit = "次",
                            icon = Icons.Filled.Schedule,
                            color = AccentGlow
                        )
                        StatusMetric(
                            label = "冷却剩余",
                            value = "${uiState.remainingCooldownMinutes}",
                            unit = "分钟",
                            icon = Icons.Filled.Timer,
                            color = if (uiState.isInCooldown) Color(0xFFEAA800) else AccentGlow
                        )
                    }
                }
            }

            // 上次触发信息
            if (uiState.lastCareTimestamp != null) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.History,
                                    contentDescription = null,
                                    tint = AccentGlow,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "上次触发",
                                    style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                                )
                            }
                            CareStatusBadge(
                                text = uiState.lastCareDescription,
                                isActive = true
                            )
                        }
                    }
                }
            }

            // ========== 配置区域 ==========
            item {
                Spacer(modifier = Modifier.height(4.dp))
                SectionHeader(
                    title = "配置",
                    subtitle = "调整关怀策略",
                    icon = Icons.Filled.Tune
                )
            }

            // 冷却时间
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "冷却时间",
                                style = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary)
                            )
                            Text(
                                text = "${cooldownMinutes} 分钟",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = AccentGlow,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                        Slider(
                            value = cooldownMinutes.toFloat(),
                            onValueChange = { cooldownMinutes = it.toInt() },
                            onValueChangeFinished = {
                                viewModel.setCooldownMinutes(cooldownMinutes)
                            },
                            valueRange = 5f..180f,
                            steps = 0,
                            colors = SliderDefaults.colors(
                                thumbColor = AccentGlow,
                                activeTrackColor = AccentGlow
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("5m", style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary))
                            Text("180m", style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary))
                        }
                    }
                }
            }

            // 每日上限
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "每日关怀上限",
                                style = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary)
                            )
                            Text(
                                text = "${dailyLimit} 次",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = AccentGlow,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                        Slider(
                            value = dailyLimit.toFloat(),
                            onValueChange = { dailyLimit = it.toInt() },
                            onValueChangeFinished = {
                                viewModel.setDailyLimit(dailyLimit)
                            },
                            valueRange = 1f..20f,
                            steps = 0,
                            colors = SliderDefaults.colors(
                                thumbColor = AccentGlow,
                                activeTrackColor = AccentGlow
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("1次", style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary))
                            Text("20次", style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary))
                        }
                    }
                }
            }

            // ========== 阈值调整（规格书 P5） ==========
            item {
                Spacer(modifier = Modifier.height(4.dp))
                SectionHeader(
                    title = "阈值调整",
                    subtitle = "微调触发灵敏度",
                    icon = Icons.Filled.Tune
                )
            }

            // 久坐阈值
            item {
                ThresholdSliderCard(
                    label = "久坐提醒阈值",
                    value = uiState.config.sedentaryThresholdMinutes,
                    unit = "分钟",
                    range = 60f..240f,
                    step = 15f,
                    onChanged = { viewModel.setSedentaryThreshold(it) }
                )
            }

            // 心率上下限
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("心率异常阈值", style = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("下限", style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary))
                                Text("${uiState.config.heartRateLower} bpm",
                                    style = MaterialTheme.typography.labelMedium.copy(color = AccentGlow))
                                Slider(
                                    value = uiState.config.heartRateLower.toFloat(),
                                    onValueChange = { viewModel.setHeartRateLower(it.toInt()) },
                                    valueRange = 30f..80f,
                                    colors = SliderDefaults.colors(thumbColor = AccentGlow, activeTrackColor = AccentGlow)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("上限", style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary))
                                Text("${uiState.config.heartRateUpper} bpm",
                                    style = MaterialTheme.typography.labelMedium.copy(color = AccentGlow))
                                Slider(
                                    value = uiState.config.heartRateUpper.toFloat(),
                                    onValueChange = { viewModel.setHeartRateUpper(it.toInt()) },
                                    valueRange = 80f..160f,
                                    colors = SliderDefaults.colors(thumbColor = AccentGlow, activeTrackColor = AccentGlow)
                                )
                            }
                        }
                    }
                }
            }

            // 压力指数阈值
            item {
                ThresholdSliderCard(
                    label = "压力指数阈值",
                    value = uiState.config.stressIndexThreshold,
                    unit = "%",
                    range = 30f..100f,
                    step = 5f,
                    onChanged = { viewModel.setStressIndexThreshold(it) }
                )
            }

            // 降水概率阈值
            item {
                ThresholdSliderCard(
                    label = "降水概率提醒阈值",
                    value = uiState.config.rainProbabilityThreshold,
                    unit = "%",
                    range = 30f..95f,
                    step = 5f,
                    onChanged = { viewModel.setRainProbabilityThreshold(it) }
                )
            }

            // 暗光阈值
            item {
                ThresholdSliderCard(
                    label = "暗光环境阈值",
                    value = uiState.config.lowLightLuxThreshold,
                    unit = "lux",
                    range = 2f..30f,
                    step = 1f,
                    onChanged = { viewModel.setLowLightLuxThreshold(it) }
                )
            }

            // ========== 勿扰时段（规格书 4.2） ==========
            item {
                Spacer(modifier = Modifier.height(4.dp))
                SectionHeader(
                    title = "勿扰时段",
                    subtitle = "该时段内静默不推送",
                    icon = Icons.Filled.Bedtime
                )
            }

            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("启用勿扰时段", style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary))
                        Switch(
                            checked = uiState.config.quietHoursEnabled,
                            onCheckedChange = { viewModel.setQuietHoursEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AccentGlow,
                                checkedTrackColor = AccentGlow.copy(alpha = 0.3f)
                            )
                        )
                    }
                    if (uiState.config.quietHoursEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("开始", style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary))
                                Spacer(modifier = Modifier.height(4.dp))
                                TimePickerButton(
                                    hour = uiState.config.quietHoursStart,
                                    onHourChanged = { viewModel.setQuietHoursStart(it) }
                                )
                            }
                            Text("—", style = MaterialTheme.typography.bodyLarge.copy(color = TextSecondary))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("结束", style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary))
                                Spacer(modifier = Modifier.height(4.dp))
                                TimePickerButton(
                                    hour = uiState.config.quietHoursEnd,
                                    onHourChanged = { viewModel.setQuietHoursEnd(it) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
            item {
                Spacer(modifier = Modifier.height(4.dp))
                SectionHeader(
                    title = "触发条件",
                    subtitle = "独立控制各类触发",
                    icon = Icons.Filled.FilterList
                )
            }

            // 按类别分组展示触发条件
            val triggerGroups = listOf(
                TriggerGroupHeader("时间触发", Icons.Filled.Schedule) to
                    listOf(TriggerType.TIME_LATE_NIGHT, TriggerType.MEAL_REMINDER, TriggerType.TIME_FIXED, TriggerType.TIME_USER_REMINDER),
                TriggerGroupHeader("行为触发", Icons.Filled.TouchApp) to
                    listOf(TriggerType.BEHAVIOR_UNLOCK, TriggerType.BEHAVIOR_LATE_APP_USE, TriggerType.BEHAVIOR_LONG_APP_STAY),
                TriggerGroupHeader("传感器触发", Icons.Filled.Sensors) to
                    listOf(TriggerType.SENSOR_SEDENTARY, TriggerType.LOW_LIGHT_FLASHLIGHT, TriggerType.SENSOR_HEART_RATE, TriggerType.STRESS_INDEX, TriggerType.SENSOR_LONG_STILL),
                TriggerGroupHeader("环境触发", Icons.Filled.Cloud) to
                    listOf(TriggerType.RAIN_UMBRELLA),
                TriggerGroupHeader("记忆触发", Icons.Filled.Bookmark) to
                    listOf(TriggerType.MEMORY_BIRTHDAY, TriggerType.MEMORY_ANNIVERSARY, TriggerType.MEMORY_NEGATIVE_MOOD),
                TriggerGroupHeader("随机关怀", Icons.Filled.Casino) to
                    listOf(TriggerType.RANDOM)
            )

            triggerGroups.forEach { (header, triggers) ->
                item {
                    Text(
                        text = header.title,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = TextSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
                triggers.forEach { triggerType ->
                    val isEnabled = uiState.config.enabledTriggerTypes.contains(triggerType)
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = triggerType.displayName,
                                    style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary)
                                )
                                Switch(
                                    checked = isEnabled,
                                    onCheckedChange = { viewModel.toggleTrigger(triggerType, it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = AccentGlow,
                                        checkedTrackColor = AccentGlow.copy(alpha = 0.3f)
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // ========== 最近关怀记录 ==========
            if (uiState.recentHistory.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    SectionHeader(
                        title = "最近关怀",
                        subtitle = "最近 ${uiState.recentHistory.size} 条",
                        icon = Icons.Filled.History
                    )
                }

                items(uiState.recentHistory) { record ->
                    CareHistoryCard(record = record)
                }
            }

            // ========== 重置按钮 ==========
            item {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.resetConfig() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = TextSecondary
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(GlassBorder)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Restore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("重置为默认配置")
                }
            }

            // 底部间距
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

// ==================== 子组件 ====================

private data class TriggerGroupHeader(
    val title: String,
    val icon: ImageVector
)

/**
 * 区域标题
 */
@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    icon: ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AccentGlow,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary)
        )
    }
}

/**
 * 状态指标（用于运行状态卡片内）
 */
@Composable
private fun StatusMetric(
    label: String,
    value: String,
    unit: String,
    icon: ImageVector,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(22.dp)
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = unit,
                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary)
        )
    }
}

/**
 * 关怀历史记录卡片
 */
@Composable
private fun CareHistoryCard(record: CareRecordEntry) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AccentGlow.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = AccentGlow,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.triggerType.categoryDisplayName(),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = TextPrimary,
                        fontWeight = FontWeight.Medium
                    )
                )
                if (record.content.isNotBlank()) {
                    Text(
                        text = record.content,
                        style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary),
                        maxLines = 2
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = formatRelativeTime(record.sentAt),
                style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary)
            )
        }
    }
}

/**
 * 格式化相对时间
 */
private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000}小时前"
        diff < 7 * 86_400_000 -> "${diff / 86_400_000}天前"
        else -> {
            val sdf = SimpleDateFormat("M月d日", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}

/**
 * 关怀状态徽章（内联替代 StatusChip）
 */
@Composable
private fun CareStatusBadge(text: String, isActive: Boolean) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isActive) AccentGlow.copy(alpha = 0.15f) else TextTertiary.copy(alpha = 0.1f),
        modifier = Modifier
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                color = if (isActive) AccentGlow else TextTertiary,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 阈值滑块卡片
 */
@Composable
private fun ThresholdSliderCard(
    label: String,
    value: Int,
    unit: String,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onChanged: (Int) -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary))
                Text(
                    text = "$value $unit",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = AccentGlow, fontWeight = FontWeight.SemiBold
                    )
                )
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onChanged(it.toInt()) },
                valueRange = range,
                steps = 0,
                colors = SliderDefaults.colors(thumbColor = AccentGlow, activeTrackColor = AccentGlow)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${range.start.toInt()}$unit", style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary))
                Text("${range.endInclusive.toInt()}$unit", style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary))
            }
        }
    }
}

/**
 * 简易时间选择器
 */
@Composable
private fun TimePickerButton(
    hour: Int,
    onHourChanged: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "${hour.toString().padStart(2, '0')}:00",
            style = MaterialTheme.typography.titleMedium.copy(
                color = AccentGlow, fontWeight = FontWeight.SemiBold
            )
        )
    }
}

/**
 * 触发类别 → 中文展示名
 */
private fun TriggerTypeCategory.categoryDisplayName(): String = when (this) {
    TriggerTypeCategory.TIME -> "时间触发"
    TriggerTypeCategory.BEHAVIOR -> "行为触发"
    TriggerTypeCategory.SENSOR -> "传感器触发"
    TriggerTypeCategory.MEMORY -> "记忆触发"
    TriggerTypeCategory.RANDOM -> "随机关怀"
}


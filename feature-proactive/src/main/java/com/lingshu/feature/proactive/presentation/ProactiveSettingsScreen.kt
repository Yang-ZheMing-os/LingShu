package com.lingshu.feature.proactive.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingshu.feature.proactive.domain.CheckStep
import com.lingshu.feature.proactive.domain.ProactiveDiagnostics
import com.lingshu.feature.proactive.domain.QuietHours
import com.lingshu.feature.proactive.domain.TriggerHitResult
import com.lingshu.feature.proactive.domain.TriggerType
import com.lingshu.core.ui.component.GlassCard

val triggerDisplayNames = mapOf(
    TriggerType.LATE_NIGHT to "深夜未睡提醒",
    TriggerType.MEAL_TIME to "饭点进食提醒",
    TriggerType.SEDENTARY to "久坐活动提醒",
    TriggerType.DARK_WALKING to "暗光行走提醒",
    TriggerType.HEART_RATE to "心率异常提醒",
    TriggerType.STRESS to "压力指数提醒",
    TriggerType.RAINY_DAY to "雨天带伞提醒"
)

val triggerDescriptions = mapOf(
    TriggerType.LATE_NIGHT to "23:30-05:00 屏幕亮屏时提醒休息",
    TriggerType.MEAL_TIME to "早中晚饭点时段提醒按时吃饭",
    TriggerType.SEDENTARY to "连续久坐2小时提醒起身活动",
    TriggerType.DARK_WALKING to "暗光环境下行走提醒打开手电筒",
    TriggerType.HEART_RATE to "静息心率异常时及时提醒",
    TriggerType.STRESS to "压力指数过高时提醒放松",
    TriggerType.RAINY_DAY to "降水概率高时提醒带伞"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProactiveSettingsScreen(
    onBackClick: () -> Unit = {},
    viewModel: ProactiveViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsState()
    val status by viewModel.status.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val diagnosticsRunning by viewModel.diagnosticsRunning.collectAsState()
    var showCooldownDialog by remember { mutableStateOf(false) }
    var showMaxPerDayDialog by remember { mutableStateOf(false) }
    var showQuietHoursDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        TopAppBar(
            title = { Text("主动关怀", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "主动关怀",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "智能识别场景，主动送上关怀",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { viewModel.toggleEnabled(it) }
                        )
                    }
                }
            }

            item {
                Text(
                    text = "今日统计",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        StatItem(
                            label = "今日推送",
                            value = "${status.todayNotificationCount}/${config.maxPerDay}"
                        )
                        StatItem(
                            label = "冷却时间",
                            value = "${config.cooldownMinutes}分钟"
                        )
                    }
                }
            }

            // ========== 🔍 实时诊断卡片 ==========
            item {
                Text(
                    text = "实时诊断",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                DiagnosticsCard(
                    diagnostics = diagnostics,
                    running = diagnosticsRunning,
                    onRefresh = { viewModel.runDiagnostics() }
                )
            }

            item {
                Text(
                    text = "触发条件",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            items(TriggerType.values().toList()) { triggerType ->
                TriggerItem(
                    triggerType = triggerType,
                    enabled = config.triggers[triggerType] ?: true,
                    onToggle = { viewModel.toggleTrigger(triggerType, it) }
                )
            }

            // Day3-3：雨天提醒专用配置（和风天气 Key + 位置）
            item {
                if ((config.triggers[TriggerType.RAINY_DAY] ?: true)) {
                    RainyDayConfigCard(
                        qWeatherKey = config.qWeatherKey,
                        location = config.qWeatherLocation,
                        onKeyChange = { viewModel.updateQWeatherKey(it) },
                        onLocationChange = { viewModel.updateQWeatherLocation(it) }
                    )
                }
            }

            item {
                Text(
                    text = "高级设置",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            item {
                SettingsItem(
                    title = "冷却时间",
                    subtitle = "${config.cooldownMinutes} 分钟",
                    onClick = { showCooldownDialog = true }
                )
            }

            item {
                SettingsItem(
                    title = "每日最大推送次数",
                    subtitle = "${config.maxPerDay} 次",
                    onClick = { showMaxPerDayDialog = true }
                )
            }

            item {
                SettingsItem(
                    title = "静音时段",
                    subtitle = "${String.format("%02d:%02d", config.quietHours.startHour, config.quietHours.startMinute)} - ${String.format("%02d:%02d", config.quietHours.endHour, config.quietHours.endMinute)}",
                    onClick = { showQuietHoursDialog = true }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    onClick = { viewModel.triggerTestNotification() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("发送测试通知")
                }
            }
        }
    }

    if (showCooldownDialog) {
        NumberInputDialog(
            title = "冷却时间",
            label = "分钟",
            initialValue = config.cooldownMinutes,
            minValue = 5,
            maxValue = 240,
            onDismiss = { showCooldownDialog = false },
            onConfirm = {
                viewModel.updateCooldownMinutes(it)
                showCooldownDialog = false
            }
        )
    }

    if (showMaxPerDayDialog) {
        NumberInputDialog(
            title = "每日最大推送次数",
            label = "次",
            initialValue = config.maxPerDay,
            minValue = 1,
            maxValue = 20,
            onDismiss = { showMaxPerDayDialog = false },
            onConfirm = {
                viewModel.updateMaxPerDay(it)
                showMaxPerDayDialog = false
            }
        )
    }

    if (showQuietHoursDialog) {
        QuietHoursDialog(
            currentQuietHours = config.quietHours,
            onDismiss = { showQuietHoursDialog = false },
            onConfirm = { quietHours ->
                viewModel.updateQuietHours(quietHours)
                showQuietHoursDialog = false
            }
        )
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun TriggerItem(
    triggerType: TriggerType,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = triggerDisplayNames[triggerType] ?: triggerType.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = triggerDescriptions[triggerType] ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle
            )
        }
    }
}

// ======================================================================
//  Day3-3：雨天提醒配置卡片：和风天气 Key + 城市位置输入框
// ======================================================================
@Composable
private fun RainyDayConfigCard(
    qWeatherKey: String,
    location: String,
    onKeyChange: (String) -> Unit,
    onLocationChange: (String) -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "雨天带伞提醒配置",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "注册 https://dev.qweather.com 免费领 Key，" +
                        "复制 Web API Key（非 SDK Key）粘贴到下面即可；" +
                        "位置留空 = 根据 IP 自动定位。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            OutlinedTextField(
                value = qWeatherKey,
                onValueChange = onKeyChange,
                label = { Text("和风天气 Web API Key") },
                placeholder = { Text("例如：a1b2c3d4e5f67890") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = if (location == "auto_ip") "" else location,
                onValueChange = { raw ->
                    onLocationChange(raw.ifBlank { "auto_ip" })
                },
                label = { Text("所在位置（城市 ID / 中文名称，留空=自动）") },
                placeholder = { Text("例如：北京 / 101010100 / 上海") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

// ======================================================================
//  🔍 实时诊断卡片：显示 4 关状态 + 各 trigger 命中 + 最终结论
// ======================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsCard(
    diagnostics: ProactiveDiagnostics?,
    running: Boolean,
    onRefresh: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题行 + 刷新按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "🔍 主动关怀诊断器",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (diagnostics != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "当前时间 ${diagnostics.currentTimeText}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (diagnostics?.activeTimeWindows?.isNotEmpty() == true) {
                        Text(
                            text = diagnostics.activeTimeWindows.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                TextButton(onClick = onRefresh, enabled = !running) {
                    Text(if (running) "诊断中…" else "重新诊断")
                }
            }

            Spacer(Modifier.height(12.dp))

            when {
                running -> LoadingRow("正在运行 4 关检查 + 触发器命中判断…")
                diagnostics == null -> LoadingRow("尚未生成诊断结果…")
                else -> {
                    // 第 1-3 关
                    StepRow(title = "① 总开关", step = diagnostics.stepEnabled)
                    StepRow(title = "② 静音时段", step = diagnostics.stepQuietHours)
                    StepRow(title = "③ 冷却 / 当日上限", step = diagnostics.stepCooldown)

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "④ 各触发器命中",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    diagnostics.stepTriggers.forEach { (t, r) ->
                        TriggerRow(trigger = t, result = r)
                    }

                    Spacer(Modifier.height(14.dp))
                    // 结论：不同 pass 情况用不同颜色
                    val allPassed = diagnostics.stepEnabled.passed &&
                                    diagnostics.stepQuietHours.passed &&
                                    diagnostics.stepCooldown.passed
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (allPassed)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            else
                                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = diagnostics.conclusion,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "⏳ ",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StepRow(title: String, step: CheckStep) {
    Row(
        modifier = Modifier.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (step.passed) "✅" else "❌",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = step.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TriggerRow(trigger: TriggerType, result: TriggerHitResult) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        val icon = when {
            !result.userEnabled -> "🔕"
            result.ultimatelyPicked -> "🎯"
            result.logicHit && result.filteredByProbability == true -> "⚖️"
            result.logicHit -> "🟢"
            else -> "⏭️"
        }
        Text(
            text = icon,
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = triggerDisplayNames[trigger] ?: trigger.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = result.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun NumberInputDialog(
    title: String,
    label: String,
    initialValue: Int,
    minValue: Int,
    maxValue: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember { mutableStateOf(initialValue.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { char -> char.isDigit() } },
                label = { Text(label) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = text.toIntOrNull() ?: initialValue
                    val clampedValue = value.coerceIn(minValue, maxValue)
                    onConfirm(clampedValue)
                }
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}


@Composable
fun QuietHoursDialog(
    currentQuietHours: QuietHours,
    onDismiss: () -> Unit,
    onConfirm: (QuietHours) -> Unit
) {
    var startHourText by remember { mutableStateOf(currentQuietHours.startHour.toString()) }
    var startMinuteText by remember { mutableStateOf(currentQuietHours.startMinute.toString()) }
    var endHourText by remember { mutableStateOf(currentQuietHours.endHour.toString()) }
    var endMinuteText by remember { mutableStateOf(currentQuietHours.endMinute.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quiet Hours") },
        text = {
            Column {
                Text("Start Time")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = startHourText,
                        onValueChange = { startHourText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Hour") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = startMinuteText,
                        onValueChange = { startMinuteText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Minute") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("End Time")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = endHourText,
                        onValueChange = { endHourText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Hour") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = endMinuteText,
                        onValueChange = { endMinuteText = it.filter { c -> c.isDigit() }.take(2) },
                        label = { Text("Minute") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val startHour = startHourText.toIntOrNull()?.coerceIn(0, 23) ?: 0
                    val startMinute = startMinuteText.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    val endHour = endHourText.toIntOrNull()?.coerceIn(0, 23) ?: 0
                    val endMinute = endMinuteText.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    onConfirm(QuietHours(startHour, startMinute, endHour, endMinute))
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

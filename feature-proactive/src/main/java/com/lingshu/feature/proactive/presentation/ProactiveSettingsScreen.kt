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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.lingshu.feature.proactive.domain.QuietHours
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
    viewModel: ProactiveViewModel = hiltViewModel()
) {
    val config by viewModel.config.collectAsState()
    val status by viewModel.status.collectAsState()
    var showCooldownDialog by remember { mutableStateOf(false) }
    var showMaxPerDayDialog by remember { mutableStateOf(false) }
    var showQuietHoursDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        TopAppBar(
            title = { Text("主动关怀", fontWeight = FontWeight.Bold) }
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

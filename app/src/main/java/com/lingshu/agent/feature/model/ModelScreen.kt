package com.lingshu.agent.feature.model

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingshu.agent.ui.theme.*
import java.io.File

/**
 * P2 模型管理页面
 *
 * 按规格书实现：
 * 1. 各模型状态：未下载/下载中/已下载/已加载
 * 2. 模型大小（MB/GB）、版本
 * 3. 下载进度（百分比+速度 KB/s）
 * 4. 操作按钮：下载/切换/删除/自动更新开关
 * 5. 扫描 /data/data/com.lingshu/files/models/ 目录
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(
    viewModel: ModelSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val modelStatuses by viewModel.modelStatuses.collectAsState()
    val context = LocalContext.current

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
                imageVector = Icons.Filled.SmartToy,
                contentDescription = null,
                tint = AccentGlow,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "模型管理",
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
            // ========== 本地模型区域 ==========
            item {
                SectionHeader(
                    title = "本地模型",
                    subtitle = "扫描 /data/data/com.lingshu/files/models/",
                    icon = Icons.Filled.PhoneAndroid
                )
            }

            if (modelStatuses.isEmpty()) {
                item {
                    EmptyStateCard(
                        message = "未找到本地模型文件",
                        hint = "请下载模型文件到 /data/data/com.lingshu/files/models/ 目录"
                    )
                }
            } else {
                items(modelStatuses, key = { it.modelId }) { model ->
                    ModelDetailCard(
                        model = model,
                        isCurrentProvider = (model.modelId == uiState.currentProviderId),
                        onDownload = { viewModel.startDownload(model.modelId) },
                        onSwitch = { viewModel.switchCurrentProvider(model.modelId) },
                        onDelete = { viewModel.deleteModel(model.modelId) },
                        onToggleAutoUpdate = { enabled -> viewModel.toggleAutoUpdate(model.modelId, enabled) }
                    )
                }
            }

            // ========== 降级策略配置 ==========
            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(
                    title = "降级策略",
                    subtitle = "Gemma → Qwen → 云端API",
                    icon = Icons.Filled.SwapHoriz
                )
            }

            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FallbackChainRow(
                            label = "Gemma 降级链",
                            chain = listOf("Gemma 4 E2B", "Qwen", "云端API"),
                            isEnabled = true
                        )
                        FallbackChainRow(
                            label = "LiteRT 降级",
                            chain = listOf("LiteRT GPU", "CPU 推理"),
                            isEnabled = modelStatuses.any { it.modelType == "gemma" && it.isLoaded }
                        )
                    }
                }
            }

            // ========== 云端 Provider 状态 ==========
            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(
                    title = "云端 Provider",
                    subtitle = "已配置的模型提供者",
                    icon = Icons.Filled.Cloud
                )
            }

            if (uiState.modelItems.isEmpty()) {
                item {
                    EmptyStateCard(
                        message = "暂无已配置的 Provider",
                        hint = "请在设置页添加 API Key 以启用云端模型"
                    )
                }
            } else {
                items(uiState.modelItems) { modelItem ->
                    ProviderStatusCard(modelItem = modelItem)
                }
            }

            // ========== 当前路由状态 ==========
            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(
                    title = "当前路由状态",
                    subtitle = "意图分类器自动选择",
                    icon = Icons.Filled.Route
                )
            }

            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "当前模型",
                                style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = uiState.currentProviderName ?: "自动路由（IntentClassifier）",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                        ProviderStatusBadge(
                            text = if (uiState.currentProviderName != null) "运行中" else "自动",
                            isActive = uiState.currentProviderName != null
                        )
                    }
                }
            }

            // ========== 全局配置摘要 ==========
            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionHeader(
                    title = "全局配置",
                    subtitle = "自动降级与Key轮询",
                    icon = Icons.Filled.Tune
                )
            }

            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ConfigRow(label = "默认对话模型", value = uiState.defaultChatProviderId)
                        ConfigRow(label = "默认视觉模型", value = uiState.defaultVisionProviderId)
                        ConfigRow(label = "默认语音识别", value = uiState.defaultTranscribeProviderId)
                        ConfigRow(label = "默认语音合成", value = uiState.defaultSynthesizeProviderId)
                        HorizontalDivider(color = GlassBorder)
                        ConfigRow(label = "自动降级", value = if (uiState.autoFallbackEnabled) "开" else "关")
                        ConfigRow(label = "Key 轮询", value = if (uiState.apiKeyRotationEnabled) "开" else "关")
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

// ==================== 模型详情卡片（规格书要求） ====================

@Composable
private fun ModelDetailCard(
    model: ModelStatusInfo,
    isCurrentProvider: Boolean,
    onDownload: () -> Unit,
    onSwitch: () -> Unit,
    onDelete: () -> Unit,
    onToggleAutoUpdate: (Boolean) -> Unit
) {
    var autoUpdateEnabled by remember { mutableStateOf(model.autoUpdateEnabled) }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 模型名称行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when (model.downloadState) {
                                    ModelDownloadState.LOADED -> AccentGlow.copy(alpha = 0.15f)
                                    ModelDownloadState.DOWNLOADING -> Color(0xFFFFA726).copy(alpha = 0.15f)
                                    ModelDownloadState.DOWNLOADED -> Color(0xFF66BB6A).copy(alpha = 0.15f)
                                    ModelDownloadState.FAILED -> Color(0xFFEF5350).copy(alpha = 0.15f)
                                    else -> GlassBackground
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (model.modelType) {
                                "gemma" -> Icons.Filled.Memory
                                "minicpm" -> Icons.Filled.Visibility
                                "qwen" -> Icons.Filled.Psychology
                                else -> Icons.Filled.SmartToy
                            },
                            contentDescription = null,
                            tint = when (model.downloadState) {
                                ModelDownloadState.LOADED -> AccentGlow
                                ModelDownloadState.DOWNLOADING -> Color(0xFFFFA726)
                                ModelDownloadState.DOWNLOADED -> Color(0xFF66BB6A)
                                ModelDownloadState.FAILED -> Color(0xFFEF5350)
                                else -> TextSecondary
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = model.modelName,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        if (model.version.isNotEmpty()) {
                            Text(
                                text = "v${model.version}",
                                style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary)
                            )
                        }
                    }
                }

                // 状态标记 + 使用中标识
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isCurrentProvider) {
                        ProviderStatusBadge(text = "当前使用", isActive = true)
                    }
                    ProviderStatusBadge(
                        text = when (model.downloadState) {
                            ModelDownloadState.NOT_DOWNLOADED -> "未下载"
                            ModelDownloadState.DOWNLOADING -> "下载中"
                            ModelDownloadState.DOWNLOADED -> "已下载"
                            ModelDownloadState.LOADED -> "已加载"
                            ModelDownloadState.FAILED -> "失败"
                        },
                        isActive = model.downloadState == ModelDownloadState.LOADED
                            || model.downloadState == ModelDownloadState.DOWNLOADED
                    )
                }
            }

            // 大小 + 版本信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                InfoChip(label = "大小", value = model.fileSizeFormatted)
                if (model.version.isNotEmpty()) {
                    InfoChip(label = "版本", value = model.version)
                }
                InfoChip(label = "类型", value = model.modelType.uppercase())
            }

            // 下载进度条（仅下载中显示）
            if (model.downloadState == ModelDownloadState.DOWNLOADING) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${model.progressPercent}%",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = AccentGlow,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Text(
                            text = model.downloadSpeedFormatted,
                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                        )
                    }
                    LinearProgressIndicator(
                        progress = { model.downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = AccentGlow,
                        trackColor = GlassBorder
                    )
                }
            }

            // 操作按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 下载/重试按钮
                if (model.downloadState == ModelDownloadState.NOT_DOWNLOADED
                    || model.downloadState == ModelDownloadState.FAILED
                ) {
                    ActionButton(
                        text = if (model.downloadState == ModelDownloadState.FAILED) "重试" else "下载",
                        icon = Icons.Filled.Download,
                        color = AccentGlow,
                        onClick = onDownload,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 切换按钮
                if (model.downloadState == ModelDownloadState.DOWNLOADED
                    || model.downloadState == ModelDownloadState.LOADED
                ) {
                    ActionButton(
                        text = if (isCurrentProvider) "使用中" else "切换",
                        icon = if (isCurrentProvider) Icons.Filled.CheckCircle else Icons.Filled.SwapHoriz,
                        color = if (isCurrentProvider) Color(0xFF66BB6A) else AccentGlow,
                        enabled = !isCurrentProvider,
                        onClick = onSwitch,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 删除按钮
                if (model.downloadState == ModelDownloadState.DOWNLOADED
                    || model.downloadState == ModelDownloadState.FAILED
                ) {
                    ActionButton(
                        text = "删除",
                        icon = Icons.Filled.Delete,
                        color = Color(0xFFEF5350),
                        onClick = onDelete,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 自动更新开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "自动更新",
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                )
                Switch(
                    checked = autoUpdateEnabled,
                    onCheckedChange = { enabled ->
                        autoUpdateEnabled = enabled
                        onToggleAutoUpdate(enabled)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AccentGlow,
                        checkedTrackColor = AccentGlow.copy(alpha = 0.3f)
                    )
                )
            }
        }
    }
}

// ==================== 子组件 ====================

@Composable
private fun SectionHeader(title: String, subtitle: String, icon: ImageVector) {
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

@Composable
private fun EmptyStateCard(message: String, hint: String) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Inbox,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(36.dp)
            )
            Text(text = message, style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary))
            Text(text = hint, style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary))
        }
    }
}

@Composable
private fun ProviderStatusCard(modelItem: ModelItemUiState) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (modelItem.isCurrentProvider) AccentGlow.copy(alpha = 0.15f) else GlassBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (modelItem.isLocal) Icons.Filled.PhoneAndroid else Icons.Filled.Cloud,
                    contentDescription = null,
                    tint = if (modelItem.isCurrentProvider) AccentGlow else TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = modelItem.providerName,
                    style = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary, fontWeight = FontWeight.Medium)
                )
                Text(text = modelItem.capabilitySummary, style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary))
                Text(text = modelItem.statusSummary, style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary))
            }
            ProviderStatusBadge(
                text = when { !modelItem.isEnabled -> "已禁用"; modelItem.isConfigured -> "就绪"; else -> "待配置" },
                isActive = modelItem.isEnabled && modelItem.isConfigured
            )
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: ImageVector,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(8.dp),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = Brush.horizontalGradient(listOf(color.copy(alpha = 0.3f), color.copy(alpha = 0.1f)))
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = color,
            disabledContentColor = TextTertiary
        )
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "$label: ", style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary))
        Text(text = value, style = MaterialTheme.typography.labelSmall.copy(color = TextPrimary, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun FallbackChainRow(label: String, chain: List<String>, isEnabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            chain.forEachIndexed { index, name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (isEnabled) AccentGlow else TextTertiary,
                        fontWeight = FontWeight.Medium
                    )
                )
                if (index < chain.size - 1) {
                    Text(text = "→", style = MaterialTheme.typography.labelSmall.copy(color = TextTertiary))
                }
            }
        }
    }
}

@Composable
private fun ConfigRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary))
        Text(text = value, style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun ProviderStatusBadge(text: String, isActive: Boolean) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isActive) AccentGlow.copy(alpha = 0.15f) else TextTertiary.copy(alpha = 0.1f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                color = if (isActive) AccentGlow else TextTertiary,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

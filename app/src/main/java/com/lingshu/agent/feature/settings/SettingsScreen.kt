package com.lingshu.agent.feature.settings

import android.content.Intent
import android.Manifest
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SpeakerNotes
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingshu.agent.core.model.BigFiveTraits
import com.lingshu.agent.core.model.Persona
import com.lingshu.agent.core.database.entity.MemoryEntity
import com.lingshu.agent.feature.model.ModelItemUiState
import com.lingshu.agent.feature.model.ModelSettingsViewModel
import com.lingshu.agent.feature.persona.PersonaViewModel
import com.lingshu.agent.feature.persona.PersonaWorkshopActivity
import com.lingshu.agent.feature.script.ScriptWorkshopActivity
import com.lingshu.agent.services.LingShuAccessibilityService
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.AutoFixHigh
import com.lingshu.agent.feature.proactive.ProactiveConfig
import com.lingshu.agent.feature.personality.PersonalityState
import com.lingshu.agent.ui.components.GlassAlertDialog
import com.lingshu.agent.ui.components.GlassButton
import com.lingshu.agent.ui.components.GlassCard
import com.lingshu.agent.ui.components.GlassChip
import com.lingshu.agent.ui.components.GlassExposedDropdownMenuBox
import com.lingshu.agent.ui.components.GlassIconButton
import com.lingshu.agent.ui.components.GlassSectionTitle
import com.lingshu.agent.ui.components.GlassSlider
import com.lingshu.agent.ui.components.GlassSwitch
import com.lingshu.agent.ui.components.GlassTextField
import com.lingshu.agent.ui.components.GlassTopAppBar
import com.lingshu.agent.ui.theme.AccentGlow
import com.lingshu.agent.ui.theme.AccentPrimary
import com.lingshu.agent.ui.theme.Error
import com.lingshu.agent.ui.theme.GlassBubble
import com.lingshu.agent.ui.theme.GlassBubbleBorder
import com.lingshu.agent.ui.theme.GlassBubbleStrong
import com.lingshu.agent.ui.theme.HealthHeartRate
import com.lingshu.agent.ui.theme.IceBlueGradientEnd
import com.lingshu.agent.ui.theme.IceBlueGradientMid
import com.lingshu.agent.ui.theme.IceBlueGradientStart
import com.lingshu.agent.ui.theme.PrimaryBackground
import com.lingshu.agent.ui.theme.Success
import com.lingshu.agent.ui.theme.TextPrimary
import com.lingshu.agent.ui.theme.TextSecondary
import com.lingshu.agent.ui.theme.TextTertiary
import com.lingshu.agent.ui.theme.Warning
import com.lingshu.agent.feature.voice.SpeechRecognizerManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modelSettingsVM: ModelSettingsViewModel = hiltViewModel(),
    personaVM: PersonaViewModel = hiltViewModel(),
    settingsVM: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val modelUiState by modelSettingsVM.uiState.collectAsState()
    val personaListState by personaVM.listUiState.collectAsState()

    var showClearDataDialog by remember { mutableStateOf(false) }
    var showAddApiKeyDialog by remember { mutableStateOf(false) }
    val proactiveEnabled by settingsVM.proactiveEnabled.collectAsState()
    val cooldownMinutes by settingsVM.proactiveCooldown.collectAsState()
    val dailyLimitInput by settingsVM.proactiveDailyLimit.collectAsState()
    val aesEnabled by settingsVM.aesEnabled.collectAsState()
    val allMemories by settingsVM.allMemories.collectAsState()
    val personalityState by settingsVM.personalityState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBackground)
    ) {
        GlassTopAppBar(
            title = {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            navigationIcon = {
                GlassIconButton(
                    onClick = onBack,
                    icon = Icons.Default.ArrowBack,
                    iconModifier = Modifier.size(22.dp)
                )
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                ModelSettingsGroup(
                    uiState = modelUiState,
                    onAddApiKey = { showAddApiKeyDialog = true },
                    onRemoveApiKey = { _, _ -> },
                    onToggleFallback = { modelSettingsVM.toggleAutoFallback(it) },
                    onToggleRotation = { modelSettingsVM.toggleApiKeyRotation(it) },
                    onSelectChatProvider = { modelSettingsVM.setDefaultChatProvider(it) },
                    onSelectVisionProvider = { modelSettingsVM.setDefaultVisionProvider(it) },
                    onSelectTranscribeProvider = { modelSettingsVM.setDefaultTranscribeProvider(it) },
                    onSelectSynthesizeProvider = { modelSettingsVM.setDefaultSynthesizeProvider(it) }
                )
            }

            // P1 语音设置分组：VAD参数 + Porcupine AccessKey
            item {
                val vadThresholdDb by settingsVM.vadSilenceThresholdDb.collectAsState()
                val vadTimeoutMs by settingsVM.vadTimeoutMs.collectAsState()
                val vadMinSpeechMs by settingsVM.vadMinSpeechMs.collectAsState()
                val porcupineKey by settingsVM.porcupineAccessKey.collectAsState()
                VoiceSettingsGroup(
                    vadSilenceThresholdDb = vadThresholdDb,
                    vadTimeoutMs = vadTimeoutMs,
                    vadMinSpeechMs = vadMinSpeechMs,
                    porcupineAccessKey = porcupineKey,
                    onVadThresholdChange = { settingsVM.setVadSilenceThresholdDb(it) },
                    onVadTimeoutChange = { settingsVM.setVadTimeoutMs(it) },
                    onVadMinSpeechChange = { settingsVM.setVadMinSpeechMs(it) },
                    onPorcupineKeyChange = { settingsVM.setPorcupineAccessKey(it) }
                )
            }

            item {
                PersonaGroup(
                    personas = personaListState.personas,
                    activePersonaId = personaListState.activePersonaId,
                    onSwitchPersona = { personaVM.switchActivePersona(it) },
                    onOpenWorkshop = {
                        context.startActivity(Intent(context, PersonaWorkshopActivity::class.java))
                    }
                )
            }

            item {
                FunctionEntryGroup(
                    onPersonaWorkshop = {
                        context.startActivity(Intent(context, PersonaWorkshopActivity::class.java))
                    },
                    onScriptWorkshop = {
                        context.startActivity(Intent(context, ScriptWorkshopActivity::class.java))
                    },
                    onVoiceClone = {
                        // 通过 MainActivity 启动导航到声音克隆页面
                        val intent = Intent(context, com.lingshu.agent.MainActivity::class.java).apply {
                            putExtra("navigate_to", "voice_clone")
                        }
                        context.startActivity(intent)
                    }
                )
            }

            item {
                PermissionGroup()
            }

            item {
                ProactiveCareGroup(
                    enabled = proactiveEnabled,
                    onToggleEnabled = { settingsVM.setProactiveEnabled(it) },
                    cooldownMinutes = cooldownMinutes,
                    onCooldownChange = { settingsVM.setProactiveCooldown(it) },
                    dailyLimit = dailyLimitInput,
                    onDailyLimitChange = { settingsVM.setProactiveDailyLimit(it) }
                )
            }

            item {
                DataPrivacyGroup(
                    aesEnabled = aesEnabled,
                    onToggleAes = { settingsVM.setAesEnabled(it) },
                    onClearData = { showClearDataDialog = true }
                )
            }

            item {
                MemoryGroup(
                    memories = allMemories,
                    onDeleteMemory = { settingsVM.deleteMemory(it) }
                )
            }

            item {
                PersonalitySlidersGroup(
                    state = personalityState,
                    onUpdate = { settingsVM.updatePersonality(it) },
                    onReset = { settingsVM.resetPersonality() }
                )
            }

            item {
                AboutGroup()
            }

            item {
                OtaUpdateGroup(
                    settingsVM = settingsVM
                )
            }

            item {
                SupportGroup(
                    context = context
                )
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (showClearDataDialog) {
        GlassAlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Warning
                )
            },
            title = {
                Text(
                    text = "清除所有数据",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "此操作将删除所有本地数据，包括聊天记录、人格配置、脚本、健康数据等。此操作不可撤销，确定要继续吗？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                GlassButton(
                    onClick = {
                        settingsVM.clearAllSettings()
                        showClearDataDialog = false
                        Toast.makeText(context, "数据已清除", Toast.LENGTH_SHORT).show()
                    },
                    text = "确认清除"
                )
            },
            dismissButton = {
                GlassButton(
                    onClick = { showClearDataDialog = false },
                    text = "取消",
                    gradient = Brush.horizontalGradient(
                        listOf(GlassBubbleStrong, GlassBubble)
                    )
                )
            }
        )
    }

    if (showAddApiKeyDialog) {
        AddApiKeyDialog(
            providers = modelUiState.modelItems.filter { !it.isLocal },
            onDismiss = { showAddApiKeyDialog = false },
            onConfirm = { providerId, apiKey ->
                modelSettingsVM.addApiKey(providerId, apiKey)
                showAddApiKeyDialog = false
                Toast.makeText(context, "API Key 已添加", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun AddApiKeyDialog(
    providers: List<ModelItemUiState>,
    onDismiss: () -> Unit,
    onConfirm: (providerId: String, apiKey: String) -> Unit
) {
    var selectedProviderId by remember { mutableStateOf(providers.firstOrNull()?.providerId ?: "") }
    var apiKey by remember { mutableStateOf("") }

    val providerOptions = providers.map { it.providerId to it.providerName }
        .ifEmpty { listOf("" to "无可用 Provider") }

    GlassAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Key,
                contentDescription = null,
                tint = AccentGlow,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = "添加 API Key",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GlassExposedDropdownMenuBox(
                    value = providers.find { it.providerId == selectedProviderId }?.providerName
                        ?: providerOptions.firstOrNull()?.second ?: "选择 Provider",
                    onValueSelected = { selectedProviderId = it },
                    options = providerOptions,
                    label = "Provider",
                    modifier = Modifier.fillMaxWidth()
                )
                GlassTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = "API Key",
                    placeholder = "请输入 API Key",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            GlassButton(
                onClick = {
                    onConfirm(selectedProviderId, apiKey)
                },
                text = "确认添加",
                enabled = apiKey.isNotBlank() && selectedProviderId.isNotBlank()
            )
        },
        dismissButton = {
            GlassButton(
                onClick = onDismiss,
                text = "取消",
                gradient = Brush.horizontalGradient(
                    listOf(GlassBubbleStrong, GlassBubble)
                )
            )
        }
    )
}

@Composable
private fun ModelSettingsGroup(
    uiState: com.lingshu.agent.feature.model.ModelSettingsUiState,
    onAddApiKey: () -> Unit,
    onRemoveApiKey: (String, Int) -> Unit,
    onToggleFallback: (Boolean) -> Unit,
    onToggleRotation: (Boolean) -> Unit,
    onSelectChatProvider: (String) -> Unit = {},
    onSelectVisionProvider: (String) -> Unit = {},
    onSelectTranscribeProvider: (String) -> Unit = {},
    onSelectSynthesizeProvider: (String) -> Unit = {}
) {
    // 从 Provider 列表构建下拉选项
    val chatProviders = uiState.modelItems.filter { it.supportsChat }
    val visionProviders = uiState.modelItems.filter { it.supportsVision }
    val transcribeProviders = uiState.modelItems.filter { it.supportsTranscribe }
    val synthesizeProviders = uiState.modelItems.filter { it.supportsSynthesize }

    fun toOptions(items: List<com.lingshu.agent.feature.model.ModelItemUiState>) =
        items.map { it.providerId to it.providerName }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassSectionTitle(title = "模型设置")

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GlassExposedDropdownMenuBox(
                    value = chatProviders.find { it.providerId == uiState.defaultChatProviderId }?.providerName ?: "未选择",
                    onValueSelected = { onSelectChatProvider(it) },
                    options = toOptions(chatProviders).ifEmpty { listOf("none" to "无可用模型") },
                    label = "默认聊天模型",
                    modifier = Modifier.fillMaxWidth()
                )

                GlassExposedDropdownMenuBox(
                    value = visionProviders.find { it.providerId == uiState.defaultVisionProviderId }?.providerName ?: "未选择",
                    onValueSelected = { onSelectVisionProvider(it) },
                    options = toOptions(visionProviders).ifEmpty { listOf("none" to "无可用模型") },
                    label = "视觉模型",
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GlassExposedDropdownMenuBox(
                        value = transcribeProviders.find { it.providerId == uiState.defaultTranscribeProviderId }?.providerName ?: "未选择",
                        onValueSelected = { onSelectTranscribeProvider(it) },
                        options = toOptions(transcribeProviders).ifEmpty { listOf("none" to "无可用模型") },
                        label = "STT引擎",
                        modifier = Modifier.weight(1f)
                    )
                    GlassExposedDropdownMenuBox(
                        value = synthesizeProviders.find { it.providerId == uiState.defaultSynthesizeProviderId }?.providerName ?: "未选择",
                        onValueSelected = { onSelectSynthesizeProvider(it) },
                        options = toOptions(synthesizeProviders).ifEmpty { listOf("none" to "无可用模型") },
                        label = "TTS引擎",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AccentGlow.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = AccentGlow,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "API Keys 管理",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                            Text(
                                text = "${uiState.modelItems.size}个Provider已配置",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                        }
                    }
                    GlassIconButton(
                        onClick = onAddApiKey,
                        icon = Icons.Default.Add,
                        iconModifier = Modifier.size(20.dp),
                        size = 36.dp
                    )
                }

                Divider(color = GlassBubbleBorder, thickness = 1.dp)

                uiState.modelItems.take(3).forEach { item ->
                    ApiKeyRow(
                        name = item.providerName,
                        keyCount = item.apiKeyCount,
                        configured = item.isConfigured,
                        enabled = item.isEnabled
                    )
                }
            }
        }

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SwitchRow(
                    icon = Icons.Default.Refresh,
                    iconTint = Success,
                    title = "自动降级",
                    description = "主模型不可用时自动切换到备用",
                    checked = uiState.autoFallbackEnabled,
                    onCheckedChange = onToggleFallback
                )
                SwitchRow(
                    icon = Icons.Default.Tune,
                    iconTint = AccentGlow,
                    title = "限流轮询",
                    description = "多个Key自动轮询使用避免限流",
                    checked = uiState.apiKeyRotationEnabled,
                    onCheckedChange = onToggleRotation
                )
            }
        }
    }
}

@Composable
private fun ApiKeyRow(
    name: String,
    keyCount: Int,
    configured: Boolean,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (configured) Success else Error)
            )
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary
            )
        }
        Text(
            text = if (keyCount > 0) "${keyCount}个Key" else "未配置",
            style = MaterialTheme.typography.labelMedium,
            color = if (configured) TextSecondary else TextTertiary
        )
    }
}

@Composable
private fun PersonaGroup(
    personas: List<Persona>,
    activePersonaId: String?,
    onSwitchPersona: (String) -> Unit,
    onOpenWorkshop: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassSectionTitle(title = "人格管理")

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenWorkshop() }
                        .border(1.dp, GlassBubbleBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(AccentPrimary, AccentGlow)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "当前激活人格",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextTertiary
                            )
                            Text(
                                text = personas.find { it.personaId == activePersonaId }?.name
                                    ?: "灵枢助手",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Text(
                    text = "快速切换",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    personas.take(4).forEach { persona ->
                        PersonaQuickCard(
                            persona = persona,
                            isActive = persona.personaId == activePersonaId,
                            onClick = { onSwitchPersona(persona.personaId) }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(
                                1.dp,
                                GlassBubbleBorder,
                                RoundedCornerShape(16.dp)
                            )
                            .background(GlassBubble)
                            .clickable { onOpenWorkshop() },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PersonAdd,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "新建",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaQuickCard(
    persona: Persona,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (isActive) Modifier.border(
                    1.5.dp,
                    AccentGlow,
                    RoundedCornerShape(16.dp)
                ) else Modifier.border(1.dp, GlassBubbleBorder, RoundedCornerShape(16.dp))
            )
            .background(
                if (isActive) AccentPrimary.copy(alpha = 0.2f) else GlassBubble
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) Brush.verticalGradient(
                            listOf(AccentPrimary, AccentGlow)
                        ) else Brush.verticalGradient(
                            listOf(GlassBubbleStrong, GlassBubble)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = persona.name.firstOrNull()?.toString() ?: "?",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isActive) Color.White else TextSecondary
                )
            }
            Text(
                text = persona.name.take(3),
                style = MaterialTheme.typography.labelSmall,
                color = if (isActive) TextPrimary else TextSecondary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun FunctionEntryGroup(
    onPersonaWorkshop: () -> Unit,
    onScriptWorkshop: () -> Unit,
    onVoiceClone: () -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassSectionTitle(title = "功能入口")

        GlassCard(
            modifier = Modifier.fillMaxWidth().clickable { onPersonaWorkshop() }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = Icons.Default.PersonAdd, contentDescription = null, tint = AccentGlow, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("人格工坊", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                    Text("创建和编辑自定义人格", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                Icon(imageVector = Icons.Default.ArrowForward, contentDescription = null, tint = TextTertiary)
            }
        }

        GlassCard(
            modifier = Modifier.fillMaxWidth().clickable { onScriptWorkshop() }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = Icons.Default.SmartToy, contentDescription = null, tint = AccentGlow, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("脚本工坊", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                    Text("录制和编辑自动化脚本", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                Icon(imageVector = Icons.Default.ArrowForward, contentDescription = null, tint = TextTertiary)
            }
        }

        GlassCard(
            modifier = Modifier.fillMaxWidth().clickable { onVoiceClone() }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = Icons.Default.Mic, contentDescription = null, tint = AccentGlow, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("声音克隆", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                    Text("录制声音并克隆你的音色", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                Icon(imageVector = Icons.Default.ArrowForward, contentDescription = null, tint = TextTertiary)
            }
        }
    }
}

@Composable
private fun PermissionGroup() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassSectionTitle(title = "权限管理")

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PermissionRow(
                    icon = Icons.Default.Mic,
                    iconTint = AccentGlow,
                    title = "录音权限",
                    description = "语音对话和语音识别需要",
                    granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                    onRequest = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.parse("package:${context.packageName}")
                        context.startActivity(intent)
                    }
                )
                PermissionRow(
                    icon = Icons.Default.Visibility,
                    iconTint = AccentPrimary,
                    title = "悬浮窗权限",
                    description = "悬浮球和快捷操作需要",
                    granted = Settings.canDrawOverlays(context),
                    onRequest = {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        intent.data = Uri.parse("package:${context.packageName}")
                        context.startActivity(intent)
                    }
                )
                PermissionRow(
                    icon = Icons.Default.SettingsAccessibility,
                    iconTint = Success,
                    title = "无障碍服务",
                    description = "自动化脚本和屏幕控制需要",
                    granted = LingShuAccessibilityService.isConnected(),
                    onRequest = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }
                )
                PermissionRow(
                    icon = Icons.Default.HealthAndSafety,
                    iconTint = HealthHeartRate,
                    title = "健康数据权限",
                    description = "健康面板和主动关怀需要",
                    granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED,
                    onRequest = { Toast.makeText(context, "请在系统健康应用中授权", Toast.LENGTH_SHORT).show() },
                    isLast = true
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit = {},
    isLast: Boolean = false
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconTint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (granted) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Success,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "已开启",
                        style = MaterialTheme.typography.labelMedium,
                        color = Success
                    )
                }
            } else {
                GlassButton(
                    onClick = onRequest,
                    text = "去开启",
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    textStyle = MaterialTheme.typography.labelMedium
                )
            }
        }
        if (!isLast) {
            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = GlassBubbleBorder, thickness = 1.dp)
        }
    }
}

@Composable
private fun ProactiveCareGroup(
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    cooldownMinutes: Float,
    onCooldownChange: (Float) -> Unit,
    dailyLimit: String,
    onDailyLimitChange: (String) -> Unit
) {
    val triggerTypes = remember {
        listOf(
            "深夜使用提醒" to true,
            "久坐提醒" to true,
            "频繁解锁提醒" to false,
            "心率异常提醒" to true,
            "随机关怀" to false
        )
    }
    var triggerStates by remember { mutableStateOf(triggerTypes) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassSectionTitle(title = "主动关怀")

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SwitchRow(
                    icon = Icons.Default.NotificationsActive,
                    iconTint = AccentGlow,
                    title = "主动关怀总开关",
                    description = "根据您的使用习惯和健康数据主动关怀",
                    checked = enabled,
                    onCheckedChange = onToggleEnabled
                )

                Divider(color = GlassBubbleBorder, thickness = 1.dp)

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "触发条件",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        triggerStates.forEachIndexed { index, pair ->
                            GlassChip(
                                label = pair.first,
                                selected = pair.second,
                                onClick = {
                                    triggerStates = triggerStates.toMutableList().apply {
                                        this[index] = pair.first to !pair.second
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "冷却时间",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = "${cooldownMinutes.toInt()} 分钟",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary
                        )
                    }
                    GlassSlider(
                        value = cooldownMinutes / 720f,
                        onValueChange = { onCooldownChange(it * 720f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "每日上限",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        GlassTextField(
                            value = dailyLimit,
                            onValueChange = onDailyLimitChange,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    GlassExposedDropdownMenuBox(
                        value = "DeepSeek",
                        onValueSelected = { _: String -> },
                        options = listOf(
                            "deepseek" to "DeepSeek",
                            "gpt4" to "GPT-4o",
                            "rule" to "仅规则"
                        ),
                        label = "生成模型",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DataPrivacyGroup(
    aesEnabled: Boolean,
    onToggleAes: (Boolean) -> Unit,
    onClearData: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassSectionTitle(title = "数据与隐私")

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SwitchRow(
                    icon = Icons.Default.EnhancedEncryption,
                    iconTint = Success,
                    title = "AES 本地加密",
                    description = "所有本地数据使用AES-256加密存储",
                    checked = aesEnabled,
                    onCheckedChange = onToggleAes
                )

                Divider(color = GlassBubbleBorder, thickness = 1.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GlassButton(
                        onClick = {  },
                        text = "导出JSON",
                        icon = Icons.Default.Download,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    )
                    GlassButton(
                        onClick = onClearData,
                        text = "清除数据",
                        icon = Icons.Default.Password,
                        modifier = Modifier.weight(1f),
                        gradient = Brush.horizontalGradient(
                            listOf(
                                Error.copy(alpha = 0.8f),
                                Error.copy(alpha = 0.6f)
                            )
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutGroup() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassSectionTitle(title = "关于")

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(IceBlueGradientStart, IceBlueGradientMid, IceBlueGradientEnd)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "灵枢 LingShu",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "版本 1.0.0 (build 1)",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                        }
                    }
                }

                Divider(color = GlassBubbleBorder, thickness = 1.dp)

                SettingNavRow(
                    icon = Icons.Default.Groups,
                    iconTint = AccentGlow,
                    title = "开源声明",
                    subtitle = "基于开源项目构建"
                )
                SettingNavRow(
                    icon = Icons.Default.Download,
                    iconTint = Success,
                    title = "检查更新",
                    subtitle = "当前已是最新版本",
                    isLast = true
                )
            }
        }
    }
}

@Composable
private fun SettingNavRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    isLast: Boolean = false
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable {  }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconTint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ArrowForward,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
        if (!isLast) {
            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = GlassBubbleBorder, thickness = 1.dp)
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary
                )
            }
        }
        GlassSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun MemoryGroup(
    memories: List<MemoryEntity>,
    onDeleteMemory: (MemoryEntity) -> Unit
) {
    val categoryLabels = mapOf(
        "偏好" to "偏好",
        "厌恶" to "厌恶",
        "习惯" to "习惯",
        "事实" to "事实"
    )
    val categoryColors = mapOf(
        "偏好" to Success,
        "厌恶" to Error,
        "习惯" to AccentGlow,
        "事实" to AccentPrimary
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassSectionTitle(title = "长期记忆")

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(16.dp)
        ) {
            if (memories.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "暂无长期记忆，多聊聊吧",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    memories.sortedByDescending { it.timestamp }.forEachIndexed { index, memory ->
                        if (index > 0) {
                            Divider(color = GlassBubbleBorder, thickness = 1.dp)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = memory.content,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextPrimary
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val color = categoryColors[memory.category] ?: TextTertiary
                                    GlassChip(
                                        label = categoryLabels[memory.category] ?: memory.category,
                                        selected = true,
                                        selectedColor = color
                                    )
                                    Text(
                                        text = formatMemoryTime(memory.timestamp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextTertiary
                                    )
                                }
                            }
                            GlassIconButton(
                                onClick = { onDeleteMemory(memory) },
                                icon = Icons.Default.Delete,
                                iconModifier = Modifier.size(18.dp),
                                size = 32.dp
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatMemoryTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

/**
 * 人格演化滑块组（模块6：人格工坊）
 */
@Composable
private fun PersonalitySlidersGroup(
    state: PersonalityState,
    onUpdate: (PersonalityState) -> Unit,
    onReset: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassSectionTitle(title = "人格工坊")

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "当前风格：${state.name}",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    GlassButton(
                        onClick = onReset,
                        text = "重置默认"
                    )
                }

                // 温度（0 ~ 2.0，步长0.01）
                SliderLabelRow(
                    label = "回复创意度 (温度)",
                    displayValue = String.format("%.2f", state.temperature)
                )
                GlassSlider(
                    value = state.temperature,
                    onValueChange = { onUpdate(state.copy(temperature = it)) },
                    valueRange = 0f..2f,
                    modifier = Modifier.fillMaxWidth()
                )

                // OCEAN 大五人格 — 开放性
                SliderLabelRow(
                    label = "开放性 (Openness)",
                    displayValue = describeTrait("openness", state.traits.openness)
                )
                GlassSlider(
                    value = state.traits.openness.toFloat(),
                    onValueChange = {
                        onUpdate(state.copy(traits = state.traits.copy(openness = it.toDouble())))
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )

                // OCEAN 大五人格 — 尽责性
                SliderLabelRow(
                    label = "尽责性 (Conscientiousness)",
                    displayValue = describeTrait("conscientiousness", state.traits.conscientiousness)
                )
                GlassSlider(
                    value = state.traits.conscientiousness.toFloat(),
                    onValueChange = {
                        onUpdate(state.copy(traits = state.traits.copy(conscientiousness = it.toDouble())))
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )

                // OCEAN 大五人格 — 外向性
                SliderLabelRow(
                    label = "外向性 (Extraversion)",
                    displayValue = describeTrait("extraversion", state.traits.extraversion)
                )
                GlassSlider(
                    value = state.traits.extraversion.toFloat(),
                    onValueChange = {
                        onUpdate(state.copy(traits = state.traits.copy(extraversion = it.toDouble())))
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )

                // OCEAN 大五人格 — 宜人性
                SliderLabelRow(
                    label = "宜人性 (Agreeableness)",
                    displayValue = describeTrait("agreeableness", state.traits.agreeableness)
                )
                GlassSlider(
                    value = state.traits.agreeableness.toFloat(),
                    onValueChange = {
                        onUpdate(state.copy(traits = state.traits.copy(agreeableness = it.toDouble())))
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )

                // OCEAN 大五人格 — 神经质
                SliderLabelRow(
                    label = "神经质 (Neuroticism)",
                    displayValue = describeTrait("neuroticism", state.traits.neuroticism)
                )
                GlassSlider(
                    value = state.traits.neuroticism.toFloat(),
                    onValueChange = {
                        onUpdate(state.copy(traits = state.traits.copy(neuroticism = it.toDouble())))
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * OCEAN 特质值的文字描述
 */
private fun describeTrait(trait: String, value: Double): String {
    val v = value.toFloat()
    return when (trait) {
        "openness" -> when {
            v < 0.3f -> "保守"
            v < 0.5f -> "偏保守"
            v < 0.7f -> "适中"
            v < 0.85f -> "开放"
            else -> "非常开放"
        }
        "conscientiousness" -> when {
            v < 0.3f -> "随性"
            v < 0.5f -> "偏随性"
            v < 0.7f -> "适中"
            v < 0.85f -> "尽责"
            else -> "非常尽责"
        }
        "extraversion" -> when {
            v < 0.3f -> "内向"
            v < 0.5f -> "偏内向"
            v < 0.7f -> "适中"
            v < 0.85f -> "外向"
            else -> "非常外向"
        }
        "agreeableness" -> when {
            v < 0.3f -> "理性"
            v < 0.5f -> "偏理性"
            v < 0.7f -> "适中"
            v < 0.85f -> "友善"
            else -> "非常友善"
        }
        "neuroticism" -> when {
            v < 0.3f -> "沉稳"
            v < 0.5f -> "偏沉稳"
            v < 0.7f -> "适中"
            v < 0.85f -> "敏感"
            else -> "非常敏感"
        }
        else -> String.format("%.2f", value)
    }
}

/**
 * 滑块标签行：左侧标签 + 右侧当前值
 */
@Composable
private fun SliderLabelRow(label: String, displayValue: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(text = displayValue, style = MaterialTheme.typography.bodyMedium, color = AccentPrimary)
    }
}

/**
 * P1 语音设置分组：VAD参数 + Porcupine AccessKey
 *
 * 布局参考 GlassCard 包裹的 Section：
 * - 静音阈值 (-40dB ~ -10dB)
 * - 超时时长 (1000ms ~ 10000ms)
 * - 最小语音时长 (200ms ~ 2000ms)
 * - Porcupine AccessKey 输入框 + 状态显示
 */
@Composable
private fun VoiceSettingsGroup(
    vadSilenceThresholdDb: Float,
    vadTimeoutMs: String,
    vadMinSpeechMs: String,
    porcupineAccessKey: String,
    onVadThresholdChange: (Float) -> Unit,
    onVadTimeoutChange: (String) -> Unit,
    onVadMinSpeechChange: (String) -> Unit,
    onPorcupineKeyChange: (String) -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GlassSectionTitle(
                title = "语音设置"
            )

            // VAD 静音阈值滑块
            Text(
                text = "静音阈值",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Text(
                text = "当前值: ${String.format("%.0f", vadSilenceThresholdDb)} dB（默认 -40）",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
            GlassSlider(
                value = vadSilenceThresholdDb,
                onValueChange = onVadThresholdChange,
                valueRange = -60f..-10f,
                steps = 49
            )

            // VAD 超时时长输入
            Text(
                text = "静音超时时长",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Text(
                text = "用户静音多久后自动结束对话（默认 3000ms = 3秒）",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
            GlassTextField(
                value = vadTimeoutMs,
                onValueChange = onVadTimeoutChange,
                label = "超时时长 (ms)",
                modifier = Modifier.fillMaxWidth()
            )

            // VAD 最小语音时长输入
            Text(
                text = "最小语音时长",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Text(
                text = "有效语音的最短持续时间（默认 500ms）",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
            GlassTextField(
                value = vadMinSpeechMs,
                onValueChange = onVadMinSpeechChange,
                label = "最小语音时长 (ms)",
                modifier = Modifier.fillMaxWidth()
            )

            Divider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = TextTertiary.copy(alpha = 0.3f)
            )

            // Porcupine AccessKey
            GlassSectionTitle(
                title = "Porcupine 唤醒词引擎"
            )
            Text(
                text = "在 Picovoice 官网注册获取 AccessKey，提升唤醒准确率。" +
                        "留空则使用内置能量阈值方案。",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
            GlassTextField(
                value = porcupineAccessKey,
                onValueChange = onPorcupineKeyChange,
                label = "Porcupine AccessKey",
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = if (porcupineAccessKey.isNotBlank()) "状态: 已配置 AccessKey" else "状态: 使用降级方案（能量阈值）",
                style = MaterialTheme.typography.labelSmall,
                color = if (porcupineAccessKey.isNotBlank()) Success else Warning
            )
        }
    }
}

/**
 * OTA 更新分组：版本检查 + 更新源配置
 */
@Composable
private fun OtaUpdateGroup(settingsVM: SettingsViewModel) {
    val otaUrl by settingsVM.otaUpdateUrl.collectAsState()
    val otaChecking by settingsVM.otaChecking.collectAsState()
    val otaLatestVersion by settingsVM.otaLatestVersion.collectAsState()

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GlassSectionTitle(title = "OTA 更新")

            Text(
                text = "更新源 URL",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Text(
                text = "自定义 OTA 更新检查地址（留空使用默认 GitHub Releases）",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
            GlassTextField(
                value = otaUrl,
                onValueChange = { settingsVM.setOtaUpdateUrl(it) },
                label = "更新源地址",
                modifier = Modifier.fillMaxWidth()
            )

            if (otaLatestVersion.isNotBlank()) {
                Text(
                    text = "最新版本: $otaLatestVersion",
                    style = MaterialTheme.typography.bodySmall,
                    color = Success
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                GlassButton(
                    onClick = { settingsVM.checkOtaUpdate() },
                    text = if (otaChecking) "检查中..." else "检查更新",
                    enabled = !otaChecking
                )
            }
        }
    }
}

/**
 * 支持与反馈分组：报告问题 / 发送反馈
 */
@Composable
private fun SupportGroup(context: android.content.Context) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GlassSectionTitle(title = "支持与反馈")

            Text(
                text = "将系统诊断信息（错误码、日志、设备信息等）生成为 JSON 报告并保存到本地，便于排查问题。",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GlassButton(
                    onClick = {
                        val errors = listOf(
                            com.lingshu.agent.core.model.ErrorReportEntry(
                                errorCode = com.lingshu.agent.core.model.ErrorCode.E013_UNKNOWN_ERROR,
                                context = "用户主动生成错误报告"
                            )
                        )
                        val report = com.lingshu.agent.core.model.ErrorCode.generateErrorReport(errors)
                        val dir = java.io.File(context.getExternalFilesDir(null), "error_reports")
                        val path = com.lingshu.agent.core.model.ErrorCode.saveReportToFile(report, dir)
                        android.widget.Toast.makeText(
                            context,
                            if (path != null) "错误报告已保存: $path" else "保存失败",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    },
                    text = "报告问题",
                    modifier = Modifier.weight(1f)
                )

                GlassButton(
                    onClick = {
                        // 打开错误报告所在目录
                        val dir = java.io.File(context.getExternalFilesDir(null), "error_reports")
                        dir.mkdirs()
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(
                                android.net.Uri.parse(dir.absolutePath),
                                "resource/folder"
                            )
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            android.widget.Toast.makeText(
                                context,
                                "报告目录: ${dir.absolutePath}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    text = "查看报告",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

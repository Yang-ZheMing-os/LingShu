@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.lingshu.agent.feature.persona

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingshu.agent.core.model.BigFiveTraits
import com.lingshu.agent.core.model.Persona
import com.lingshu.agent.core.model.PersonaRules
import com.lingshu.agent.ui.components.GlassButton
import com.lingshu.agent.ui.components.GlassCard
import com.lingshu.agent.ui.components.GlassChip
import com.lingshu.agent.ui.components.GlassDivider
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
import com.lingshu.agent.ui.theme.IceBlueGradientEnd
import com.lingshu.agent.ui.theme.IceBlueGradientMid
import com.lingshu.agent.ui.theme.IceBlueGradientStart
import com.lingshu.agent.ui.theme.PrimaryBackground
import com.lingshu.agent.ui.theme.Success
import com.lingshu.agent.ui.theme.TextPrimary
import com.lingshu.agent.ui.theme.TextSecondary
import com.lingshu.agent.ui.theme.TextTertiary
import com.lingshu.agent.ui.theme.WarmWhiteGradientEnd
import com.lingshu.agent.ui.theme.WarmWhiteGradientMid
import com.lingshu.agent.ui.theme.WarmWhiteGradientStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaWorkshopScreen(
    viewModel: PersonaViewModel = hiltViewModel(),
    personaId: String? = null,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.editorUiState.collectAsState()
    val scope = rememberCoroutineScope()
    var showAdvancedEditor by remember { mutableStateOf(false) }
    var showAddMemory by remember { mutableStateOf(false) }
    var showAddDialogue by remember { mutableStateOf(false) }
    var newMemoryText by remember { mutableStateOf("") }
    var newDialogueUser by remember { mutableStateOf("") }
    var newDialogueAssistant by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState()

    val persona = uiState?.persona ?: Persona(
        personaId = "new_${System.currentTimeMillis()}",
        name = "新人设",
        traits = BigFiveTraits.neutral(),
        rules = PersonaRules()
    )

    val toneOptions = listOf("温柔", "幽默", "犀利", "冷静", "活泼", "严谨", "治愈", "傲娇", "腹黑", "天然呆")
    val voiceOptions = listOf(
        "default" to "默认音色",
        "warm_female" to "温暖女声",
        "cool_male" to "磁性男声",
        "soft_girl" to "软萌少女",
        "gentle_mature" to "温柔成熟"
    )
    val traitColors = listOf(
        Color(0xFF60A5FA),
        Color(0xFF34D399),
        Color(0xFFF472B6),
        Color(0xFFFBBF24),
        Color(0xFFA78BFA)
    )
    val traitLabels = listOf(
        "开放性" to "好奇探索 vs 保守传统",
        "尽责性" to "自律有序 vs 灵活随性",
        "外向性" to "热情社交 vs 内敛独处",
        "宜人性" to "友善合作 vs 独立竞争",
        "神经质" to "敏感感性 vs 沉稳理性"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBackground)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            GlassTopAppBar(
                title = {
                    Text(
                        text = "人格工坊",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    GlassIconButton(
                        onClick = onBack,
                        icon = Icons.Default.ArrowBack,
                        iconModifier = Modifier.size(22.dp)
                    )
                },
                actions = {
                    GlassIconButton(
                        onClick = {
                            scope.launch {
                                viewModel.savePersona(persona)
                            }
                        },
                        icon = Icons.Default.Save,
                        iconModifier = Modifier.size(20.dp),
                        backgroundColor = AccentPrimary.copy(alpha = 0.3f)
                    )
                }
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    PersonaPreviewCard(
                        persona = persona,
                        onClick = {}
                    )
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        padding = PaddingValues(16.dp)
                    ) {
                        GlassSectionTitle(title = "基本信息")
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(AccentPrimary, AccentGlow)
                                            )
                                        )
                                        .border(2.dp, GlassBubbleBorder, CircleShape)
                                        .clickable { },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    GlassTextField(
                                        value = persona.name,
                                        onValueChange = { viewModel.updateName(it) },
                                        label = "人格名称",
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            GlassExposedDropdownMenuBoxWrapper(
                                value = voiceOptions.find { it.first == persona.voiceId }?.second ?: "默认音色",
                                options = voiceOptions,
                                label = "关联TTS音色",
                                onValueSelected = { viewModel.updateVoiceId(it) }
                            )
                            GlassTextField(
                                value = persona.openingLine ?: "",
                                onValueChange = { viewModel.updateOpeningLine(it) },
                                label = "开场白",
                                placeholder = "第一次对话时说的话...",
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 3
                            )
                        }
                    }
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        padding = PaddingValues(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlassSectionTitle(title = "系统提示词", showGlow = false)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "高级编辑",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary
                                )
                                GlassSwitch(
                                    checked = showAdvancedEditor,
                                    onCheckedChange = { showAdvancedEditor = it }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        if (showAdvancedEditor) {
                            GlassTextField(
                                value = persona.systemPrompt,
                                onValueChange = { viewModel.updateSystemPrompt(it) },
                                label = "完整 System Prompt",
                                placeholder = "直接编辑prompt内容...",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                minLines = 8,
                                maxLines = 12
                            )
                        } else {
                            GlassTextField(
                                value = persona.systemPrompt,
                                onValueChange = { viewModel.updateSystemPrompt(it) },
                                label = "角色设定",
                                placeholder = "描述这个人格的身份、性格、说话风格...",
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 4,
                                maxLines = 6
                            )
                        }
                    }
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        padding = PaddingValues(16.dp)
                    ) {
                        GlassSectionTitle(title = "语气标签")
                        Spacer(modifier = Modifier.height(12.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            toneOptions.forEach { tone ->
                                val selected = persona.toneTags.contains(tone)
                                GlassChip(
                                    label = tone,
                                    selected = selected,
                                    onClick = {
                                        if (selected) {
                                            viewModel.removeToneTag(tone)
                                        } else {
                                            viewModel.addToneTag(tone)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        padding = PaddingValues(16.dp)
                    ) {
                        GlassSectionTitle(title = "大五人格维度")
                        Spacer(modifier = Modifier.height(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            traitLabels.forEachIndexed { index, (label, desc) ->
                                val value = when (index) {
                                    0 -> persona.traits.openness
                                    1 -> persona.traits.conscientiousness
                                    2 -> persona.traits.extraversion
                                    3 -> persona.traits.agreeableness
                                    else -> persona.traits.neuroticism
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = "${(value * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = traitColors[index]
                                        )
                                    }
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextTertiary
                                    )
                                    TraitSlider(
                                        value = value.toFloat(),
                                        onValueChange = {
                                            viewModel.updateSingleTrait(
                                                when (index) {
                                                    0 -> TraitType.OPENNESS
                                                    1 -> TraitType.CONSCIENTIOUSNESS
                                                    2 -> TraitType.EXTRAVERSION
                                                    3 -> TraitType.AGREEABLENESS
                                                    else -> TraitType.NEUROTICISM
                                                },
                                                it.toDouble()
                                            )
                                        },
                                        color = traitColors[index]
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        padding = PaddingValues(16.dp)
                    ) {
                        GlassSectionTitle(title = "模型参数")
                        Spacer(modifier = Modifier.height(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Temperature",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = String.format("%.2f", persona.temperature),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AccentGlow
                                )
                            }
                            Text(
                                text = "低值更确定聚焦 · 高值更随机发散",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                            TemperatureSlider(
                                value = persona.temperature.toFloat(),
                                onValueChange = { viewModel.updateTemperature(it.toDouble()) }
                            )
                        }
                    }
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        padding = PaddingValues(16.dp)
                    ) {
                        GlassSectionTitle(title = "行为规则")
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            RuleItem(
                                title = "主动发起对话",
                                description = "在特定场景下主动与用户交流",
                                checked = persona.rules.canInitiateConversation,
                                onCheckedChange = {
                                    viewModel.updateSingleRule(RuleType.CAN_INITIATE, it)
                                }
                            )
                            GlassDivider()
                            RuleItem(
                                title = "操作前确认",
                                description = "执行系统操作前先询问用户意见",
                                checked = persona.rules.confirmBeforeExecute,
                                onCheckedChange = {
                                    viewModel.updateSingleRule(RuleType.CONFIRM_BEFORE_EXECUTE, it)
                                }
                            )
                            GlassDivider()
                            RuleItem(
                                title = "记住上下文",
                                description = "在对话中保持上下文连贯性",
                                checked = true,
                                onCheckedChange = {}
                            )
                            GlassDivider()
                            RuleItem(
                                title = "访问健康数据",
                                description = "读取健康数据提供更贴心的建议",
                                checked = persona.rules.canUseSensitiveOperations,
                                onCheckedChange = {
                                    viewModel.updateSingleRule(RuleType.CAN_USE_SENSITIVE, it)
                                }
                            )
                        }
                    }
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        padding = PaddingValues(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlassSectionTitle(title = "示例对话 (Few-shot)", showGlow = false)
                            GlassIconButton(
                                onClick = { showAddDialogue = true },
                                icon = Icons.Default.Add,
                                iconModifier = Modifier.size(18.dp),
                                size = 36.dp,
                                backgroundColor = AccentPrimary.copy(alpha = 0.25f)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        if (persona.exampleDialogues.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "还没有示例对话，点击 + 添加参考对话对",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextTertiary
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                persona.exampleDialogues.forEachIndexed { index, (user, assistant) ->
                                    ExampleDialogueItem(
                                        index = index + 1,
                                        userText = user,
                                        assistantText = assistant,
                                        onDelete = { viewModel.removeExampleDialogue(index) }
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        padding = PaddingValues(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlassSectionTitle(title = "记忆管理", showGlow = false)
                            GlassIconButton(
                                onClick = { showAddMemory = true },
                                icon = Icons.Default.Add,
                                iconModifier = Modifier.size(18.dp),
                                size = 36.dp,
                                backgroundColor = AccentPrimary.copy(alpha = 0.25f)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        if (persona.memory.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "还没有记忆内容，点击 + 添加永久记忆",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextTertiary
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                persona.memory.forEachIndexed { index, memory ->
                                    MemoryItem(
                                        index = index + 1,
                                        content = memory,
                                        onDelete = { viewModel.removeMemoryFromEditing(index) }
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(GlassBubbleStrong)
                .border(
                    width = 1.dp,
                    color = GlassBubbleBorder,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassButton(
                    onClick = {
                        scope.launch { sheetState.show() }
                    },
                    text = "导出",
                    icon = Icons.Default.FileDownload,
                    modifier = Modifier.weight(1f)
                )
                    GlassButton(
                        onClick = {  },
                        text = "分享",
                        icon = Icons.Default.Share,
                        modifier = Modifier.weight(1f),
                        gradient = Brush.horizontalGradient(
                            listOf(WarmWhiteGradientStart, WarmWhiteGradientMid, WarmWhiteGradientEnd)
                        )
                    )
                    GlassButton(
                        onClick = {
                            scope.launch {
                                viewModel.savePersona(persona)
                            }
                        },
                        text = "保存",
                        icon = Icons.Default.Save,
                        modifier = Modifier.weight(1f),
                        gradient = Brush.horizontalGradient(
                            listOf(Success.copy(alpha = 0.9f), Success.copy(alpha = 0.7f))
                        )
                    )
            }
        }
    }

    if (showAddMemory) {
        AddItemDialog(
            title = "添加记忆",
            description = "这条记忆会永久注入人格的上下文",
            value = newMemoryText,
            onValueChange = { newMemoryText = it },
            placeholder = "用户喜欢喝无糖拿铁...",
            onConfirm = {
                if (newMemoryText.isNotBlank()) {
                    viewModel.addMemoryToEditing(newMemoryText)
                    newMemoryText = ""
                    showAddMemory = false
                }
            },
            onDismiss = { showAddMemory = false }
        )
    }

    if (showAddDialogue) {
        AddExampleDialogueDialog(
            userText = newDialogueUser,
            assistantText = newDialogueAssistant,
            onUserChange = { newDialogueUser = it },
            onAssistantChange = { newDialogueAssistant = it },
            onConfirm = {
                if (newDialogueUser.isNotBlank() && newDialogueAssistant.isNotBlank()) {
                    viewModel.addExampleDialogue(newDialogueUser, newDialogueAssistant)
                    newDialogueUser = ""
                    newDialogueAssistant = ""
                    showAddDialogue = false
                }
            },
            onDismiss = { showAddDialogue = false }
        )
    }
}

@Composable
private fun PersonaPreviewCard(
    persona: Persona,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(24.dp),
        padding = PaddingValues(20.dp),
        glowAlpha = 0.2f
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(IceBlueGradientStart, IceBlueGradientMid, IceBlueGradientEnd)
                        )
                    )
                    .border(2.dp, AccentGlow.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = persona.name.firstOrNull()?.toString() ?: "?",
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = persona.name,
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    if (persona.isActive) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Success.copy(alpha = 0.8f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "激活中",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }
                persona.openingLine?.let {
                    Text(
                        text = "\"$it\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (persona.toneTags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        persona.toneTags.take(4).forEach { tag ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(AccentPrimary.copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AccentGlow
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TraitSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    color: Color
) {
    Box {
        GlassSlider(
            value = value,
            onValueChange = onValueChange,
            activeTrackColor = color,
            thumbColor = color,
            showGradient = false
        )
    }
}

@Composable
private fun TemperatureSlider(
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "0.0",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary
        )
        Box(modifier = Modifier.weight(1f)) {
            GlassSlider(
                value = (value / 2f).coerceIn(0f, 1f),
                onValueChange = { onValueChange((it * 2f * 100).roundToInt() / 100f) },
                activeTrackColor = AccentGlow,
                thumbColor = AccentGlow,
                showGradient = false
            )
        }
        Text(
            text = "2.0",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary
        )
    }
}

@Composable
private fun RuleItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
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
        GlassSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun ExampleDialogueItem(
    index: Int,
    userText: String,
    assistantText: String,
    onDelete: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        padding = PaddingValues(12.dp),
        strong = false
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = "对话 #$index",
                style = MaterialTheme.typography.labelMedium,
                color = AccentGlow
            )
            GlassIconButton(
                onClick = onDelete,
                icon = Icons.Default.Delete,
                iconModifier = Modifier.size(16.dp),
                size = 28.dp,
                backgroundColor = Error.copy(alpha = 0.2f),
                iconTint = Error
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(GlassBubbleStrong)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "用户",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
                Text(
                    text = userText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentPrimary.copy(alpha = 0.3f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "助理",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentGlow
                    )
                }
                Text(
                    text = assistantText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MemoryItem(
    index: Int,
    content: String,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(GlassBubble)
            .border(1.dp, GlassBubbleBorder, RoundedCornerShape(10.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(AccentPrimary.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = index.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = AccentGlow
            )
        }
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        GlassIconButton(
            onClick = onDelete,
            icon = Icons.Default.Delete,
            iconModifier = Modifier.size(16.dp),
            size = 28.dp,
            backgroundColor = Color.Transparent,
            iconTint = Error
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> GlassExposedDropdownMenuBoxWrapper(
    value: String,
    options: List<Pair<T, String>>,
    label: String,
    onValueSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        GlassTextField(
            value = value,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
            label = label,
            trailingIcon = {
                Text(
                    text = if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            Modifier
                .fillMaxWidth()
                .background(GlassBubbleStrong)
        ) {
            options.forEach { (option, display) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = display,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary
                        )
                    },
                    onClick = {
                        onValueSelected(option)
                        expanded = false
                    }
                )
            }
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .alpha(0f)
                .clickable { expanded = true }
        )
    }
}

@Composable
private fun AddItemDialog(
    title: String,
    description: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            GlassButton(
                onClick = onConfirm,
                text = "添加"
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
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                GlassTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = placeholder,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = GlassBubbleStrong,
        iconContentColor = AccentGlow,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        tonalElevation = 0.dp
    )
}

@Composable
private fun AddExampleDialogueDialog(
    userText: String,
    assistantText: String,
    onUserChange: (String) -> Unit,
    onAssistantChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            GlassButton(
                onClick = onConfirm,
                text = "添加"
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
        },
        title = {
            Text(
                text = "添加示例对话",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "添加一对用户-助理的示例对话，帮助人格学习说话风格",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                GlassTextField(
                    value = userText,
                    onValueChange = onUserChange,
                    label = "用户说",
                    placeholder = "用户可能会说的话...",
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3
                )
                GlassTextField(
                    value = assistantText,
                    onValueChange = onAssistantChange,
                    label = "助理回答",
                    placeholder = "人格应该如何回应...",
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3
                )
            }
        },
        shape = RoundedCornerShape(24.dp),
        containerColor = GlassBubbleStrong,
        iconContentColor = AccentGlow,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        tonalElevation = 0.dp
    )
}

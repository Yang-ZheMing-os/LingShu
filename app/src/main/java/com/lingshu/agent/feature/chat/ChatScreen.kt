package com.lingshu.agent.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MenuOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalContext
import com.lingshu.agent.ui.theme.BackgroundSecondary
import com.lingshu.agent.ui.theme.StatusThinking
import com.lingshu.agent.DrawerSide
import com.lingshu.agent.LingShuAppState
import com.lingshu.agent.core.model.Message
import com.lingshu.agent.core.model.MessageRole
import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.lingshu.agent.feature.voice.VoiceSession
import com.lingshu.agent.ui.theme.AccentGlow
import com.lingshu.agent.ui.theme.BackgroundPrimary
import com.lingshu.agent.ui.theme.BubbleType
import com.lingshu.agent.ui.theme.BreathingConfig
import com.lingshu.agent.ui.theme.BreathingLightDot
import com.lingshu.agent.ui.theme.GlassBackground
import com.lingshu.agent.ui.theme.GlassBorder
import com.lingshu.agent.ui.theme.GlassCard
import com.lingshu.agent.ui.theme.GlassInputContainer
import com.lingshu.agent.ui.theme.GlassTopBar
import com.lingshu.agent.ui.theme.GradientBubble
import com.lingshu.agent.ui.theme.PulseGlowConfig
import com.lingshu.agent.ui.theme.PulseGlowWrapper
import com.lingshu.agent.ui.theme.StatusChip
import com.lingshu.agent.ui.theme.StatusListening
import com.lingshu.agent.ui.theme.StatusType
import com.lingshu.agent.ui.theme.TextPrimary
import com.lingshu.agent.ui.theme.TextSecondary
import com.lingshu.agent.ui.theme.TextTertiary
import com.lingshu.agent.ui.theme.ThinkingDotsIndicator
import com.lingshu.agent.ui.theme.UserBubbleStart
import com.lingshu.agent.ui.theme.VolumeWaveformIndicator
import kotlinx.coroutines.launch

/**
 * 主对话界面 - ChatScreen
 *
 * 结构：
 * ┌──────────────────────────────────┐
 * │  GlassTopBar 顶部状态栏          │  人格名 + 模型名 + 状态Chip
 * ├──────────────────────────────────┤
 * │                                  │
 * │  LazyColumn 气泡流               │  用户冰蓝渐变右对齐
 * │    · AI 气泡(暖白左)             │  AI暖白渐变左对齐
 * │    · 用户气泡(冰蓝右)            │  支持图片/语音播放按钮
 * │    ...                           │
 * │                                  │
 * ├──────────────────────────────────┤
 * │  底部输入区域                    │
 * │  [+][输入框      ][🎤脉冲光晕][→]│  语音按钮LISTENING时显示脉冲
 * └──────────────────────────────────┘
 *
 * 语音按钮脉冲光晕：
 * - VoiceSession状态 = LISTENING / RECOGNIZING → 脉冲激活 + 音量波形可视化
 * - 其他状态 → 普通玻璃按钮
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    appState: LingShuAppState,
    modifier: Modifier = Modifier
) {
    // ==================== 收集ViewModel状态 ====================

    val messages by viewModel.messages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val isThinking by viewModel.isThinking.collectAsState()
    val voiceState by viewModel.voiceState.collectAsState()
    val voiceError by viewModel.voiceError.collectAsState()
    val voiceVolume by viewModel.voiceVolume.collectAsState()
    val voicePartial by viewModel.voicePartialText.collectAsState()
    val speechState by viewModel.speechState.collectAsState()
    val speechPartialText by viewModel.speechPartialText.collectAsState()
    val currentModelName by viewModel.currentModelName.collectAsState()
    val statusChipType by viewModel.statusType.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()

    val context = LocalContext.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 语音监听激活态判断（VoiceSession + 语音输入）
    val isVoiceActive = voiceState == VoiceSession.SessionState.RECOGNIZING ||
            voiceState == VoiceSession.SessionState.LISTENING
    val isSpeechRecording = speechState == SpeechState.LISTENING ||
            speechState == SpeechState.PROCESSING

    // 权限请求状态
    var showPermissionRationale by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.toggleVoiceInput()
        } else {
            Toast.makeText(context, "请在设置中授予麦克风权限", Toast.LENGTH_SHORT).show()
        }
    }

    // 权限申请对话框
    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            title = { Text("需要麦克风权限") },
            text = { Text("灵枢需要麦克风权限才能听到你的声音，所有语音数据只在本地处理，不会上传") },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionRationale = false
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("授予") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPermissionRationale = false
                    Toast.makeText(context, "请在设置中授予麦克风权限", Toast.LENGTH_SHORT).show()
                }) { Text("拒绝") }
            }
        )
    }

    // ==================== 新消息时自动滚动到底部 ====================

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    // ==================== 整体布局 ====================

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundPrimary)
    ) {
        // ========== 1. 顶部状态栏 ==========
        ChatTopBar(
            appState = appState,
            personaName = appState.currentPersona?.name ?: "灵枢",
            modelName = currentModelName,
            statusChipType = statusChipType,
            onClearChat = {
                viewModel.clearConversation()
            }
        )

        // 语音唤醒状态指示器（呼吸灯）
        VoiceStatusIndicator(
            voiceState = voiceState ?: VoiceSession.SessionState.IDLE,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            size = 10f
        )

        // ========== 2. 气泡列表区 ==========
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty()) {
                // 空状态占位
                EmptyChatPlaceholder(
                    personaName = appState.currentPersona?.name ?: "灵枢"
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = 16.dp,
                        vertical = 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    reverseLayout = false
                ) {
                    items(
                        items = messages,
                        key = { message -> message.id }
                    ) { message ->
                        ChatMessageBubble(
                            message = message,
                            isStreamingLast = isStreaming &&
                                    message.id == messages.lastOrNull()?.id,
                            onPlayAudio = { messageId ->
                                // 预留：点击语音播放
                            }
                        )
                    }

                    // 底部占位（防止被输入栏遮挡）
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            // 语音STT实时识别文字浮层（居中底部）
            if (isVoiceActive && voicePartial.isNotBlank()) {
                VoicePartialOverlay(text = voicePartial)
            }
        }

        // ========== 3. 底部输入区 ==========
        // 录音波形（录音时显示在输入区域上方）
        VoiceWaveform(isActive = isSpeechRecording)

        ChatInputBar(
            inputText = inputText,
            onInputChanged = { viewModel.updateInputText(it) },
            onSendClick = { viewModel.sendTextMessage() },
            onStopStreaming = { viewModel.stopStreaming() },
            onVoiceClick = {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    viewModel.toggleVoiceInput()
                } else {
                    showPermissionRationale = true
                }
            },
            onAddAttachment = {
                // 预留：图片/文件选择
            },
            isSending = isSending,
            isStreaming = isStreaming,
            isVoiceActive = isVoiceActive,
            isSpeechRecording = isSpeechRecording,
            voiceVolume = voiceVolume,
            appState = appState
        )
    }
}

// ==================== 顶部状态栏 ====================

/**
 * 聊天界面顶部状态栏
 *
 * 结构：
 * [菜单≡]  [人格头像] 人格名 + 模型名   状态Chip  [清空🗑] [控制面板⊞]
 */
@Composable
private fun VoiceStatusIndicator(
    voiceState: VoiceSession.SessionState,
    modifier: Modifier = Modifier,
    size: Float = 12f
) {
    // 颜色映射
    val color = when (voiceState) {
        VoiceSession.SessionState.IDLE,
        VoiceSession.SessionState.ENDED -> Color.Gray
        VoiceSession.SessionState.LISTENING,
        VoiceSession.SessionState.RECOGNIZING -> Color(0xFF4CAF50)  // 绿色
        VoiceSession.SessionState.THINKING -> Color(0xFF2196F3)     // 蓝色
        VoiceSession.SessionState.SPEAKING -> Color(0xFFFF9800)     // 橙色
        VoiceSession.SessionState.ERROR -> Color(0xFFF44336)        // 红色
    }

    // 蓝色脉冲动画（THINKING 状态）
    val infiniteTransition = rememberInfiniteTransition(label = "voicePulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val shouldPulse = voiceState == VoiceSession.SessionState.THINKING
    val alpha by infiniteTransition.animateFloat(
        initialValue = if (shouldPulse) 0.4f else 0.7f,
        targetValue = if (shouldPulse) 1f else 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Canvas(
        modifier = modifier.size(size.dp)
    ) {
        val center = this.center
        val radius = if (shouldPulse) size / 2 * pulseScale else size / 2

        // 外圈光晕
        drawCircle(
            color = color.copy(alpha = alpha * 0.3f),
            radius = radius * 1.5f,
            center = center
        )
        // 内圈核心
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = radius,
            center = center
        )
    }
}

/**
 * 主界面顶部栏
 *
 * 结构：
 * [菜单≡]  [人格头像] 人格名 + 模型名   状态Chip  [清空🗑] [控制面板⊞]
 */
@Composable
private fun ChatTopBar(
    appState: LingShuAppState,
    personaName: String,
    modelName: String,
    statusChipType: ChatViewModel.StatusChipType,
    onClearChat: () -> Unit
) {
    val statusType = when (statusChipType) {
        ChatViewModel.StatusChipType.LISTENING -> StatusType.LISTENING
        ChatViewModel.StatusChipType.THINKING -> StatusType.THINKING
        ChatViewModel.StatusChipType.EXECUTING -> StatusType.EXECUTING
        else -> StatusType.IDLE
    }

    GlassTopBar(
        modifier = Modifier.fillMaxWidth(),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 人格头像（占位）
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    AccentGlow.copy(alpha = 0.3f),
                                    AccentGlow.copy(alpha = 0.15f)
                                )
                            )
                        )
                        .border(
                            border = BorderStroke(1.dp, GlassBorder),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = AccentGlow,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Text(
                        text = personaName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Text(
                        text = modelName,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = TextSecondary
                        )
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = { appState.openDrawer(DrawerSide.LEFT) }) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = "健康面板",
                    tint = TextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        actions = {
            StatusChip(type = statusType)

            Spacer(Modifier.width(4.dp))

            // 清空聊天
            IconButton(onClick = onClearChat) {
                Icon(
                    imageVector = Icons.Filled.ClearAll,
                    contentDescription = "清空对话",
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }

            // 右侧控制面板按钮
            IconButton(onClick = { appState.openDrawer(DrawerSide.RIGHT) }) {
                Icon(
                    imageVector = Icons.Filled.MenuOpen,
                    contentDescription = "控制面板",
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    )
}

// ==================== 空对话占位 ====================

@Composable
private fun EmptyChatPlaceholder(
    personaName: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(horizontal = 40.dp)
        ) {
            // 发光Logo
            BreathingLightDot(
                color = AccentGlow,
                size = 64.dp,
                config = com.lingshu.agent.ui.theme.BreathingConfig(
                    durationMs = 2500,
                    glowRadius = 32.dp
                )
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "你好，我是$personaName",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Text(
                    text = "有什么我可以帮助你的吗？",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = TextSecondary
                    ),
                    textAlign = TextAlign.Center
                )
            }

            // 快捷提示卡片
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                QuickTipCard(text = "今天天气怎么样？")
                QuickTipCard(text = "帮我写一首关于秋天的诗")
                QuickTipCard(text = "总结一下最近的健康报告")
            }
        }
    }
}

@Composable
private fun QuickTipCard(text: String) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        glowAlpha = 0.03f
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(AccentGlow.copy(alpha = 0.6f))
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = TextSecondary
                ),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "→",
                style = MaterialTheme.typography.titleSmall.copy(
                    color = AccentGlow
                )
            )
        }
    }
}

// ==================== 单条消息气泡 ====================

/**
 * 单条聊天消息气泡
 *
 * @param message 消息数据
 * @param isStreamingLast 是否为当前正在流式输出的最后一条消息
 * @param onPlayAudio 点击语音播放按钮回调
 */
@Composable
private fun ChatMessageBubble(
    message: Message,
    isStreamingLast: Boolean,
    onPlayAudio: (String) -> Unit
) {
    val isUser = message.role == MessageRole.USER
    val bubbleType = if (isUser) BubbleType.USER else BubbleType.AI

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        // 头像（AI消息在左，用户消息可以没有或者在右）
        if (!isUser) {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                AccentGlow.copy(alpha = 0.3f),
                                AccentGlow.copy(alpha = 0.1f)
                            )
                        )
                    )
                    .border(0.5.dp, GlassBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = AccentGlow,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // 气泡主体
        GradientBubble(
            type = bubbleType,
            modifier = Modifier
                .animateContentSize()
                .then(
                    if (isUser) Modifier.widthIn(max = 320.dp)
                    else Modifier.widthIn(max = 320.dp)
                )
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 图片列表（如果有）
                if (message.images.isNotEmpty()) {
                    MessageImageGrid(images = message.images)
                }

                // 文本内容
                if (message.content.isNotBlank()) {
                    val textColor = if (isUser) {
                        TextPrimary
                    } else {
                        Color(0xFF2A2A2A) // 暖白气泡上的深色文字
                    }

                    if (isStreamingLast && message.content.isBlank()) {
                        // 正在加载中的思考点点点
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            ThinkingDotsIndicator(
                                color = if (isUser) TextPrimary.copy(alpha = 0.9f)
                                else Color(0xFF2A2A2A).copy(alpha = 0.7f),
                                dotSize = 6.dp
                            )
                        }
                    } else {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = textColor,
                                lineHeight = 24.sp
                            )
                        )

                        // 流式最后一条时的光标闪烁
                        if (isStreamingLast) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(18.dp)
                                    .alpha(0.8f)
                                    .background(textColor)
                            )
                        }
                    }
                } else if (isStreamingLast) {
                    // 空内容且是流式，显示思考中
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ThinkingDotsIndicator(
                            color = Color(0xFF2A2A2A).copy(alpha = 0.7f)
                        )
                    }
                }

                // 语音播放按钮（如果有audioUrl）
                if (!message.audioUrl.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MiniAudioPlayButtonImpl(
                            onClick = { onPlayAudio(message.id) },
                            isPlaying = false,
                            bubbleType = bubbleType
                        )
                        // 语音时长占位
                        Text(
                            text = "00:12",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (isUser) TextPrimary.copy(alpha = 0.7f)
                                else Color(0xFF2A2A2A).copy(alpha = 0.5f)
                            )
                        )
                    }
                }

                // 模型名（仅AI消息，且非流式中）
                if (!isUser && !message.modelName.isNullOrBlank() && !isStreamingLast) {
                    Text(
                        text = "· ${message.modelName}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color(0xFF2A2A2A).copy(alpha = 0.4f),
                            fontSize = 10.sp
                        )
                    )
                }
            }
        }

        // 用户端头像（可选占位，一般不需要）
        if (isUser) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                UserBubbleStart.copy(alpha = 0.9f),
                                UserBubbleStart.copy(alpha = 0.7f)
                            )
                        )
                    )
                    .border(0.5.dp, GlassBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * 消息图片网格（1~4张图）
 */
@Composable
private fun MessageImageGrid(
    images: List<String>,
    maxImages: Int = 4
) {
    val displayImages = images.take(maxImages)
    val size = when (displayImages.size) {
        1 -> 160.dp to 160.dp
        2 -> 140.dp to 140.dp
        else -> 100.dp to 100.dp
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (displayImages.size <= 2) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                displayImages.forEach { url ->
                    MessageImageItem(
                        url = url,
                        width = size.first,
                        height = size.second
                    )
                }
            }
        } else {
            // 3~4 张图：2x2 网格
            val row1 = displayImages.take(2)
            val row2 = displayImages.drop(2)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row1.forEach { url ->
                    MessageImageItem(url, size.first, size.second)
                }
            }
            if (row2.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row2.forEach { url ->
                        MessageImageItem(url, size.first, size.second)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageImageItem(
    url: String,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = Modifier
            .size(width, height)
            .clip(RoundedCornerShape(12.dp))
            .background(BackgroundSecondary.copy(alpha = 0.3f))
            .border(0.5.dp, GlassBorder, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * 气泡内迷你语音播放按钮
 */
@Composable
private fun MiniAudioPlayButtonImpl(
    onClick: () -> Unit,
    isPlaying: Boolean,
    bubbleType: BubbleType
) {
    val iconColor = if (bubbleType == BubbleType.USER) TextPrimary else Color(0xFF2A2A2A)
    val bgColor = if (bubbleType == BubbleType.USER) {
        TextPrimary.copy(alpha = 0.15f)
    } else {
        Color(0xFF2A2A2A).copy(alpha = 0.1f)
    }

    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(if (isPlaying) 10.dp else 8.dp)
                .clip(
                    if (isPlaying) RoundedCornerShape(2.dp)
                    else androidx.compose.foundation.shape.GenericShape { _, _ ->
                        // 播放三角占位
                    }
                )
                .background(iconColor)
        )
    }
}

// ==================== 语音实时转写浮层 ====================

@Composable
private fun VoicePartialOverlay(
    text: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 120.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = text.isNotBlank(),
            enter = fadeIn() + scaleIn(initialScale = 0.95f),
            exit = fadeOut() + scaleOut(targetScale = 0.95f)
        ) {
            GlassCard(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                glowColor = StatusListening,
                glowAlpha = 0.1f
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BreathingLightDot(
                        color = StatusListening,
                        size = 10.dp,
                        config = com.lingshu.agent.ui.theme.BreathingConfig(
                            durationMs = 1200,
                            glowRadius = 8.dp
                        )
                    )
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = TextPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ==================== 底部输入栏 ====================

/**
 * 聊天底部输入区域
 *
 * 结构：
 * [附件+] [玻璃输入框(可多行)] [语音脉冲光晕(Mic)] [发送/停止]
 *
 * - 正在流式输出时：发送按钮变为停止按钮
 * - 语音LISTENING时：语音按钮激活脉冲光晕 + 音量波形
 */
@Composable
private fun ChatInputBar(
    inputText: String,
    onInputChanged: (String) -> Unit,
    onSendClick: () -> Unit,
    onStopStreaming: () -> Unit,
    onVoiceClick: () -> Unit,
    onAddAttachment: () -> Unit,
    isSending: Boolean,
    isStreaming: Boolean,
    isVoiceActive: Boolean,
    isSpeechRecording: Boolean,
    voiceVolume: Float,
    appState: LingShuAppState
) {
    val canSend = inputText.isNotBlank() && !isSending && !isStreaming

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        BackgroundPrimary.copy(alpha = 0.98f)
                    )
                )
            )
            .padding(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp,
                bottom = 8.dp
            )
            .navigationBarsPadding()
            .imePadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 附件按钮
            IconButton(
                onClick = onAddAttachment,
                modifier = Modifier
                    .size(48.dp)
                    .padding(bottom = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(14.dp))
                        .background(GlassBackground.copy(alpha = 0.12f))
                        .border(0.5.dp, GlassBorder, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "附件",
                        tint = TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // 输入框（玻璃容器 + Material3 OutlinedTextField）
            GlassInputContainer(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 4.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChanged,
                    modifier = Modifier
                        .fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = if (isVoiceActive) "正在聆听..." else "和灵枢说点什么...",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = TextTertiary
                            )
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = TextPrimary
                    ),
                    maxLines = 5,
                    minLines = 1,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = AccentGlow,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
            }

            // 语音按钮（LISTENING时脉冲光晕）
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .padding(bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                PulseGlowWrapper(
                    isActive = isVoiceActive,
                    config = PulseGlowConfig(
                        maxScale = 1.35f,
                        maxAlpha = 0.85f,
                        durationMs = 1200,
                        glowColor = StatusListening
                    )
                ) {
                    // 音量波形（激活时显示在按钮周围）
                    if (isVoiceActive) {
                        VolumeWaveformIndicator(
                            volume = voiceVolume,
                            color = StatusListening,
                            modifier = Modifier
                                .size(72.dp)
                                .padding(8.dp)
                        )
                    }

                    // 实际按钮
                    val micActive = isVoiceActive || isSpeechRecording
                    val glowColor = when {
                        isSpeechRecording -> StatusListening
                        isVoiceActive -> StatusListening
                        else -> AccentGlow
                    }
                    com.lingshu.agent.ui.theme.CircleGlowIconButton(
                        onClick = onVoiceClick,
                        size = 48.dp,
                        glowColor = glowColor,
                        isActive = micActive
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = if (isSpeechRecording) "停止录音" else "语音输入",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // 发送 / 停止 按钮
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .padding(bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isStreaming) {
                    // 流式输出中 → 停止按钮
                    com.lingshu.agent.ui.theme.CircleGlowIconButton(
                        onClick = onStopStreaming,
                        size = 48.dp,
                        glowColor = StatusThinking,
                        isActive = true
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "停止生成",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    // 普通发送按钮
                    androidx.compose.animation.AnimatedVisibility(
                        visible = canSend,
                        enter = scaleIn(initialScale = 0.8f) + fadeIn(),
                        exit = scaleOut(targetScale = 0.8f) + fadeOut()
                    ) {
                        com.lingshu.agent.ui.theme.CircleGlowIconButton(
                            onClick = onSendClick,
                            size = 48.dp,
                            glowColor = AccentGlow,
                            isActive = true
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Send,
                                contentDescription = "发送",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    // 不可发送时的占位按钮（静态）
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !canSend,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(GlassBackground.copy(alpha = 0.08f))
                                .border(0.5.dp, GlassBorder, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Send,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

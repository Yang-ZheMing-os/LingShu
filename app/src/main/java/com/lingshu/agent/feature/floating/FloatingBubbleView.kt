package com.lingshu.agent.feature.floating

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingshu.agent.R
import kotlin.math.roundToInt

// ==================== 悬浮窗状态枚举 ====================

/**
 * 悬浮气泡状态
 *
 * 四种状态对应不同的视觉样式：
 * - STANDBY（待机）：蓝色，静止不动，用户未交互
 * - AWAKENED（唤醒）：绿色，刚被唤醒，等待输入
 * - THINKING（思考中）：紫色，正在调用模型处理用户请求
 * - EXECUTING（执行中）：橙色，正在执行自动化操作/控制
 */
enum class FloatingBubbleState(
    val displayName: String,
    val primaryColor: Color,
    val accentColor: Color,
    val glowColor: Color
) {
    STANDBY(
        displayName = "待机",
        primaryColor = Color(0xFF3B82F6),
        accentColor = Color(0xFF60A5FA),
        glowColor = Color(0x663B82F6)
    ),
    AWAKENED(
        displayName = "唤醒",
        primaryColor = Color(0xFF10B981),
        accentColor = Color(0xFF34D399),
        glowColor = Color(0x6610B981)
    ),
    THINKING(
        displayName = "思考",
        primaryColor = Color(0xFF8B5CF6),
        accentColor = Color(0xFFA78BFA),
        glowColor = Color(0x668B5CF6)
    ),
    EXECUTING(
        displayName = "执行",
        primaryColor = Color(0xFFF97316),
        accentColor = Color(0xFFFB923C),
        glowColor = Color(0x66F97316)
    );

    companion object {
        fun safeValueOf(name: String?): FloatingBubbleState = runCatching {
            valueOf(name ?: STANDBY.name)
        }.getOrDefault(STANDBY)
    }
}

// ==================== Compose 自定义气泡视图 ====================

/**
 * 悬浮气泡 Compose 视图
 *
 * 设计要素：
 * 1. 玻璃磨砂（毛玻璃）背景：半透明 + 模糊光晕
 * 2. 外发光动画：状态色外发光，THINKING状态呼吸脉冲
 * 3. 状态色区分：待机蓝、唤醒绿、思考紫、执行橙
 * 4. 中央Icon：默认灵枢Logo图标，THINKING 时换成三个跳动圆点
 * 5. 脉冲光晕：思考/执行状态的呼吸动画
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun FloatingBubbleView(
    state: FloatingBubbleState,
    sizeDp: Int = 64,
    alpha: Float = 0.92f,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> }
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bubble_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha"
    )
    val rotate by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "glow_rotate"
    )

    val needPulse = state == FloatingBubbleState.THINKING || state == FloatingBubbleState.EXECUTING
    val pulseScaleFinal = if (needPulse) pulseScale else 1.0f
    val glowColor = state.glowColor

    Box(
        modifier = Modifier
            .size((sizeDp * 1.7f).dp)
            .graphicsLayer {
                scaleX = pulseScaleFinal
                scaleY = pulseScaleFinal
                this.alpha = if (needPulse) pulseAlpha else 0.25f
                rotationZ = if (needPulse) rotate * 360f else 0f
            }
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColor.copy(alpha = 0.9f),
                        glowColor.copy(alpha = 0.0f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .border(
                    width = 1.5.dp,
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            state.accentColor,
                            state.primaryColor,
                            state.accentColor
                        )
                    ),
                    shape = CircleShape
                )
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            state.primaryColor.copy(alpha = alpha * 0.85f),
                            state.primaryColor.copy(alpha = alpha * 0.65f),
                            state.accentColor.copy(alpha = alpha * 0.8f)
                        )
                    ),
                    shape = CircleShape
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size((sizeDp * 0.82f).dp)
                    .clip(CircleShape)
                    .background(
                        color = Color.White.copy(alpha = 0.08f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                BubbleCenterContent(state = state, sizeDp = sizeDp)
            }
        }
    }
}

/**
 * 气泡中心内容：图标或思考动画
 */
@Composable
private fun BubbleCenterContent(
    state: FloatingBubbleState,
    sizeDp: Int
) {
    when (state) {
        FloatingBubbleState.THINKING -> {
            ThinkingDots(sizeDp = sizeDp)
        }
        FloatingBubbleState.EXECUTING -> {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_mic_notification),
                    contentDescription = null,
                    modifier = Modifier.size((sizeDp * 0.4f).dp),
                    tint = Color.White
                )
                Text(
                    text = state.displayName,
                    color = Color.White,
                    fontSize = (sizeDp * 0.12f).sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                )
            }
        }
        FloatingBubbleState.AWAKENED -> {
            Icon(
                painter = painterResource(id = R.drawable.ic_mic_notification),
                contentDescription = null,
                modifier = Modifier.size((sizeDp * 0.45f).dp),
                tint = Color.White
            )
        }
        FloatingBubbleState.STANDBY -> {
            Icon(
                painter = painterResource(id = R.drawable.ic_mic_notification),
                contentDescription = null,
                modifier = Modifier.size((sizeDp * 0.42f).dp),
                tint = Color.White.copy(alpha = 0.95f)
            )
        }
    }
}

/**
 * 思考中：三个跳动圆点
 */
@Composable
private fun ThinkingDots(sizeDp: Int) {
    val infinite = rememberInfiniteTransition(label = "dots_bounce")
    val dotCount = 3
    val animatedOffsets = List(dotCount) { idx ->
        val target = -(sizeDp * 0.08f)
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = target,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 400 + idx * 120, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dot_$idx"
        )
    }
    Row(
        modifier = Modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(dotCount) { i ->
            Box(
                modifier = Modifier
                    .size((sizeDp * 0.09f).dp)
                    .offset { IntOffset(0, -animatedOffsets[i].value.roundToInt()) }
                    .background(
                        color = Color.White,
                        shape = CircleShape
                    )
            )
        }
    }
}

// ==================== 快捷对话弹出面板 ====================

/**
 * 快捷对话面板（点击气泡后弹出的Compose小窗）
 * 含：输入框、最近对话快捷入口、常用指令按钮
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickChatPanel(
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
    onQuickCommand: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    Surface(
        modifier = Modifier
            .width(300.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(color = Color(0xE61F2937))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(20.dp)
            ),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "灵枢助手",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_close_notification),
                        contentDescription = "关闭",
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.1f)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "问我任何事…",
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = Color(0xFF60A5FA),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    singleLine = true
                )
                IconButton(
                    onClick = {
                        if (input.isNotBlank()) {
                            onSend(input)
                            input = ""
                        }
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_mic_notification),
                        contentDescription = "发送",
                        tint = Color(0xFF60A5FA)
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickCommandChip("今天日程") { onQuickCommand("今天日程") }
                QuickCommandChip("健康建议") { onQuickCommand("根据我的健康数据给建议") }
                QuickCommandChip("播放音乐") { onQuickCommand("推荐一些放松音乐") }
                QuickCommandChip("定时提醒") { onQuickCommand("设置一个30分钟后的喝水提醒") }
            }
        }
    }
}

@Composable
private fun QuickCommandChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

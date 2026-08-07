package com.lingshu.agent.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 玻璃磨砂通用组件集合
 *
 * 包含：
 * - GlassCard: 半透明玻璃卡片（背景模糊 + 边框 + 发光阴影）
 * - GradientBubble: 用户/AI渐变气泡组件
 * - GlowButton: 带发光效果的按钮
 * - StatusChip: 小型状态标签（待机/监听/思考/执行）
 * - SectionTitle: 分区标题
 */

// ==================== GlassCard - 玻璃磨砂卡片 ====================

/**
 * 玻璃磨砂卡片组件
 *
 * 实现效果：
 * - 半透明背景（GlassBackground rgba 6%）
 * - 半透明边框（GlassBorder rgba 8%）
 * - 柔和发光阴影
 * - 大圆角设计（默认20dp）
 *
 * @param modifier 通用Modifier
 * @param shape 卡片形状，默认大圆角
 * @param contentPadding 内部内容padding
 * @param glowColor 发光颜色，默认强调发光色
 * @param glowAlpha 发光透明度
 * @param content 卡片内容
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    glowColor: Color = AccentGlow,
    glowAlpha: Float = 0.08f,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            // 底层发光效果（用blur模拟光晕）
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = glowColor.copy(alpha = glowAlpha),
                spotColor = glowColor.copy(alpha = glowAlpha * 0.5f)
            )
            // 卡片形状裁剪
            .clip(shape)
            // 玻璃磨砂半透明背景
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        GlassBackground.copy(alpha = 0.08f),
                        GlassBackground
                    )
                )
            )
            // 玻璃边框
            .border(
                border = BorderStroke(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            GlassBorder.copy(alpha = 0.12f),
                            GlassBorder
                        )
                    )
                ),
                shape = shape
            )
            // 内容内边距
            .padding(contentPadding),
        content = content
    )
}

// ==================== GradientBubble - 渐变气泡组件 ====================

/**
 * 气泡类型枚举
 */
enum class BubbleType {
    /** 用户消息气泡 - 冰蓝渐变，右对齐，深色文字 */
    USER,

    /** AI消息气泡 - 暖白渐变，左对齐，深色文字 */
    AI,

    /** 系统/时间戳气泡 - 玻璃风格 */
    SYSTEM
}

/**
 * 渐变气泡组件
 *
 * 用户气泡：冰蓝渐变（#4A8CFF → #6E9CFF）右对齐
 * AI气泡：暖白渐变（#F5E6D3 → #FFFFFF）左对齐
 *
 * @param modifier 通用Modifier
 * @param type 气泡类型（USER/AI/SYSTEM）
 * @param content 气泡内容（通常是Text，但可包含图片、语音按钮等）
 */
@Composable
fun GradientBubble(
    modifier: Modifier = Modifier,
    type: BubbleType = BubbleType.AI,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = when (type) {
        // 用户气泡：左下圆角较小，其余较大
        BubbleType.USER -> RoundedCornerShape(
            topStart = 24.dp,
            topEnd = 24.dp,
            bottomStart = 24.dp,
            bottomEnd = 8.dp
        )
        // AI气泡：右下圆角较小，其余较大
        BubbleType.AI -> RoundedCornerShape(
            topStart = 24.dp,
            topEnd = 24.dp,
            bottomStart = 8.dp,
            bottomEnd = 24.dp
        )
        // 系统气泡：均匀圆角
        BubbleType.SYSTEM -> RoundedCornerShape(12.dp)
    }

    val backgroundBrush = when (type) {
        BubbleType.USER -> Brush.verticalGradient(
            colors = listOf(
                UserBubbleEnd,
                UserBubbleStart
            )
        )
        BubbleType.AI -> Brush.verticalGradient(
            colors = listOf(
                AiBubbleEnd,
                AiBubbleStart
            )
        )
        BubbleType.SYSTEM -> Brush.verticalGradient(
            colors = listOf(
                GlassBackground.copy(alpha = 0.1f),
                GlassBackground
            )
        )
    }

    val shadowElevation = when (type) {
        BubbleType.USER -> 6.dp
        BubbleType.AI -> 4.dp
        BubbleType.SYSTEM -> 2.dp
    }

    val shadowColor = when (type) {
        BubbleType.USER -> UserBubbleStart.copy(alpha = 0.25f)
        BubbleType.AI -> AiBubbleStart.copy(alpha = 0.15f)
        BubbleType.SYSTEM -> Color.Transparent
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = shadowElevation,
                shape = shape,
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
            .clip(shape)
            .background(backgroundBrush)
            .then(
                if (type == BubbleType.SYSTEM) {
                    Modifier.border(
                        border = BorderStroke(0.5.dp, GlassBorder),
                        shape = shape
                    )
                } else Modifier
            )
            .padding(
                horizontal = 16.dp,
                vertical = 12.dp
            ),
        content = content
    )
}

// ==================== GlowButton - 发光按钮 ====================

/**
 * 发光按钮组件
 *
 * 带强调色光晕效果的按钮，用于主要操作
 *
 * @param onClick 点击回调
 * @param modifier 通用Modifier
 * @param enabled 是否启用
 * @param glowColor 发光颜色，默认AccentGlow
 * @param contentPadding 内容内边距
 * @param content 按钮内容（通常是Text+Icon）
 */
@Composable
fun GlowButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glowColor: Color = AccentGlow,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            // 外层发光晕（用两层blur模拟）
            .then(
                if (enabled) {
                    Modifier
                        .shadow(
                            elevation = 12.dp,
                            shape = shape,
                            ambientColor = glowColor.copy(alpha = 0.4f),
                            spotColor = glowColor.copy(alpha = 0.5f)
                        )
                } else Modifier
            )
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .clip(shape),
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (enabled) {
                    Brush.verticalGradient(
                        colors = listOf(
                            glowColor,
                            glowColor.copy(alpha = 0.85f)
                        )
                    ).let {
                        // Material3 ButtonColors需要Color，这里用近似纯色
                        glowColor
                    }
                } else {
                    Color(0xFF2A3A5C)
                },
                contentColor = TextPrimary,
                disabledContainerColor = Color(0xFF2A3A5C),
                disabledContentColor = TextTertiary
            ),
            contentPadding = contentPadding,
            interactionSource = interactionSource,
            elevation = null
        ) {
            CompositionLocalProvider(
                LocalContentColor provides TextPrimary
            ) {
                content()
            }
        }
    }
}

/**
 * 圆形发光图标按钮（用于语音按钮等）
 *
 * @param onClick 点击回调
 * @param modifier 通用Modifier
 * @param size 按钮直径
 * @param glowColor 发光颜色
 * @param isActive 是否处于激活状态（激活时光晕更强）
 * @param content 图标内容
 */
@Composable
fun CircleGlowIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    glowColor: Color = AccentGlow,
    isActive: Boolean = false,
    content: @Composable () -> Unit
) {
    val baseGlow = if (isActive) 20.dp else 8.dp
    val glowAlpha = if (isActive) 0.6f else 0.3f
    val backgroundColor = if (isActive) {
        Brush.verticalGradient(
            colors = listOf(
                glowColor,
                glowColor.copy(alpha = 0.8f)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                GlassBackground.copy(alpha = 0.12f),
                GlassBackground.copy(alpha = 0.08f)
            )
        )
    }

    Box(
        modifier = modifier
            .size(size)
            .shadow(
                elevation = baseGlow,
                shape = CircleShape,
                ambientColor = glowColor.copy(alpha = glowAlpha),
                spotColor = glowColor.copy(alpha = glowAlpha)
            )
            .clip(CircleShape)
            .background(backgroundColor)
            .border(
                border = BorderStroke(
                    width = if (isActive) 2.dp else 1.dp,
                    color = if (isActive) glowColor else GlassBorder
                ),
                shape = CircleShape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(
                    bounded = true,
                    color = glowColor
                ),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (isActive) TextPrimary else AccentGlow
        ) {
            content()
        }
    }
}

// ==================== StatusChip - 状态标签 ====================

/**
 * 状态枚举
 */
enum class StatusType {
    /** 待机 */
    IDLE,
    /** 正在监听 */
    LISTENING,
    /** 正在思考 */
    THINKING,
    /** 正在执行 */
    EXECUTING,
    /** 成功 */
    SUCCESS,
    /** 警告 */
    WARNING,
    /** 错误 */
    ERROR
}

/**
 * 小型状态标签组件
 *
 * 用于显示系统运行状态：待机/监听/思考/执行等
 *
 * @param type 状态类型
 * @param modifier 通用Modifier
 * @param customText 可选自定义文字，默认使用状态中文名
 */
@Composable
fun StatusChip(
    type: StatusType,
    modifier: Modifier = Modifier,
    customText: String? = null
) {
    val (backgroundColor, textColor, statusText) = when (type) {
        StatusType.IDLE -> Triple(
            GlassBackground.copy(alpha = 0.08f),
            TextSecondary,
            customText ?: "待机"
        )
        StatusType.LISTENING -> Triple(
            StatusListening.copy(alpha = 0.15f),
            StatusListening,
            customText ?: "监听中"
        )
        StatusType.THINKING -> Triple(
            StatusThinking.copy(alpha = 0.15f),
            StatusThinking,
            customText ?: "思考中"
        )
        StatusType.EXECUTING -> Triple(
            StatusExecuting.copy(alpha = 0.15f),
            StatusExecuting,
            customText ?: "执行中"
        )
        StatusType.SUCCESS -> Triple(
            StatusSuccess.copy(alpha = 0.15f),
            StatusSuccess,
            customText ?: "成功"
        )
        StatusType.WARNING -> Triple(
            StatusWarning.copy(alpha = 0.15f),
            StatusWarning,
            customText ?: "警告"
        )
        StatusType.ERROR -> Triple(
            StatusError.copy(alpha = 0.15f),
            StatusError,
            customText ?: "错误"
        )
    }

    val shape = RoundedCornerShape(100.dp) // 胶囊形

    Row(
        modifier = modifier
            .clip(shape)
            .background(backgroundColor)
            .border(
                border = BorderStroke(0.5.dp, textColor.copy(alpha = 0.3f)),
                shape = shape
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 状态指示圆点
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(textColor)
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall.copy(
                color = textColor,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            )
        )
    }
}

// ==================== SectionTitle - 分区标题 ====================

/**
 * 分区标题组件
 *
 * 次级色文字 + 分隔线，用于区域分组
 *
 * @param title 标题文字
 * @param modifier 通用Modifier
 * @param showDivider 是否显示分隔线
 */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(
                color = TextSecondary,
                letterSpacing = 0.5.sp
            ),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )
        if (showDivider) {
            Spacer(
                modifier = Modifier
                    .height(0.5.dp)
                    .fillMaxWidth()
                    .background(GlassBorder)
            )
        }
    }
}

// ==================== GlassOutlinedTextField - 玻璃风格输入框容器 ====================

/**
 * 玻璃风格输入框外部容器
 *
 * 用于包装TextField的玻璃外观（背景+边框+发光）
 *
 * @param modifier 通用Modifier
 * @param shape 形状
 * @param content 内容
 */
@Composable
fun GlassInputContainer(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = 4.dp,
                shape = shape,
                ambientColor = GlassBackground.copy(alpha = 0.1f),
                spotColor = GlassBackground.copy(alpha = 0.05f)
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        GlassBackground.copy(alpha = 0.1f),
                        GlassBackground.copy(alpha = 0.06f)
                    )
                )
            )
            .border(
                border = BorderStroke(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            GlassBorder.copy(alpha = 0.15f),
                            GlassBorder.copy(alpha = 0.08f)
                        )
                    )
                ),
                shape = shape
            ),
        content = content
    )
}

// ==================== GlassTopBar - 玻璃风格顶部栏 ====================

/**
 * 玻璃磨砂风格顶部栏
 *
 * @param modifier 通用Modifier
 * @param title 标题
 * @param navigationIcon 左侧图标按钮
 * @param actions 右侧操作按钮
 */
@Composable
fun GlassTopBar(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit = {},
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    val shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape),
        color = Color.Transparent,
        shape = shape
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            BackgroundSecondary.copy(alpha = 0.95f),
                            BackgroundSecondary.copy(alpha = 0.7f)
                        )
                    )
                )
                .border(
                    border = BorderStroke(
                        width = 0.5.dp,
                        color = GlassBorder
                    ),
                    shape = shape
                )
                .padding(
                    start = 8.dp,
                    end = 8.dp,
                    top = 8.dp,
                    bottom = 16.dp
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.size(40.dp)) {
                    navigationIcon()
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    title()
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    actions()
                }
            }
        }
    }
}

// ==================== 播放/语音按钮 ====================

/**
 * 语音播放迷你按钮（气泡内用）
 *
 * @param onClick 点击回调
 * @param modifier 通用Modifier
 * @param isPlaying 是否正在播放
 */
@Composable
fun MiniAudioPlayButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false
) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(TextPrimary.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // 简单的播放图标占位（实际可换成Icons.Default.PlayArrow/Pause）
        Box(
            modifier = Modifier
                .size(if (isPlaying) 10.dp else 0.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(TextPrimary.copy(alpha = 0.9f))
        )
    }
}

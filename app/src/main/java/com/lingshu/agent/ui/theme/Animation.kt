package com.lingshu.agent.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 通用动画集合
 *
 * 包含：
 * - PulseGlowAnimation: 脉冲光晕动画（infiniteTransition + scale/alpha）
 * - TypewriterText: 打字机文字动画
 * - SlideNavigationTransitions: 滑入滑出导航过渡
 * - BreathingLight: 呼吸灯动画
 */

// ==================== 脉冲光晕动画 ====================

/**
 * 脉冲光晕动画参数配置
 */
data class PulseGlowConfig(
    val minScale: Float = 1.0f,
    val maxScale: Float = 1.25f,
    val minAlpha: Float = 0.3f,
    val maxAlpha: Float = 0.9f,
    val durationMs: Int = 1500,
    val glowColor: Color = AccentGlow
)

/**
 * 脉冲光晕外层 - 包裹在需要发光的组件外部
 *
 * 通过无限循环的 scale + alpha 变化实现呼吸发光效果
 * 常用于语音监听按钮激活状态等场景
 *
 * @param modifier 通用Modifier
 * @param isActive 是否激活脉冲（true时播放动画，false时静态不发光）
 * @param config 动画配置
 * @param content 被包裹的内容
 */
@Composable
fun PulseGlowWrapper(
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    config: PulseGlowConfig = PulseGlowConfig(),
    content: @Composable BoxScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_glow")

    val scale by if (isActive) {
        infiniteTransition.animateFloat(
            initialValue = config.minScale,
            targetValue = config.maxScale,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = config.durationMs,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_scale"
        )
    } else {
        animateFloatAsState(
            targetValue = 1.0f,
            animationSpec = tween(300),
            label = "pulse_scale_static"
        )
    }

    val alpha by if (isActive) {
        infiniteTransition.animateFloat(
            initialValue = config.minAlpha,
            targetValue = config.maxAlpha,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = config.durationMs,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_alpha"
        )
    } else {
        animateFloatAsState(
            targetValue = 0f,
            animationSpec = tween(300),
            label = "pulse_alpha_static"
        )
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // 外层发光圈（Canvas绘制光晕）
        if (isActive) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(scale)
            ) {
                val center = Offset(this.size.width / 2, this.size.height / 2)
                val radius = size.minDimension / 2

                // 多层同心圆模拟光晕
                for (i in 0 until 3) {
                    val layerRadius = radius * (0.85f + i * 0.15f)
                    val layerAlpha = alpha * (1f - i * 0.3f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                config.glowColor.copy(alpha = layerAlpha),
                                Color.Transparent
                            ),
                            center = center,
                            radius = layerRadius
                        ),
                        radius = layerRadius,
                        center = center,
                        alpha = 1f
                    )
                }

                // 外圈描边
                drawCircle(
                    color = config.glowColor.copy(alpha = alpha * 0.5f),
                    radius = radius * 0.95f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        // 实际内容
        content()
    }
}

// ==================== 打字机文字动画 ====================

/**
 * 打字机效果文字动画
 *
 * 逐字显示文本内容，常用于AI回复的流式输出效果展示
 *
 * @param text 完整目标文本
 * @param modifier 通用Modifier
 * @param typewriterSpeedMs 每个字符显示间隔（毫秒），默认30ms
 * @param startDelayMs 开始前延迟
 * @param onTypingComplete 打字完成回调
 * @param textStyle 文字样式（通常由外部Text提供，这里只是辅助参数）
 * @param content 实际渲染Composable，参数为当前已显示的字符串
 */
@Composable
fun TypewriterText(
    text: String,
    modifier: Modifier = Modifier,
    typewriterSpeedMs: Long = 30L,
    startDelayMs: Long = 0L,
    cursorVisible: Boolean = true,
    onTypingComplete: () -> Unit = {},
    content: @Composable (displayText: String, showCursor: Boolean) -> Unit
) {
    var displayedText by remember(text) { mutableStateOf("") }
    var isComplete by remember(text) { mutableStateOf(false) }

    // 光标闪烁
    val infiniteTransition = rememberInfiniteTransition(label = "cursor_blink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_alpha"
    )

    LaunchedEffect(text) {
        displayedText = ""
        isComplete = false

        if (startDelayMs > 0) {
            delay(startDelayMs)
        }

        // 逐字显示
        for (i in text.indices) {
            displayedText = text.substring(0, i + 1)
            delay(typewriterSpeedMs)
        }

        isComplete = true
        onTypingComplete()
    }

    Box(modifier = modifier) {
        val showCursor = cursorVisible && !isComplete
        content(displayedText, showCursor && cursorAlpha > 0.5f)
    }
}

/**
 * 简化版打字机Text（直接提供Text Composable）
 *
 * @param text 完整目标文本
 * @param modifier 通用Modifier
 * @param color 文字颜色
 * @param style 文字样式
 * @param speedMs 打字速度（每字符毫秒）
 */
@Composable
fun TypewriterTextSimple(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
    speedMs: Long = 30L
) {
    TypewriterText(
        text = text,
        modifier = modifier,
        typewriterSpeedMs = speedMs
    ) { displayText, showCursor ->
        val displayWithCursor = if (showCursor) "$displayText|" else displayText
        androidx.compose.material3.Text(
            text = displayWithCursor,
            color = color,
            style = style
        )
    }
}

// ==================== 导航滑入滑出过渡 ====================

/**
 * 导航方向枚举
 */
enum class NavDirection {
    /** 向左滑入（新内容从右侧进入） */
    FORWARD,
    /** 向右滑入（新内容从左侧进入 = 返回） */
    BACKWARD,
    /** 向上滑入（底部面板） */
    UPWARD,
    /** 淡入淡出 */
    FADE
}

/**
 * 导航过渡动画 - 进入动画
 *
 * 提供不同方向的滑入过渡
 *
 * @param direction 方向
 * @param durationMs 动画时长
 */
fun slideInTransition(
    direction: NavDirection = NavDirection.FORWARD,
    durationMs: Int = 300
): EnterTransition {
    return when (direction) {
        NavDirection.FORWARD -> {
            // 从右往左滑入 + 淡入
            slideInHorizontally(
                initialOffsetX = { it }, // 从最右侧滑入
                animationSpec = tween(durationMs, easing = FastOutSlowInEasing)
            ) + fadeIn(
                animationSpec = tween(durationMs, easing = FastOutLinearInEasing)
            )
        }
        NavDirection.BACKWARD -> {
            // 从左往右滑入 + 淡入
            slideInHorizontally(
                initialOffsetX = { -it }, // 从最左侧滑入
                animationSpec = tween(durationMs, easing = FastOutSlowInEasing)
            ) + fadeIn(
                animationSpec = tween(durationMs, easing = FastOutLinearInEasing)
            )
        }
        NavDirection.UPWARD -> {
            // 从底部向上滑入
            slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(durationMs, easing = FastOutSlowInEasing)
            ) + fadeIn(
                animationSpec = tween(durationMs, easing = FastOutLinearInEasing)
            )
        }
        NavDirection.FADE -> {
            // 纯淡入 + 轻微放大
            scaleIn(
                initialScale = 0.95f,
                animationSpec = tween(durationMs, easing = FastOutSlowInEasing)
            ) + fadeIn(
                animationSpec = tween(durationMs, easing = FastOutLinearInEasing)
            )
        }
    }
}

/**
 * 导航过渡动画 - 退出动画
 *
 * @param direction 方向
 * @param durationMs 动画时长
 */
fun slideOutTransition(
    direction: NavDirection = NavDirection.FORWARD,
    durationMs: Int = 250
): ExitTransition {
    return when (direction) {
        NavDirection.FORWARD -> {
            // 旧内容向左滑出 + 淡出
            slideOutHorizontally(
                targetOffsetX = { -it / 3 }, // 滑出到左侧（部分滑出）
                animationSpec = tween(durationMs, easing = FastOutSlowInEasing)
            ) + fadeOut(
                animationSpec = tween(durationMs, easing = FastOutLinearInEasing)
            )
        }
        NavDirection.BACKWARD -> {
            // 旧内容向右滑出 + 淡出
            slideOutHorizontally(
                targetOffsetX = { it / 3 },
                animationSpec = tween(durationMs, easing = FastOutSlowInEasing)
            ) + fadeOut(
                animationSpec = tween(durationMs, easing = FastOutLinearInEasing)
            )
        }
        NavDirection.UPWARD -> {
            // 向下滑出
            slideOutVertically(
                targetOffsetY = { it / 4 },
                animationSpec = tween(durationMs, easing = FastOutSlowInEasing)
            ) + fadeOut(
                animationSpec = tween(durationMs, easing = FastOutLinearInEasing)
            )
        }
        NavDirection.FADE -> {
            // 纯淡出 + 轻微缩小
            scaleOut(
                targetScale = 0.98f,
                animationSpec = tween(durationMs, easing = FastOutSlowInEasing)
            ) + fadeOut(
                animationSpec = tween(durationMs, easing = FastOutLinearInEasing)
            )
        }
    }
}

/**
 * 包装 AnimatedVisibility 的便捷函数
 *
 * @param visible 是否可见
 * @param direction 过渡方向
 * @param modifier Modifier
 * @param label 动画标签
 * @param content 内容
 */
@Composable
fun AnimatedSlideVisibility(
    visible: Boolean,
    direction: NavDirection = NavDirection.FADE,
    modifier: Modifier = Modifier,
    label: String = "animated_visibility",
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInTransition(direction),
        exit = slideOutTransition(direction),
        modifier = modifier,
        label = label,
        content = content
    )
}

// ==================== 呼吸灯动画 ====================

/**
 * 呼吸灯配置
 */
data class BreathingConfig(
    val minAlpha: Float = 0.3f,
    val maxAlpha: Float = 1.0f,
    val durationMs: Int = 2000,
    val glowRadius: Dp = 16.dp
)

/**
 * 呼吸灯效果组件 - 一个带呼吸光效的圆点
 *
 * 常用于指示在线状态、录制状态等
 *
 * @param color 呼吸灯颜色
 * @param isBreathing 是否处于呼吸状态
 * @param modifier Modifier
 * @param size 圆点大小
 * @param config 呼吸配置
 */
@Composable
fun BreathingLightDot(
    color: Color = AccentGlow,
    isBreathing: Boolean = true,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    config: BreathingConfig = BreathingConfig()
) {
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")

    val alpha by if (isBreathing) {
        infiniteTransition.animateFloat(
            initialValue = config.minAlpha,
            targetValue = config.maxAlpha,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = config.durationMs,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathing_alpha"
        )
    } else {
        animateFloatAsState(
            targetValue = config.maxAlpha,
            animationSpec = tween(200),
            label = "breathing_alpha_static"
        )
    }

    val scale by if (isBreathing) {
        infiniteTransition.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = config.durationMs,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathing_scale"
        )
    } else {
        animateFloatAsState(
            targetValue = 1f,
            animationSpec = tween(200),
            label = "breathing_scale_static"
        )
    }

    Box(
        modifier = modifier
            .size(size + config.glowRadius)
            .scale(scale),
        contentAlignment = Alignment.Center
    ) {
        // 外层光晕
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val halfSize = size.toPx() / 2

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = alpha * 0.6f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = config.glowRadius.toPx() + halfSize
                ),
                radius = config.glowRadius.toPx() + halfSize,
                center = center
            )
        }

        // 实心圆点
        Box(
            modifier = Modifier
                .size(size)
                .scale(scale)
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                drawCircle(
                    color = color.copy(alpha = alpha),
                    radius = size.toPx() / 2
                )
            }
        }
    }
}

// ==================== 音量波动动画（用于语音监听可视化） ====================

/**
 * 语音音量波动可视化
 *
 * 绘制一排竖直条形，根据音量值波动，用于录音/监听状态展示
 *
 * @param volume 音量值 0~1
 * @param color 条形颜色
 * @param modifier Modifier
 * @param barCount 条形数量
 */
@Composable
fun VolumeWaveformIndicator(
    volume: Float,
    color: Color = StatusListening,
    modifier: Modifier = Modifier,
    barCount: Int = 5
) {
    val infiniteTransition = rememberInfiniteTransition(label = "volume_wave")

    // 给每个条形生成不同的波动相位
    val phases = remember(barCount) {
        (0 until barCount).map { it * (1f / barCount) }
    }

    val animProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "volume_anim_progress"
    )

    Canvas(
        modifier = modifier
    ) {
        val barWidth = size.width / (barCount * 2 - 1)
        val maxHeight = size.height
        val safeVolume = volume.coerceIn(0f, 1f)

        phases.forEachIndexed { index, phase ->
            // 计算每个条形的高度：基础高度 + 音量影响 + 相位波动
            val offset = (phase + animProgress) % 1f
            val wave = kotlin.math.sin(offset * Math.PI * 2).toFloat()
            val barHeight = (maxHeight * (0.2f + safeVolume * 0.5f + wave * safeVolume * 0.3f))
                .coerceIn(4.dp.toPx(), maxHeight)

            val x = index * barWidth * 2
            val y = (maxHeight - barHeight) / 2

            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
                alpha = 0.6f + safeVolume * 0.4f
            )
        }
    }
}

// ==================== 思考点点点动画 ====================

/**
 * "正在思考" 三点跳动动画
 *
 * 常用于AI回复加载状态
 *
 * @param color 点颜色
 * @param modifier Modifier
 */
@Composable
fun ThinkingDotsIndicator(
    color: Color = TextSecondary,
    modifier: Modifier = Modifier,
    dotSize: Dp = 6.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking_dots")

    val dot1Y by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 900
                0f at 0
                -1f at 150
                0f at 300
                0f at 900
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot1_y"
    )

    val dot2Y by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 900
                0f at 150
                -1f at 300
                0f at 450
                0f at 900
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot2_y"
    )

    val dot3Y by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 900
                0f at 300
                -1f at 450
                0f at 600
                0f at 900
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "dot3_y"
    )

    val jumpDistance = dotSize * 1.5f

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dotSize / 2)
        ) {
            // 点1
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .then(Modifier)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = color,
                        radius = dotSize.toPx() / 2,
                        center = Offset(size.width / 2, size.height / 2 + dot1Y * jumpDistance.toPx())
                    )
                }
            }
            // 点2
            Box(modifier = Modifier.size(dotSize)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = color,
                        radius = dotSize.toPx() / 2,
                        center = Offset(size.width / 2, size.height / 2 + dot2Y * jumpDistance.toPx())
                    )
                }
            }
            // 点3
            Box(modifier = Modifier.size(dotSize)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = color,
                        radius = dotSize.toPx() / 2,
                        center = Offset(size.width / 2, size.height / 2 + dot3Y * jumpDistance.toPx())
                    )
                }
            }
        }
    }
}

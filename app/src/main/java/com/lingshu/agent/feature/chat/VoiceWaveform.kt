package com.lingshu.agent.feature.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * 录音波形动画组件
 *
 * 在录音时显示在输入区域上方，模拟声波波形的随机波动效果。
 *
 * @param isActive 是否处于录音状态，false 时隐藏（不占用布局空间）
 * @param barCount 波形竖线条数，默认 5
 * @param barWidth 每条竖线的宽度，默认 4dp
 * @param maxHeight 竖线最大高度，默认 32dp
 * @param minHeight 竖线最小高度，默认 8dp
 * @param color 竖线颜色，默认红色
 * @param modifier 外部 Modifier
 */
@Composable
fun VoiceWaveform(
    isActive: Boolean,
    barCount: Int = 5,
    barWidth: Dp = 4.dp,
    maxHeight: Dp = 32.dp,
    minHeight: Dp = 8.dp,
    color: Color = Color(0xFFEF4444),
    modifier: Modifier = Modifier
) {
    if (!isActive) return

    // 每条竖线的动画高度
    val animatables = remember(barCount) {
        List(barCount) { Animatable(minHeight.value) }
    }

    // 启动无限循环动画，每条竖线独立随机变化
    animatables.forEachIndexed { index, animatable ->
        LaunchedEffect(isActive, index) {
            while (true) {
                val target = Random.nextFloat() * (maxHeight.value - minHeight.value) + minHeight.value
                animatable.animateTo(
                    targetValue = target,
                    animationSpec = tween(
                        durationMillis = 200 + Random.nextInt(300),
                        easing = FastOutSlowInEasing
                    )
                )
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(maxHeight)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        animatables.forEachIndexed { index, animatable ->
            if (index > 0) Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(animatable.value.dp)
                    .clip(RoundedCornerShape(barWidth / 2))
                    .background(color)
            )
        }
    }
}

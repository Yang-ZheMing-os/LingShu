package com.lingshu.feature.floating.presentation

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lingshu.core.ui.theme.LingShuTheme
import com.lingshu.core.common.event.FloatingSize
import com.lingshu.core.common.event.FloatingState

@Composable
fun FloatingBall(
    state: FloatingState,
    size: FloatingSize,
    opacity: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    LingShuTheme {
        Surface(
            modifier = modifier
                .size(size.dp.dp)
                .clip(CircleShape)
                .clickable { onClick() },
            color = Color.Transparent
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                when (state) {
                    FloatingState.IDLE -> IdleBall(Color(state.color), opacity, size.dp.dp)
                    FloatingState.LISTENING -> ListeningBall(Color(state.color), opacity, size.dp.dp)
                    FloatingState.THINKING -> ThinkingBall(Color(state.color), opacity, size.dp.dp)
                    FloatingState.EXECUTING -> ExecutingBall(Color(state.color), opacity, size.dp.dp)
                    FloatingState.ERROR -> ErrorBall(Color(state.color), opacity, size.dp.dp)
                }
            }
        }
    }
}

@Composable
private fun IdleBall(color: Color, opacity: Float, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        drawCircle(
            color = color.copy(alpha = opacity),
            radius = size.toPx() / 2 - 4.dp.toPx()
        )
    }
}

@Composable
private fun ListeningBall(color: Color, opacity: Float, size: Dp) {
    val infiniteTransition = rememberInfiniteTransition(label = "listening")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "listening_scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "listening_alpha"
    )

    Canvas(modifier = Modifier.size(size)) {
        val centerRadius = size.toPx() / 2 - 8.dp.toPx()
        val glowRadius = (size.toPx() / 2 - 2.dp.toPx()) * scale

        drawCircle(
            color = color.copy(alpha = alpha * opacity),
            radius = glowRadius
        )
        drawCircle(
            color = color.copy(alpha = opacity),
            radius = centerRadius
        )
    }
}

@Composable
private fun ThinkingBall(color: Color, opacity: Float, size: Dp) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "thinking_pulse"
    )

    Canvas(modifier = Modifier.size(size)) {
        val centerRadius = size.toPx() / 2 - 10.dp.toPx()

        for (i in 0..2) {
            val adjustedPulse = (pulse + i * 0.33f) % 1f
            val pulseRadius = centerRadius + (size.toPx() / 2 - centerRadius) * adjustedPulse
            val pulseAlpha = (1f - adjustedPulse) * 0.6f

            drawCircle(
                color = color.copy(alpha = pulseAlpha * opacity),
                radius = pulseRadius,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        drawCircle(
            color = color.copy(alpha = opacity),
            radius = centerRadius
        )
    }
}

@Composable
private fun ExecutingBall(color: Color, opacity: Float, size: Dp) {
    val infiniteTransition = rememberInfiniteTransition(label = "executing")
    val flash by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "executing_flash"
    )

    Canvas(modifier = Modifier.size(size)) {
        val radius = size.toPx() / 2 - 6.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = flash * opacity),
                    color.copy(alpha = (flash * 0.7f) * opacity)
                )
            ),
            radius = radius
        )
    }
}

@Composable
private fun ErrorBall(color: Color, opacity: Float, size: Dp) {
    val infiniteTransition = rememberInfiniteTransition(label = "error")
    val shake by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "error_shake"
    )

    Canvas(modifier = Modifier.size(size)) {
        val radius = size.toPx() / 2 - 6.dp.toPx()
        drawCircle(
            color = color.copy(alpha = opacity),
            radius = radius,
            center = center.copy(x = center.x + shake.dp.toPx())
        )
    }
}

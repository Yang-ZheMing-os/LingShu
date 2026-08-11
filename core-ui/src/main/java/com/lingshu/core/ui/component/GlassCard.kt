package com.lingshu.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lingshu.core.ui.theme.CardShape
import com.lingshu.core.ui.theme.GlassBackground
import com.lingshu.core.ui.theme.GlassBorder

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    borderWidth: Dp = 1.dp,
    borderColor: Color = GlassBorder,
    backgroundColor: Color = GlassBackground,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                color = backgroundColor,
                shape = shape
            )
            .border(
                width = borderWidth,
                color = borderColor,
                shape = shape
            )
    ) {
        content()
    }
}

@Composable
fun GlassCardGradient(
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    borderWidth: Dp = 1.dp,
    borderColor: Color = GlassBorder,
    gradientColors: List<Color> = listOf(
        GlassBackground,
        GlassBackground.copy(alpha = 0.3f)
    ),
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(colors = gradientColors),
                shape = shape
            )
            .border(
                width = borderWidth,
                color = borderColor,
                shape = shape
            )
    ) {
        content()
    }
}

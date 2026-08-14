package com.lingshu.feature.chat.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingshu.core.common.ToolCallCleaner
import com.lingshu.core.ui.theme.OnPrimary
import com.lingshu.core.ui.theme.Primary
import com.lingshu.core.common.event.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val UserBubbleColor = Primary
private val AiBubbleColor = Color(0xFFFFF8F0)
private val AiBubbleTextColor = Color(0xFF2D2D2D)
private val TypingDotColor = Color(0xFF94A3B8)

@Composable
fun ChatBubble(
    message: Message,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false
) {
    val isUser = message.isUser

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .then(
                    if (isUser) Modifier.padding(start = 48.dp)
                    else Modifier.padding(end = 48.dp)
                ),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = if (isUser) UserBubbleColor else AiBubbleColor,
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (isStreaming && message.content.isEmpty()) {
                    // AI 思考中：三个跳动小圆点
                    TypingIndicator()
                } else {
                    // 显示前先剥掉 [TOOL_CALL]...[/TOOL_CALL] 标记，避免把 JSON 指令暴露给用户
                    val cleanedContent = ToolCallCleaner.stripToolCallMarks(message.content)
                    // 统一用 AnnotatedString，流式时末尾追加着色光标 "▍"
                    val displayText = buildAnnotatedString {
                        append(cleanedContent)
                        if (isStreaming) {
                            withStyle(SpanStyle(color = Primary)) {
                                append(" ▍")
                            }
                        }
                    }
                    Text(
                        text = displayText,
                        color = if (isUser) OnPrimary else AiBubbleTextColor,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    )
                }
            }

            // 流式进行中不显示时间戳（消息尚未落库）
            if (!isStreaming) {
                Text(
                    text = formatTime(message.timestamp),
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
                )
            }
        }
    }
}

/**
 * AI 思考中的三点动画指示器，依次淡入淡出。
 */
@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    val dots = listOf(0, 1, 2).map { index ->
        transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, delayMillis = index * 150),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dot_$index"
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        dots.forEach { alpha ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(alpha.value)
                    .background(color = TypingDotColor, shape = CircleShape)
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

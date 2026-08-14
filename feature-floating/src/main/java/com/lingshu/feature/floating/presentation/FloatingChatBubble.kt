package com.lingshu.feature.floating.presentation

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingshu.core.ui.theme.LingShuTheme

@Composable
fun FloatingChatBubble(
    streamingReply: String,
    isSending: Boolean,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }

    LingShuTheme {
        Surface(
            modifier = modifier
                .fillMaxSize(),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1A1A2E),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "灵枢助手",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    )
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = "关闭",
                            style = TextStyle(
                                color = Color(0xFF888888),
                                fontSize = 12.sp
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // AI 回复显示区域（可滚动）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(
                            Color(0xFF2A2A3E),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp)
                ) {
                    if (streamingReply.isEmpty() && isSending) {
                        // AI 思考中：三点动画
                        TypingDots()
                    } else if (streamingReply.isNotEmpty()) {
                        // 显示流式回复，流式进行中末尾追加光标
                        val displayText = if (isSending) "$streamingReply |" else streamingReply
                        Text(
                            text = displayText,
                            style = TextStyle(
                                color = Color(0xFFE0E0E0),
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            ),
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    } else {
                        // 空闲态提示
                        Text(
                            text = "输入消息开始对话",
                            style = TextStyle(
                                color = Color(0xFF666666),
                                fontSize = 13.sp
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 输入框
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(
                            Color(0xFF2A2A3E),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp)
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxSize(),
                        enabled = !isSending,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 13.sp
                        ),
                        decorationBox = { innerTextField ->
                            if (text.isEmpty()) {
                                Text(
                                    text = if (isSending) "等待回复..." else "输入消息...",
                                    style = TextStyle(
                                        color = Color(0xFF666666),
                                        fontSize = 13.sp
                                    )
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        if (text.isNotBlank() && !isSending) {
                            onSend(text.trim())
                            text = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = text.isNotBlank() && !isSending,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4A8CFF),
                        disabledContainerColor = Color(0xFF2A3A5A)
                    )
                ) {
                    Text(
                        text = if (isSending) "回复中..." else "发送",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    )
                }
            }
        }
    }
}

/** AI 思考中的三点动画 */
@Composable
private fun TypingDots() {
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
                    .background(color = Color(0xFF94A3B8), shape = RoundedCornerShape(50))
            )
        }
    }
}

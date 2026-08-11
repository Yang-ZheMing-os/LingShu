package com.lingshu.feature.chat.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingshu.core.ui.theme.OnSurfaceVariant
import com.lingshu.core.ui.theme.Primary
import com.lingshu.core.ui.theme.Surface
import com.lingshu.core.ui.theme.SurfaceVariant

@Composable
fun MessageInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    ttsEnabled: Boolean,
    onToggleTts: () -> Unit,
    isListening: Boolean = false,
    onToggleVoiceInput: () -> Unit = {},
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(
            onClick = onToggleTts,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = if (ttsEnabled) Icons.Default.Star else Icons.Default.Info,
                contentDescription = if (ttsEnabled) "关闭语音播报" else "开启语音播报",
                tint = if (ttsEnabled) Primary else OnSurfaceVariant
            )
        }

        IconButton(
            onClick = onToggleVoiceInput,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = if (isListening) "停止语音输入" else "开始语音输入",
                tint = if (isListening) Primary else OnSurfaceVariant
            )
        }

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    text = if (isListening) "正在聆听..." else "输入消息...",
                    color = OnSurfaceVariant,
                    fontSize = 15.sp
                )
            },
            textStyle = TextStyle(fontSize = 15.sp),
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = SurfaceVariant,
                unfocusedContainerColor = SurfaceVariant,
                focusedIndicatorColor = Primary,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Primary
            ),
            singleLine = false,
            maxLines = 4,
            enabled = enabled
        )

        IconButton(
            onClick = onSend,
            enabled = text.isNotBlank() && enabled,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Send,
                contentDescription = "发送",
                tint = if (text.isNotBlank() && enabled) Primary else OnSurfaceVariant
            )
        }
    }
}

package com.lingshu.feature.wakeword.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingshu.core.common.state.UiState
import com.lingshu.core.ui.theme.Background
import com.lingshu.core.ui.theme.OnBackground
import com.lingshu.core.ui.theme.OnSurfaceVariant
import com.lingshu.core.ui.theme.Primary
import com.lingshu.core.ui.theme.Surface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WakeWordScreen(
    viewModel: WakeWordViewModel = hiltViewModel()
) {
    val isRunning by viewModel.isRunning.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val lastEvent by viewModel.lastWakeWordEvent.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "唤醒词",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            )
        },
        containerColor = Background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 状态卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = null,
                            tint = if (isRunning) Primary else OnSurfaceVariant
                        )
                        Spacer(modifier = Modifier.padding(end = 8.dp))
                        Text(
                            text = if (isRunning) "监听中" else "已停止",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            color = OnBackground
                        )
                    }

                    Text(
                        text = "当前方案：系统语音识别（降级模式）\n" +
                                "说明：Porcupine SDK 未集成，使用 Android SpeechRecognizer 作为降级方案。国产设备可能不可用。",
                        fontSize = 13.sp,
                        color = OnSurfaceVariant
                    )

                    lastEvent?.let { event ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "最近唤醒：${event.keyword}",
                            fontSize = 14.sp,
                            color = Primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 错误提示
            if (engineState is UiState.Error) {
                val error = engineState as UiState.Error
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = error.message ?: "唤醒词引擎错误",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 控制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isRunning) {
                    OutlinedButton(
                        onClick = { viewModel.stopService() },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) {
                        Text(text = "停止监听")
                    }
                } else {
                    Button(
                        onClick = { viewModel.startService() },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                    ) {
                        Text(text = "开始监听")
                    }
                }
            }

            Text(
                text = "提示：唤醒词功能需要麦克风权限和后台运行权限。启动后会以常驻通知形式运行。",
                fontSize = 12.sp,
                color = OnSurfaceVariant
            )
        }
    }
}

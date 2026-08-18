package com.lingshu.feature.chat.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingshu.core.common.state.UiState
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.ui.component.LoadingState
import com.lingshu.core.ui.theme.Background
import com.lingshu.core.ui.theme.Error
import com.lingshu.core.ui.theme.OnBackground
import com.lingshu.core.ui.theme.OnSurfaceVariant
import com.lingshu.core.ui.theme.Surface
import com.lingshu.core.common.event.Message
import com.lingshu.feature.chat.presentation.components.ChatBubble
import com.lingshu.feature.chat.presentation.components.MessageInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messagesState by viewModel.messagesState.collectAsState()
    val sendState by viewModel.sendState.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val ttsEnabled by viewModel.ttsEnabled.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val streamingMessage by viewModel.streamingMessage.collectAsState()
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "对话",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    IconButton(onClick = { viewModel.clearChat() }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "清空对话",
                            tint = OnSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Surface,
                    titleContentColor = OnBackground,
                    actionIconContentColor = OnSurfaceVariant
                )
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                QuickActionsBar(onClick = { text -> viewModel.sendQuickCommand(text) })
                MessageInput(
                    text = inputText,
                    onTextChange = { viewModel.updateInputText(it) },
                    onSend = { viewModel.sendMessage() },
                    ttsEnabled = ttsEnabled,
                    onToggleTts = { viewModel.toggleTts() },
                    isListening = isListening,
                    onToggleVoiceInput = { viewModel.toggleVoiceInput() },
                    enabled = !sendState.isLoading
                )
            }
        },
        containerColor = Background,
        contentWindowInsets = WindowInsets(0)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (messagesState) {
                is UiState.Loading -> {
                    LoadingState(
                        modifier = Modifier.fillMaxSize(),
                        message = "加载中..."
                    )
                }
                is UiState.Error -> {
                    ErrorState(
                        errorCode = (messagesState as UiState.Error).code,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                is UiState.Success -> {
                    val messages = (messagesState as UiState.Success<List<Message>>).data

                    if (messages.isEmpty() && streamingMessage == null) {
                        EmptyState(
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            reverseLayout = false
                        ) {
                            items(
                                items = messages,
                                key = { it.id }
                            ) { message ->
                                ChatBubble(message = message)
                            }

                            // 流式生成中的 AI 消息气泡（逐字刷新）
                            streamingMessage?.let { streaming ->
                                item(key = "streaming-bubble") {
                                    ChatBubble(
                                        message = streaming,
                                        isStreaming = true
                                    )
                                }
                            }
                        }

                        // 消息数或流式内容变化时，滚动到底部
                        LaunchedEffect(messages.size, streamingMessage?.content) {
                            val total = messages.size + (if (streamingMessage != null) 1 else 0)
                            if (total > 0) {
                                listState.animateScrollToItem(total - 1)
                            }
                        }
                    }
                }
                UiState.Idle -> Unit
            }

            if (sendState is UiState.Error) {
                val errorState = sendState as UiState.Error
                ErrorToast(
                    message = ErrorCodes.getMessage(errorState.code ?: ErrorCodes.UNKNOWN_ERROR),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "开始对话吧",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = OnBackground
        )
        Text(
            text = "输入消息开始与 AI 交流",
            fontSize = 14.sp,
            color = OnSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun ErrorState(
    errorCode: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "加载失败",
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = Error
        )
        Text(
            text = ErrorCodes.getMessage(errorCode ?: ErrorCodes.UNKNOWN_ERROR),
            fontSize = 14.sp,
            color = OnSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun ErrorToast(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                color = Error.copy(alpha = 0.9f),
                shape = MaterialTheme.shapes.medium
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = message,
            color = Color.White,
            fontSize = 14.sp
        )
    }
}

// ==============================================================================
//  快捷指令按钮条：8 个最高频动作，横向可滚动，新手不用记口语
// ==============================================================================

private data class QuickAction(
    val label: String,
    val icon: ImageVector,
    /** 填进输入框后交给 CommandParser 解析的文本（尽量用口语化表达） */
    val commandText: String,
    val tint: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickActionsBar(onClick: (String) -> Unit) {
    val actions = remember {
        listOf(
            QuickAction(
                label = "WiFi",
                icon = Icons.Default.Wifi,
                commandText = "打开WiFi",
                tint = Color(0xFF3B82F6)
            ),
            QuickAction(
                label = "调亮",
                icon = Icons.Default.Brightness6,
                commandText = "亮度太暗了",
                tint = Color(0xFFF59E0B)
            ),
            QuickAction(
                label = "音量",
                icon = Icons.Default.VolumeUp,
                commandText = "声音大点",
                tint = Color(0xFF7C5CFF)
            ),
            QuickAction(
                label = "截屏",
                icon = Icons.Default.CameraAlt,
                commandText = "截屏",
                tint = Color(0xFF0EA5A0)
            ),
            QuickAction(
                label = "拍照",
                icon = Icons.Default.PhotoCamera,
                commandText = "拍照",
                tint = Color(0xFFEF4444)
            ),
            QuickAction(
                label = "闹钟",
                icon = Icons.Default.Alarm,
                commandText = "设置闹钟",
                tint = Color(0xFF6366F1)
            ),
            QuickAction(
                label = "搜索",
                icon = Icons.Default.Search,
                commandText = "搜索",
                tint = Color(0xFF14B8A6)
            ),
            QuickAction(
                label = "音乐",
                icon = Icons.Default.MusicNote,
                commandText = "播放音乐",
                tint = Color(0xFFEC4899)
            )
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(actions, key = { it.label }) { a ->
                QuickActionChip(action = a, onClick = { onClick(a.commandText) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickActionChip(
    action: QuickAction,
    onClick: () -> Unit
) {
    SuggestionChip(
        onClick = onClick,
        label = {
            Text(
                text = action.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        icon = {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = action.tint,
                modifier = Modifier.size(18.dp)
            )
        },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
            labelColor = OnBackground,
            iconContentColor = action.tint
        ),
        shape = RoundedCornerShape(999.dp)
    )
}

package com.lingshu.feature.chat.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
        },
        containerColor = Background
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
                    
                    if (messages.isEmpty()) {
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
                        }

                        LaunchedEffect(messages.size) {
                            if (messages.isNotEmpty()) {
                                listState.animateScrollToItem(messages.size - 1)
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

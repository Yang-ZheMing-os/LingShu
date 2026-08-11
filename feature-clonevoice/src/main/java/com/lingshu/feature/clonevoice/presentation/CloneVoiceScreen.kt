package com.lingshu.feature.clonevoice.presentation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingshu.core.common.state.UiState
import com.lingshu.core.ui.component.GlassCard
import com.lingshu.feature.clonevoice.domain.Voice
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloneVoiceScreen(
    viewModel: CloneVoiceViewModel = hiltViewModel()
) {
    val voices by viewModel.voices.collectAsState()
    val currentVoice by viewModel.currentVoice.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingTime by viewModel.recordingTime.collectAsState()
    val waveformAmplitudes by viewModel.waveformAmplitudes.collectAsState()
    val cloneState by viewModel.cloneState.collectAsState()
    val previewState by viewModel.previewState.collectAsState()

    var showDeleteDialog by remember { mutableStateOf<Voice?>(null) }
    var previewText by remember { mutableStateOf("你好，这是我的声音测试。") }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            viewModel.updateRecordingTime()
            delay(50)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("声音克隆") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isRecording) "录音中..." else "点击开始录音",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = formatDuration(recordingTime),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    WaveformDisplay(
                        amplitudes = waveformAmplitudes,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        isRecording = isRecording
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(
                                if (isRecording)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.primary
                            )
                            .clickable {
                                if (isRecording) {
                                    val file = viewModel.stopRecording()
                                    file?.let { viewModel.cloneVoice(it) }
                                } else {
                                    viewModel.startRecording()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Default.Star else Icons.Default.Info,
                            contentDescription = if (isRecording) "停止录音" else "开始录音",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    if (cloneState is UiState.Loading) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("正在克隆声音...")
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "我的声音 (${voices.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                currentVoice?.let {
                    Text(
                        text = "当前: ${it.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (voices.isEmpty()) {
                    item {
                        Text(
                            "暂无克隆声音，快去录制一个吧！",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(voices) { voice ->
                        VoiceCard(
                            voice = voice,
                            isCurrent = currentVoice?.id == voice.id,
                            onSelect = { viewModel.setCurrentVoice(voice.id) },
                            onDelete = { showDeleteDialog = voice },
                            onPreview = { viewModel.previewVoice(voice.id, previewText) },
                            previewText = previewText,
                            onPreviewTextChange = { previewText = it },
                            isPreviewing = previewState is UiState.Loading
                        )
                    }
                }
            }
        }
    }

    if (cloneState is UiState.Success) {
        AlertDialog(
            onDismissRequest = { viewModel.resetCloneState() },
            title = { Text("克隆成功") },
            text = { Text("声音克隆完成！你可以在声音列表中找到它。") },
            confirmButton = {
                TextButton(onClick = { viewModel.resetCloneState() }) {
                    Text("确定")
                }
            }
        )
    }

    if (cloneState is UiState.Error) {
        AlertDialog(
            onDismissRequest = { viewModel.resetCloneState() },
            title = { Text("克隆失败") },
            text = { Text("声音克隆失败，请重试。") },
            confirmButton = {
                TextButton(onClick = { viewModel.resetCloneState() }) {
                    Text("确定")
                }
            }
        )
    }

    showDeleteDialog?.let { voice ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除确认") },
            text = { Text("确定要删除声音\"${voice.name}\"吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteVoice(voice.id)
                    showDeleteDialog = null
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun WaveformDisplay(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
    isRecording: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val animatedAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        val barCount = 50
        val barWidth = size.width / barCount
        val barSpacing = 2.dp.toPx()
        val centerY = size.height / 2

        for (i in 0 until barCount) {
            val amplitudeIndex = (i * (amplitudes.size.toFloat() / barCount)).toInt()
            val amplitude = if (amplitudes.isNotEmpty() && amplitudeIndex < amplitudes.size) {
                amplitudes[amplitudeIndex]
            } else {
                0.1f
            }
            val barHeight = amplitude * size.height * 0.8f

            drawRect(
                color = primaryColor.copy(
                    alpha = if (isRecording) animatedAlpha else 0.5f
                ),
                topLeft = Offset(
                    x = i * barWidth + barSpacing / 2,
                    y = centerY - barHeight / 2
                ),
                size = androidx.compose.ui.geometry.Size(
                    width = barWidth - barSpacing,
                    height = barHeight.coerceAtLeast(4.dp.toPx())
                )
            )
        }
    }
}

@Composable
private fun VoiceCard(
    voice: Voice,
    isCurrent: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onPreview: () -> Unit,
    previewText: String,
    onPreviewTextChange: (String) -> Unit,
    isPreviewing: Boolean
) {
    var showPreview by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = voice.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = formatDate(voice.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    IconButton(onClick = { showPreview = !showPreview }) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "试听",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (showPreview) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = previewText,
                    onValueChange = onPreviewTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入要试听的文本...") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onPreview,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isPreviewing
                ) {
                    if (isPreviewing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("播放中...")
                    } else {
                        Text("试听")
                    }
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
    val millis = (durationMs % 1000) / 10
    return String.format("%02d:%02d.%02d", minutes, seconds, millis)
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

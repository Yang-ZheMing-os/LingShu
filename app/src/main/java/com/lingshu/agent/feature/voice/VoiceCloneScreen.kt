package com.lingshu.agent.feature.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingshu.agent.core.database.entity.ClonedVoiceEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Composable
fun VoiceCloneScreen(
    viewModel: VoiceCloneViewModel,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("声音克隆") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RecordingSection(
                state = uiState.recordingState,
                elapsedSeconds = uiState.elapsedSeconds,
                onStartRecord = { viewModel.startRecording() },
                onStopRecord = { viewModel.stopRecording() },
                onCancelRecord = { viewModel.cancelRecording() },
                onReset = { viewModel.resetState() }
            )

            if (uiState.lastClonedVoice != null && uiState.recordingState == RecordingState.DONE) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "克隆成功: ${uiState.lastClonedVoice!!.name}",
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "已克隆的声音",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = uiState.clonedVoices,
                    key = { it.id }
                ) { voice ->
                    VoiceListItem(
                        voice = voice,
                        onDelete = { viewModel.deleteVoice(voice.id) },
                        onActivate = { viewModel.setActiveVoice(voice.id) }
                    )
                }
            }
        }
    }

    if (uiState.errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("提示") },
            text = { Text(uiState.errorMessage!!) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text("确定")
                }
            }
        )
    }
}

@Composable
private fun RecordingSection(
    state: RecordingState,
    elapsedSeconds: Int,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit,
    onCancelRecord: () -> Unit,
    onReset: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(targetState = state, label = "recording_state") { currentState ->
            when (currentState) {
                RecordingState.IDLE -> {
                    RecordButton(
                        onClick = onStartRecord,
                        label = "开始录音",
                        color = Color(0xFFE53935)
                    )
                }
                RecordingState.RECORDING -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TimerDisplay(seconds = elapsedSeconds)
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RecordButton(onClick = onStopRecord, label = "停止", color = Color(0xFF4CAF50))
                            OutlinedRecordButton(onClick = onCancelRecord, label = "取消")
                        }
                    }
                }
                RecordingState.CLONING -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(64.dp), strokeWidth = 4.dp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "正在克隆声音...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                RecordingState.DONE -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.RadioButtonChecked,
                            contentDescription = "完成",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onReset) { Text("录制新的声音") }
                    }
                }
                RecordingState.ERROR -> {
                    RecordButton(onClick = onStartRecord, label = "重试", color = Color(0xFFFF9800))
                }
            }
        }
    }
}

@Composable
private fun RecordButton(onClick: () -> Unit, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            modifier = Modifier.size(80.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = color)
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun OutlinedRecordButton(onClick: () -> Unit, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White)
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = label,
                tint = Color(0xFF757575),
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF757575))
    }
}

@Composable
private fun TimerDisplay(seconds: Int) {
    val minutes = seconds / 60
    val secs = seconds % 60
    val timeText = "%02d:%02d".format(minutes, secs)
    val progress = seconds.coerceIn(0, 30).toFloat() / 30f
    val color = when {
        seconds < 10 -> Color(0xFF757575)
        seconds in 10..25 -> Color(0xFF4CAF50)
        else -> Color(0xFFE53935)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.FiberManualRecord,
                contentDescription = "录制中",
                tint = Color(0xFFE53935),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = timeText, fontSize = 48.sp, fontWeight = FontWeight.Bold, color = color)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .width(200.dp).height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFFE0E0E0))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress).height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun VoiceListItem(
    voice: ClonedVoiceEntity,
    onDelete: () -> Unit,
    onActivate: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (voice.isActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = voice.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "时长: ${voice.durationSeconds}秒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (voice.isActive) "当前激活" else "未激活",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (voice.isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!voice.isActive) {
                TextButton(onClick = onActivate) { Text("激活") }
                Spacer(modifier = Modifier.width(4.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = Color(0xFFE53935)
                )
            }
        }
    }
}


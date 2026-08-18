package com.lingshu.feature.clonevoice.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.max
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingshu.core.common.state.UiState
import com.lingshu.core.ui.component.GlassCard
import com.lingshu.feature.clonevoice.domain.Voice
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 简单的自动换行布局（替换实验性 FlowRow，避免 @OptIn 编译器报错）
 * 只支持横向间距；verticalGap 行间距
 */
@Composable
private fun WrapRow(
    modifier: Modifier = Modifier,
    horizontalGap: androidx.compose.ui.unit.Dp = 0.dp,
    verticalGap: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit
) {
    Layout(content, modifier) { measurables, constraints ->
        val hGapPx = horizontalGap.roundToPx()
        val vGapPx = verticalGap.roundToPx()
        val rowWidth = constraints.maxWidth
        var x = 0
        var y = 0
        var lineHeight = 0
        var widthSoFar = 0
        val placeables = measurables.map { it.measure(Constraints()) }
        val positions = mutableListOf<Pair<Int, Int>>()
        placeables.forEach { p ->
            val w = p.width
            val needNext = x != 0 && x + w > rowWidth
            if (needNext) {
                x = 0
                y += lineHeight + vGapPx
                lineHeight = 0
            }
            positions.add(x to y)
            x += w + hGapPx
            lineHeight = max(lineHeight, p.height)
            widthSoFar = max(widthSoFar, x - hGapPx)
        }
        val totalHeight = y + lineHeight
        layout(widthSoFar.coerceAtLeast(0), totalHeight.coerceAtLeast(0)) {
            placeables.forEachIndexed { i, p ->
                val (px, py) = positions[i]
                p.place(px, py)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CloneVoiceScreen(
    onBackClick: () -> Unit = {},
    onPickPresetFile: (onFileReady: (File?) -> Unit) -> Unit = {},
    onSavePresetFile: (suggestedName: String, onFileReady: (File?) -> Unit) -> Unit = { _, cb -> cb(null) },
    viewModel: CloneVoiceViewModel = hiltViewModel()
) {
    val voices by viewModel.voices.collectAsState()
    val currentVoice by viewModel.currentVoice.collectAsState()
    val previewState by viewModel.previewState.collectAsState()
    val presetOperation by viewModel.presetOperation.collectAsState()

    var selectedTab by remember { mutableStateOf(VPTab.LIBRARY) }
    var showDeleteDialog by remember { mutableStateOf<Voice?>(null) }
    var previewText by remember { mutableStateOf("你好，这是我的声音测试。") }
    var exportTarget by remember { mutableStateOf<Voice?>(null) }

    LaunchedEffect(exportTarget) {
        val v = exportTarget ?: return@LaunchedEffect
        onSavePresetFile("${v.name}.voicepreset") { file ->
            if (file != null) {
                viewModel.exportPreset(v.id, file) {
                    // 完成
                }
            }
            exportTarget = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("音色库分享") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                VPTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.label) }
                    )
                }
            }

            when (selectedTab) {
                VPTab.LIBRARY -> LibraryTab(
                    voices = voices,
                    currentVoice = currentVoice,
                    previewText = previewText,
                    onPreviewTextChange = { previewText = it },
                    isPreviewing = previewState is UiState.Loading,
                    onSelect = { viewModel.applyVoice(it.id) },
                    onDelete = { showDeleteDialog = it },
                    onPreview = { viewModel.previewVoice(it.id, previewText) },
                    onExport = { exportTarget = it }
                )
                VPTab.CREATE -> CreatePresetTab(
                    onCreate = { name, author, desc, tags, voiceName, pitch, rate ->
                        viewModel.createCustomPreset(name, author, desc, tags, voiceName, pitch, rate)
                    }
                )
                VPTab.IMPORT -> ImportPresetTab(
                    onPickFile = {
                        onPickPresetFile { file ->
                            file?.let { viewModel.importPreset(it) }
                        }
                    }
                )
            }
        }
    }

    val presetOp = presetOperation
    if (presetOp is UiState.Success) {
        AlertDialog(
            onDismissRequest = { viewModel.resetPresetOperation() },
            title = { Text("完成") },
            text = { Text(presetOp.data) },
            confirmButton = {
                TextButton(onClick = { viewModel.resetPresetOperation() }) { Text("确定") }
            }
        )
    } else if (presetOp is UiState.Error) {
        AlertDialog(
            onDismissRequest = { viewModel.resetPresetOperation() },
            title = { Text("操作失败") },
            text = { Text(presetOp.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.resetPresetOperation() }) { Text("确定") }
            }
        )
    } else if (presetOp is UiState.Loading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("处理中") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("请稍候...")
                }
            },
            confirmButton = {}
        )
    }

    showDeleteDialog?.let { voice ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除确认") },
            text = { Text("确定要删除音色 \"${voice.name}\" 吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteVoice(voice.id)
                    showDeleteDialog = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("取消") }
            }
        )
    }
}

private enum class VPTab(val label: String) {
    LIBRARY("本地音色库"),
    CREATE("快捷创建"),
    IMPORT("导入/分享")
}

@Composable
private fun LibraryTab(
    voices: List<Voice>,
    currentVoice: Voice?,
    previewText: String,
    onPreviewTextChange: (String) -> Unit,
    isPreviewing: Boolean,
    onSelect: (Voice) -> Unit,
    onDelete: (Voice) -> Unit,
    onPreview: (Voice) -> Unit,
    onExport: (Voice) -> Unit
) {
    if (voices.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有音色，去「快捷创建」或「导入预设」试试吧。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            currentVoice?.let {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("当前应用：${it.name}", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        val authorLine = listOfNotNull(
                            if (it.isSystemVoice) "系统预置" else "自定义预设",
                            it.author?.let { a -> "作者:$a" },
                            "pitch=${"%.2f".format(it.pitch)}",
                            "rate=${"%.2f".format(it.rate)}"
                        ).joinToString(" · ")
                        Text(authorLine, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (it.tags.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            WrapRow(horizontalGap = 6.dp, verticalGap = 4.dp) {
                                it.tags.take(6).forEach { tag ->
                                    SmallChip(tag)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        items(voices) { voice ->
            VoiceCard(
                voice = voice,
                isCurrent = currentVoice?.id == voice.id,
                previewText = previewText,
                onPreviewTextChange = onPreviewTextChange,
                isPreviewing = isPreviewing,
                onSelect = { onSelect(voice) },
                onDelete = { onDelete(voice) },
                onPreview = { onPreview(voice) },
                onExport = { onExport(voice) }
            )
        }
    }
}

@Composable
private fun CreatePresetTab(
    onCreate: (name: String, author: String, desc: String, tags: List<String>, voiceName: String?, pitch: Float, rate: Float) -> Unit
) {
    var name by remember { mutableStateOf("温柔姐姐") }
    var author by remember { mutableStateOf("Me") }
    var desc by remember { mutableStateOf("偏高音调、柔和、适合夜间播报") }
    var tagsText by remember { mutableStateOf("温柔,女声,治愈") }
    var voiceNameText by remember { mutableStateOf("") }
    var pitch by remember { mutableStateOf(1.2f) }
    var rate by remember { mutableStateOf(0.9f) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("🎨 调一个专属音色", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("不用录音。基于系统 TTS / Edge-TTS 调 pitch/rate，存成 .voicepreset 分享给朋友。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名字") }, singleLine = true) }
        item { OutlinedTextField(author, { author = it }, Modifier.fillMaxWidth(), label = { Text("作者") }, singleLine = true) }
        item { OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), label = { Text("一句话介绍") }, singleLine = true) }
        item { OutlinedTextField(tagsText, { tagsText = it }, Modifier.fillMaxWidth(), label = { Text("标签 (英文逗号分隔)") }, singleLine = true) }
        item { OutlinedTextField(voiceNameText, { voiceNameText = it }, Modifier.fillMaxWidth(), label = { Text("voiceName（可选，Edge-TTS 名如 zh-CN-XiaoxiaoNeural）") }, singleLine = true) }
        item {
            Column {
                Text("音调 Pitch: ${"%.2f".format(pitch)}")
                Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.5f..2.0f)
            }
        }
        item {
            Column {
                Text("语速 Rate: ${"%.2f".format(rate)}")
                Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.5f..2.0f)
            }
        }
        item {
            Button(onClick = {
                val tags = tagsText.split(",", "，", " ").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                onCreate(name, author, desc, tags, voiceNameText.ifBlank { null }, pitch, rate)
            }, modifier = Modifier.fillMaxWidth()) {
                Text("创建音色预设")
            }
        }
    }
}

@Composable
private fun ImportPresetTab(onPickFile: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("🎁 导入朋友分享的 .voicepreset", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("找朋友要一个 .voicepreset JSON 文件，点下面按钮导入，TA 调好的 pitch/rate 音色立刻就是你的。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
            Text("选择 .voicepreset 文件")
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "要分享自己的预设？回到「本地音色库」，每张卡片右上角有 ↗ 导出按钮。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VoiceCard(
    voice: Voice,
    isCurrent: Boolean,
    previewText: String,
    onPreviewTextChange: (String) -> Unit,
    isPreviewing: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onPreview: () -> Unit,
    onExport: () -> Unit
) {
    var showPreview by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(voice.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when {
                                voice.isSystemVoice -> "系统"
                                voice.author != null -> "作者:${voice.author}"
                                else -> "自定义"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (voice.isSystemVoice) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                    Row {
                        Text(formatDate(voice.createdAt), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (voice.voiceName != null) {
                            Text(" · ${voice.voiceName}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(" · p${"%.2f".format(voice.pitch)} r${"%.2f".format(voice.rate)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row {
                    IconButton(onClick = { showPreview = !showPreview }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "试听",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onExport) {
                        Icon(Icons.Default.IosShare, contentDescription = "导出",
                            tint = MaterialTheme.colorScheme.secondary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (!voice.description.isNullOrBlank() || voice.tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                voice.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                if (voice.tags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    WrapRow(horizontalGap = 6.dp, verticalGap = 4.dp) {
                        voice.tags.take(6).forEach { tag -> SmallChip(tag) }
                    }
                }
            }
            if (showPreview) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(previewText, onPreviewTextChange, Modifier.fillMaxWidth(),
                    placeholder = { Text("输入要试听的文本...") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onPreview, Modifier.fillMaxWidth(), enabled = !isPreviewing) {
                    if (isPreviewing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("播放中...")
                    } else {
                        Text("试听")
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallChip(text: String) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    ) {
        Text(text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** 波形展示（CreatePreset 没用到，但保留给以后扩展试听波形） */
@Composable
fun waveformPreviewStub(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val n = 40
        val w = size.width / n
        for (i in 0 until n) {
            val h = (0.2f + ((i % 7) / 10f)) * size.height
            drawRect(
                color = primary.copy(alpha = 0.5f),
                topLeft = Offset(i * w, (size.height - h) / 2),
                size = androidx.compose.ui.geometry.Size(w - 2.dp.toPx(), h)
            )
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

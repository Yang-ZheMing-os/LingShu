package com.lingshu.agent.feature.script

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingshu.agent.ui.components.GlassButton
import com.lingshu.agent.ui.components.GlassCard
import com.lingshu.agent.ui.components.GlassDivider
import com.lingshu.agent.ui.components.GlassIconButton
import com.lingshu.agent.ui.components.GlassSectionTitle
import com.lingshu.agent.ui.components.GlassTextField
import com.lingshu.agent.ui.components.GlassTopAppBar
import com.lingshu.agent.ui.theme.AccentGlow
import com.lingshu.agent.ui.theme.AccentPrimary
import com.lingshu.agent.ui.theme.Error
import com.lingshu.agent.ui.theme.GlassBubble
import com.lingshu.agent.ui.theme.GlassBubbleBorder
import com.lingshu.agent.ui.theme.GlassBubbleStrong
import com.lingshu.agent.ui.theme.IceBlueGradientEnd
import com.lingshu.agent.ui.theme.IceBlueGradientMid
import com.lingshu.agent.ui.theme.IceBlueGradientStart
import com.lingshu.agent.ui.theme.PrimaryBackground
import com.lingshu.agent.ui.theme.SecondaryBackground
import com.lingshu.agent.ui.theme.Success
import com.lingshu.agent.ui.theme.TextPrimary
import com.lingshu.agent.ui.theme.TextSecondary
import com.lingshu.agent.ui.theme.TextTertiary
import com.lingshu.agent.ui.theme.Warning
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptWorkshopScreen(
    viewModel: ScriptViewModel = hiltViewModel(),
    scriptId: String? = null,
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    val tabs = listOf("录制", "编辑", "测试")
    val tabIcons = listOf(
        Icons.Default.SmartToy,
        Icons.Default.Add,
        Icons.Default.PlayArrow
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBackground)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            GlassTopAppBar(
                title = {
                    Text(
                        text = "脚本工坊",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    GlassIconButton(
                        onClick = onBack,
                        icon = Icons.Default.ArrowBack,
                        iconModifier = Modifier.size(22.dp)
                    )
                },
                actions = {
                    GlassIconButton(
                        onClick = {
                            scope.launch {
                                viewModel.saveScript()
                            }
                        },
                        icon = Icons.Default.Save,
                        iconModifier = Modifier.size(20.dp),
                        backgroundColor = AccentPrimary.copy(alpha = 0.3f)
                    )
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                GlassTextField(
                    value = uiState.script.name,
                    onValueChange = { viewModel.updateName(it) },
                    label = "脚本名称",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                GlassTextField(
                    value = uiState.script.description,
                    onValueChange = { viewModel.updateDescription(it) },
                    label = "脚本描述",
                    placeholder = "描述这个脚本的功能和使用场景...",
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3
                )
            }

            ScrollableTabRow(
                selectedTabIndex = uiState.selectedTab,
                containerColor = Color.Transparent,
                contentColor = TextPrimary,
                edgePadding = 16.dp,
                indicator = { tabPositions ->
                    if (uiState.selectedTab < tabPositions.size) {
                        TabRowDefaults.Indicator(
                            Modifier
                                .width(tabPositions[uiState.selectedTab].width)
                                .padding(horizontal = 16.dp),
                            height = 2.dp,
                            color = AccentGlow
                        )
                    }
                },
                divider = {}
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = uiState.selectedTab == index,
                        onClick = { viewModel.selectTab(index) },
                        selectedContentColor = TextPrimary,
                        unselectedContentColor = TextTertiary,
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = tabIcons[index],
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                        }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                when (uiState.selectedTab) {
                    0 -> RecordTab(
                        uiState = uiState,
                        onToggleRecord = { viewModel.toggleRecording() },
                        onAddDemoSteps = { viewModel.addDemoSteps() },
                        onClearSteps = { viewModel.clearRecordedSteps() },
                        onRemoveStep = { viewModel.removeRecordedStep(it) },
                        onGenerateScript = { viewModel.generateScriptFromSteps() }
                    )
                    1 -> EditorTab(
                        content = uiState.script.content,
                        onContentChange = { viewModel.updateContent(it) }
                    )
                    2 -> TestTab(
                        logs = uiState.testLogs,
                        isRunning = uiState.isTestRunning,
                        onRunTest = { viewModel.runTest() },
                        onStopTest = { viewModel.stopTest() },
                        onClearLogs = { viewModel.clearLogs() }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(GlassBubbleStrong)
                    .border(
                        width = 1.dp,
                        color = GlassBubbleBorder,
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassButton(
                        onClick = { viewModel.importFromLspack("") },
                        text = "导入",
                        icon = Icons.Default.FileUpload,
                        modifier = Modifier.weight(1f)
                    )
                    GlassButton(
                        onClick = { viewModel.exportToLspack() },
                        text = "导出",
                        icon = Icons.Default.FileDownload,
                        modifier = Modifier.weight(1f)
                    )
                    GlassButton(
                        onClick = {
                            scope.launch {
                                viewModel.saveScript()
                            }
                        },
                        text = "保存",
                        icon = Icons.Default.Save,
                        modifier = Modifier.weight(1f),
                        gradient = Brush.horizontalGradient(
                            listOf(Success.copy(alpha = 0.9f), Success.copy(alpha = 0.7f))
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordTab(
    uiState: ScriptWorkshopUiState,
    onToggleRecord: () -> Unit,
    onAddDemoSteps: () -> Unit,
    onClearSteps: () -> Unit,
    onRemoveStep: (Long) -> Unit,
    onGenerateScript: () -> Unit
) {
    val pulseScale by animateFloatAsState(
        targetValue = if (uiState.isRecording) 1.15f else 1f,
        animationSpec = tween(durationMillis = 600),
        label = "pulse"
    )
    val timeFormat = remember { SimpleDateFormat("mm:ss.SSS", Locale.CHINA) }
    val startTime = uiState.recordedSteps.firstOrNull()?.timestamp ?: System.currentTimeMillis()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(20.dp),
            glowAlpha = if (uiState.isRecording) 0.35f else 0.15f,
            glowColor = if (uiState.isRecording) Error else AccentGlow
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (uiState.isRecording) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Error)
                            )
                        }
                        Text(
                            text = if (uiState.isRecording) "录制中..." else "准备录制",
                            style = MaterialTheme.typography.titleLarge,
                            color = if (uiState.isRecording) Error else TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "已记录 ${uiState.recordedSteps.size} 个步骤",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            if (uiState.isRecording) {
                                Brush.verticalGradient(listOf(Error, Error.copy(alpha = 0.7f)))
                            } else {
                                Brush.verticalGradient(listOf(IceBlueGradientStart, IceBlueGradientMid, IceBlueGradientEnd))
                            }
                        )
                        .border(
                            width = 2.dp,
                            color = if (uiState.isRecording) Error.copy(alpha = 0.5f) else AccentGlow.copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                        .clickable { onToggleRecord() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (uiState.isRecording) Icons.Default.Stop else Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassButton(
                    onClick = onAddDemoSteps,
                    text = "模拟示例",
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.isRecording
                )
                GlassButton(
                    onClick = onGenerateScript,
                    text = "生成代码",
                    icon = Icons.Default.Refresh,
                    modifier = Modifier.weight(1f),
                    enabled = uiState.recordedSteps.isNotEmpty()
                )
                GlassIconButton(
                    onClick = onClearSteps,
                    icon = Icons.Default.Clear,
                    iconModifier = Modifier.size(20.dp),
                    backgroundColor = Error.copy(alpha = 0.2f),
                    iconTint = Error,
                    enabled = uiState.recordedSteps.isNotEmpty()
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            GlassSectionTitle(title = "步骤记录")
            Spacer(modifier = Modifier.height(10.dp))
            if (uiState.recordedSteps.isEmpty()) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    padding = PaddingValues(24.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "还没有录制的步骤",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextTertiary
                            )
                            Text(
                                text = "点击红色按钮开始在手机上录制操作",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextTertiary
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.recordedSteps, key = { it.id }) { step ->
                        StepItem(
                            step = step,
                            stepNumber = uiState.recordedSteps.indexOf(step) + 1,
                            timeOffset = step.timestamp - startTime,
                            timeFormat = timeFormat,
                            onRemove = { onRemoveStep(step.id) }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StepItem(
    step: ScriptViewModel.RecordStep,
    stepNumber: Int,
    timeOffset: Long,
    timeFormat: SimpleDateFormat,
    onRemove: () -> Unit
) {
    val icon = when (step.type) {
        ScriptViewModel.StepType.CLICK -> Icons.Default.Add
        ScriptViewModel.StepType.SWIPE -> Icons.Default.Refresh
        ScriptViewModel.StepType.INPUT -> Icons.Default.Add
        ScriptViewModel.StepType.WAIT -> Icons.Default.Refresh
        ScriptViewModel.StepType.APP_LAUNCH -> Icons.Default.SmartToy
        ScriptViewModel.StepType.PRESS_BACK -> Icons.Default.ArrowBack
        ScriptViewModel.StepType.PRESS_HOME -> Icons.Default.SmartToy
    }
    val bgColor = when (step.type) {
        ScriptViewModel.StepType.CLICK -> AccentPrimary.copy(alpha = 0.25f) to AccentGlow
        ScriptViewModel.StepType.SWIPE -> Color(0xFFA78BFA).copy(alpha = 0.25f) to Color(0xFFA78BFA)
        ScriptViewModel.StepType.INPUT -> Color(0xFF34D399).copy(alpha = 0.25f) to Color(0xFF34D399)
        ScriptViewModel.StepType.WAIT -> Color(0xFFFBBF24).copy(alpha = 0.25f) to Color(0xFFFBBF24)
        ScriptViewModel.StepType.APP_LAUNCH -> Color(0xFFF472B6).copy(alpha = 0.25f) to Color(0xFFF472B6)
        ScriptViewModel.StepType.PRESS_BACK -> Warning.copy(alpha = 0.25f) to Warning
        ScriptViewModel.StepType.PRESS_HOME -> Warning.copy(alpha = 0.25f) to Warning
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(GlassBubble)
            .border(1.dp, GlassBubbleBorder, RoundedCornerShape(12.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(bgColor.first),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stepNumber.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = bgColor.second,
                fontWeight = FontWeight.Bold
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = step.description,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            Text(
                text = "+${timeFormat.format(Date(timeOffset))} · ${step.type.name}",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
        }
        GlassIconButton(
            onClick = onRemove,
            icon = Icons.Default.Delete,
            iconModifier = Modifier.size(16.dp),
            size = 30.dp,
            backgroundColor = Color.Transparent,
            iconTint = Error
        )
    }
}

@Composable
private fun EditorTab(
    content: String,
    onContentChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            padding = PaddingValues(0.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SecondaryBackground.copy(alpha = 0.8f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Error)
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFBBF24))
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF34D399))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "script.js",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${content.lines().size} 行",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    fontFamily = FontFamily.Monospace
                )
            }
            GlassDivider()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = content,
                    onValueChange = onContentChange,
                    modifier = Modifier.fillMaxSize(),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = TextPrimary
                    ),
                    cursorBrush = Brush.verticalGradient(listOf(AccentGlow, AccentPrimary))
                ) { innerTextField ->
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .width(40.dp)
                                .fillMaxSize()
                                .background(GlassBubble)
                                .padding(top = 8.dp, end = 8.dp),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.Top
                        ) {
                            val lineCount = content.lines().size.coerceAtLeast(1)
                            repeat(lineCount) { idx ->
                                Text(
                                    text = (idx + 1).toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextTertiary.copy(alpha = 0.7f),
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.height(20.dp)
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 8.dp, top = 8.dp, end = 8.dp)
                        ) {
                            innerTextField()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TestTab(
    logs: List<ScriptViewModel.TestLog>,
    isRunning: Boolean,
    onRunTest: () -> Unit,
    onStopTest: () -> Unit,
    onClearLogs: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.CHINA) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            padding = PaddingValues(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isRunning) {
                            CircularProgressIndicator(
                                color = AccentGlow,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = if (isRunning) "脚本运行中..." else "准备运行测试",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "日志条目: ${logs.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassIconButton(
                        onClick = onClearLogs,
                        icon = Icons.Default.Clear,
                        iconModifier = Modifier.size(18.dp),
                        backgroundColor = Error.copy(alpha = 0.2f),
                        iconTint = Error,
                        enabled = logs.isNotEmpty()
                    )
                    if (isRunning) {
                        GlassButton(
                            onClick = onStopTest,
                            text = "停止",
                            icon = Icons.Default.Stop,
                            gradient = Brush.horizontalGradient(
                                listOf(Error.copy(alpha = 0.9f), Error.copy(alpha = 0.7f))
                            )
                        )
                    } else {
                        GlassButton(
                            onClick = onRunTest,
                            text = "运行",
                            icon = Icons.Default.PlayArrow
                        )
                    }
                }
            }
        }

        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            padding = PaddingValues(0.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SecondaryBackground.copy(alpha = 0.8f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = AccentGlow,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "运行日志",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }
            GlassDivider()
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "暂无运行日志",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextTertiary
                        )
                        Text(
                            text = "点击\"运行\"按钮开始测试脚本",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(logs, key = { it.timestamp }) { log ->
                        LogItem(
                            log = log,
                            timeFormat = timeFormat
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogItem(
    log: ScriptViewModel.TestLog,
    timeFormat: SimpleDateFormat
) {
    val (bgColor, textColor) = when (log.level) {
        ScriptViewModel.LogLevel.DEBUG -> GlassBubble to TextTertiary
        ScriptViewModel.LogLevel.INFO -> GlassBubble to TextSecondary
        ScriptViewModel.LogLevel.WARN -> Color(0xFFFBBF24).copy(alpha = 0.1f) to Color(0xFFFBBF24)
        ScriptViewModel.LogLevel.ERROR -> Error.copy(alpha = 0.12f) to Error
        ScriptViewModel.LogLevel.SUCCESS -> Success.copy(alpha = 0.12f) to Success
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = timeFormat.format(Date(log.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            fontFamily = FontFamily.Monospace
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(
                    when (log.level) {
                        ScriptViewModel.LogLevel.DEBUG -> TextTertiary.copy(alpha = 0.25f)
                        ScriptViewModel.LogLevel.INFO -> AccentPrimary.copy(alpha = 0.25f)
                        ScriptViewModel.LogLevel.WARN -> Color(0xFFFBBF24).copy(alpha = 0.25f)
                        ScriptViewModel.LogLevel.ERROR -> Error.copy(alpha = 0.25f)
                        ScriptViewModel.LogLevel.SUCCESS -> Success.copy(alpha = 0.25f)
                    }
                )
                .padding(horizontal = 6.dp, vertical = 1.dp)
        ) {
            Text(
                text = log.level.name,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            text = log.message,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

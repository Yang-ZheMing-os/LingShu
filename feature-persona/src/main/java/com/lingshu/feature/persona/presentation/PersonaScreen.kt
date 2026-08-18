package com.lingshu.feature.persona.presentation

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingshu.feature.persona.domain.Persona
import com.lingshu.feature.persona.domain.TraitType
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaScreen(
    onBackClick: () -> Unit = {},
    viewModel: PersonaViewModel = hiltViewModel()
) {
    val currentPersona by viewModel.currentPersona.collectAsState()
    val history by viewModel.history.collectAsState()
    val isResetting by viewModel.isResetting.collectAsState()
    val generatedPrompt by viewModel.generatedPrompt.collectAsState()

    var showResetDialog by remember { mutableStateOf(false) }
    var showPromptDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("人格系统") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.generatePrompt()
                        showPromptDialog = true
                    }) {
                        Icon(Icons.Default.TextFields, contentDescription = "查看Prompt")
                    }
                    IconButton(onClick = { showResetDialog = true }) {
                        Icon(Icons.Default.Refresh, contentDescription = "重置")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            RadarChartCard(persona = currentPersona)

            Spacer(modifier = Modifier.height(16.dp))

            TraitDetailsCard(persona = currentPersona)

            Spacer(modifier = Modifier.height(16.dp))

            HistoryCard(historySize = history.size)

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重置人格") },
            text = { Text("确定要将人格重置为默认值吗？此操作将清除所有演化记录。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetPersona()
                    showResetDialog = false
                }) {
                    Text("确认重置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showPromptDialog) {
        AlertDialog(
            onDismissRequest = {
                showPromptDialog = false
                viewModel.clearGeneratedPrompt()
            },
            title = { Text("System Prompt") },
            text = {
                Text(
                    text = generatedPrompt,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPromptDialog = false
                    viewModel.clearGeneratedPrompt()
                }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
private fun RadarChartCard(persona: Persona) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "人格雷达图",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            RadarChart(
                persona = persona,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            )
        }
    }
}

@Composable
private fun RadarChart(
    persona: Persona,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val traits = TraitType.values()
    val traitCount = traits.size
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier = modifier) {
        val centerX = size.width / 2
        val centerY = size.height / 2
        val radius = minOf(size.width, size.height) / 2 - 60f
        val angleStep = (2 * Math.PI) / traitCount
        val startAngle = -Math.PI / 2

        drawRadarGrid(
            centerX = centerX,
            centerY = centerY,
            radius = radius,
            levels = 5,
            traitCount = traitCount,
            startAngle = startAngle,
            angleStep = angleStep,
            color = onSurfaceVariant.copy(alpha = 0.3f)
        )

        drawRadarData(
            centerX = centerX,
            centerY = centerY,
            radius = radius,
            persona = persona,
            traits = traits,
            startAngle = startAngle,
            angleStep = angleStep,
            color = primaryColor
        )

        drawTraitLabels(
            centerX = centerX,
            centerY = centerY,
            radius = radius,
            traits = traits,
            startAngle = startAngle,
            angleStep = angleStep,
            textMeasurer = textMeasurer,
            color = onSurfaceVariant
        )
    }
}

private fun DrawScope.drawRadarGrid(
    centerX: Float,
    centerY: Float,
    radius: Float,
    levels: Int,
    traitCount: Int,
    startAngle: Double,
    angleStep: Double,
    color: Color
) {
    for (level in 1..levels) {
        val levelRadius = radius * level / levels
        val path = Path()

        for (i in 0 until traitCount) {
            val angle = startAngle + i * angleStep
            val x = centerX + levelRadius * cos(angle).toFloat()
            val y = centerY + levelRadius * sin(angle).toFloat()

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        path.close()
        drawPath(path = path, color = color, style = Stroke(width = 1.dp.toPx()))
    }

    for (i in 0 until traitCount) {
        val angle = startAngle + i * angleStep
        val x = centerX + radius * cos(angle).toFloat()
        val y = centerY + radius * sin(angle).toFloat()

        drawLine(
            color = color,
            start = Offset(centerX, centerY),
            end = Offset(x, y),
            strokeWidth = 1.dp.toPx()
        )
    }
}

private fun DrawScope.drawRadarData(
    centerX: Float,
    centerY: Float,
    radius: Float,
    persona: Persona,
    traits: Array<TraitType>,
    startAngle: Double,
    angleStep: Double,
    color: Color
) {
    val path = Path()

    traits.forEachIndexed { index, trait ->
        val value = persona.getTrait(trait)
        val angle = startAngle + index * angleStep
        val pointRadius = radius * value
        val x = centerX + pointRadius * cos(angle).toFloat()
        val y = centerY + pointRadius * sin(angle).toFloat()

        if (index == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, y)
        }
    }
    path.close()

    drawPath(path = path, color = color.copy(alpha = 0.3f))
    drawPath(path = path, color = color, style = Stroke(width = 2.dp.toPx()))

    traits.forEachIndexed { index, trait ->
        val value = persona.getTrait(trait)
        val angle = startAngle + index * angleStep
        val pointRadius = radius * value
        val x = centerX + pointRadius * cos(angle).toFloat()
        val y = centerY + pointRadius * sin(angle).toFloat()

        drawCircle(
            color = color,
            radius = 4.dp.toPx(),
            center = Offset(x, y)
        )
    }
}

private fun DrawScope.drawTraitLabels(
    centerX: Float,
    centerY: Float,
    radius: Float,
    traits: Array<TraitType>,
    startAngle: Double,
    angleStep: Double,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    color: Color
) {
    val labelRadius = radius + 40f

    traits.forEachIndexed { index, trait ->
        val angle = startAngle + index * angleStep
        val x = centerX + labelRadius * cos(angle).toFloat()
        val y = centerY + labelRadius * sin(angle).toFloat()

        val textLayoutResult = textMeasurer.measure(
            text = trait.displayName,
            style = TextStyle(fontSize = 12.sp, color = color)
        )

        val textX = x - textLayoutResult.size.width / 2
        val textY = y - textLayoutResult.size.height / 2

        drawText(
            textLayoutResult = textLayoutResult,
            topLeft = Offset(textX, textY)
        )
    }
}

@Composable
private fun TraitDetailsCard(persona: Persona) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "人格特质详情",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            TraitType.values().forEach { trait ->
                TraitBar(
                    traitType = trait,
                    value = persona.getTrait(trait)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TraitBar(
    traitType: TraitType,
    value: Float
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = traitType.displayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(80.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        LinearProgressIndicator(
            progress = value,
            modifier = Modifier.weight(1f).height(8.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = String.format("%.1f%%", value * 100),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(50.dp)
        )
    }
}

@Composable
private fun HistoryCard(historySize: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "演化历史",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text(
                    text = "$historySize 条记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (historySize == 0) {
                    "暂无演化记录，开始对话后人格会根据对话内容逐渐演化。"
                } else {
                    "人格已记录 $historySize 次演化。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

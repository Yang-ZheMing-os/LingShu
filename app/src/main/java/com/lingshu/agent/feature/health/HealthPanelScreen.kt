package com.lingshu.agent.feature.health

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingshu.agent.ui.components.GlassCard
import com.lingshu.agent.ui.components.GlassIconButton
import com.lingshu.agent.ui.components.GlassSectionTitle
import com.lingshu.agent.ui.theme.AccentGlow
import com.lingshu.agent.ui.theme.GlassBubble
import com.lingshu.agent.ui.theme.GlassBubbleBorder
import com.lingshu.agent.ui.theme.GlassBubbleStrong
import com.lingshu.agent.ui.theme.HealthHeartRate
import com.lingshu.agent.ui.theme.HealthSleep
import com.lingshu.agent.ui.theme.HealthSpO2
import com.lingshu.agent.ui.theme.HealthSteps
import com.lingshu.agent.ui.theme.HealthStress
import com.lingshu.agent.ui.theme.IceBlueGradientEnd
import com.lingshu.agent.ui.theme.IceBlueGradientMid
import com.lingshu.agent.ui.theme.IceBlueGradientStart
import com.lingshu.agent.ui.theme.PrimaryBackground
import com.lingshu.agent.ui.theme.Success
import com.lingshu.agent.ui.theme.TextPrimary
import com.lingshu.agent.ui.theme.TextSecondary
import com.lingshu.agent.ui.theme.TextTertiary
import com.lingshu.agent.ui.theme.Warning
import com.lingshu.agent.ui.theme.Error

@Composable
fun HealthPanelScreen(
    viewModel: HealthViewModel = hiltViewModel(),
    isPanelOpen: Boolean = false,
    onPanelClose: () -> Unit = {}
) {
    val latestData by viewModel.latestRealTime.collectAsState()
    val stepsTrend by viewModel.stepsTrend.collectAsState()
    val ruleAdvice by viewModel.ruleAdvice.collectAsState()
    val aiAdvice by viewModel.aiAdvice.collectAsState()
    val isAiLoading by viewModel.isAiAdviceLoading.collectAsState()

    val panelOffset by animateFloatAsState(
        targetValue = if (isPanelOpen) 0f else -1f,
        label = "panelOffset",
        animationSpec = androidx.compose.animation.core.tween(300)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBackground)
    ) {
        if (isPanelOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { onPanelClose() }
            )
        }

        AnimatedVisibility(
            visible = isPanelOpen,
            enter = slideInHorizontally(initialOffsetX = { -it }),
            exit = slideOutHorizontally(targetOffsetX = { -it })
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            if (dragAmount < -50f) onPanelClose()
                        }
                    }
            ) {
                HealthPanelContent(
                    latestData = latestData,
                    stepsTrend = stepsTrend,
                    ruleAdvice = ruleAdvice,
                    aiAdvice = aiAdvice,
                    isAiLoading = isAiLoading,
                    onRefreshAi = { viewModel.generateAiAdvice() }
                )
            }
        }
    }
}

@Composable
private fun HealthPanelContent(
    latestData: com.lingshu.agent.core.model.HealthData,
    stepsTrend: List<Pair<Long, Long>>,
    ruleAdvice: List<String>,
    aiAdvice: String,
    isAiLoading: Boolean,
    onRefreshAi: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        PrimaryBackground,
                        PrimaryBackground.copy(alpha = 0.95f)
                    )
                )
            )
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "健康面板",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                GlassIconButton(
                    onClick = onRefreshAi,
                    icon = Icons.Default.Refresh,
                    iconModifier = Modifier.size(20.dp)
                )
            }
        }

        item {
            GlassSectionTitle(title = "实时数据")
            Spacer(modifier = Modifier.height(12.dp))
            VitalStatsGrid(latestData = latestData)
        }

        item {
            GlassSectionTitle(title = "7天步数趋势")
            Spacer(modifier = Modifier.height(12.dp))
            StepsTrendChart(stepsTrend = stepsTrend)
        }

        item {
            GlassSectionTitle(title = "健康建议")
            Spacer(modifier = Modifier.height(12.dp))
            HealthAdviceList(
                ruleAdvice = ruleAdvice,
                aiAdvice = aiAdvice,
                isAiLoading = isAiLoading
            )
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun VitalStatsGrid(latestData: com.lingshu.agent.core.model.HealthData) {
    val stats = listOf(
        VitalStat(
            icon = Icons.Default.Favorite,
            label = "心率",
            value = latestData.heartRate?.toString() ?: "--",
            unit = "BPM",
            trend = VitalTrend.UP,
            trendValue = "+2.3%",
            color = HealthHeartRate
        ),
        VitalStat(
            icon = Icons.Default.WaterDrop,
            label = "血氧",
            value = latestData.spo2?.let { "${it.toInt()}" } ?: "--",
            unit = "%",
            trend = VitalTrend.STABLE,
            trendValue = "稳定",
            color = HealthSpO2
        ),
        VitalStat(
            icon = Icons.Default.DirectionsRun,
            label = "步数",
            value = latestData.steps?.toString() ?: "--",
            unit = "步",
            trend = VitalTrend.UP,
            trendValue = "+15%",
            color = HealthSteps
        ),
        VitalStat(
            icon = Icons.Default.Bedtime,
            label = "睡眠",
            value = latestData.sleepTotalMinutes?.let { "${it / 60}h${it % 60}m" } ?: "--",
            unit = latestData.sleepEfficiency?.let { "${(it * 100).toInt()}%" } ?: "",
            trend = VitalTrend.STABLE,
            trendValue = "深睡${latestData.sleepDeepMinutes?.let { "${it}m" } ?: "--"}",
            color = HealthSleep
        ),
        VitalStat(
            icon = Icons.Default.Psychology,
            label = "压力",
            value = latestData.stressLevel?.toString() ?: "--",
            unit = "/100",
            trend = VitalTrend.DOWN,
            trendValue = "-8%",
            color = HealthStress
        ),
        VitalStat(
            icon = Icons.Default.SelfImprovement,
            label = "卡路里",
            value = latestData.calories?.toString() ?: "--",
            unit = "kcal",
            trend = VitalTrend.UP,
            trendValue = "活跃${latestData.activeMinutes ?: 0}min",
            color = HealthHeartRate
        )
    )

    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val rows = stats.chunked(2)
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { stat ->
                    Box(modifier = Modifier.weight(1f)) {
                        VitalStatCard(stat = stat)
                    }
                }
                // 补占位：奇数个时最后一个 slot 空出
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun VitalStatCard(stat: VitalStat) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.1f),
        shape = RoundedCornerShape(16.dp),
        glowColor = stat.color,
        glowAlpha = 0.15f,
        padding = PaddingValues(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(stat.color.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = stat.icon,
                        contentDescription = stat.label,
                        tint = stat.color,
                        modifier = Modifier.size(18.dp)
                    )
                }
                TrendIndicator(direction = stat.trend, color = stat.color)
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stat.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stat.value,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextPrimary,
                        fontSize = 28.sp
                    )
                    Text(
                        text = stat.unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Text(
                    text = stat.trendValue,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (stat.trend) {
                        VitalTrend.UP -> stat.color
                        VitalTrend.DOWN -> stat.color
                        VitalTrend.STABLE -> TextTertiary
                    }
                )
            }
        }
    }
}

@Composable
private fun TrendIndicator(
    direction: VitalTrend,
    color: Color
) {
    val icon = when (direction) {
        VitalTrend.UP -> Icons.Default.TrendingUp
        VitalTrend.DOWN -> Icons.Default.TrendingDown
        VitalTrend.STABLE -> Icons.Default.SelfImprovement
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (direction == VitalTrend.STABLE) TextTertiary else color,
        modifier = Modifier.size(16.dp)
    )
}

@Composable
private fun StepsTrendChart(stepsTrend: List<Pair<Long, Long>>) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        padding = PaddingValues(16.dp)
    ) {
        val data = remember(stepsTrend) {
            if (stepsTrend.isEmpty()) {
                List(7) { index ->
                    (index + 1).toLong() to (3000L + (Math.random() * 7000).toLong())
                }
            } else {
                stepsTrend.take(7)
            }
        }

        val labels = listOf("一", "二", "三", "四", "五", "六", "日")
        val maxValue = data.maxOfOrNull { it.second }?.toFloat() ?: 10000f
        val minValue = 0f

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "步数",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Text(
                    text = "目标: 10,000",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp, bottom = 4.dp)
            ) {
                Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val width = size.width
                    val height = size.height
                    val stepCount = data.size
                    val horizontalStep = width / (stepCount - 1)

                    val points = data.mapIndexed { index, pair ->
                        val x = index * horizontalStep
                        val normalizedValue = (pair.second - minValue) / (maxValue - minValue)
                        val y = height - (normalizedValue * height * 0.85f) - height * 0.05f
                        Offset(x, y)
                    }

                    for (i in 0 until 5) {
                        val y = height * (1f - i * 0.25f)
                        drawLine(
                            color = GlassBubbleBorder,
                            start = Offset(0f, y),
                            end = Offset(width, y),
                            strokeWidth = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                        )
                    }

                    val fillPath = Path().apply {
                        if (points.isNotEmpty()) {
                            moveTo(points.first().x, height)
                            points.forEach { lineTo(it.x, it.y) }
                            lineTo(points.last().x, height)
                            close()
                        }
                    }
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                HealthSteps.copy(alpha = 0.3f),
                                HealthSteps.copy(alpha = 0.02f)
                            )
                        )
                    )

                    val linePath = Path().apply {
                        if (points.isNotEmpty()) {
                            moveTo(points.first().x, points.first().y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                    }
                    drawPath(
                        path = linePath,
                        brush = Brush.linearGradient(
                            colors = listOf(IceBlueGradientStart, IceBlueGradientMid, IceBlueGradientEnd)
                        ),
                        style = Stroke(width = 3.dp.toPx())
                    )

                    points.forEach { point ->
                        drawCircle(
                            color = Color.White,
                            radius = 6f,
                            center = point
                        )
                        drawCircle(
                            color = AccentGlow,
                            radius = 4f,
                            center = point
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                labels.take(data.size).forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthAdviceList(
    ruleAdvice: List<String>,
    aiAdvice: String,
    isAiLoading: Boolean
) {
    val adviceItems = remember(ruleAdvice, aiAdvice) {
        val items = mutableListOf<AdviceItem>()

        if (ruleAdvice.isNotEmpty()) {
            items.add(
                AdviceItem(
                    icon = Icons.Default.Lightbulb,
                    title = "今日提醒",
                    description = ruleAdvice.firstOrNull() ?: "保持良好作息",
                    type = AdviceType.TIP,
                    color = Warning
                )
            )
        }

        items.add(
            AdviceItem(
                icon = Icons.Default.Bedtime,
                title = "睡眠建议",
                description = "建议今晚11点前入睡，保证7-8小时优质睡眠",
                type = AdviceType.SLEEP,
                color = HealthSleep
            )
        )

        items.add(
            AdviceItem(
                icon = Icons.Default.DirectionsRun,
                title = "运动目标",
                description = "距离今日目标还差 ${(10000 - 5234).coerceAtLeast(0)} 步，起身活动一下吧",
                type = AdviceType.EXERCISE,
                color = HealthSteps
            )
        )

        if (aiAdvice.isNotBlank()) {
            items.add(
                AdviceItem(
                    icon = Icons.Default.AutoAwesome,
                    title = "灵枢AI建议",
                    description = aiAdvice.take(100) + if (aiAdvice.length > 100) "..." else "",
                    type = AdviceType.AI,
                    color = AccentGlow
                )
            )
        }

        items
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (isAiLoading) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                padding = PaddingValues(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = AccentGlow,
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "AI正在分析您的健康数据...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }

        adviceItems.forEach { item ->
            AdviceCard(item = item)
        }
    }
}

@Composable
private fun AdviceCard(item: AdviceItem) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        glowColor = item.color,
        glowAlpha = 0.1f,
        padding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(item.color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.title,
                    tint = item.color,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

private enum class VitalTrend {
    UP, DOWN, STABLE
}

private data class VitalStat(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val unit: String,
    val trend: VitalTrend,
    val trendValue: String,
    val color: Color
)

private enum class AdviceType {
    TIP, SLEEP, EXERCISE, AI, HYDRATION
}

private data class AdviceItem(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val type: AdviceType,
    val color: Color
)

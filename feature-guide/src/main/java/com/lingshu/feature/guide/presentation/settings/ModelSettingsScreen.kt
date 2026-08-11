package com.lingshu.feature.guide.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.common.state.UiState
import com.lingshu.core.data.llm.ModelProviderType
import com.lingshu.core.ui.component.GlassCard
import com.lingshu.core.ui.theme.Error
import com.lingshu.core.ui.theme.Success
import com.lingshu.core.ui.theme.Warning

private const val TAG = "ModelSettings"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsScreen(
    onBackClick: () -> Unit,
    viewModel: ModelSettingsViewModel = hiltViewModel()
) {
    LingShuLog.i(TAG, "ModelSettingsScreen: composing")

    val currentProvider by viewModel.currentProvider.collectAsState()
    val config by viewModel.configForCurrent.collectAsState()
    val memoryInjected by viewModel.memoryInjected.collectAsState()
    val personaEvolve by viewModel.personaEvolve.collectAsState()
    val ragEnabled by viewModel.ragEnabled.collectAsState()
    val ragThreshold by viewModel.ragThreshold.collectAsState()
    val historyCount by viewModel.historyCount.collectAsState()
    val testResult by viewModel.testResult.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "模型管理",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        LingShuLog.d(TAG, "topBar: back clicked")
                        onBackClick()
                    }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(16.dp)) }

            item {
                Text(
                    text = "Provider 选择",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                ProviderSelectorCard(
                    currentProvider = currentProvider,
                    onSelect = { provider ->
                        LingShuLog.d(TAG, "ProviderSelectorCard: selected ${provider.name}")
                        viewModel.selectProvider(provider)
                    }
                )
            }

            item {
                Text(
                    text = "${currentProvider.name} 配置",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                ProviderConfigCard(
                    provider = currentProvider,
                    baseUrl = config.baseUrl,
                    apiKey = config.apiKey,
                    modelName = config.modelName,
                    temperature = config.temperature,
                    topP = config.topP,
                    maxTokens = config.maxTokens,
                    timeoutSeconds = config.timeoutSeconds,
                    testResult = testResult,
                    onBaseUrlChange = viewModel::updateBaseUrl,
                    onApiKeyChange = viewModel::updateApiKey,
                    onModelNameChange = viewModel::updateModelName,
                    onTemperatureChange = viewModel::updateTemperature,
                    onTopPChange = viewModel::updateTopP,
                    onMaxTokensChange = viewModel::updateMaxTokens,
                    onTimeoutChange = viewModel::updateTimeoutSeconds,
                    onTestConnection = {
                        LingShuLog.d(TAG, "ProviderConfigCard: test connection clicked")
                        viewModel.testConnection()
                    }
                )
            }

            item {
                Text(
                    text = "Prompt 注入配置",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                PromptInjectionCard(
                    memoryInjected = memoryInjected,
                    personaEvolve = personaEvolve,
                    ragEnabled = ragEnabled,
                    ragThreshold = ragThreshold,
                    historyCount = historyCount,
                    onMemoryInjectedChange = viewModel::toggleMemoryInjected,
                    onPersonaEvolveChange = viewModel::togglePersonaEvolve,
                    onRagEnabledChange = viewModel::toggleRagEnabled,
                    onRagThresholdChange = viewModel::updateRagThreshold,
                    onHistoryCountChange = viewModel::updateHistoryCount
                )
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun ProviderSelectorCard(
    currentProvider: ModelProviderType,
    onSelect: (ModelProviderType) -> Unit
) {
    val providers = ModelProviderType.values()
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                providers.forEach { provider ->
                    val selected = provider == currentProvider
                    FilterChip(
                        selected = selected,
                        onClick = { onSelect(provider) },
                        label = {
                            Text(
                                text = provider.name,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderConfigCard(
    provider: ModelProviderType,
    baseUrl: String,
    apiKey: String,
    modelName: String,
    temperature: Float,
    topP: Float,
    maxTokens: Int,
    timeoutSeconds: Int,
    testResult: UiState<String>,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelNameChange: (String) -> Unit,
    onTemperatureChange: (Float) -> Unit,
    onTopPChange: (Float) -> Unit,
    onMaxTokensChange: (Int) -> Unit,
    onTimeoutChange: (Int) -> Unit,
    onTestConnection: () -> Unit
) {
    val modelSuggestions = remember(provider) {
        when (provider) {
            ModelProviderType.DEEPSEEK -> listOf("deepseek-chat", "deepseek-coder")
            ModelProviderType.GEMINI -> listOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-2.0-flash")
            ModelProviderType.OLLAMA -> listOf("qwen2.5:7b", "llama3.1:8b", "deepseek-coder-v2", "qwen3:8b")
            ModelProviderType.OPENAI -> listOf("gpt-4o-mini", "gpt-4o", "gpt-3.5-turbo")
            ModelProviderType.QWEN -> listOf("qwen-plus", "qwen-max", "qwen-turbo")
        }
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ConfigTextField(
                label = "Base URL",
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                placeholder = "https://api.example.com/v1"
            )

            ConfigTextField(
                label = "API Key",
                value = apiKey,
                onValueChange = onApiKeyChange,
                placeholder = "输入 API Key",
                isPassword = true
            )

            Column {
                Text(
                    text = "Model Name",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = modelName,
                    onValueChange = onModelNameChange,
                    placeholder = {
                        Text(
                            text = "选择或输入模型名称",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = outlinedTextFieldColors(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    modelSuggestions.forEach { suggestion ->
                        FilterChip(
                            selected = modelName == suggestion,
                            onClick = { onModelNameChange(suggestion) },
                            label = {
                                Text(
                                    text = suggestion,
                                    fontSize = 11.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }

            SliderWithLabel(
                label = "Temperature",
                value = temperature,
                valueRange = 0f..2f,
                steps = 19,
                displayValue = String.format("%.2f", temperature),
                onValueChange = onTemperatureChange
            )

            SliderWithLabel(
                label = "Top P",
                value = topP,
                valueRange = 0f..1f,
                steps = 9,
                displayValue = String.format("%.2f", topP),
                onValueChange = onTopPChange
            )

            IntSliderWithLabel(
                label = "Max Tokens",
                value = maxTokens,
                valueRange = 256f..8192f,
                steps = 30,
                displayValue = "$maxTokens",
                stepSize = 256,
                onValueChange = onMaxTokensChange
            )

            IntSliderWithLabel(
                label = "Timeout (秒)",
                value = timeoutSeconds,
                valueRange = 5f..120f,
                steps = 114,
                displayValue = "${timeoutSeconds}s",
                stepSize = 1,
                onValueChange = onTimeoutChange
            )

            Column {
                Button(
                    onClick = onTestConnection,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    enabled = !testResult.isLoading
                ) {
                    Text(
                        text = if (testResult.isLoading) "测试中..." else "立即测试连接",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                when {
                    testResult.isLoading -> {
                        Text(
                            text = "正在连接...",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    testResult is UiState.Success -> {
                        val msg = (testResult as UiState.Success<String>).data
                        val color = when {
                            msg.startsWith("✓") -> Success
                            msg.startsWith("△") -> Warning
                            else -> MaterialTheme.colorScheme.onBackground
                        }
                        Text(
                            text = msg,
                            fontSize = 13.sp,
                            color = color,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    testResult is UiState.Error -> {
                        val msg = (testResult as UiState.Error).message
                        Text(
                            text = msg,
                            fontSize = 13.sp,
                            color = Error,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptInjectionCard(
    memoryInjected: Boolean,
    personaEvolve: Boolean,
    ragEnabled: Boolean,
    ragThreshold: Float,
    historyCount: Int,
    onMemoryInjectedChange: (Boolean) -> Unit,
    onPersonaEvolveChange: (Boolean) -> Unit,
    onRagEnabledChange: (Boolean) -> Unit,
    onRagThresholdChange: (Float) -> Unit,
    onHistoryCountChange: (Int) -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SwitchRow(
                title = "启用记忆注入",
                description = "将历史记忆注入系统 Prompt",
                checked = memoryInjected,
                onCheckedChange = onMemoryInjectedChange
            )

            SwitchRow(
                title = "启用人格演化",
                description = "随对话动态调整人格参数",
                checked = personaEvolve,
                onCheckedChange = onPersonaEvolveChange
            )

            SwitchRow(
                title = "启用 RAG 检索",
                description = "对话中检索知识库内容",
                checked = ragEnabled,
                onCheckedChange = onRagEnabledChange
            )

            if (ragEnabled) {
                SliderWithLabel(
                    label = "RAG 相似度阈值",
                    value = ragThreshold,
                    valueRange = 0f..1f,
                    steps = 19,
                    displayValue = String.format("%.2f", ragThreshold),
                    onValueChange = onRagThresholdChange
                )
            }

            IntSliderWithLabel(
                label = "历史上下文轮数",
                value = historyCount,
                valueRange = 5f..50f,
                steps = 44,
                displayValue = "$historyCount 轮",
                stepSize = 1,
                onValueChange = onHistoryCountChange
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false
) {
    Column {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    text = placeholder,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = outlinedTextFieldColors(),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            singleLine = true
        )
    }
}

@Composable
private fun SliderWithLabel(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    displayValue: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = displayValue,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
private fun IntSliderWithLabel(
    label: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    displayValue: String,
    stepSize: Int,
    onValueChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = displayValue,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                val snapped = (raw / stepSize).toInt() * stepSize
                val coerced = snapped.coerceIn(valueRange.start.toInt(), valueRange.endInclusive.toInt())
                onValueChange(coerced)
            },
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun outlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedTextColor = MaterialTheme.colorScheme.onBackground,
    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
    cursorColor = MaterialTheme.colorScheme.primary
)

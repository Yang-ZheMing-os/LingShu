package com.lingshu.feature.guide.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BackHand
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.data.datastore.AppPreferences
import com.lingshu.core.ui.component.GlassCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val SETTINGS_TAG = "SettingsScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToModelSettings: () -> Unit,
    onNavigateToRag: () -> Unit = {},
    onNavigateToCloneVoice: () -> Unit = {},
    onNavigateToMod: () -> Unit = {},
    onNavigateToWakeWord: () -> Unit = {},
    onNavigateToCommunity: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    LingShuLog.i(SETTINGS_TAG, "SettingsScreen: composing")
    val apiKey by viewModel.apiKey.collectAsState()
    val ttsEnabled by viewModel.ttsEnabled.collectAsState()
    val llmProvider by viewModel.llmProvider.collectAsState()
    val ollamaUrl by viewModel.ollamaUrl.collectAsState()
    val ollamaModel by viewModel.ollamaModel.collectAsState()
    val geminiApiKey by viewModel.geminiApiKey.collectAsState()
    val ollamaModels by viewModel.ollamaModels.collectAsState()
    val modelsLoading by viewModel.modelsLoading.collectAsState()
    val modelsError by viewModel.modelsError.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "设置",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
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
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                SettingsSection(title = "API 设置") {
                    SettingsItem(
                        icon = Icons.Default.Key,
                        title = "API Key",
                        description = "配置 DeepSeek API Key"
                    ) {
                        var text by remember(apiKey) { mutableStateOf(apiKey) }

                        OutlinedTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                viewModel.setApiKey(it)
                            },
                            placeholder = {
                                Text(
                                    text = "输入你的 API Key",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                                unfocusedTextColor = MaterialTheme.colorScheme.onBackground
                            ),
                            singleLine = true
                        )
                    }
                }
            }

            item {
                SettingsSection(title = "模型设置") {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                LingShuLog.d(SETTINGS_TAG, "model settings card clicked")
                                onNavigateToModelSettings()
                            },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 16.dp)
                            )
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "模型管理 >",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "选择 Provider / 温度参数 / Prompt 注入开关",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // LLM Provider 选择
                    Text(
                        text = "AI 引擎",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    val providers = listOf(
                        "DEEPSEEK" to "DeepSeek（云端，需 API Key）",
                        "OLLAMA" to "Ollama（本地，需 PC 运行 Ollama）",
                        "GEMINI" to "Gemini（Google，需 API Key）"
                    )
                    providers.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setLlmProvider(value) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = llmProvider == value,
                                onClick = { viewModel.setLlmProvider(value) }
                            )
                            Text(
                                text = label,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    // Ollama 配置
                    if (llmProvider == "OLLAMA") {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Ollama 服务地址",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        var ollamaUrlText by remember(ollamaUrl) { mutableStateOf(ollamaUrl) }
                        OutlinedTextField(
                            value = ollamaUrlText,
                            onValueChange = {
                                ollamaUrlText = it
                                viewModel.setOllamaUrl(it)
                            },
                            placeholder = { Text("http://10.0.2.2:11434") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // 模型选择行：标题 + 刷新按钮
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "选择模型",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            androidx.compose.material3.TextButton(
                                onClick = { viewModel.refreshOllamaModels() },
                                enabled = !modelsLoading
                            ) {
                                androidx.compose.material3.Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Default.Refresh,
                                    contentDescription = "刷新",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (modelsLoading) "加载中..." else "刷新列表",
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // 错误提示
                        if (modelsError != null) {
                            Text(
                                text = "获取失败：$modelsError",
                                fontSize = 11.sp,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                            )
                        }

                        // 可选模型列表（从 Ollama 服务拉取）
                        if (ollamaModels.isNotEmpty()) {
                            ollamaModels.forEach { modelName ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.setOllamaModel(modelName) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    androidx.compose.material3.RadioButton(
                                        selected = ollamaModel == modelName,
                                        onClick = { viewModel.setOllamaModel(modelName) }
                                    )
                                    Text(
                                        text = modelName,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                            androidx.compose.material3.HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        // 手动输入模型名（仍保留，用于自定义）
                        Text(
                            text = "或手动输入模型名",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        var ollamaModelText by remember(ollamaModel) { mutableStateOf(ollamaModel) }
                        OutlinedTextField(
                            value = ollamaModelText,
                            onValueChange = {
                                ollamaModelText = it
                                viewModel.setOllamaModel(it)
                            },
                            placeholder = { Text("如 qwen2.5:7b") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )
                        Text(
                            text = "提示：模拟器用 10.0.2.2 访问宿主机 Ollama；真机用局域网 IP。点击「刷新列表」可获取 PC 上已安装的模型",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // Gemini 配置
                    if (llmProvider == "GEMINI") {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Gemini API Key",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        var geminiKeyText by remember(geminiApiKey) { mutableStateOf(geminiApiKey) }
                        OutlinedTextField(
                            value = geminiKeyText,
                            onValueChange = {
                                geminiKeyText = it
                                viewModel.setGeminiApiKey(it)
                            },
                            placeholder = { Text("输入 Gemini API Key") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )
                    }
                }
            }

            item {
                SettingsSection(title = "语音设置") {
                    SettingsItem(
                        icon = Icons.Default.VolumeUp,
                        title = "TTS 语音播报",
                        description = "开启后 AI 回复将自动朗读"
                    ) {
                        Switch(
                            checked = ttsEnabled,
                            onCheckedChange = { viewModel.toggleTts(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }
            }

            item {
                SettingsSection(title = "功能模块") {
                    NavCard(
                        icon = Icons.Default.Book,
                        title = "RAG 知识库",
                        description = "导入文档，让 AI 基于你的资料回答",
                        onClick = {
                            LingShuLog.d(SETTINGS_TAG, "navigate to RAG")
                            onNavigateToRag()
                        }
                    )
                    NavCard(
                        icon = Icons.Default.RecordVoiceOver,
                        title = "声音克隆",
                        description = "录制样本，克隆你的专属声音",
                        onClick = {
                            LingShuLog.d(SETTINGS_TAG, "navigate to CloneVoice")
                            onNavigateToCloneVoice()
                        }
                    )
                    NavCard(
                        icon = Icons.Default.AutoAwesome,
                        title = "Mod 管理",
                        description = "安装人格与技能扩展包",
                        onClick = {
                            LingShuLog.d(SETTINGS_TAG, "navigate to Mod")
                            onNavigateToMod()
                        }
                    )
                    NavCard(
                        icon = Icons.Default.BackHand,
                        title = "唤醒词",
                        description = "后台监听唤醒词，免手触启动",
                        onClick = {
                            LingShuLog.d(SETTINGS_TAG, "navigate to WakeWord")
                            onNavigateToWakeWord()
                        }
                    )
                    NavCard(
                        icon = Icons.Default.Campaign,
                        title = "社区",
                        description = "浏览与分享 Mod / 声音 / 知识库",
                        onClick = {
                            LingShuLog.d(SETTINGS_TAG, "navigate to Community")
                            onNavigateToCommunity()
                        }
                    )
                }
            }

            item {
                SettingsSection(title = "关于") {
                    SettingsItem(
                        icon = Icons.Default.Settings,
                        title = "版本",
                        description = "1.0.0",
                        trailing = {}
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        content()
    }
}

@Composable
private fun NavCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 16.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    description: String,
    trailing: @Composable () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 16.dp)
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        }
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val ollamaProvider: com.lingshu.core.data.llm.OllamaProvider
) : ViewModel() {

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _ttsEnabled = MutableStateFlow(true)
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

    private val _llmProvider = MutableStateFlow("OLLAMA")
    val llmProvider: StateFlow<String> = _llmProvider.asStateFlow()

    private val _ollamaUrl = MutableStateFlow("http://10.0.2.2:11434")
    val ollamaUrl: StateFlow<String> = _ollamaUrl.asStateFlow()

    private val _ollamaModel = MutableStateFlow("qwen2.5:0.5b")
    val ollamaModel: StateFlow<String> = _ollamaModel.asStateFlow()

    private val _geminiApiKey = MutableStateFlow("")
    val geminiApiKey: StateFlow<String> = _geminiApiKey.asStateFlow()

    // Ollama 可用模型列表（从服务动态拉取）
    private val _ollamaModels = MutableStateFlow<List<String>>(emptyList())
    val ollamaModels: StateFlow<List<String>> = _ollamaModels.asStateFlow()

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError: StateFlow<String?> = _modelsError.asStateFlow()

    init {
        viewModelScope.launch {
            appPreferences.apiKey.collect { key ->
                _apiKey.value = key
            }
        }
        viewModelScope.launch {
            appPreferences.llmProvider.collect { _llmProvider.value = it }
        }
        viewModelScope.launch {
            appPreferences.ollamaUrl.collect { _ollamaUrl.value = it }
        }
        viewModelScope.launch {
            appPreferences.ollamaModel.collect { _ollamaModel.value = it }
        }
        viewModelScope.launch {
            appPreferences.geminiApiKey.collect { _geminiApiKey.value = it }
        }
    }

    fun setApiKey(key: String) {
        _apiKey.value = key
        viewModelScope.launch {
            appPreferences.setApiKey(key)
        }
    }

    fun setLlmProvider(provider: String) {
        _llmProvider.value = provider
        viewModelScope.launch {
            appPreferences.setLlmProvider(provider)
        }
    }

    fun setOllamaUrl(url: String) {
        _ollamaUrl.value = url
        viewModelScope.launch {
            appPreferences.setOllamaUrl(url)
        }
    }

    fun setOllamaModel(model: String) {
        _ollamaModel.value = model
        viewModelScope.launch {
            appPreferences.setOllamaModel(model)
        }
    }

    fun setGeminiApiKey(key: String) {
        _geminiApiKey.value = key
        viewModelScope.launch {
            appPreferences.setGeminiApiKey(key)
        }
    }

    fun toggleTts(enabled: Boolean) {
        _ttsEnabled.value = enabled
    }

    /**
     * 从 Ollama 服务拉取已安装的模型列表，用于在 UI 中选择。
     */
    fun refreshOllamaModels() {
        viewModelScope.launch {
            _modelsLoading.value = true
            _modelsError.value = null
            val result = ollamaProvider.listInstalledModels(_ollamaUrl.value)
            when (result) {
                is com.lingshu.core.common.error.Result.Success -> {
                    _ollamaModels.value = result.data
                    LingShuLog.i("SettingsVM", "刷新模型列表成功: ${result.data}")
                }
                is com.lingshu.core.common.error.Result.Error -> {
                    _modelsError.value = result.message
                    LingShuLog.w("SettingsVM", "刷新模型列表失败: ${result.message}")
                }
            }
            _modelsLoading.value = false
        }
    }
}

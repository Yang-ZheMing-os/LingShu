package com.lingshu.feature.guide.presentation

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.BackHand
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.data.datastore.AppPreferences
import com.lingshu.core.data.llm.LlmConfig
import com.lingshu.core.data.llm.LlmConfigStore
import com.lingshu.core.data.llm.ModelProviderType
import com.lingshu.core.ui.component.GlassCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
    onNavigateToHealth: () -> Unit = {},
    onNavigateToMemory: () -> Unit = {},
    onNavigateToPersona: () -> Unit = {},
    onNavigateToProactive: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    LingShuLog.i(SETTINGS_TAG, "SettingsScreen: composing")
    val apiKey by viewModel.apiKey.collectAsState()
    val ttsEnabled by viewModel.ttsEnabled.collectAsState()
    val accessibilityEnabled by viewModel.accessibilityEnabled.collectAsState()
    val llmProvider by viewModel.llmProvider.collectAsState()
    val currentConfig by viewModel.currentConfig.collectAsState()
    val ollamaModels by viewModel.ollamaModels.collectAsState()
    val modelsLoading by viewModel.modelsLoading.collectAsState()
    val modelsError by viewModel.modelsError.collectAsState()

    val context = LocalContext.current

    // 从系统无障碍设置页返回时（ON_RESUME）刷新服务开启状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAccessibilityStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

                    // 5 个 Provider 选项
                    val providers = listOf(
                        "DEEPSEEK" to "DeepSeek（云端，需 API Key）",
                        "OPENAI" to "OpenAI 兼容（含通义千问/智谱/Kimi，改 baseUrl）",
                        "GEMINI" to "Gemini（Google，需 API Key）",
                        "QWEN" to "通义千问（阿里云，需 API Key）",
                        "OLLAMA" to "Ollama（本地，需 PC 运行 Ollama）"
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
                                selected = llmProvider.name == value,
                                onClick = { viewModel.setLlmProvider(value) }
                            )
                            Text(
                                text = label,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    // API Key 输入框：所有云端 Provider 都显示，Ollama 不需要故隐藏
                    if (llmProvider != ModelProviderType.OLLAMA) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "API Key",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        var apiKeyText by remember(currentConfig.apiKey) {
                            mutableStateOf(currentConfig.apiKey)
                        }
                        OutlinedTextField(
                            value = apiKeyText,
                            onValueChange = {
                                apiKeyText = it
                                viewModel.saveApiKey(it)
                            },
                            placeholder = {
                                Text("输入当前 Provider 的 API Key")
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

                    // Base URL 输入框：允许用户编辑（用于切换智谱/Kimi 等 OpenAI 兼容服务）
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (llmProvider == ModelProviderType.OLLAMA) "Ollama 服务地址" else "Base URL",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    var baseUrlText by remember(currentConfig.baseUrl) {
                        mutableStateOf(currentConfig.baseUrl)
                    }
                    OutlinedTextField(
                        value = baseUrlText,
                        onValueChange = {
                            baseUrlText = it
                            viewModel.saveBaseUrl(it)
                        },
                        placeholder = {
                            Text(
                                if (llmProvider == ModelProviderType.OLLAMA) "http://10.0.2.2:11434"
                                else "https://api.example.com/v1"
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

                    // 模型名输入框：允许用户指定模型名
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "模型名",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    var modelNameText by remember(currentConfig.modelName) {
                        mutableStateOf(currentConfig.modelName)
                    }
                    OutlinedTextField(
                        value = modelNameText,
                        onValueChange = {
                            modelNameText = it
                            viewModel.saveModelName(it)
                        },
                        placeholder = {
                            Text("如 deepseek-chat / qwen-plus / gemini-1.5-flash")
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

                    // Ollama 模型列表刷新（仅 Ollama 显示）
                    if (llmProvider == ModelProviderType.OLLAMA) {
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
                                    imageVector = Icons.Default.Refresh,
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
                                        .clickable { viewModel.saveModelName(modelName) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    androidx.compose.material3.RadioButton(
                                        selected = currentConfig.modelName == modelName,
                                        onClick = { viewModel.saveModelName(modelName) }
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

                        // 使用提示
                        Text(
                            text = "提示：模拟器用 10.0.2.2 访问宿主机 Ollama；真机用局域网 IP。点击「刷新列表」可获取 PC 上已安装的模型",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
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
                SettingsSection(title = "权限与服务") {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                LingShuLog.d(SETTINGS_TAG, "navigate to system accessibility settings")
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }.onFailure { e ->
                                    LingShuLog.e(SETTINGS_TAG, "跳转无障碍设置失败", e)
                                }
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
                                imageVector = Icons.Default.Accessibility,
                                contentDescription = null,
                                tint = if (accessibilityEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                modifier = Modifier.padding(end = 16.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "无障碍服务 >",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = if (accessibilityEnabled) {
                                        "已开启 · 支持自动点击、滑动、输入等操作"
                                    } else {
                                        "未开启 · 点击前往系统设置开启「灵枢」"
                                    },
                                    fontSize = 13.sp,
                                    color = if (accessibilityEnabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
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
                    NavCard(
                        icon = Icons.Default.Star,
                        title = "健康数据",
                        description = "查看睡眠、心率等健康指标",
                        onClick = {
                            LingShuLog.d(SETTINGS_TAG, "navigate to Health")
                            onNavigateToHealth()
                        }
                    )
                    NavCard(
                        icon = Icons.Default.Search,
                        title = "记忆管理",
                        description = "查看与管理 AI 记住的偏好与事实",
                        onClick = {
                            LingShuLog.d(SETTINGS_TAG, "navigate to Memory")
                            onNavigateToMemory()
                        }
                    )
                    NavCard(
                        icon = Icons.Default.AccountCircle,
                        title = "人格特质",
                        description = "查看与重置 AI 的人格画像",
                        onClick = {
                            LingShuLog.d(SETTINGS_TAG, "navigate to Persona")
                            onNavigateToPersona()
                        }
                    )
                    NavCard(
                        icon = Icons.Default.Info,
                        title = "主动关怀",
                        description = "配置定时提醒与关怀触发规则",
                        onClick = {
                            LingShuLog.d(SETTINGS_TAG, "navigate to Proactive")
                            onNavigateToProactive()
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val ollamaProvider: com.lingshu.core.data.llm.OllamaProvider,
    private val llmConfigStore: LlmConfigStore,
    private val accessibilityControl: com.lingshu.feature.accessibility.domain.IAccessibilityControl
) : ViewModel() {

    private val vmTag = "SettingsVM"

    // 顶部「API 设置」区块使用（旧 AppPreferences，保留兼容）
    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _ttsEnabled = MutableStateFlow(true)
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

    // 无障碍服务是否已开启（用于设置页状态展示，ON_RESUME 时刷新）
    private val _accessibilityEnabled = MutableStateFlow(false)
    val accessibilityEnabled: StateFlow<Boolean> = _accessibilityEnabled.asStateFlow()

    // 当前选中的 Provider，来自 LlmConfigStore
    private val _llmProvider = MutableStateFlow(ModelProviderType.DEEPSEEK)
    val llmProvider: StateFlow<ModelProviderType> = _llmProvider.asStateFlow()

    // 当前 Provider 的配置流，切换 Provider 时自动刷新
    private val _currentConfig = MutableStateFlow(LlmConfig())
    val currentConfig: StateFlow<LlmConfig> = _currentConfig.asStateFlow()

    // Ollama 可用模型列表（从服务动态拉取）
    private val _ollamaModels = MutableStateFlow<List<String>>(emptyList())
    val ollamaModels: StateFlow<List<String>> = _ollamaModels.asStateFlow()

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError: StateFlow<String?> = _modelsError.asStateFlow()

    init {
        LingShuLog.i(vmTag, "init: SettingsViewModel created")

        // 顶部「API 设置」区块的 API Key（AppPreferences，保留兼容）
        viewModelScope.launch {
            appPreferences.apiKey.collect { key ->
                _apiKey.value = key
            }
        }

        // 选中的 Provider 来自 LlmConfigStore
        llmConfigStore.selectedProvider
            .onEach { provider ->
                _llmProvider.value = provider
                LingShuLog.d(vmTag, "selectedProvider collected: ${provider.name}")
            }
            .launchIn(viewModelScope)

        // 当前 Provider 的配置流：Provider 切换时自动加载对应配置
        _llmProvider
            .flatMapLatest { provider ->
                LingShuLog.d(vmTag, "currentProvider changed, loading config for ${provider.name}")
                llmConfigStore.getConfigFlow(provider)
            }
            .onEach { config ->
                _currentConfig.value = config
                LingShuLog.d(
                    vmTag,
                    "currentConfig updated: provider=${config.provider.name}, " +
                            "model=${config.modelName}, url=${config.baseUrl}"
                )
            }
            .launchIn(viewModelScope)
    }

    /** 顶部「API 设置」区块使用，写入旧 AppPreferences（保留兼容） */
    fun setApiKey(key: String) {
        _apiKey.value = key
        viewModelScope.launch {
            appPreferences.setApiKey(key)
        }
    }

    /** 切换 LLM Provider，写入 LlmConfigStore */
    fun setLlmProvider(provider: String) {
        val type = runCatching { ModelProviderType.valueOf(provider) }.getOrNull() ?: run {
            LingShuLog.w(vmTag, "setLlmProvider: unknown provider=$provider, ignore")
            return
        }
        _llmProvider.value = type
        viewModelScope.launch {
            llmConfigStore.setSelectedProvider(type)
        }
    }

    /** 保存 API Key 到当前 Provider 的配置 */
    fun saveApiKey(key: String) {
        val updated = _currentConfig.value.copy(apiKey = key)
        _currentConfig.value = updated
        viewModelScope.launch {
            llmConfigStore.saveConfig(updated)
        }
    }

    /** 保存 Base URL 到当前 Provider 的配置 */
    fun saveBaseUrl(url: String) {
        val updated = _currentConfig.value.copy(baseUrl = url)
        _currentConfig.value = updated
        viewModelScope.launch {
            llmConfigStore.saveConfig(updated)
        }
    }

    /** 保存模型名到当前 Provider 的配置 */
    fun saveModelName(model: String) {
        val updated = _currentConfig.value.copy(modelName = model)
        _currentConfig.value = updated
        viewModelScope.launch {
            llmConfigStore.saveConfig(updated)
        }
    }

    fun toggleTts(enabled: Boolean) {
        _ttsEnabled.value = enabled
    }

    /** 查询无障碍服务当前状态（设置页 ON_RESUME / 点击入口时调用） */
    fun refreshAccessibilityStatus() {
        viewModelScope.launch {
            val running = accessibilityControl.isServiceRunning()
            LingShuLog.d(vmTag, "无障碍服务状态: $running")
            _accessibilityEnabled.value = running
        }
    }

    /**
     * 从 Ollama 服务拉取已安装的模型列表，用于在 UI 中选择。
     * 使用当前 Provider 配置中的 baseUrl 作为服务地址。
     */
    fun refreshOllamaModels() {
        viewModelScope.launch {
            _modelsLoading.value = true
            _modelsError.value = null
            val url = _currentConfig.value.baseUrl
            val result = ollamaProvider.listInstalledModels(url)
            when (result) {
                is com.lingshu.core.common.error.Result.Success -> {
                    _ollamaModels.value = result.data
                    LingShuLog.i(vmTag, "刷新模型列表成功: ${result.data}")
                }
                is com.lingshu.core.common.error.Result.Error -> {
                    _modelsError.value = result.message
                    LingShuLog.w(vmTag, "刷新模型列表失败: ${result.message}")
                }
            }
            _modelsLoading.value = false
        }
    }
}

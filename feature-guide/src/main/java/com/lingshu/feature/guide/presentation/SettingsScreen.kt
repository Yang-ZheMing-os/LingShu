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
import androidx.compose.material3.HorizontalDivider
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
import com.lingshu.feature.control.data.CustomCommandManager
import com.lingshu.feature.control.domain.CustomCommand
import com.lingshu.feature.offlinetts.data.EdgeTtsEngine
import com.lingshu.feature.stt.data.ModelDownloadManager
import com.lingshu.core.common.event.ISttEngine
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
    onNavigateToSceneManager: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    LingShuLog.i(SETTINGS_TAG, "SettingsScreen: composing")
    val apiKey by viewModel.apiKey.collectAsState()
    val ttsEnabled by viewModel.ttsEnabled.collectAsState()
    val ttsVoiceId by viewModel.ttsVoiceId.collectAsState()
    val accessibilityEnabled by viewModel.accessibilityEnabled.collectAsState()
    val llmProvider by viewModel.llmProvider.collectAsState()
    val currentConfig by viewModel.currentConfig.collectAsState()
    val ollamaModels by viewModel.ollamaModels.collectAsState()
    val modelsLoading by viewModel.modelsLoading.collectAsState()
    val modelsError by viewModel.modelsError.collectAsState()
    val customCommands by viewModel.customCommands.collectAsState()

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

                    Spacer(modifier = Modifier.height(4.dp))

                    var voiceExpanded by remember { mutableStateOf(false) }
                    val currentVoiceName = EdgeTtsEngine.VOICE_DISPLAY_NAMES[ttsVoiceId] ?: ttsVoiceId

                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { voiceExpanded = !voiceExpanded },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 16.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "TTS 音色",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        text = currentVoiceName,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                Text(
                                    text = if (voiceExpanded) "收起" else "展开",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (voiceExpanded) {
                                Spacer(modifier = Modifier.height(12.dp))
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(8.dp))

                                // 中文女声
                                Text(
                                    text = "中文女声",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                                EdgeTtsEngine.VOICE_DISPLAY_NAMES
                                    .filter { it.key.startsWith("zh-CN-Xiao") }
                                    .forEach { (voiceId, displayName) ->
                                        VoiceOptionRow(
                                            name = displayName,
                                            selected = ttsVoiceId == voiceId,
                                            onSelect = {
                                                viewModel.setTtsVoice(voiceId)
                                                voiceExpanded = false
                                            }
                                        )
                                    }

                                // 中文男声
                                Text(
                                    text = "中文男声",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                                EdgeTtsEngine.VOICE_DISPLAY_NAMES
                                    .filter { it.key.startsWith("zh-CN-Yun") }
                                    .forEach { (voiceId, displayName) ->
                                        VoiceOptionRow(
                                            name = displayName,
                                            selected = ttsVoiceId == voiceId,
                                            onSelect = {
                                                viewModel.setTtsVoice(voiceId)
                                                voiceExpanded = false
                                            }
                                        )
                                    }

                                // 方言
                                val dialectVoices = EdgeTtsEngine.VOICE_DISPLAY_NAMES
                                    .filter { it.key.startsWith("zh-HK") || it.key.startsWith("zh-TW") }
                                if (dialectVoices.isNotEmpty()) {
                                    Text(
                                        text = "粤语 / 台湾",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                    )
                                    dialectVoices.forEach { (voiceId, displayName) ->
                                        VoiceOptionRow(
                                            name = displayName,
                                            selected = ttsVoiceId == voiceId,
                                            onSelect = {
                                                viewModel.setTtsVoice(voiceId)
                                                voiceExpanded = false
                                            }
                                        )
                                    }
                                }

                                // 外语
                                val foreignVoices = EdgeTtsEngine.VOICE_DISPLAY_NAMES
                                    .filter { !it.key.startsWith("zh-") }
                                if (foreignVoices.isNotEmpty()) {
                                    Text(
                                        text = "外语",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                    )
                                    foreignVoices.forEach { (voiceId, displayName) ->
                                        VoiceOptionRow(
                                            name = displayName,
                                            selected = ttsVoiceId == voiceId,
                                            onSelect = {
                                                viewModel.setTtsVoice(voiceId)
                                                voiceExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "语音识别模型") {
                    val sttStatus by viewModel.sttStatus.collectAsState()
                    val downloadProgress by viewModel.downloadProgress.collectAsState()
                    val isDownloading = downloadProgress?.isDownloading == true

                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 16.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "SenseVoice 离线识别",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        text = sttStatus,
                                        fontSize = 12.sp,
                                        color = if (sttStatus.startsWith("✓"))
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }

                            if (isDownloading && downloadProgress != null) {
                                Spacer(modifier = Modifier.height(12.dp))
                                val prog = downloadProgress!!
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { if (prog.totalBytes > 0) prog.downloadedBytes.toFloat() / prog.totalBytes else 0f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = "正在下载 ${prog.fileName}: ${"%.1f".format(prog.downloadedMB)}MB / ${"%.1f".format(prog.totalMB)}MB (${prog.percent}%)",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }

                            downloadProgress?.error?.let { err ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "下载失败: $err",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (!sttStatus.startsWith("✓") && !isDownloading) {
                                androidx.compose.material3.Button(
                                    onClick = { viewModel.downloadSttModel() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("下载 SenseVoice 模型 (约 240MB)")
                                }
                                Text(
                                    text = "下载后可离线使用，支持口语化识别、自动标点、情绪检测",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            } else if (isDownloading) {
                                androidx.compose.material3.OutlinedButton(
                                    onClick = { viewModel.cancelDownload() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("取消下载")
                                }
                            }
                        }
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
                SettingsSection(title = "自定义指令（AES-256 加密存储）") {
                    var aliasText by remember { mutableStateOf("") }
                    var targetText by remember { mutableStateOf("") }

                    Text(
                        text = "把你日常的口语习惯映射为标准指令，例如「睡觉觉」→「我要睡了」",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )

                    if (customCommands.isNotEmpty()) {
                        customCommands.forEach { cmd ->
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = cmd.alias,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                        Text(
                                            text = "→ ${cmd.target}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                    androidx.compose.material3.TextButton(
                                        onClick = { viewModel.removeCustomCommand(cmd.alias) }
                                    ) {
                                        Text("删除", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedTextField(
                        value = aliasText,
                        onValueChange = { aliasText = it },
                        label = { Text("你的说法（别名）") },
                        placeholder = { Text("如：睡觉觉") },
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

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = targetText,
                        onValueChange = { targetText = it },
                        label = { Text("对应标准指令") },
                        placeholder = { Text("如：我要睡了、打开微信、太亮了") },
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

                    Spacer(modifier = Modifier.height(8.dp))

                    androidx.compose.material3.Button(
                        onClick = {
                            if (aliasText.isNotBlank() && targetText.isNotBlank()) {
                                viewModel.addCustomCommand(aliasText, targetText)
                                aliasText = ""
                                targetText = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("添加自定义指令")
                    }
                }
            }

            item {
                SettingsSection(title = "功能模块") {
                    NavCard(
                        icon = Icons.Default.AutoAwesome,
                        title = "🧩 通用场景（自定义/导入）",
                        description = "AI 不只三个场景：查看内置 12 条、导入 JSON 新增自定义",
                        onClick = {
                            LingShuLog.d(SETTINGS_TAG, "navigate to SceneManager")
                            onNavigateToSceneManager()
                        }
                    )
                    NavCard(
                        icon = Icons.Default.AutoAwesome,
                        title = "Mod 中心 / 导入（声明式 JSON）",
                        description = "安装 Day2 声明式 Mod：manifest aliases/persona/quickActions",
                        onClick = {
                            LingShuLog.d(SETTINGS_TAG, "navigate to Mod")
                            onNavigateToMod()
                        }
                    )
                    NavCard(
                        icon = Icons.Default.RecordVoiceOver,
                        title = "音色库分享（导入/导出 .voicepreset）",
                        description = "Day2-2 音色库：不要录样本也能分享 TTS 音色参数",
                        onClick = {
                            LingShuLog.d(SETTINGS_TAG, "navigate to CloneVoice(音色库)")
                            onNavigateToCloneVoice()
                        }
                    )
                    NavCard(
                        icon = Icons.Default.Info,
                        title = "☔ 雨天提醒（和风天气 Key 配置）",
                        description = "Day3-3 主动关怀：打开雨天提醒、输入 API Key/Location",
                        onClick = {
                            LingShuLog.d(SETTINGS_TAG, "navigate to Proactive(雨天提醒)")
                            onNavigateToProactive()
                        }
                    )
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

@Composable
private fun VoiceOptionRow(
    name: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.RadioButton(
            selected = selected,
            onClick = onSelect
        )
        Text(
            text = name,
            fontSize = 13.sp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onBackground
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val ollamaProvider: com.lingshu.core.data.llm.OllamaProvider,
    private val llmConfigStore: LlmConfigStore,
    private val accessibilityControl: com.lingshu.feature.accessibility.domain.IAccessibilityControl,
    private val customCommandManager: CustomCommandManager,
    private val modelDownloadManager: ModelDownloadManager,
    private val sttEngine: ISttEngine
) : ViewModel() {

    private val vmTag = "SettingsVM"

    // 顶部「API 设置」区块使用（旧 AppPreferences，保留兼容）
    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _ttsEnabled = MutableStateFlow(true)
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

    private val _ttsVoiceId = MutableStateFlow(EdgeTtsEngine.DEFAULT_VOICE)
    val ttsVoiceId: StateFlow<String> = _ttsVoiceId.asStateFlow()

    // 无障碍服务是否已开启（用于设置页状态展示，ON_RESUME 时刷新）
    private val _accessibilityEnabled = MutableStateFlow(false)
    val accessibilityEnabled: StateFlow<Boolean> = _accessibilityEnabled.asStateFlow()

    // STT 引擎状态
    private val _sttStatus = MutableStateFlow("检测中...")
    val sttStatus: StateFlow<String> = _sttStatus.asStateFlow()

    // 模型下载进度
    val downloadProgress = modelDownloadManager.downloadProgress

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

    // 用户自定义指令（AES256 加密存储）
    val customCommands: StateFlow<List<CustomCommand>> = customCommandManager.commands

    init {
        LingShuLog.i(vmTag, "init: SettingsViewModel created")

        // 顶部「API 设置」区块的 API Key（AppPreferences，保留兼容）
        viewModelScope.launch {
            appPreferences.apiKey.collect { key ->
                _apiKey.value = key
            }
        }

        // TTS 音色偏好
        viewModelScope.launch {
            appPreferences.ttsVoiceId.collect { voice ->
                _ttsVoiceId.value = voice
            }
        }

        // 检查 STT 引擎状态
        checkSttStatus()

        // 监听下载进度，下载完成后刷新状态
        viewModelScope.launch {
            modelDownloadManager.downloadProgress.collect { progress ->
                if (progress?.isComplete == true) {
                    checkSttStatus()
                }
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

    fun setTtsVoice(voiceId: String) {
        _ttsVoiceId.value = voiceId
        viewModelScope.launch {
            appPreferences.setTtsVoiceId(voiceId)
        }
    }

    private fun checkSttStatus() {
        viewModelScope.launch {
            val ready = modelDownloadManager.isModelReady()
            val sizeMB = modelDownloadManager.getModelFileSize() / (1024f * 1024f)
            _sttStatus.value = if (ready) {
                "✓ SenseVoice 已就绪 (${"%.0f".format(sizeMB)}MB)"
            } else {
                "未部署，将使用系统语音识别（建议下载以获得更好的口语化识别效果）"
            }
        }
    }

    fun downloadSttModel() {
        viewModelScope.launch {
            LingShuLog.i(vmTag, "开始下载 SenseVoice 模型")
            modelDownloadManager.downloadAll()
        }
    }

    fun cancelDownload() {
        modelDownloadManager.cancelDownload()
        checkSttStatus()
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

    fun addCustomCommand(alias: String, target: String) {
        customCommandManager.addCommand(alias, target)
    }

    fun removeCustomCommand(alias: String) {
        customCommandManager.removeCommand(alias)
    }
}

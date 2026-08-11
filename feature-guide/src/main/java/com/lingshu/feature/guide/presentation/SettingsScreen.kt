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
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _ttsEnabled = MutableStateFlow(true)
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()

    init {
        viewModelScope.launch {
            appPreferences.apiKey.collect { key ->
                _apiKey.value = key
            }
        }
    }

    fun setApiKey(key: String) {
        _apiKey.value = key
        viewModelScope.launch {
            appPreferences.setApiKey(key)
        }
    }

    fun toggleTts(enabled: Boolean) {
        _ttsEnabled.value = enabled
    }
}

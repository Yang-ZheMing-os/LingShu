package com.lingshu.agent.feature.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.agent.data.SettingsManager
import com.lingshu.agent.feature.model.providers.DeepSeekProvider
import com.lingshu.agent.feature.model.providers.GPT4VisionProvider
import com.lingshu.agent.feature.model.providers.OllamaProvider
import com.lingshu.agent.feature.model.providers.SystemTTSProvider
import com.lingshu.agent.feature.model.providers.VoskTranscribeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 模型设置 ViewModel（适配 feature/model 新架构）
 *
 * 为模型设置页面提供数据绑定和操作能力：
 * 1. 展示全局配置（各场景默认模型、自动降级开关、Key轮询开关）
 * 2. 展示所有已注册 Provider 列表及其状态、配置信息
 * 3. 提供修改配置的操作方法（即时生效，DataStore → Flow → UI 自动刷新）
 * 4. 支持测试各 Provider 连接/可用性状态
 * 5. 支持手动切换当前使用的 Provider
 *
 * 架构变更说明：
 * 此版本适配 com.lingshu.agent.feature.model 新架构，
 * 使用 ModelSettings（DataStore封装） + ModelRouter（智能路由），
 * 替代旧版 core/model/routing 下的 ModelConfigRepository / ModelManager。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ModelSettingsViewModel @Inject constructor(
    private val modelSettings: ModelSettings,
    private val modelRouter: ModelRouter,
    private val settingsManager: SettingsManager
) : ViewModel() {

    // ==================== P2 模型状态与下载管理 ====================

    /** 模型目录路径 */
    private val modelsDir = File("/data/data/com.lingshu/files/models/")

    /** 下载源 URL 配置 */
    val downloadSources = ModelDownloadSources()

    /** 降级策略配置 */
    val fallbackStrategy = FallbackStrategyConfig()

    /** 模型状态列表（扫描 /data/data/com.lingshu/files/models/） */
    private val _modelStatuses = MutableStateFlow<List<ModelStatusInfo>>(emptyList())
    val modelStatuses: StateFlow<List<ModelStatusInfo>> = _modelStatuses.asStateFlow()

    /** 下载中标记（modelId → 是否下载中） */
    private val _downloadInProgress = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    init {
        scanModelsDirectory()
    }

    /**
     * 扫描模型目录并更新状态
     *
     * 扫描 /data/data/com.lingshu/files/models/ 下的 .litert/.gguf 文件
     */
    fun scanModelsDirectory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val statusList = mutableListOf<ModelStatusInfo>()

                // 预定义模型信息
                val knownModels = mapOf(
                    "gemma" to ModelStatusInfo(
                        modelId = "gemma",
                        modelName = "Gemma 4 E2B",
                        modelType = "gemma",
                        version = "4.0-e2b",
                        downloadUrl = downloadSources.gemmaDownloadUrl,
                        autoUpdateEnabled = false
                    ),
                    "minicpm" to ModelStatusInfo(
                        modelId = "minicpm",
                        modelName = "MiniCPM-V 2.6",
                        modelType = "minicpm",
                        version = "2.6",
                        downloadUrl = downloadSources.minicpmvDownloadUrl,
                        autoUpdateEnabled = false
                    ),
                    "qwen" to ModelStatusInfo(
                        modelId = "qwen",
                        modelName = "Qwen2.5 1.5B",
                        modelType = "qwen",
                        version = "2.5",
                        downloadUrl = downloadSources.qwenDownloadUrl,
                        autoUpdateEnabled = false
                    )
                )

                // 确保目录存在
                if (!modelsDir.exists()) {
                    modelsDir.mkdirs()
                }

                // 扫描实际文件
                val existingFiles = if (modelsDir.exists() && modelsDir.isDirectory) {
                    modelsDir.listFiles()?.associateBy { it.nameWithoutExtension.lowercase() } ?: emptyMap()
                } else {
                    emptyMap()
                }

                for ((id, base) in knownModels) {
                    val matchingFile = existingFiles.entries.firstOrNull { (fileName, _) ->
                        fileName.contains(id, ignoreCase = true)
                    }

                    val status = if (matchingFile != null) {
                        val file = matchingFile.value
                        base.copy(
                            downloadState = ModelDownloadState.DOWNLOADED,
                            fileSizeBytes = file.length(),
                            localFilePath = file.absolutePath,
                            isLoaded = false // Runtime 层判断
                        )
                    } else {
                        base.copy(downloadState = ModelDownloadState.NOT_DOWNLOADED)
                    }

                    statusList.add(status)
                }

                _modelStatuses.value = statusList
            }
        }
    }

    /**
     * 开始下载模型
     *
     * @param modelId 模型标识（gemma/minicpm/qwen）
     */
    fun startDownload(modelId: String) {
        viewModelScope.launch {
            updateDownloadState(modelId, ModelDownloadState.DOWNLOADING, 0f, 0L)

            try {
                // 模拟下载过程（实际应接入 DownloadManager 或 OkHttp）
                val modelInfo = _modelStatuses.value.find { it.modelId == modelId } ?: return@launch
                val targetFile = File(modelsDir, "${modelId}_model.litert")

                // 模拟下载进度
                for (progress in 1..100) {
                    if (!isActive) break
                    delay(50)
                    val progressFloat = progress / 100f
                    val speed = (Math.random() * 500 + 500).toLong() * 1024 // 500-1000 KB/s
                    updateDownloadState(modelId, ModelDownloadState.DOWNLOADING, progressFloat, speed)
                }

                // 下载完成
                targetFile.parentFile?.mkdirs()
                targetFile.writeText("") // 占位文件

                _modelStatuses.value = _modelStatuses.value.map {
                    if (it.modelId == modelId) {
                        it.copy(
                            downloadState = ModelDownloadState.DOWNLOADED,
                            downloadProgress = 1f,
                            fileSizeBytes = targetFile.length(),
                            localFilePath = targetFile.absolutePath
                        )
                    } else it
                }
            } catch (e: Exception) {
                _modelStatuses.value = _modelStatuses.value.map {
                    if (it.modelId == modelId) {
                        it.copy(downloadState = ModelDownloadState.FAILED)
                    } else it
                }
            }
        }
    }

    /**
     * 删除已下载的模型文件
     */
    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            val modelInfo = _modelStatuses.value.find { it.modelId == modelId } ?: return@launch

            withContext(Dispatchers.IO) {
                if (modelInfo.localFilePath.isNotEmpty()) {
                    File(modelInfo.localFilePath).delete()
                }
                // 也尝试删除 modelsDir 下的匹配文件
                modelsDir.listFiles()?.forEach { file ->
                    if (file.name.contains(modelId, ignoreCase = true)) {
                        file.delete()
                    }
                }
            }

            // 刷新状态
            _modelStatuses.value = _modelStatuses.value.map {
                if (it.modelId == modelId) {
                    it.copy(
                        downloadState = ModelDownloadState.NOT_DOWNLOADED,
                        downloadProgress = 0f,
                        fileSizeBytes = 0L,
                        localFilePath = "",
                        isLoaded = false
                    )
                } else it
            }
        }
    }

    /**
     * 切换自动更新开关
     */
    fun toggleAutoUpdate(modelId: String, enabled: Boolean) {
        _modelStatuses.value = _modelStatuses.value.map {
            if (it.modelId == modelId) it.copy(autoUpdateEnabled = enabled) else it
        }
    }

    /**
     * 更新下载状态
     */
    private fun updateDownloadState(
        modelId: String,
        state: ModelDownloadState,
        progress: Float,
        speedBytesPerSec: Long
    ) {
        _modelStatuses.value = _modelStatuses.value.map {
            if (it.modelId == modelId) {
                it.copy(
                    downloadState = state,
                    downloadProgress = progress,
                    downloadSpeedBytesPerSec = speedBytesPerSec
                )
            } else it
        }
    }

    /**
     * 页面完整 UI 状态（StateFlow 供 Compose 绑定）
     *
     * 通过 combine 合并多个独立 Flow：
     * - 四个场景的默认模型 Provider ID
     * - 两个全局开关（自动降级、Key轮询）
     * - 当前路由选中的 Provider
     * - 以及每个 Provider 的 API Keys / BaseURL / Enabled 状态（flatMapLatest 动态组合）
     */
    val uiState: StateFlow<ModelSettingsUiState> = combine(
        combine(
            modelSettings.defaultChatProviderFlow,
            modelSettings.defaultVisionProviderFlow,
            modelSettings.defaultTranscribeProviderFlow,
            modelSettings.defaultSynthesizeProviderFlow,
            modelSettings.autoFallbackEnabledFlow
        ) { chatId, visionId, transcribeId, synthesizeId, fallback ->
            arrayOf(chatId, visionId, transcribeId, synthesizeId, fallback)
        },
        modelSettings.apiKeyRotationEnabledFlow,
        modelRouter.currentProviderFlow
    ) { firstFive, rotation, currentProvider ->
        val chatId = firstFive[0] as String
        val visionId = firstFive[1] as String
        val transcribeId = firstFive[2] as String
        val synthesizeId = firstFive[3] as String
        val fallback = firstFive[4] as Boolean

        // 获取所有已注册 Provider 列表，并构建对应的 UI 项
        val providers = modelRouter.getAllProviders()
        val modelItems = providers.map { provider ->
            // 读取该 Provider 的配置（从 Flow 同步取快照）
            val apiKeys = readProviderApiKeysSnapshot(provider.providerId)
            val baseUrl = readProviderBaseUrlSnapshot(provider.providerId)
            val enabled = readProviderEnabledSnapshot(provider.providerId)

            ModelItemUiState(
                providerId = provider.providerId,
                providerName = provider.providerName,
                capabilities = provider.capabilities,
                isEnabled = enabled,
                isLocal = isLocalProvider(provider.providerId),
                apiKeys = apiKeys,
                baseUrl = baseUrl,
                apiKeyCount = apiKeys.size,
                isConfigured = when {
                    isLocalProvider(provider.providerId) -> true
                    else -> apiKeys.any { it.isNotBlank() }
                },
                supportsChat = provider.supports(ModelCapability.CHAT),
                supportsVision = provider.supports(ModelCapability.VISION),
                supportsTranscribe = provider.supports(ModelCapability.TRANSCRIBE),
                supportsSynthesize = provider.supports(ModelCapability.SYNTHESIZE),
                isCurrentProvider = (currentProvider?.providerId == provider.providerId)
            )
        }

        ModelSettingsUiState(
            defaultChatProviderId = chatId,
            defaultVisionProviderId = visionId,
            defaultTranscribeProviderId = transcribeId,
            defaultSynthesizeProviderId = synthesizeId,
            autoFallbackEnabled = fallback,
            apiKeyRotationEnabled = rotation,
            currentProviderId = currentProvider?.providerId,
            currentProviderName = currentProvider?.providerName,
            modelItems = modelItems
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ModelSettingsUiState.EMPTY
    )

    // ==================== 内部：同步读取配置快照 ====================
    // 由于 combine 中只能拿到 flow 的最新值，这里提供同步快照读取。
    // 实际生产中可考虑将 Provider 配置也暴露为 Flow 再做 combine。

    private fun readProviderApiKeysSnapshot(providerId: String): List<String> {
        // 简化实现：返回空列表，真实场景可以改用 Flow combine
        return emptyList()
    }

    private fun readProviderBaseUrlSnapshot(providerId: String): String? {
        return null
    }

    private fun readProviderEnabledSnapshot(providerId: String): Boolean {
        return true
    }

    private fun isLocalProvider(providerId: String): Boolean {
        return when (providerId) {
            OllamaProvider.PROVIDER_ID,
            VoskTranscribeProvider.PROVIDER_ID,
            SystemTTSProvider.PROVIDER_ID -> true
            else -> false
        }
    }

    // ==================== 公共操作 API（写入配置） ====================

    /**
     * 设置指定能力场景的默认 Provider
     */
    fun setDefaultProvider(capability: ModelCapability, providerId: String) {
        viewModelScope.launch {
            modelSettings.setDefaultProvider(capability, providerId)
        }
    }

    /** 便捷方法：设置默认对话模型 */
    fun setDefaultChatProvider(providerId: String) =
        setDefaultProvider(ModelCapability.CHAT, providerId)

    /** 便捷方法：设置默认视觉模型 */
    fun setDefaultVisionProvider(providerId: String) =
        setDefaultProvider(ModelCapability.VISION, providerId)

    /** 便捷方法：设置默认语音识别模型 */
    fun setDefaultTranscribeProvider(providerId: String) =
        setDefaultProvider(ModelCapability.TRANSCRIBE, providerId)

    /** 便捷方法：设置默认语音合成模型 */
    fun setDefaultSynthesizeProvider(providerId: String) =
        setDefaultProvider(ModelCapability.SYNTHESIZE, providerId)

    /**
     * 切换自动降级开关
     */
    fun toggleAutoFallback(enabled: Boolean) {
        viewModelScope.launch {
            modelSettings.setAutoFallbackEnabled(enabled)
        }
    }

    /**
     * 切换 API Key 轮询开关
     */
    fun toggleApiKeyRotation(enabled: Boolean) {
        viewModelScope.launch {
            modelSettings.setApiKeyRotationEnabled(enabled)
        }
    }

    /**
     * 切换单个 Provider 的启用/禁用状态
     */
    fun toggleProviderEnabled(providerId: String, enabled: Boolean) {
        viewModelScope.launch {
            modelSettings.setProviderEnabled(providerId, enabled)
        }
    }

    /**
     * 设置 Provider 的 API Keys 列表（覆盖原有）
     */
    fun setProviderApiKeys(providerId: String, apiKeys: List<String>) {
        viewModelScope.launch {
            modelSettings.setProviderApiKeys(providerId, apiKeys)
        }
    }

    /**
     * 追加单个 API Key 到指定 Provider
     */
    fun addApiKey(providerId: String, newApiKey: String) {
        if (newApiKey.isBlank()) return
        viewModelScope.launch {
            modelSettings.addApiKey(providerId, newApiKey)
            // 同步写入 SettingsManager，确保 ChatViewModel 能读到 API Key
            settingsManager.setApiKey(newApiKey)
        }
    }

    /**
     * 移除指定 Provider 的某个 API Key（按索引）
     */
    fun removeApiKey(providerId: String, index: Int) {
        viewModelScope.launch {
            modelSettings.removeApiKey(providerId, index)
        }
    }

    /**
     * 设置 Provider 的自定义 Base URL（传空或 blank 表示使用默认）
     */
    fun setProviderBaseUrl(providerId: String, baseUrl: String?) {
        viewModelScope.launch {
            modelSettings.setProviderBaseUrl(providerId, baseUrl)
        }
    }

    /**
     * 设置 Provider 的具体模型名称（如 "gpt-4o"、"deepseek-chat"）
     */
    fun setProviderModelName(providerId: String, modelName: String) {
        viewModelScope.launch {
            modelSettings.setProviderModelName(providerId, modelName)
        }
    }

    /**
     * 手动切换并锁定当前使用的 Provider
     *
     * @param providerId 目标 Provider ID；传 null 表示取消锁定，恢复自动路由
     */
    fun switchCurrentProvider(providerId: String?) {
        modelRouter.switchProvider(providerId)
    }

    // ==================== 公共操作 API（运行时状态） ====================

    /**
     * 测试指定 Provider 的可用性（异步，挂起函数）
     *
     * @return 是否可用（已启用 + 可成功连接/初始化）
     */
    suspend fun testProviderConnection(providerId: String): Boolean {
        val provider = modelRouter.getProvider(providerId) ?: return false
        return provider.isAvailable()
    }

    /**
     * 测试所有 Provider 的连接状态（并行）
     *
     * @return Map<ProviderID, 是否可用>
     */
    suspend fun testAllConnections(): Map<String, Boolean> {
        val providers = modelRouter.getAllProviders()
        val result = mutableMapOf<String, Boolean>()
        for (p in providers) {
            result[p.providerId] = runCatching { p.isAvailable() }.getOrDefault(false)
        }
        return result
    }

    /**
     * 重置所有模型设置为默认值
     */
    fun resetAllToDefaults() {
        viewModelScope.launch {
            modelSettings.resetToDefaults()
            // 取消手动锁定，恢复自动路由
            modelRouter.switchProvider(null)
        }
    }

    /**
     * ViewModel 销毁时清理
     * 注意：ModelRouter 和 Provider 是应用级单例，不要在这里 releaseAll
     */
    override fun onCleared() {
        super.onCleared()
    }
}

// ==================== UI 状态数据类 ====================

/**
 * 模型设置页面完整 UI 状态
 *
 * @property defaultChatProviderId 默认对话模型 Provider ID
 * @property defaultVisionProviderId 默认视觉模型 Provider ID
 * @property defaultTranscribeProviderId 默认语音识别模型 Provider ID
 * @property defaultSynthesizeProviderId 默认语音合成模型 Provider ID
 * @property autoFallbackEnabled 是否启用自动降级
 * @property apiKeyRotationEnabled 是否启用 API Key 轮询
 * @property currentProviderId 当前路由实际使用的 Provider ID
 * @property currentProviderName 当前路由实际使用的 Provider 名称
 * @property modelItems 所有 Provider 列表项的 UI 状态
 */
data class ModelSettingsUiState(
    val defaultChatProviderId: String,
    val defaultVisionProviderId: String,
    val defaultTranscribeProviderId: String,
    val defaultSynthesizeProviderId: String,
    val autoFallbackEnabled: Boolean,
    val apiKeyRotationEnabled: Boolean,
    val currentProviderId: String?,
    val currentProviderName: String?,
    val modelItems: List<ModelItemUiState>
) {
    companion object {
        val EMPTY = ModelSettingsUiState(
            defaultChatProviderId = ModelSettings.DEFAULT_CHAT_PROVIDER,
            defaultVisionProviderId = ModelSettings.DEFAULT_VISION_PROVIDER,
            defaultTranscribeProviderId = ModelSettings.DEFAULT_TRANSCRIBE_PROVIDER,
            defaultSynthesizeProviderId = ModelSettings.DEFAULT_SYNTHESIZE_PROVIDER,
            autoFallbackEnabled = true,
            apiKeyRotationEnabled = true,
            currentProviderId = null,
            currentProviderName = null,
            modelItems = emptyList()
        )
    }

    /** 获取所有支持对话能力的 Provider 列表（用于下拉选择默认模型） */
    val chatOptions: List<ModelItemUiState>
        get() = modelItems.filter { it.supportsChat }

    /** 获取所有支持视觉能力的 Provider 列表 */
    val visionOptions: List<ModelItemUiState>
        get() = modelItems.filter { it.supportsVision }

    /** 获取所有支持语音识别能力的 Provider 列表 */
    val transcribeOptions: List<ModelItemUiState>
        get() = modelItems.filter { it.supportsTranscribe }

    /** 获取所有支持语音合成能力的 Provider 列表 */
    val synthesizeOptions: List<ModelItemUiState>
        get() = modelItems.filter { it.supportsSynthesize }

    /** 获取所有云端 Provider 列表 */
    val cloudProviders: List<ModelItemUiState>
        get() = modelItems.filter { !it.isLocal }

    /** 获取所有本地 Provider 列表 */
    val localProviders: List<ModelItemUiState>
        get() = modelItems.filter { it.isLocal }
}

/**
 * 单个 Provider 列表项的 UI 状态
 */
data class ModelItemUiState(
    val providerId: String,
    val providerName: String,
    val capabilities: Set<ModelCapability>,
    val isEnabled: Boolean,
    val isLocal: Boolean,
    val apiKeys: List<String>,
    val baseUrl: String?,
    val apiKeyCount: Int,
    val isConfigured: Boolean,
    val supportsChat: Boolean,
    val supportsVision: Boolean,
    val supportsTranscribe: Boolean,
    val supportsSynthesize: Boolean,
    val isCurrentProvider: Boolean
) {
    /** 能力摘要文本（用于列表展示） */
    val capabilitySummary: String
        get() {
            val parts = mutableListOf<String>()
            if (supportsChat) parts.add("对话")
            if (supportsVision) parts.add("视觉")
            if (supportsTranscribe) parts.add("语音识别")
            if (supportsSynthesize) parts.add("语音合成")
            return parts.joinToString(" / ")
        }

    /** 状态摘要（用于列表副标题展示） */
    val statusSummary: String
        get() {
            val parts = mutableListOf<String>()
            if (!isEnabled) parts.add("已禁用")
            if (isLocal) parts.add("本地")
            if (!isLocal && apiKeyCount > 0) parts.add("${apiKeyCount}个Key")
            if (isCurrentProvider) parts.add("当前使用")
            return if (parts.isEmpty()) "就绪" else parts.joinToString(" · ")
        }
}

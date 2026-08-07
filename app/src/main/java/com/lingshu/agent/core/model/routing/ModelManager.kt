package com.lingshu.agent.core.model.routing

import com.lingshu.agent.core.model.routing.providers.BaseModelProvider
import com.lingshu.agent.core.model.routing.providers.ClaudeProvider
import com.lingshu.agent.core.model.routing.providers.DeepSeekProvider
import com.lingshu.agent.core.model.routing.providers.GPT4Provider
import com.lingshu.agent.core.model.routing.providers.GeminiProvider
import com.lingshu.agent.core.model.routing.providers.OllamaProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * 模型管理器
 *
 * 核心职责：
 * 1. 管理所有支持的模型列表
 * 2. 检查各模型的可用性状态
 * 3. 创建和复用Provider实例（单例模式）
 * 4. 提供模型列表、状态等信息的查询接口
 *
 * 设计原则：
 * - Provider实例通过懒加载 + 缓存机制创建，避免重复初始化
 * - 配置变更时自动更新已有Provider的配置
 * - 线程安全的Provider访问
 */
class ModelManager @Inject constructor(
    private val configRepository: ModelConfigRepository
) {

    /** Provider实例缓存（懒加载 + 复用） */
    private val providerCache = mutableMapOf<ModelType, ModelProvider>()

    /** Provider创建/访问的互斥锁（保证线程安全） */
    private val providerMutex = Mutex()

    /** 模型状态缓存（包含可用性信息） */
    private val _modelStates = MutableStateFlow<Map<ModelType, ModelState>>(
        ModelType.values().associateWith {
            ModelState(
                modelType = it,
                isEnabled = true,
                isConfigured = false,
                isAvailable = false,
                lastCheckTime = 0L
            )
        }
    )

    /**
     * 模型状态流（只读）
     * UI层可订阅此Flow获取模型状态更新
     */
    val modelStates: StateFlow<Map<ModelType, ModelState>> = _modelStates.asStateFlow()

    /**
     * 初始化模型管理器
     * 1. 加载配置
     * 2. 刷新模型状态
     */
    suspend fun initialize() {
        val config = configRepository.getConfig()
        refreshStatesFromConfig(config)

        // 订阅配置变更，配置更新时刷新状态
        configRepository.configFlow.collect { newConfig ->
            refreshStatesFromConfig(newConfig)
            updateProvidersConfig(newConfig)
        }
    }

    /**
     * 获取指定模型的Provider实例
     * 如果实例不存在，则创建并缓存；如果已存在，则直接返回
     *
     * @param modelType 模型类型
     * @return Provider实例，如果模型不支持则返回null
     */
    suspend fun getProvider(modelType: ModelType): ModelProvider? {
        // 先检查缓存
        providerCache[modelType]?.let { return it }

        // 缓存未命中，在锁内创建
        return providerMutex.withLock {
            // 双重检查，避免多线程下重复创建
            providerCache[modelType]?.let { return@withLock it }

            val config = configRepository.getConfig()
            val modelConfig = config.getModelConfig(modelType)
            val provider = createProvider(modelType, modelConfig)

            provider?.let {
                providerCache[modelType] = it
            }

            provider
        }
    }

    /**
     * 获取所有已启用且可用的模型列表
     * @param taskType 可选的任务类型筛选
     */
    suspend fun getAvailableModels(taskType: TaskType? = null): List<ModelConfig> {
        val config = configRepository.getConfig()
        return when (taskType) {
            TaskType.CHAT, TaskType.CODE, TaskType.SUMMARY, TaskType.TRANSLATION ->
                config.getAvailableChatModels()
            TaskType.VISION -> config.getAvailableVisionModels()
            TaskType.TRANSCRIBE -> config.getAvailableTranscribeModels()
            TaskType.SYNTHESIZE -> config.getAvailableSynthesizeModels()
            null -> config.getEnabledConfigs().filter { it.isAvailable }
        }
    }

    /**
     * 获取所有支持的模型类型列表
     */
    fun getAllModelTypes(): List<ModelType> = ModelType.values().toList()

    /**
     * 获取指定模型的配置
     */
    suspend fun getModelConfig(modelType: ModelType): ModelConfig {
        return configRepository.getConfig().getModelConfig(modelType)
    }

    /**
     * 检查指定模型是否可用
     * 会触发实际的可用性探测（对于本地模型会检查服务是否运行）
     */
    suspend fun checkModelAvailability(modelType: ModelType): Boolean {
        val provider = getProvider(modelType) ?: return false
        val isAvailable = provider.isAvailable()

        // 更新状态缓存
        val currentStates = _modelStates.value.toMutableMap()
        currentStates[modelType] = currentStates[modelType]?.copy(
            isAvailable = isAvailable,
            lastCheckTime = System.currentTimeMillis()
        ) ?: ModelState(
            modelType = modelType,
            isEnabled = true,
            isConfigured = false,
            isAvailable = isAvailable,
            lastCheckTime = System.currentTimeMillis()
        )
        _modelStates.value = currentStates

        return isAvailable
    }

    /**
     * 检查所有模型的可用性
     */
    suspend fun checkAllModelsAvailability(): Map<ModelType, Boolean> {
        val result = mutableMapOf<ModelType, Boolean>()
        for (modelType in ModelType.values()) {
            result[modelType] = checkModelAvailability(modelType)
        }
        return result
    }

    /**
     * 获取指定模型的当前状态
     */
    fun getModelState(modelType: ModelType): ModelState? {
        return _modelStates.value[modelType]
    }

    /**
     * 订阅指定模型的状态变化
     */
    fun observeModelState(modelType: ModelType): Flow<ModelState?> {
        return _modelStates.map { it[modelType] }
    }

    /**
     * 释放所有Provider资源
     * 在应用退出或需要重置时调用
     */
    fun releaseAll() {
        providerCache.values.forEach { it.release() }
        providerCache.clear()
    }

    /**
     * 强制重建指定模型的Provider实例
     * 用于配置发生重大变更，需要完全重新初始化时
     */
    suspend fun recreateProvider(modelType: ModelType): ModelProvider? {
        providerMutex.withLock {
            providerCache.remove(modelType)?.release()
        }
        return getProvider(modelType)
    }

    // ==================== 内部方法 ====================

    /**
     * 根据模型类型创建对应的Provider实例
     * 工厂方法
     */
    private fun createProvider(
        modelType: ModelType,
        config: ModelConfig
    ): ModelProvider? {
        return when (modelType) {
            ModelType.DEEPSEEK -> DeepSeekProvider(config)
            ModelType.GPT4 -> GPT4Provider(config)
            ModelType.CLAUDE -> ClaudeProvider(config)
            ModelType.GEMINI -> GeminiProvider(config)
            ModelType.OLLAMA -> OllamaProvider(config)
            ModelType.GEMMA -> OllamaProvider(config)    // Gemma 通过 Ollama 兼容接口加载
            ModelType.QWEN -> OllamaProvider(config)     // Qwen 通过 Ollama 兼容接口加载
            ModelType.MINICPM_V -> OllamaProvider(config) // MiniCPM-V 通过 Ollama 兼容接口加载
        }
    }

    /**
     * 根据全局配置刷新模型状态缓存
     */
    private fun refreshStatesFromConfig(config: GlobalModelConfig) {
        val newStates = mutableMapOf<ModelType, ModelState>()
        config.modelConfigs.forEach { (modelType, modelConfig) ->
            val oldState = _modelStates.value[modelType]
            newStates[modelType] = ModelState(
                modelType = modelType,
                isEnabled = modelConfig.isEnabled,
                isConfigured = modelConfig.isAvailable,
                isAvailable = oldState?.isAvailable ?: false,
                lastCheckTime = oldState?.lastCheckTime ?: 0L
            )
        }
        _modelStates.value = newStates
    }

    /**
     * 配置变更时，更新所有已存在的Provider实例的配置
     */
    private fun updateProvidersConfig(config: GlobalModelConfig) {
        providerCache.forEach { (modelType, provider) ->
            val modelConfig = config.getModelConfig(modelType)
            if (provider is BaseModelProvider) {
                provider.updateConfig(modelConfig)
            }
        }
    }
}

/**
 * 模型状态数据类
 * 表示单个模型的运行时状态信息
 *
 * @property modelType 模型类型
 * @property isEnabled 用户是否启用此模型
 * @property isConfigured 是否完成必要配置（如API Key）
 * @property isAvailable 上次检查时是否实际可用（连通性检查）
 * @property lastCheckTime 上次可用性检查时间戳（毫秒）
 */
data class ModelState(
    val modelType: ModelType,
    val isEnabled: Boolean,
    val isConfigured: Boolean,
    val isAvailable: Boolean,
    val lastCheckTime: Long
) {
    /**
     * 状态总结：用户是否可选择/使用此模型
     * 需要同时满足：已启用 + 已配置
     */
    val isSelectable: Boolean get() = isEnabled && isConfigured

    /**
     * 状态描述文本（用于UI显示）
     */
    val statusDescription: String
        get() = when {
            !isEnabled -> "已禁用"
            !isConfigured -> "待配置"
            isAvailable -> "可用"
            lastCheckTime == 0L -> "未检测"
            else -> "不可用"
        }
}

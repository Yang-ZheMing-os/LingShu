package com.lingshu.agent.core.model.routing

/**
 * 单个模型的配置信息
 * @property modelType 模型类型
 * @property apiKeys API Key列表（支持多Key轮询）
 * @property baseUrl API基础URL（可选，用于自定义端点）
 * @property modelName 具体模型名称（如 deepseek-chat、gpt-4o 等）
 * @property isEnabled 是否启用该模型
 * @property priority 优先级（数值越小优先级越高，用于路由选择）
 * @property temperature 生成温度参数 (0.0 - 2.0)
 * @property maxTokens 最大生成长度
 * @property timeoutMs 请求超时时间（毫秒）
 */
data class ModelConfig(
    val modelType: ModelType,
    val apiKeys: List<String> = emptyList(),
    val baseUrl: String? = null,
    val modelName: String = getDefaultModelName(modelType),
    val isEnabled: Boolean = true,
    val priority: Int = getDefaultPriority(modelType),
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val timeoutMs: Long = 30_000L
) {
    /**
     * 判断模型是否配置了至少一个有效的API Key
     */
    val hasValidApiKey: Boolean get() = apiKeys.any { it.isNotBlank() }

    /**
     * 判断模型是否可用（已启用 + 有有效配置）
     */
    val isAvailable: Boolean get() {
        if (!isEnabled) return false
        // 本地模型不需要API Key
        if (modelType.isLocal) return true
        return hasValidApiKey
    }

    /**
     * 获取当前使用的API Key索引（支持轮询时使用）
     * 这里只保存配置，实际轮询逻辑在Provider中实现
     */
    fun getApiKeyCount(): Int = apiKeys.size

    companion object {
        /**
         * 获取默认模型名称
         */
        private fun getDefaultModelName(modelType: ModelType): String = when (modelType) {
            ModelType.DEEPSEEK -> "deepseek-chat"
            ModelType.GPT4 -> "gpt-4o"
            ModelType.CLAUDE -> "claude-3-5-sonnet-20240620"
            ModelType.GEMINI -> "gemini-1.5-pro"
            ModelType.OLLAMA -> "llama3.1"
            ModelType.GEMMA -> "gemma-4-e2b"
            ModelType.QWEN -> "qwen2.5"
            ModelType.MINICPM_V -> "minicpm-v"
        }

        /**
         * 获取默认优先级
         */
        private fun getDefaultPriority(modelType: ModelType): Int = when (modelType) {
            ModelType.DEEPSEEK -> 1
            ModelType.GPT4 -> 2
            ModelType.CLAUDE -> 3
            ModelType.GEMINI -> 4
            ModelType.OLLAMA -> 10
            ModelType.GEMMA -> 8
            ModelType.QWEN -> 9
            ModelType.MINICPM_V -> 7
        }

        /**
         * 创建默认配置
         */
        fun default(modelType: ModelType): ModelConfig = ModelConfig(
            modelType = modelType
        )
    }

    /**
     * 创建启用状态切换后的新配置
     */
    fun copyWithEnabled(enabled: Boolean): ModelConfig = copy(isEnabled = enabled)

    /**
     * 创建更新API Keys后的新配置
     */
    fun copyWithApiKeys(keys: List<String>): ModelConfig = copy(apiKeys = keys)
}

/**
 * 全局模型设置配置
 * @property defaultChatModel 默认对话模型
 * @property defaultVisionModel 默认视觉模型
 * @property defaultTranscribeModel 默认语音识别模型
 * @property defaultSynthesizeModel 默认语音合成模型
 * @property enableAutoFallback 是否启用自动降级（云端不可用时降级到本地）
 * @property enableApiKeyRotation 是否启用API Key轮询
 * @property modelConfigs 各模型的具体配置
 */
data class GlobalModelConfig(
    val defaultChatModel: ModelType = ModelType.DEEPSEEK,
    val defaultVisionModel: ModelType = ModelType.GPT4,
    val defaultTranscribeModel: ModelType = ModelType.GEMINI,
    val defaultSynthesizeModel: ModelType = ModelType.GEMINI,
    val enableAutoFallback: Boolean = true,
    val enableApiKeyRotation: Boolean = true,
    val modelConfigs: Map<ModelType, ModelConfig> = ModelType.values().associateWith { ModelConfig.default(it) }
) {
    /**
     * 获取指定模型的配置
     */
    fun getModelConfig(modelType: ModelType): ModelConfig =
        modelConfigs[modelType] ?: ModelConfig.default(modelType)

    /**
     * 获取所有已启用的模型配置
     */
    fun getEnabledConfigs(): List<ModelConfig> =
        modelConfigs.values.filter { it.isEnabled }

    /**
     * 获取所有可用的对话模型配置（按优先级排序）
     */
    fun getAvailableChatModels(): List<ModelConfig> =
        getEnabledConfigs()
            .filter { it.isAvailable && it.modelType.supportsChat }
            .sortedBy { it.priority }

    /**
     * 获取所有可用的视觉模型配置（按优先级排序）
     */
    fun getAvailableVisionModels(): List<ModelConfig> =
        getEnabledConfigs()
            .filter { it.isAvailable && it.modelType.supportsVision }
            .sortedBy { it.priority }

    /**
     * 获取所有可用的语音识别模型配置
     */
    fun getAvailableTranscribeModels(): List<ModelConfig> =
        getEnabledConfigs()
            .filter { it.isAvailable && it.modelType.supportsTranscribe }
            .sortedBy { it.priority }

    /**
     * 获取所有可用的语音合成模型配置
     */
    fun getAvailableSynthesizeModels(): List<ModelConfig> =
        getEnabledConfigs()
            .filter { it.isAvailable && it.modelType.supportsSynthesize }
            .sortedBy { it.priority }

    companion object {
        /** 默认全局配置实例 */
        val DEFAULT = GlobalModelConfig()
    }
}

/**
 * 任务类型枚举
 * 用于智能路由时根据任务选择合适的模型
 */
enum class TaskType {
    /** 文本对话任务 */
    CHAT,
    /** 图像理解任务 */
    VISION,
    /** 语音识别任务 */
    TRANSCRIBE,
    /** 语音合成任务 */
    SYNTHESIZE,
    /** 代码生成任务 */
    CODE,
    /** 长文本摘要任务 */
    SUMMARY,
    /** 翻译任务 */
    TRANSLATION
}

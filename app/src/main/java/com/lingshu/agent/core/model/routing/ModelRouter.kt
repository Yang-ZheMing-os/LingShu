package com.lingshu.agent.core.model.routing

import android.graphics.Bitmap
import com.lingshu.agent.core.model.routing.providers.BaseModelProvider
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * 智能模型路由层
 *
 * 核心职责：
 * 1. 根据任务类型自动选择合适的模型
 * 2. 支持用户指定默认模型
 * 3. 云端模型不可用时自动降级到本地模型
 * 4. 多API Key轮询避免限流（实际轮询逻辑在Provider中）
 * 5. 管理模型配置的热更新（即时生效无需重启）
 *
 * 路由策略优先级（从高到低）：
 * 1. 用户显式指定的模型
 * 2. 任务类型匹配 + 用户设置的默认模型
 * 3. 按配置优先级排序的可用模型列表
 * 4. 自动降级（云端 -> 本地）
 */
class ModelRouter @Inject constructor(
    private val modelManager: ModelManager,
    private val configRepository: ModelConfigRepository
) {

    /** 路由事件记录（用于调试和统计） */
    private val _routingEvents = MutableStateFlow<RoutingEvent?>(null)
    val routingEvents: StateFlow<RoutingEvent?> = _routingEvents.asStateFlow()

    /** 当前全局配置（缓存） */
    private var currentConfig: GlobalModelConfig = GlobalModelConfig.DEFAULT

    /**
     * 初始化：订阅配置变更
     */
    suspend fun initialize() {
        currentConfig = configRepository.getConfig()
        configRepository.configFlow.collect { newConfig ->
            currentConfig = newConfig
            applyConfigToProviders(newConfig)
        }
    }

    /**
     * P2：意图分类路由 — 文本对话（集成 IntentClassifier）
     *
     * 路由决策链：IntentClassifier → 根据意图选择 Provider → 降级链
     */
    suspend fun chat(
        messages: List<Message>,
        preferredModel: ModelType? = null,
        taskType: TaskType = TaskType.CHAT,
        hasImage: Boolean = false
    ): Response {
        val startTime = System.currentTimeMillis()

        // 取最后一条用户消息做意图分类
        val lastUserMessage = messages.lastOrNull { it.role == Role.USER }?.content ?: ""
        val intent = IntentClassifier.classify(lastUserMessage, hasImage)

        // 根据意图选择降级链
        val fallbackChain = getFallbackChain(intent)

        // 如果有用户指定的 preferredModel，置为最高优先
        val candidates = if (preferredModel != null) {
            val prefConfig = currentConfig.modelConfigs[preferredModel]
            val chainConfigs = fallbackChain.mapNotNull { currentConfig.modelConfigs[it] }
            if (prefConfig != null && prefConfig.isAvailable) {
                listOf(prefConfig) + chainConfigs.filter { it.modelType != preferredModel }
            } else {
                chainConfigs
            }
        } else {
            fallbackChain.mapNotNull { currentConfig.modelConfigs[it] }.filter { it.isAvailable }
        }

        emitRoutingEvent(
            taskType = taskType,
            candidates = candidates,
            selectedIndex = 0
        )

        var lastResponse: Response? = null
        for ((index, modelConfig) in candidates.withIndex()) {
            val provider = modelManager.getProvider(modelConfig.modelType) ?: continue

            if (index > 0) {
                emitRoutingEvent(
                    taskType = taskType,
                    candidates = candidates,
                    selectedIndex = index,
                    fallbackFrom = candidates[index - 1].modelType,
                    fallbackReason = lastResponse?.errorMessage ?: "上一个模型调用失败"
                )
            }

            try {
                val response = provider.chat(messages)
                val totalLatency = System.currentTimeMillis() - startTime

                if (response.isSuccess || !shouldFallback(response)) {
                    return if (response.latencyMs == 0L) {
                        response.copy(latencyMs = totalLatency)
                    } else {
                        response
                    }
                }
                lastResponse = response
            } catch (e: Exception) {
                lastResponse = Response.error(
                    "调用${modelConfig.modelType.displayName}异常: ${e.message}",
                    modelConfig.modelType
                )
            }
        }

        return lastResponse ?: Response.unavailable("没有可用的模型，请检查模型配置", null)
    }

    /**
     * P2：获取降级链（根据意图类型）
     *
     * Gemma → Qwen → 云端API 三级降级
     */
    private fun getFallbackChain(intent: IntentResult): List<ModelType> {
        return when (intent.intentType) {
            IntentType.RULE -> {
                // 规则匹配：本地快速响应
                listOf(ModelType.GEMMA, ModelType.QWEN, ModelType.DEEPSEEK)
            }
            IntentType.VISION -> {
                // 视觉匹配：MiniCPM-V → GPT4 → Claude
                listOf(ModelType.MINICPM_V, ModelType.GPT4, ModelType.CLAUDE)
            }
            IntentType.CONVERSATION -> {
                // 对话匹配：Gemma → Qwen → 云端
                listOf(ModelType.GEMMA, ModelType.QWEN, ModelType.DEEPSEEK)
            }
        }
    }

    /**
     * 视觉理解（带智能路由）
     */
    suspend fun vision(
        image: Bitmap,
        prompt: String,
        preferredModel: ModelType? = null
    ): String {
        val candidateModels = selectCandidateModels(
            taskType = TaskType.VISION,
            preferredModel = preferredModel,
            defaultModel = currentConfig.defaultVisionModel,
            capabilityCheck = { config ->
                config.modelType.supportsVision && config.isAvailable
            }
        )

        emitRoutingEvent(
            taskType = TaskType.VISION,
            candidates = candidateModels,
            selectedIndex = 0
        )

        var lastError: Exception? = null
        for ((index, modelConfig) in candidateModels.withIndex()) {
            val provider = modelManager.getProvider(modelConfig.modelType)
                ?: continue

            if (index > 0) {
                emitRoutingEvent(
                    taskType = TaskType.VISION,
                    candidates = candidateModels,
                    selectedIndex = index,
                    fallbackFrom = candidateModels[index - 1].modelType,
                    fallbackReason = lastError?.message ?: "上一个模型调用失败"
                )
            }

            try {
                return provider.vision(image, prompt)
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: Exception("没有可用的视觉模型")
    }

    /**
     * 语音识别（带智能路由）
     */
    suspend fun transcribe(
        audio: ByteArray,
        preferredModel: ModelType? = null
    ): String {
        val candidateModels = selectCandidateModels(
            taskType = TaskType.TRANSCRIBE,
            preferredModel = preferredModel,
            defaultModel = currentConfig.defaultTranscribeModel,
            capabilityCheck = { config ->
                config.modelType.supportsTranscribe && config.isAvailable
            }
        )

        emitRoutingEvent(
            taskType = TaskType.TRANSCRIBE,
            candidates = candidateModels,
            selectedIndex = 0
        )

        var lastError: Exception? = null
        for ((index, modelConfig) in candidateModels.withIndex()) {
            val provider = modelManager.getProvider(modelConfig.modelType)
                ?: continue

            if (index > 0) {
                emitRoutingEvent(
                    taskType = TaskType.TRANSCRIBE,
                    candidates = candidateModels,
                    selectedIndex = index,
                    fallbackFrom = candidateModels[index - 1].modelType,
                    fallbackReason = lastError?.message ?: "上一个模型调用失败"
                )
            }

            try {
                return provider.transcribe(audio)
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: Exception("没有可用的语音识别模型")
    }

    /**
     * 语音合成（带智能路由）
     */
    suspend fun synthesize(
        text: String,
        preferredModel: ModelType? = null
    ): ByteArray {
        val candidateModels = selectCandidateModels(
            taskType = TaskType.SYNTHESIZE,
            preferredModel = preferredModel,
            defaultModel = currentConfig.defaultSynthesizeModel,
            capabilityCheck = { config ->
                config.modelType.supportsSynthesize && config.isAvailable
            }
        )

        emitRoutingEvent(
            taskType = TaskType.SYNTHESIZE,
            candidates = candidateModels,
            selectedIndex = 0
        )

        var lastError: Exception? = null
        for ((index, modelConfig) in candidateModels.withIndex()) {
            val provider = modelManager.getProvider(modelConfig.modelType)
                ?: continue

            if (index > 0) {
                emitRoutingEvent(
                    taskType = TaskType.SYNTHESIZE,
                    candidates = candidateModels,
                    selectedIndex = index,
                    fallbackFrom = candidateModels[index - 1].modelType,
                    fallbackReason = lastError?.message ?: "上一个模型调用失败"
                )
            }

            try {
                return provider.synthesize(text)
            } catch (e: Exception) {
                lastError = e
            }
        }

        throw lastError ?: Exception("没有可用的语音合成模型")
    }

    // ==================== 内部方法 ====================

    /**
     * 选择候选模型列表
     *
     * @param taskType 任务类型
     * @param preferredModel 用户偏好模型
     * @param defaultModel 默认模型（用户在设置中指定）
     * @param capabilityCheck 能力检查函数
     * @return 按优先级排序的候选模型配置列表
     */
    private fun selectCandidateModels(
        taskType: TaskType,
        preferredModel: ModelType?,
        defaultModel: ModelType,
        capabilityCheck: (ModelConfig) -> Boolean
    ): List<ModelConfig> {
        val allConfigs = currentConfig.modelConfigs.values
        val result = mutableListOf<ModelConfig>()
        val added = mutableSetOf<ModelType>()

        // 1. 最高优先级：用户显式指定的模型
        preferredModel?.let { modelType ->
            val config = allConfigs.find { it.modelType == modelType }
            if (config != null && capabilityCheck(config)) {
                result.add(config)
                added.add(modelType)
            }
        }

        // 2. 次高优先级：用户设置的默认模型
        val defaultConfig = allConfigs.find { it.modelType == defaultModel }
        if (defaultConfig != null && defaultModel !in added && capabilityCheck(defaultConfig)) {
            result.add(defaultConfig)
            added.add(defaultModel)
        }

        // 3. 中优先级：按优先级排序的云端可用模型
        val cloudAvailable = allConfigs
            .filter { !it.modelType.isLocal && it.modelType !in added && capabilityCheck(it) }
            .sortedBy { it.priority }
        result.addAll(cloudAvailable)
        cloudAvailable.forEach { added.add(it.modelType) }

        // 4. 最低优先级：自动降级到本地模型（如果开启了自动降级）
        if (currentConfig.enableAutoFallback) {
            val localAvailable = allConfigs
                .filter { it.modelType.isLocal && it.modelType !in added && capabilityCheck(it) }
                .sortedBy { it.priority }
            result.addAll(localAvailable)
        }

        return result
    }

    /**
     * 判断是否应该触发降级
     */
    private fun shouldFallback(response: Response): Boolean {
        // 限流、不可用状态需要降级
        return response.isRateLimited ||
                response.status == ResponseStatus.UNAVAILABLE
    }

    /**
     * 发送路由事件
     */
    private fun emitRoutingEvent(
        taskType: TaskType,
        candidates: List<ModelConfig>,
        selectedIndex: Int,
        fallbackFrom: ModelType? = null,
        fallbackReason: String? = null
    ) {
        val event = RoutingEvent(
            timestamp = System.currentTimeMillis(),
            taskType = taskType,
            candidates = candidates.map { it.modelType },
            selectedModel = candidates.getOrNull(selectedIndex)?.modelType,
            fallbackFrom = fallbackFrom,
            fallbackReason = fallbackReason
        )
        _routingEvents.value = event
    }

    /**
     * 将新配置应用到所有Provider（热更新）
     */
    private fun applyConfigToProviders(newConfig: GlobalModelConfig) {
        newConfig.modelConfigs.forEach { (modelType, modelConfig) ->
            val provider = runBlocking { modelManager.getProvider(modelType) }
            if (provider is BaseModelProvider) {
                provider.updateConfig(modelConfig)
            }
        }
    }
}

/**
 * 路由事件数据类
 * 用于记录和调试模型路由决策过程
 */
data class RoutingEvent(
    val timestamp: Long,
    val taskType: TaskType,
    val candidates: List<ModelType>,
    val selectedModel: ModelType?,
    val fallbackFrom: ModelType? = null,
    val fallbackReason: String? = null
)

package com.lingshu.agent.feature.model

import android.graphics.Bitmap
import com.lingshu.agent.core.model.routing.IntentClassifier
import com.lingshu.agent.core.model.routing.IntentType
import com.lingshu.agent.feature.model.providers.DeepSeekProvider
import com.lingshu.agent.feature.model.providers.GPT4VisionProvider
import com.lingshu.agent.feature.model.providers.LocalLlmProvider
import com.lingshu.agent.feature.model.providers.MiniCPMVisionProvider
import com.lingshu.agent.feature.model.providers.OllamaProvider
import com.lingshu.agent.feature.model.providers.SystemTTSProvider
import com.lingshu.agent.feature.model.providers.VoskTranscribeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智能模型路由中心
 *
 * 【模块1：已旁路】ChatViewModel 现直接使用 DeepSeekApi 而非通过本路由。
 * 本类保留供后续模块2（多 Provider 路由）恢复使用。
 *
 * 核心职责：
 * 1. 维护并管理多个 ModelProvider 实例（DeepSeek、Ollama、GPT-4V、Vosk、系统TTS等）
 * 2. 根据任务类型（CHAT / VISION / TRANSCRIBE / SYNTHESIZE）自动选择最优模型
 * 3. 云端模型不可用时自动降级到本地模型（Ollama / Vosk / 系统TTS）
 * 4. 支持 API Key 轮询（具体轮询逻辑在各 Provider 内部实现）
 * 5. 支持手动切换 Provider，通过 currentProviderFlow 通知 UI 变更
 * 6. 响应 ModelSettings 配置热更新，无需重启应用
 *
 * 路由选择优先级（从高到低）：
 * 1. 用户显式指定的 Provider（通过 preferredProviderId 参数）
 * 2. 当前手动锁定的 Provider（switchProvider 锁定）
 * 3. 用户在设置中配置的各场景默认 Provider
 * 4. 按内置优先级排序的云端 Provider（优先质量高的）
 * 5. 自动降级到本地 Provider（云端全部失败且开启自动降级时）
 */
@Singleton
class ModelRouter @Inject constructor(
    private val modelSettings: ModelSettings,
    private val deepSeekProvider: DeepSeekProvider,
    private val ollamaProvider: OllamaProvider,
    private val gpt4VisionProvider: GPT4VisionProvider,
    private val miniCPMVisionProvider: MiniCPMVisionProvider,
    private val voskTranscribeProvider: VoskTranscribeProvider,
    private val systemTTSProvider: SystemTTSProvider,
    private val localLlmProvider: LocalLlmProvider
) {

    companion object {
        /** 云端 Provider 优先级（数字越小优先级越高） */
        private val CLOUD_PRIORITY = mapOf(
            DeepSeekProvider.PROVIDER_ID to 1,
            GPT4VisionProvider.PROVIDER_ID to 2
        )

        /** 本地 Provider 优先级（降级时使用） */
        private val LOCAL_PRIORITY = mapOf(
            LocalLlmProvider.PROVIDER_ID to 0,
            OllamaProvider.PROVIDER_ID to 1,
            VoskTranscribeProvider.PROVIDER_ID to 2,
            SystemTTSProvider.PROVIDER_ID to 3
        )
    }

    /** 协程作用域（应用级生命周期） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 路由操作互斥锁（避免并发路由冲突） */
    private val routerMutex = Mutex()

    // ==================== Provider 注册表 ====================

    /** 所有已注册的 Provider 映射表（providerId → Provider 实例） */
    private val providerRegistry: Map<String, ModelProvider> by lazy {
        mapOf(
            DeepSeekProvider.PROVIDER_ID to deepSeekProvider,
            OllamaProvider.PROVIDER_ID to ollamaProvider,
            GPT4VisionProvider.PROVIDER_ID to gpt4VisionProvider,
            MiniCPMVisionProvider.PROVIDER_ID to miniCPMVisionProvider,
            VoskTranscribeProvider.PROVIDER_ID to voskTranscribeProvider,
            SystemTTSProvider.PROVIDER_ID to systemTTSProvider,
            LocalLlmProvider.PROVIDER_ID to localLlmProvider
        )
    }

    // ==================== 当前手动锁定的 Provider ====================

    /**
     * 用户通过 switchProvider() 手动锁定的 Provider ID（null 表示未锁定，自动路由）
     * 一旦被锁定，路由时优先使用锁定的 Provider（前提是它支持当前任务能力）
     */
    private val _lockedProviderId = MutableStateFlow<String?>(null)

    /**
     * 当前正在使用的 Provider（StateFlow，便于 UI 绑定观察）
     * 每次路由决策后更新，反映实际使用的 Provider
     */
    private val _currentProviderFlow = MutableStateFlow<ModelProvider?>(null)
    val currentProviderFlow: StateFlow<ModelProvider?> = _currentProviderFlow.asStateFlow()

    /**
     * 路由事件记录（用于调试和统计，StateFlow 只保留最近一条）
     */
    private val _lastRoutingEvent = MutableStateFlow<RoutingEvent?>(null)
    val lastRoutingEvent: StateFlow<RoutingEvent?> = _lastRoutingEvent.asStateFlow()

    // ==================== 初始化 ====================

    init {
        // 订阅 ModelSettings 的配置变更（此处为占位，实际可监听具体 Flow）
        // 由于 Provider 直接读取 ModelSettings，热更新已经自然生效
        scope.launch {
            // 启动时设置初始的默认 Provider 为对话默认模型
            val defaultChatId = modelSettings.getDefaultProviderForCapability(ModelCapability.CHAT)
            getProvider(defaultChatId)?.let {
                _currentProviderFlow.value = it
            }
        }
    }

    // ==================== 公共 API：Provider 管理 ====================

    /**
     * 获取指定 ID 的 Provider 实例
     *
     * @param providerId Provider 唯一标识
     * @return Provider 实例，未找到则返回 null
     */
    fun getProvider(providerId: String): ModelProvider? = providerRegistry[providerId]

    /**
     * 获取所有已注册的 Provider 列表
     */
    fun getAllProviders(): List<ModelProvider> = providerRegistry.values.toList()

    /**
     * 获取所有支持指定能力的 Provider ID 列表
     *
     * @param capability 模型能力
     * @return 支持该能力的 Provider 列表（ID 集合）
     */
    fun getProvidersForCapability(capability: ModelCapability): List<ModelProvider> {
        return providerRegistry.values.filter { it.supports(capability) }
    }

    /**
     * 手动切换并锁定当前使用的 Provider
     *
     * 锁定后，路由时会优先尝试该 Provider（如果支持对应能力）。
     * 传 null 表示取消手动锁定，恢复自动路由。
     *
     * @param providerId 要锁定的 Provider ID，null 则解锁
     * @return 是否切换成功（Provider 是否存在且可用）
     */
    fun switchProvider(providerId: String?): Boolean {
        return if (providerId == null) {
            // 解锁，恢复自动路由
            _lockedProviderId.value = null
            true
        } else {
            val provider = getProvider(providerId)
            if (provider != null) {
                _lockedProviderId.value = providerId
                _currentProviderFlow.value = provider
                true
            } else {
                false
            }
        }
    }

    /**
     * 获取当前正在使用的 Provider 实例（最近一次路由选择的）
     */
    fun getCurrentProvider(): ModelProvider? = _currentProviderFlow.value

    /**
     * 检查是否启用了自动降级
     */
    suspend fun isAutoFallbackEnabled(): Boolean = modelSettings.isAutoFallbackEnabled()

    // ==================== 公共 API：路由执行 ====================

    /**
     * 文本对话（智能路由 + IntentClassifier 分类）
     *
     * P2 路由流程：
     * 1. IntentClassifier 分类末条用户消息 → RULE / VISION / CONVERSATION
     * 2. 按意图选择降级链：
     *    - RULE: Gemma → Qwen → DeepSeek
     *    - VISION: MiniCPM-V → GPT4V → DeepSeek
     *    - CONVERSATION: Gemma → Qwen → DeepSeek
     * 3. 顺序尝试降级链中第一个可用的 Provider
     *
     * @param messages 对话消息列表
     * @param preferredProviderId 用户可选的偏好 Provider ID（最高优先级）
     * @return 模型响应结果
     */
    suspend fun chat(
        messages: List<ModelMessage>,
        preferredProviderId: String? = null
    ): ModelResponse {
        // 1. 用户显式指定优先
        if (preferredProviderId != null) {
            return executeRoutedTask(
                capability = ModelCapability.CHAT,
                preferredProviderId = preferredProviderId
            ) { provider -> provider.chat(messages) }
        }

        // 2. IntentClassifier 分类
        val lastUserMsg = messages.lastOrNull { it.role == MessageRole.USER }
        val userText = lastUserMsg?.content ?: ""
        val hasImage = lastUserMsg?.images?.isNotEmpty() == true
        val intentResult = IntentClassifier.classify(userText, hasImage)

        // 3. 按意图生成降级链
        val fallbackChain = getFallbackChain(intentResult.intentType)

        // 4. 顺序尝试降级链
        for (providerId in fallbackChain) {
            val provider = providerRegistry[providerId] ?: continue
            if (!provider.isAvailable()) continue
            if (!provider.supports(ModelCapability.CHAT)) continue

            _currentProviderFlow.value = provider
            emitRoutingEvent(
                capability = ModelCapability.CHAT,
                selectedProviderId = providerId,
                candidates = fallbackChain
            )
            return provider.chat(messages)
        }

        // 5. 全部不可用
        return ModelResponse.unavailable(
            "没有可用的对话模型（意图: ${intentResult.intentType}，降级链: $fallbackChain）",
            currentProviderFlow.value?.providerId ?: "unknown"
        )
    }

    /**
     * 根据意图类型生成降级链
     *
     * RULE / CONVERSATION → Gemma → Qwen → DeepSeek（云端）
     * VISION → MiniCPM-V → GPT4V → DeepSeek（云端）
     */
    private fun getFallbackChain(intentType: IntentType): List<String> {
        return when (intentType) {
            IntentType.RULE -> listOf(
                LocalLlmProvider.PROVIDER_ID,      // Gemma 本地
                OllamaProvider.PROVIDER_ID,         // Qwen (Ollama)
                DeepSeekProvider.PROVIDER_ID        // 云端 DeepSeek
            )
            IntentType.VISION -> listOf(
                MiniCPMVisionProvider.PROVIDER_ID,  // MiniCPM-V 本地
                GPT4VisionProvider.PROVIDER_ID,     // GPT4V 云端
                DeepSeekProvider.PROVIDER_ID        // 云端 DeepSeek
            )
            IntentType.CONVERSATION -> listOf(
                LocalLlmProvider.PROVIDER_ID,       // Gemma 本地
                OllamaProvider.PROVIDER_ID,          // Qwen (Ollama)
                DeepSeekProvider.PROVIDER_ID         // 云端 DeepSeek
            )
        }
    }

    /**
     * 文本对话（流式响应 + 智能路由）
     *
     * 路由时会选择第一个可用的 Provider 并执行其 chatStream。
     * 流式时的降级策略：仅路由阶段尝试多个 Provider，流建立后不再降级。
     */
    suspend fun chatStream(
        messages: List<ModelMessage>,
        preferredProviderId: String? = null
    ): Flow<String> {
        val provider = selectFirstAvailableProvider(
            capability = ModelCapability.CHAT,
            preferredProviderId = preferredProviderId
        ) ?: throw IllegalStateException("没有可用的对话模型，请检查模型配置")

        _currentProviderFlow.value = provider
        return provider.chatStream(messages)
    }

    /**
     * 视觉理解（智能路由）
     *
     * @param image 图片 Bitmap
     * @param prompt 提示词
     * @param preferredProviderId 用户可选的偏好 Provider ID
     * @return 视觉理解结果文本
     */
    suspend fun vision(
        image: Bitmap,
        prompt: String,
        preferredProviderId: String? = null
    ): String {
        val provider = selectFirstAvailableProvider(
            capability = ModelCapability.VISION,
            preferredProviderId = preferredProviderId
        ) ?: throw IllegalStateException("没有可用的视觉模型，请检查模型配置")

        _currentProviderFlow.value = provider
        emitRoutingEvent(
            capability = ModelCapability.VISION,
            selectedProviderId = provider.providerId,
            candidates = getCandidateProviderIds(ModelCapability.VISION)
        )

        return provider.vision(image, prompt)
    }

    /**
     * 语音识别（智能路由）
     */
    suspend fun transcribe(
        audio: ByteArray,
        preferredProviderId: String? = null
    ): String {
        val provider = selectFirstAvailableProvider(
            capability = ModelCapability.TRANSCRIBE,
            preferredProviderId = preferredProviderId
        ) ?: throw IllegalStateException("没有可用的语音识别模型，请检查模型配置")

        _currentProviderFlow.value = provider
        emitRoutingEvent(
            capability = ModelCapability.TRANSCRIBE,
            selectedProviderId = provider.providerId,
            candidates = getCandidateProviderIds(ModelCapability.TRANSCRIBE)
        )

        return provider.transcribe(audio)
    }

    /**
     * 语音合成（智能路由）
     */
    suspend fun synthesize(
        text: String,
        preferredProviderId: String? = null
    ): ByteArray {
        val provider = selectFirstAvailableProvider(
            capability = ModelCapability.SYNTHESIZE,
            preferredProviderId = preferredProviderId
        ) ?: throw IllegalStateException("没有可用的语音合成模型，请检查模型配置")

        _currentProviderFlow.value = provider
        emitRoutingEvent(
            capability = ModelCapability.SYNTHESIZE,
            selectedProviderId = provider.providerId,
            candidates = getCandidateProviderIds(ModelCapability.SYNTHESIZE)
        )

        return provider.synthesize(text)
    }

    // ==================== 核心路由算法 ====================

    /**
     * 执行带自动降级的路由任务（用于 chat 这种有 ModelResponse 返回的）
     *
     * 遍历候选 Provider，依次尝试调用，直到遇到：
     * - 成功响应 → 直接返回
     * - 业务错误（ERROR）→ 直接返回（不降级，因为可能是请求格式问题）
     * - 限流 / 不可用 → 尝试下一个 Provider（自动降级）
     * - 抛出异常 → 记录并尝试下一个 Provider
     */
    private suspend fun executeRoutedTask(
        capability: ModelCapability,
        preferredProviderId: String?,
        task: suspend (ModelProvider) -> ModelResponse
    ): ModelResponse {
        val startTime = System.currentTimeMillis()
        val candidates = buildCandidateProviderList(capability, preferredProviderId)

        if (candidates.isEmpty()) {
            return ModelResponse.unavailable(
                "没有可用的${getCapabilityName(capability)}模型，请检查模型配置",
                null
            )
        }

        var lastResponse: ModelResponse? = null
        var lastException: Exception? = null

        for ((index, provider) in candidates.withIndex()) {
            // 记录路由事件
            val fallbackFrom = if (index > 0) candidates[index - 1].providerId else null
            val fallbackReason = when {
                index == 0 -> null
                lastResponse != null -> lastResponse.errorMessage
                lastException != null -> lastException.message
                else -> "上一个模型调用失败"
            }

            emitRoutingEvent(
                capability = capability,
                selectedProviderId = provider.providerId,
                candidates = candidates.map { it.providerId },
                fallbackFromProviderId = fallbackFrom,
                fallbackReason = fallbackReason
            )

            _currentProviderFlow.value = provider

            try {
                val response = task(provider)
                val totalLatency = System.currentTimeMillis() - startTime

                // 成功 → 直接返回
                if (response.isSuccess) {
                    return if (response.latencyMs == 0L) {
                        response.copy(latencyMs = totalLatency)
                    } else {
                        response
                    }
                }

                // 业务错误（非限流/不可用）→ 不降级，直接返回
                if (response.isError) {
                    return response
                }

                // 限流或不可用 → 继续下一个候选（降级）
                lastResponse = response
                continue

            } catch (e: Exception) {
                // 调用异常 → 降级到下一个
                lastException = e
                lastResponse = ModelResponse.error(
                    "调用${provider.providerName}异常：${e.message}",
                    provider.providerId
                )
                continue
            }
        }

        // 所有候选都失败
        return lastResponse ?: ModelResponse.unavailable(
            "所有${getCapabilityName(capability)}模型均不可用",
            null
        )
    }

    /**
     * 选择第一个可用的 Provider（用于 vision/transcribe/synthesize 非 ModelResponse 返回的场景）
     */
    private suspend fun selectFirstAvailableProvider(
        capability: ModelCapability,
        preferredProviderId: String?
    ): ModelProvider? {
        val candidates = buildCandidateProviderList(capability, preferredProviderId)
        for (provider in candidates) {
            if (provider.isAvailable()) {
                return provider
            }
        }
        return null
    }

    /**
     * 构建候选 Provider 列表（按优先级排序，包含自动降级策略）
     *
     * 顺序：
     * 1. 偏好的 Provider（用户显式传参）
     * 2. 手动锁定的 Provider（switchProvider 锁定）
     * 3. 用户设置的默认 Provider
     * 4. 所有支持该能力的云端 Provider（按优先级排序）
     * 5. 所有支持该能力的本地 Provider（自动降级开启时才加入）
     */
    private suspend fun buildCandidateProviderList(
        capability: ModelCapability,
        preferredProviderId: String?
    ): List<ModelProvider> {
        val result = mutableListOf<ModelProvider>()
        val added = mutableSetOf<String>()
        val autoFallbackEnabled = isAutoFallbackEnabled()

        // 1. 用户偏好 Provider（最高优先级）
        preferredProviderId?.let { id ->
            getProvider(id)?.let { p ->
                if (p.supports(capability)) {
                    result.add(p)
                    added.add(id)
                }
            }
        }

        // 2. 手动锁定的 Provider
        _lockedProviderId.value?.let { id ->
            if (id !in added) {
                getProvider(id)?.let { p ->
                    if (p.supports(capability)) {
                        result.add(p)
                        added.add(id)
                    }
                }
            }
        }

        // 3. 用户设置的默认 Provider
        val defaultForCapability = modelSettings.getDefaultProviderForCapability(capability)
        if (defaultForCapability !in added) {
            getProvider(defaultForCapability)?.let { p ->
                if (p.supports(capability)) {
                    result.add(p)
                    added.add(defaultForCapability)
                }
            }
        }

        // 4. 云端 Provider（按内置优先级）
        val sortedCloud = providerRegistry.values
            .filter {
                it.supports(capability) &&
                        it.providerId !in added &&
                        !isLocalProvider(it.providerId)
            }
            .sortedBy { CLOUD_PRIORITY[it.providerId] ?: Int.MAX_VALUE }
        result.addAll(sortedCloud)
        sortedCloud.forEach { added.add(it.providerId) }

        // 5. 本地 Provider（仅当开启自动降级时追加到末尾）
        if (autoFallbackEnabled) {
            val sortedLocal = providerRegistry.values
                .filter {
                    it.supports(capability) &&
                            it.providerId !in added &&
                            isLocalProvider(it.providerId)
                }
                .sortedBy { LOCAL_PRIORITY[it.providerId] ?: Int.MAX_VALUE }
            result.addAll(sortedLocal)
        }

        return result
    }

    /**
     * 获取候选 Provider ID 列表（用于路由事件记录）
     */
    private suspend fun getCandidateProviderIds(
        capability: ModelCapability,
        preferredProviderId: String? = null
    ): List<String> {
        return buildCandidateProviderList(capability, preferredProviderId).map { it.providerId }
    }

    // ==================== 辅助方法 ====================

    /** 判断一个 Provider 是否为本地模型（无需网络） */
    private fun isLocalProvider(providerId: String): Boolean {
        return when (providerId) {
            LocalLlmProvider.PROVIDER_ID,
            OllamaProvider.PROVIDER_ID,
            VoskTranscribeProvider.PROVIDER_ID,
            SystemTTSProvider.PROVIDER_ID -> true
            else -> false
        }
    }

    /** 获取能力中文名（用于错误提示） */
    private fun getCapabilityName(capability: ModelCapability): String = when (capability) {
        ModelCapability.CHAT -> "对话"
        ModelCapability.VISION -> "视觉"
        ModelCapability.TRANSCRIBE -> "语音识别"
        ModelCapability.SYNTHESIZE -> "语音合成"
    }

    /** 发送路由事件（更新 lastRoutingEvent Flow） */
    private fun emitRoutingEvent(
        capability: ModelCapability,
        selectedProviderId: String,
        candidates: List<String>,
        fallbackFromProviderId: String? = null,
        fallbackReason: String? = null
    ) {
        _lastRoutingEvent.value = RoutingEvent(
            timestamp = System.currentTimeMillis(),
            capability = capability,
            selectedProviderId = selectedProviderId,
            candidateProviderIds = candidates,
            fallbackFromProviderId = fallbackFromProviderId,
            fallbackReason = fallbackReason
        )
    }

    /**
     * 释放所有 Provider 资源
     * 应用退出时调用
     */
    fun releaseAll() {
        providerRegistry.values.forEach {
            runCatching { it.release() }
        }
    }
}

/**
 * 路由决策事件
 *
 * 记录每一次路由选择的详细信息，用于：
 * - 调试路由策略是否正常工作
 * - 收集用户使用习惯（统计各 Provider 使用频率）
 * - 排查降级链路问题
 */
data class RoutingEvent(
    val timestamp: Long,
    val capability: ModelCapability,
    val selectedProviderId: String,
    val candidateProviderIds: List<String>,
    val fallbackFromProviderId: String? = null,
    val fallbackReason: String? = null
) {
    /** 是否是由上一个 Provider 降级而来 */
    val isFallback: Boolean get() = fallbackFromProviderId != null
}

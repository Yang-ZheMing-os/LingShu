package com.lingshu.core.data.llm

import com.lingshu.core.common.di.IoDispatcher
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmRouter @Inject constructor(
    private val deepSeekProvider: DeepSeekProvider,
    private val ollamaProvider: OllamaProvider,
    private val geminiProvider: GeminiProvider,
    private val openAiProvider: OpenAiProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    private val moduleTag = "LlmRouter"

    private val providerMap: Map<ModelProviderType, ILlmProvider> by lazy {
        mapOf(
            ModelProviderType.DEEPSEEK to deepSeekProvider,
            ModelProviderType.OLLAMA to ollamaProvider,
            ModelProviderType.GEMINI to geminiProvider,
            ModelProviderType.OPENAI to openAiProvider,
            ModelProviderType.QWEN to openAiProvider
        )
    }

    private val defaultFallbackChain: List<ModelProviderType> = listOf(
        ModelProviderType.OLLAMA,
        ModelProviderType.DEEPSEEK,
        ModelProviderType.OPENAI,
        ModelProviderType.QWEN,
        ModelProviderType.GEMINI
    )

    fun getProvider(type: ModelProviderType): ILlmProvider? {
        val provider = providerMap[type]
        LingShuLog.d(moduleTag, "getProvider: type=$type | found=${provider != null}")
        return provider
    }

    fun listAvailableProviders(configs: Map<ModelProviderType, LlmConfig>): List<ModelProviderType> {
        val available = mutableListOf<ModelProviderType>()
        for ((type, config) in configs) {
            val provider = providerMap[type]
            if (provider != null && provider.isAvailable(config)) {
                available.add(type)
            }
        }
        LingShuLog.d(moduleTag, "listAvailableProviders: totalRegistered=${providerMap.size} | " +
                "checkedConfigs=${configs.size} | available=${available.joinToString { it.name }}")
        return available
    }

    private fun buildFallbackChain(
        preferred: ModelProviderType,
        perProviderConfigs: Map<ModelProviderType, LlmConfig>
    ): List<Pair<ILlmProvider, LlmConfig>> {
        val chain = mutableListOf<Pair<ILlmProvider, LlmConfig>>()
        val used = mutableSetOf<ModelProviderType>()

        val preferredProvider = providerMap[preferred]
        val preferredConfig = perProviderConfigs[preferred]
        if (preferredProvider != null && preferredConfig != null) {
            if (preferredProvider.isAvailable(preferredConfig)) {
                chain.add(preferredProvider to preferredConfig)
                LingShuLog.d(moduleTag, "fallbackChain: primary=$preferred (available=true)")
            } else {
                LingShuLog.w(moduleTag, "fallbackChain: primary=$preferred NOT available, will skip")
            }
            used.add(preferred)
        }

        for (fallbackType in defaultFallbackChain) {
            if (fallbackType in used) continue
            val provider = providerMap[fallbackType]
            val config = perProviderConfigs[fallbackType]
            if (provider != null && config != null && provider.isAvailable(config)) {
                chain.add(provider to config)
                used.add(fallbackType)
            }
        }

        for ((type, provider) in providerMap) {
            if (type in used) continue
            val config = perProviderConfigs[type]
            if (config != null && provider.isAvailable(config)) {
                chain.add(provider to config)
                used.add(type)
            }
        }

        LingShuLog.i(moduleTag, "fallbackChain built | preferred=$preferred | chainSize=${chain.size} | " +
                "order=${chain.joinToString(" -> ") { "${it.first.type}(${it.second.modelName})" }}")

        return chain
    }

    suspend fun chat(
        messages: List<ChatMessage>,
        primaryConfig: LlmConfig,
        fallbackConfigs: Map<ModelProviderType, LlmConfig> = emptyMap(),
        traceId: String = ""
    ): Result<String> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val primary = primaryConfig.provider

        LingShuLog.i(moduleTag, "[$traceId] chat ROUTE start | preferred=$primary | " +
                "model=${primaryConfig.modelName} | msgCount=${messages.size} | " +
                "fallbackConfigsProvided=${fallbackConfigs.size}")

        val allConfigs = fallbackConfigs.toMutableMap()
        allConfigs[primary] = primaryConfig
        val chain = buildFallbackChain(primary, allConfigs)

        if (chain.isEmpty()) {
            LingShuLog.e(moduleTag, "[$traceId] chat ROUTE abort: NO providers available in chain")
            return@withContext Result.error(
                code = ErrorCodes.API_KEY_INVALID,
                message = "No available LLM provider. Please check API key or model configuration."
            )
        }

        var lastError: Result.Error? = null
        var attemptIndex = 0

        for ((provider, config) in chain) {
            attemptIndex++
            val isPrimary = attemptIndex == 1

            if (!isPrimary) {
                LingShuLog.w(moduleTag, "[$traceId] chat FALLBACK | attempt=$attemptIndex/${chain.size} | " +
                        "switching ${lastError?.let { "from ${chain[attemptIndex - 2].first.type}" } ?: ""} " +
                        "-> to ${provider.type} (model=${config.modelName}) | " +
                        "lastError=${lastError?.code}:${lastError?.message?.take(80)}")
            }

            LingShuLog.i(moduleTag, "[$traceId] chat DISPATCH attempt=$attemptIndex | " +
                    "provider=${provider.type} | model=${config.modelName} | baseUrl=${config.baseUrl}")

            val callTraceId = if (traceId.isEmpty()) traceId else "${traceId}-a$attemptIndex"

            when (val result = provider.chat(messages, config, callTraceId)) {
                is Result.Success -> {
                    val elapsed = System.currentTimeMillis() - startTime
                    val switched = !isPrimary
                    LingShuLog.i(moduleTag, "[$traceId] chat ROUTE success | attempts=$attemptIndex | " +
                            "finalProvider=${provider.type} | switched=$switched | elapsedMs=$elapsed")
                    return@withContext result
                }
                is Result.Error -> {
                    lastError = result
                    LingShuLog.w(moduleTag, "[$traceId] chat provider ${provider.type} failed | " +
                            "attempt=$attemptIndex | code=${result.code} | msg=${result.message.take(120)}")
                    if (isLastAttempt(attemptIndex, chain.size)) {
                        break
                    }
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        LingShuLog.e(moduleTag, "[$traceId] chat ROUTE failed ALL attempts | attempts=$attemptIndex | " +
                "chainSize=${chain.size} | lastCode=${lastError?.code} | elapsedMs=$elapsed")

        return@withContext lastError ?: Result.error(
            code = ErrorCodes.UNKNOWN_ERROR,
            message = "All LLM providers failed without specific error."
        )
    }

    suspend fun chatStream(
        messages: List<ChatMessage>,
        primaryConfig: LlmConfig,
        onToken: (String) -> Unit,
        fallbackConfigs: Map<ModelProviderType, LlmConfig> = emptyMap(),
        traceId: String = ""
    ): Result<String> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val primary = primaryConfig.provider

        LingShuLog.i(moduleTag, "[$traceId] chatStream ROUTE start | preferred=$primary | " +
                "model=${primaryConfig.modelName} | msgCount=${messages.size}")

        val allConfigs = fallbackConfigs.toMutableMap()
        allConfigs[primary] = primaryConfig
        val chain = buildFallbackChain(primary, allConfigs)

        if (chain.isEmpty()) {
            LingShuLog.e(moduleTag, "[$traceId] chatStream ROUTE abort: NO providers available")
            return@withContext Result.error(
                code = ErrorCodes.API_KEY_INVALID,
                message = "No available LLM provider for streaming."
            )
        }

        var lastError: Result.Error? = null
        var attemptIndex = 0
        var streamStarted = false

        for ((provider, config) in chain) {
            attemptIndex++
            val isPrimary = attemptIndex == 1

            if (!isPrimary && !streamStarted) {
                LingShuLog.w(moduleTag, "[$traceId] chatStream FALLBACK | attempt=$attemptIndex/${chain.size} | " +
                        "switch -> ${provider.type} (model=${config.modelName})")
            }

            LingShuLog.i(moduleTag, "[$traceId] chatStream DISPATCH attempt=$attemptIndex | " +
                    "provider=${provider.type} | model=${config.modelName}")

            val callTraceId = if (traceId.isEmpty()) traceId else "${traceId}-a$attemptIndex"
            val wrappedOnToken: (String) -> Unit = { token ->
                streamStarted = true
                onToken(token)
            }

            when (val result = provider.chatStream(messages, config, wrappedOnToken, callTraceId)) {
                is Result.Success -> {
                    val elapsed = System.currentTimeMillis() - startTime
                    val switched = !isPrimary
                    LingShuLog.i(moduleTag, "[$traceId] chatStream ROUTE success | attempts=$attemptIndex | " +
                            "finalProvider=${provider.type} | switched=$switched | elapsedMs=$elapsed")
                    return@withContext result
                }
                is Result.Error -> {
                    lastError = result
                    LingShuLog.w(moduleTag, "[$traceId] chatStream provider ${provider.type} failed | " +
                            "attempt=$attemptIndex | code=${result.code} | streamStarted=$streamStarted")
                    if (streamStarted) {
                        LingShuLog.w(moduleTag, "[$traceId] stream already started, cannot fallback further")
                        break
                    }
                    if (isLastAttempt(attemptIndex, chain.size)) break
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        LingShuLog.e(moduleTag, "[$traceId] chatStream ROUTE failed | attempts=$attemptIndex | " +
                "chainSize=${chain.size} | elapsedMs=$elapsed")

        return@withContext lastError ?: Result.error(
            code = ErrorCodes.UNKNOWN_ERROR,
            message = "All LLM streaming providers failed."
        )
    }

    suspend fun embeddings(
        texts: List<String>,
        primaryConfig: LlmConfig,
        fallbackConfigs: Map<ModelProviderType, LlmConfig> = emptyMap(),
        traceId: String = ""
    ): Result<List<List<Float>>> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val primary = primaryConfig.provider

        LingShuLog.i(moduleTag, "[$traceId] embeddings ROUTE start | preferred=$primary | " +
                "model=${primaryConfig.modelName} | textCount=${texts.size}")

        val allConfigs = fallbackConfigs.toMutableMap()
        allConfigs[primary] = primaryConfig
        val chain = buildFallbackChain(primary, allConfigs)

        if (chain.isEmpty()) {
            LingShuLog.e(moduleTag, "[$traceId] embeddings ROUTE abort: NO providers available")
            return@withContext Result.error(
                code = ErrorCodes.API_KEY_INVALID,
                message = "No available LLM provider for embeddings."
            )
        }

        var lastError: Result.Error? = null
        var attemptIndex = 0

        for ((provider, config) in chain) {
            attemptIndex++
            val isPrimary = attemptIndex == 1

            if (!isPrimary) {
                LingShuLog.w(moduleTag, "[$traceId] embeddings FALLBACK | attempt=$attemptIndex/${chain.size} | " +
                        "switch -> ${provider.type}")
            }

            LingShuLog.i(moduleTag, "[$traceId] embeddings DISPATCH attempt=$attemptIndex | " +
                    "provider=${provider.type} | model=${config.modelName}")

            val callTraceId = if (traceId.isEmpty()) traceId else "${traceId}-a$attemptIndex"

            when (val result = provider.embeddings(texts, config, callTraceId)) {
                is Result.Success -> {
                    val elapsed = System.currentTimeMillis() - startTime
                    val switched = !isPrimary
                    LingShuLog.i(moduleTag, "[$traceId] embeddings ROUTE success | attempts=$attemptIndex | " +
                            "finalProvider=${provider.type} | switched=$switched | elapsedMs=$elapsed")
                    return@withContext result
                }
                is Result.Error -> {
                    lastError = result
                    LingShuLog.w(moduleTag, "[$traceId] embeddings provider ${provider.type} failed | " +
                            "attempt=$attemptIndex | code=${result.code}")
                    if (isLastAttempt(attemptIndex, chain.size)) break
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        LingShuLog.e(moduleTag, "[$traceId] embeddings ROUTE failed | attempts=$attemptIndex | " +
                "chainSize=${chain.size} | elapsedMs=$elapsed")

        return@withContext lastError ?: Result.error(
            code = ErrorCodes.UNKNOWN_ERROR,
            message = "All LLM embeddings providers failed."
        )
    }

    fun isAnyProviderAvailable(configs: Map<ModelProviderType, LlmConfig>): Boolean {
        for ((type, config) in configs) {
            val provider = providerMap[type]
            if (provider != null && provider.isAvailable(config)) {
                return true
            }
        }
        return false
    }

    private fun isLastAttempt(current: Int, total: Int): Boolean = current >= total
}

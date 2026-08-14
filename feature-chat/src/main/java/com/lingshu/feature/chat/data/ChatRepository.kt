package com.lingshu.feature.chat.data

import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.data.database.MessageDao
import com.lingshu.core.data.database.MessageEntity
import com.lingshu.core.data.datastore.AppPreferences
import com.lingshu.core.data.llm.LlmConfig
import com.lingshu.core.data.llm.LlmRouter
import com.lingshu.core.data.llm.ModelProviderType
import com.lingshu.feature.chat.data.prompt.IPromptAssembler
import com.lingshu.feature.chat.data.prompt.PromptInjector
import com.lingshu.core.common.event.IAppEventBus
import com.lingshu.core.common.event.AppEvent
import com.lingshu.core.common.event.IChatRepository
import com.lingshu.core.common.event.Message
import com.lingshu.feature.memory.domain.IMemoryService
import com.lingshu.feature.persona.domain.IPersonaService
import com.lingshu.feature.rag.domain.IRagService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val appPreferences: AppPreferences,
    private val promptAssembler: IPromptAssembler,
    private val promptInjector: PromptInjector,
    private val llmRouter: LlmRouter,
    private val llmConfigStore: com.lingshu.core.data.llm.LlmConfigStore,
    private val eventBus: IAppEventBus,
    @Suppress("unused") private val ragService: IRagService,
    private val memoryServiceLazy: Provider<IMemoryService>,
    private val personaServiceLazy: Provider<IPersonaService>
) : IChatRepository {

    companion object {
        private const val TAG = "ChatRepository"
        private const val MAX_MESSAGES = 1000
        private const val FALLBACK_REPLY = "当前模型不可用，请稍后再试或更换模型"
        private val RETRY_DELAYS = listOf(1000L, 2000L, 4000L)
    }

    override fun getMessages(): Flow<List<Message>> {
        return messageDao.getRecentMessages(MAX_MESSAGES)
            .map { entities ->
                entities.map { it.toDomain() }.reversed()
            }
    }

    override suspend fun sendMessage(content: String): Result<Message> {
        val traceId = generateTraceId()
        val tracePrefix = "[$traceId] "

        LingShuLog.i(TAG, "${tracePrefix}sendMessage start, contentLength=${content.length}")

        return try {
            val userMessage = MessageEntity(
                content = content,
                isUser = true
            )
            messageDao.insertMessage(userMessage)

            val history = getMessages().first()

            val assemblyStartTime = System.currentTimeMillis()
            val promptAssembly = promptAssembler.assemble(
                userInput = content,
                history = history,
                traceId = traceId
            )
            val assemblyCost = System.currentTimeMillis() - assemblyStartTime

            LingShuLog.i(
                TAG,
                "${tracePrefix}PromptAssembly完成, cost=${assemblyCost}ms, " +
                        "systemPromptBytes=${promptAssembly.systemPrompt.toByteArray().size}, " +
                        "meta=${promptAssembly.injectionMeta}"
            )

            val llmMessages = promptInjector.inject(promptAssembly, traceId)
            val coreLlmMessages = llmMessages.map {
                com.lingshu.core.data.llm.ChatMessage(
                    role = it.role,
                    content = it.content
                )
            }

            val llmConfig = buildLlmConfig()

            val llmCallStart = System.currentTimeMillis()
            val llmResult = try {
                callLlmWithRetry(coreLlmMessages, llmConfig, traceId)
            } catch (e: Exception) {
                LingShuLog.e(TAG, "${tracePrefix}LLM调用异常，触发fallback", e)
                null
            }
            val llmCallCostMs = System.currentTimeMillis() - llmCallStart

            val (aiReply, isFallback) = when (llmResult) {
                is Result.Success -> {
                    Pair(llmResult.data, false)
                }
                is Result.Error, null -> {
                    LingShuLog.w(TAG, "${tracePrefix}LLM不可用或失败，使用fallback回复")
                    Pair(FALLBACK_REPLY, true)
                }
            }

            val replyChars = aiReply.length

            val aiMessage = MessageEntity(
                content = aiReply,
                isUser = false
            )
            messageDao.insertMessage(aiMessage)
            cleanupOldMessages()

            postProcessDialogue(
                traceId = traceId,
                userInput = content,
                aiResponse = aiReply,
                promptAssembly = promptAssembly,
                llmCallCostMs = llmCallCostMs,
                replyChars = replyChars,
                isFallback = isFallback
            )

            Result.Success(aiMessage.toDomain())

        } catch (e: Exception) {
            LingShuLog.e(TAG, "${tracePrefix}发送消息失败", e)
            Result.Error(
                code = mapErrorToCode(e),
                message = e.message ?: "Unknown error",
                cause = e
            )
        }
    }

    override suspend fun sendMessageStream(
        content: String,
        onToken: (String) -> Unit
    ): Result<Message> {
        val traceId = generateTraceId()
        val tracePrefix = "[$traceId] "

        LingShuLog.i(TAG, "${tracePrefix}sendMessageStream start, contentLength=${content.length}")

        return try {
            val userMessage = MessageEntity(
                content = content,
                isUser = true
            )
            messageDao.insertMessage(userMessage)

            val history = getMessages().first()

            val assemblyStartTime = System.currentTimeMillis()
            val promptAssembly = promptAssembler.assemble(
                userInput = content,
                history = history,
                traceId = traceId
            )
            val assemblyCost = System.currentTimeMillis() - assemblyStartTime

            LingShuLog.i(
                TAG,
                "${tracePrefix}PromptAssembly完成(stream), cost=${assemblyCost}ms, " +
                        "systemPromptBytes=${promptAssembly.systemPrompt.toByteArray().size}, " +
                        "meta=${promptAssembly.injectionMeta}"
            )

            val llmMessages = promptInjector.inject(promptAssembly, traceId)
            val coreLlmMessages = llmMessages.map {
                com.lingshu.core.data.llm.ChatMessage(
                    role = it.role,
                    content = it.content
                )
            }

            val llmConfig = buildLlmConfig()

            // 外层累加 token，防止流式中断时丢失已输出内容
            val accumulated = StringBuilder()

            val llmCallStart = System.currentTimeMillis()
            val llmResult = try {
                llmRouter.chatStream(
                    messages = coreLlmMessages,
                    primaryConfig = llmConfig,
                    onToken = { token ->
                        accumulated.append(token)
                        onToken(token)
                    },
                    fallbackConfigs = llmConfigStore.getAllConfigs(),
                    traceId = traceId
                )
            } catch (e: Exception) {
                LingShuLog.e(TAG, "${tracePrefix}LLM流式调用异常，触发fallback", e)
                null
            }
            val llmCallCostMs = System.currentTimeMillis() - llmCallStart

            val (aiReply, isFallback) = when (llmResult) {
                is Result.Success -> {
                    Pair(llmResult.data, false)
                }
                is Result.Error, null -> {
                    if (accumulated.isNotEmpty()) {
                        LingShuLog.w(
                            TAG,
                            "${tracePrefix}流式中断，保留已输出 ${accumulated.length} 字符"
                        )
                        Pair(accumulated.toString(), false)
                    } else {
                        LingShuLog.w(TAG, "${tracePrefix}LLM不可用或失败，使用fallback回复")
                        Pair(FALLBACK_REPLY, true)
                    }
                }
            }

            val aiMessage = MessageEntity(
                content = aiReply,
                isUser = false
            )
            messageDao.insertMessage(aiMessage)
            cleanupOldMessages()

            postProcessDialogue(
                traceId = traceId,
                userInput = content,
                aiResponse = aiReply,
                promptAssembly = promptAssembly,
                llmCallCostMs = llmCallCostMs,
                replyChars = aiReply.length,
                isFallback = isFallback
            )

            Result.Success(aiMessage.toDomain())

        } catch (e: Exception) {
            LingShuLog.e(TAG, "${tracePrefix}流式发送消息失败", e)
            Result.Error(
                code = mapErrorToCode(e),
                message = e.message ?: "Unknown error",
                cause = e
            )
        }
    }

    override suspend fun clearMessages() {

        messageDao.deleteAllMessages()
    }

    override suspend fun rewriteLastAssistantMessage(newContent: String) {
        val id = messageDao.getLastAssistantMessageId()
        if (id == null) {
            LingShuLog.w(TAG, "rewriteLastAssistantMessage: 未找到上一条 AI 消息，跳过")
            return
        }
        messageDao.updateMessageContent(id, newContent)
        LingShuLog.i(TAG, "已将最后一条 AI 消息重写为: ${newContent.take(40)} (id=$id)")
    }

    private suspend fun callLlmWithRetry(
        messages: List<com.lingshu.core.data.llm.ChatMessage>,
        config: LlmConfig,
        traceId: String
    ): Result<String> {
        val tracePrefix = "[$traceId] "
        var lastResult: Result<String>? = null
        val totalStart = System.currentTimeMillis()

        for (i in RETRY_DELAYS.indices) {
            val attemptStartTime = System.currentTimeMillis()
            val provider = llmRouter.getProvider(config.provider)

            if (provider == null || !provider.isAvailable(config)) {
                LingShuLog.w(
                    TAG,
                    "${tracePrefix}LLM Provider不可用, provider=${config.provider}, attempt=${i + 1}/${RETRY_DELAYS.size}"
                )
                lastResult = Result.Error(
                    code = ErrorCodes.SERVER_NO_RESPONSE,
                    message = "LLM Provider not available"
                )
                if (i < RETRY_DELAYS.size - 1) {
                    kotlinx.coroutines.delay(RETRY_DELAYS[i])
                }
                continue
            }

            val callResult = try {
                provider.chat(messages, config, traceId)
            } catch (e: Exception) {
                LingShuLog.e(TAG, "${tracePrefix}provider.chat抛出异常, attempt=${i + 1}", e)
                Result.Error(
                    code = mapErrorToCode(e),
                    message = e.message ?: "Chat failed",
                    cause = e
                )
            }

            val attemptCost = System.currentTimeMillis() - attemptStartTime

            when (callResult) {
                is Result.Success<*> -> {
                    val totalCost = System.currentTimeMillis() - totalStart
                    LingShuLog.i(
                        TAG,
                        "${tracePrefix}LLM调用成功, attempt=${i + 1}, " +
                                "replyLength=${callResult.data?.toString()?.length ?: 0}, " +
                                "attemptCost=${attemptCost}ms, totalCost=${totalCost}ms"
                    )
                    return callResult
                }
                is Result.Error -> {
                    lastResult = callResult
                    LingShuLog.w(
                        TAG,
                        "${tracePrefix}LLM调用失败, attempt=${i + 1}/${RETRY_DELAYS.size}, " +
                                "code=${callResult.code}, msg=${callResult.message}, cost=${attemptCost}ms"
                    )
                    if (i < RETRY_DELAYS.size - 1 && shouldRetry(callResult.code)) {
                        kotlinx.coroutines.delay(RETRY_DELAYS[i])
                    } else {
                        break
                    }
                }
            }
        }

        return lastResult ?: Result.Error(
            code = ErrorCodes.UNKNOWN_ERROR,
            message = "All attempts failed"
        )
    }

    private suspend fun postProcessDialogue(
        traceId: String,
        userInput: String,
        aiResponse: String,
        promptAssembly: com.lingshu.feature.chat.data.prompt.PromptAssembly,
        llmCallCostMs: Long,
        replyChars: Int,
        isFallback: Boolean
    ) {
        val tracePrefix = "[$traceId] "
        val meta = promptAssembly.injectionMeta

        LingShuLog.i(
            TAG,
            "${tracePrefix}=== 对话调用埋点 ===\n" +
                    "traceId: $traceId\n" +
                    "isFallback: $isFallback\n" +
                    "Prompt构建耗时: ${meta.buildCostMs}ms\n" +
                    "LLM调用耗时: ${llmCallCostMs}ms\n" +
                    "人格注入字节: ${meta.personaTokens}\n" +
                    "记忆注入行数: ${meta.memoryLines}\n" +
                    "RAG块数量: ${meta.ragChunkCount}\n" +
                    "RAG来源: ${meta.ragSources}\n" +
                    "系统Prompt字数: ${promptAssembly.systemPrompt.length}\n" +
                    "回复字数: $replyChars\n" +
                    "======================"
        )

        val memoryService = memoryServiceLazy.get()
        val extractedMemories = try {
            val memories = memoryService.extractFromDialogue(userInput, aiResponse)
            LingShuLog.i(
                TAG,
                "${tracePrefix}记忆抽取完成, count=${memories.size}"
            )
            memories
        } catch (e: Exception) {
            LingShuLog.e(TAG, "${tracePrefix}记忆抽取失败", e)
            emptyList()
        }

        val personaService = personaServiceLazy.get()
        try {
            personaService.evolvePersona(userInput, aiResponse)
            LingShuLog.i(
                TAG,
                "${tracePrefix}人格演化完成, evolveReason=${buildEvolveReason(userInput, aiResponse, extractedMemories.size)}"
            )
        } catch (e: Exception) {
            LingShuLog.e(TAG, "${tracePrefix}人格演化失败", e)
        }
    }

    private fun buildEvolveReason(
        userInput: String,
        aiResponse: String,
        memoryCount: Int
    ): String {
        val topics = mutableListOf<String>()
        if (userInput.contains("你好") || userInput.contains("谢谢")) topics.add("礼貌互动")
        if (userInput.contains("?") || userInput.contains("？")) topics.add("问题解答")
        if (userInput.length > 100) topics.add("长对话")
        if (memoryCount > 0) topics.add("抽取${memoryCount}条记忆")

        return topics.ifEmpty { listOf("常规对话") }.joinToString("+")
    }

    private suspend fun buildLlmConfig(): LlmConfig {
        val providerName = appPreferences.llmProvider.first()
        val providerType = runCatching { ModelProviderType.valueOf(providerName) }
            .getOrDefault(ModelProviderType.DEEPSEEK)

        return when (providerType) {
            ModelProviderType.OLLAMA -> {
                val ollamaUrl = appPreferences.ollamaUrl.first()
                val ollamaModel = appPreferences.ollamaModel.first()
                LingShuLog.i(TAG, "使用 Ollama: url=$ollamaUrl, model=$ollamaModel")
                LlmConfig(
                    provider = ModelProviderType.OLLAMA,
                    apiKey = "",
                    baseUrl = ollamaUrl,
                    modelName = ollamaModel,
                    temperature = 0.7f,
                    maxTokens = 2048,
                    timeoutSeconds = 60
                )
            }
            ModelProviderType.GEMINI -> {
                val geminiKey = appPreferences.geminiApiKey.first()
                LingShuLog.i(TAG, "使用 Gemini")
                LlmConfig(
                    provider = ModelProviderType.GEMINI,
                    apiKey = geminiKey,
                    baseUrl = "https://generativelanguage.googleapis.com",
                    modelName = "gemini-1.5-flash",
                    temperature = 0.7f,
                    maxTokens = 2048,
                    timeoutSeconds = 30
                )
            }
            else -> {
                val apiKey = appPreferences.apiKey.first()
                LingShuLog.i(TAG, "使用 DeepSeek")
                LlmConfig(
                    provider = ModelProviderType.DEEPSEEK,
                    apiKey = apiKey,
                    baseUrl = "https://api.deepseek.com/v1",
                    modelName = "deepseek-chat",
                    temperature = 0.7f,
                    maxTokens = 2048,
                    timeoutSeconds = 30
                )
            }
        }
    }

    private fun generateTraceId(): String {
        return UUID.randomUUID().toString().replace("-", "").take(16)
    }

    private fun shouldRetry(errorCode: String?): Boolean {
        return when (errorCode) {
            ErrorCodes.SERVER_NO_RESPONSE -> true
            ErrorCodes.NETWORK_UNAVAILABLE -> true
            "E-429" -> true
            "E-500" -> true
            else -> false
        }
    }

    private fun mapErrorToCode(throwable: Throwable): String {
        return when (throwable) {
            is java.net.SocketTimeoutException -> ErrorCodes.SERVER_NO_RESPONSE
            is java.io.IOException -> ErrorCodes.NETWORK_UNAVAILABLE
            is retrofit2.HttpException -> mapHttpErrorCode(throwable.code())
            else -> ErrorCodes.UNKNOWN_ERROR
        }
    }

    private fun mapHttpErrorCode(httpCode: Int): String {
        return when (httpCode) {
            401 -> ErrorCodes.API_KEY_INVALID
            408, 504 -> ErrorCodes.SERVER_NO_RESPONSE
            429 -> "E-429"
            in 500..599 -> "E-500"
            else -> ErrorCodes.UNKNOWN_ERROR
        }
    }

    private suspend fun cleanupOldMessages() {
        val count = messageDao.getMessageCount()
        if (count > MAX_MESSAGES) {
            LingShuLog.i(TAG, "消息数量超过 $MAX_MESSAGES，清理旧消息")
        }
    }

    private fun MessageEntity.toDomain(): Message {
        return Message(
            id = id,
            content = content,
            isUser = isUser,
            timestamp = timestamp
        )
    }
}

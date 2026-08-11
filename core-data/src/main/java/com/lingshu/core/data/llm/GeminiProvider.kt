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
class GeminiProvider @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ILlmProvider {

    override val type: ModelProviderType = ModelProviderType.GEMINI

    private val moduleTag = "GeminiProvider"

    companion object {
        private const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }

    override suspend fun chat(
        messages: List<ChatMessage>,
        config: LlmConfig,
        traceId: String
    ): Result<String> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val totalMsgLength = messages.sumOf { it.content.length }

        LingShuLog.i(moduleTag, "[$traceId] chat start (STUB) | provider=$type | model=${config.modelName} | " +
                "baseUrl=${config.baseUrl.ifBlank { DEFAULT_BASE_URL }} | apiKeyMasked=${maskApiKey(config.apiKey)} | " +
                "msgCount=${messages.size} | totalMsgLength=$totalMsgLength | " +
                "temp=${config.temperature} | topP=${config.topP} | maxTokens=${config.maxTokens}")

        logConfigDetails(config, traceId)

        for (i in messages.indices) {
            LingShuLog.d(moduleTag, "[$traceId] msg[$i] role=${messages[i].role} length=${messages[i].content.length}")
        }

        LingShuLog.w(moduleTag, "[$traceId] TODO: Integrate Google AI SDK (com.google.ai.client.generativeai)")
        LingShuLog.d(moduleTag, "[$traceId] TODO pseudocode:")
        LingShuLog.d(moduleTag, "[$traceId]   val generativeModel = GenerativeModel(")
        LingShuLog.d(moduleTag, "[$traceId]     modelName = config.modelName, // e.g. gemini-1.5-flash, gemini-1.5-pro")
        LingShuLog.d(moduleTag, "[$traceId]     apiKey = config.apiKey,")
        LingShuLog.d(moduleTag, "[$traceId]     generationConfig = generationConfig {")
        LingShuLog.d(moduleTag, "[$traceId]       temperature = config.temperature")
        LingShuLog.d(moduleTag, "[$traceId]       topP = config.topP")
        LingShuLog.d(moduleTag, "[$traceId]       maxOutputTokens = config.maxTokens")
        LingShuLog.d(moduleTag, "[$traceId]     }")
        LingShuLog.d(moduleTag, "[$traceId]     // if first message is system, use systemInstruction parameter")
        LingShuLog.d(moduleTag, "[$traceId]   )")
        LingShuLog.d(moduleTag, "[$traceId]   val response = generativeModel.generateContent {")
        LingShuLog.d(moduleTag, "[$traceId]     for (msg in messages) {")
        LingShuLog.d(moduleTag, "[$traceId]       if (msg.role == \"user\") content { text(msg.content) }")
        LingShuLog.d(moduleTag, "[$traceId]       else if (msg.role == \"model\" || msg.role == \"assistant\") modelContent { text(msg.content) }")
        LingShuLog.d(moduleTag, "[$traceId]     }")
        LingShuLog.d(moduleTag, "[$traceId]   }")
        LingShuLog.d(moduleTag, "[$traceId]   // return response.text ?: \"\"")

        val elapsed = System.currentTimeMillis() - startTime
        LingShuLog.i(moduleTag, "[$traceId] chat END STUB | elapsedMs=$elapsed | returning unavailable error")

        return@withContext Result.error(
            code = ErrorCodes.MODEL_LOAD_FAILED,
            message = "Gemini provider not yet implemented. Waiting for Google AI SDK integration."
        )
    }

    override suspend fun chatStream(
        messages: List<ChatMessage>,
        config: LlmConfig,
        onToken: (String) -> Unit,
        traceId: String
    ): Result<String> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()

        LingShuLog.i(moduleTag, "[$traceId] chatStream start (STUB) | provider=$type | model=${config.modelName} | " +
                "msgCount=${messages.size}")
        logConfigDetails(config, traceId)

        LingShuLog.w(moduleTag, "[$traceId] TODO: Use generativeModel.generateContentStream(content)")
        LingShuLog.d(moduleTag, "[$traceId] Collect flow: for (chunk in response) chunk.text?.let { onToken(it) }")

        val elapsed = System.currentTimeMillis() - startTime
        LingShuLog.i(moduleTag, "[$traceId] chatStream END STUB | elapsedMs=$elapsed")

        return@withContext Result.error(
            code = ErrorCodes.MODEL_LOAD_FAILED,
            message = "Gemini provider streaming not yet implemented."
        )
    }

    override suspend fun embeddings(
        texts: List<String>,
        config: LlmConfig,
        traceId: String
    ): Result<List<List<Float>>> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()

        LingShuLog.i(moduleTag, "[$traceId] embeddings start (STUB) | provider=$type | model=${config.modelName} | " +
                "textCount=${texts.size}")
        logConfigDetails(config, traceId)

        LingShuLog.w(moduleTag, "[$traceId] TODO: Use model.embedContent / batchEmbedContents API")
        LingShuLog.d(moduleTag, "[$traceId] For task_type=retrieval_document or retrieval_query, use:")
        LingShuLog.d(moduleTag, "[$traceId]   val em = EmbeddingService(apiKey)")
        LingShuLog.d(moduleTag, "[$traceId]   val response = em.embedContent(")
        LingShuLog.d(moduleTag, "[$traceId]     model = \"models/embedding-001\",")
        LingShuLog.d(moduleTag, "[$traceId]     request = EmbedContentRequest(content =...)")
        LingShuLog.d(moduleTag, "[$traceId]   )")
        LingShuLog.d(moduleTag, "[$traceId] Then response.embedding.values.toList() of Float")

        val elapsed = System.currentTimeMillis() - startTime
        LingShuLog.i(moduleTag, "[$traceId] embeddings END STUB | elapsedMs=$elapsed")

        return@withContext Result.error(
            code = ErrorCodes.MODEL_LOAD_FAILED,
            message = "Gemini embeddings not yet implemented."
        )
    }

    override fun isAvailable(config: LlmConfig): Boolean {
        val apiKeyOk = config.apiKey.isNotBlank()
        val modelOk = config.modelName.isNotBlank()
        val valid = apiKeyOk && modelOk

        LingShuLog.d(moduleTag, "isAvailable check: valid=$valid | " +
                "apiKeyOk=$apiKeyOk (len=${config.apiKey.length}) | " +
                "modelOk=$modelOk (model=${config.modelName}) | " +
                "baseUrl=${config.baseUrl.ifBlank { DEFAULT_BASE_URL }}")

        if (!valid) {
            LingShuLog.w(moduleTag, "isAvailable=false (missing apiKey or modelName)")
        }
        return valid
    }

    private fun logConfigDetails(config: LlmConfig, traceId: String) {
        LingShuLog.d(moduleTag, "[$traceId] Config details:")
        LingShuLog.d(moduleTag, "[$traceId]   -> provider         = ${config.provider}")
        LingShuLog.d(moduleTag, "[$traceId]   -> baseUrl          = ${config.baseUrl.ifBlank { DEFAULT_BASE_URL }}")
        LingShuLog.d(moduleTag, "[$traceId]   -> apiKey (masked)  = ${maskApiKey(config.apiKey)}")
        LingShuLog.d(moduleTag, "[$traceId]   -> modelName        = ${config.modelName}")
        LingShuLog.d(moduleTag, "[$traceId]   -> temperature      = ${config.temperature}")
        LingShuLog.d(moduleTag, "[$traceId]   -> topP             = ${config.topP}")
        LingShuLog.d(moduleTag, "[$traceId]   -> maxTokens        = ${config.maxTokens}")
        LingShuLog.d(moduleTag, "[$traceId]   -> timeoutSeconds   = ${config.timeoutSeconds}s")
        LingShuLog.d(moduleTag, "[$traceId]   -> systemPromptOvr  = ${if (config.systemPromptOverride != null) "SET (${config.systemPromptOverride.length} chars)" else "null"}")
    }

    private fun maskApiKey(apiKey: String): String {
        if (apiKey.isBlank()) return "(empty)"
        return if (apiKey.length <= 8) {
            "*".repeat(apiKey.length)
        } else {
            apiKey.take(4) + "*".repeat(apiKey.length - 8) + apiKey.takeLast(4) + " (len=${apiKey.length})"
        }
    }
}

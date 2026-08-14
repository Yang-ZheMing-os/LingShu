package com.lingshu.core.data.llm

enum class ModelProviderType {
    DEEPSEEK,
    GEMINI,
    OLLAMA,
    OPENAI,
    QWEN
}

data class LlmConfig(
    val provider: ModelProviderType = ModelProviderType.DEEPSEEK,
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val modelName: String = "deepseek-chat",
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val maxTokens: Int = 2048,
    val timeoutSeconds: Int = 30,
    val systemPromptOverride: String? = null,
    /**
     * 备用 API Key 列表（用于 Key 轮询）。
     * apiKey 为主 Key，apiKeys 为备用 Key。
     * 当主 Key 触发 429/401 时，LlmRouter 会轮询 apiKeys 中的下一个可用 Key。
     */
    val apiKeys: List<String> = emptyList()
) {

    /**
     * 获取所有可用 Key（主 Key + 备用 Key），去重并保留顺序。
     * 空字符串会被过滤。
     */
    fun allKeys(): List<String> {
        val result = mutableListOf<String>()
        if (apiKey.isNotBlank()) result.add(apiKey)
        apiKeys.forEach { if (it.isNotBlank() && it !in result) result.add(it) }
        return result
    }
}

data class ChatMessage(
    val role: String,
    val content: String
)

interface ILlmProvider {
    val type: ModelProviderType

    suspend fun chat(
        messages: List<ChatMessage>,
        config: LlmConfig,
        traceId: String = ""
    ): com.lingshu.core.common.error.Result<String>

    suspend fun chatStream(
        messages: List<ChatMessage>,
        config: LlmConfig,
        onToken: (String) -> Unit,
        traceId: String = ""
    ): com.lingshu.core.common.error.Result<String>

    suspend fun embeddings(
        texts: List<String>,
        config: LlmConfig,
        traceId: String = ""
    ): com.lingshu.core.common.error.Result<List<List<Float>>>

    fun isAvailable(config: LlmConfig): Boolean
}

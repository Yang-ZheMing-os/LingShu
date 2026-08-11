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
    val systemPromptOverride: String? = null
)

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

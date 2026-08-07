package com.lingshu.agent.feature.model.providers

import android.graphics.Bitmap
import android.util.Base64
import com.lingshu.agent.core.network.RetrofitClient
import com.lingshu.agent.feature.model.ModelCapability
import com.lingshu.agent.feature.model.ModelMessage
import com.lingshu.agent.feature.model.ModelProvider
import com.lingshu.agent.feature.model.ModelResponse
import com.lingshu.agent.feature.model.ModelSettings
import com.lingshu.agent.feature.model.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeepSeek 文本模型提供者
 *
 * 基于 DeepSeek OpenAI 兼容 API 实现。
 * 官方文档：https://platform.deepseek.com/api-docs/
 *
 * 主要特性：
 * 1. 支持文本对话（主打中文理解与生成，长上下文）
 * 2. 支持流式响应（SSE），实现打字机效果
 * 3. 支持多 API Key 轮询，避免限流
 * 4. 完整错误处理与降级支持
 * 5. 使用 Retrofit + OkHttp 进行网络请求
 */
@Singleton
class DeepSeekProvider @Inject constructor(
    private val retrofitClient: RetrofitClient,
    private val modelSettings: ModelSettings,
    private val baseOkHttpClient: OkHttpClient
) : ModelProvider {

    companion object {
        /** Provider 唯一标识 */
        const val PROVIDER_ID = "deepseek"

        /** Provider 显示名称 */
        const val PROVIDER_NAME = "DeepSeek 深度求索"

        /** DeepSeek 默认 API 基础 URL */
        private const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1/"

        /** 默认模型名称 */
        private const val DEFAULT_MODEL = "deepseek-chat"

        /** 对话端点路径 */
        private const val CHAT_ENDPOINT = "chat/completions"
    }

    /** 支持的能力：仅文本对话 */
    override val capabilities: Set<ModelCapability> = setOf(ModelCapability.CHAT)

    override val providerId: String = PROVIDER_ID
    override val providerName: String = PROVIDER_NAME

    /** API Key 轮询索引（原子整数，线程安全） */
    private val apiKeyIndex = AtomicInteger(0)

    /** 自定义 OkHttp 客户端（更长的超时，适配流式响应） */
    private val streamingClient: OkHttpClient by lazy {
        baseOkHttpClient.newBuilder()
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    /**
     * 获取当前配置的自定义 Base URL（或默认值）
     */
    private suspend fun getBaseUrl(): String {
        return modelSettings.getProviderBaseUrl(PROVIDER_ID) ?: DEFAULT_BASE_URL
    }

    /**
     * 获取当前配置的模型名称
     */
    private suspend fun getModelName(): String {
        return modelSettings.getProviderModelNameFlow(PROVIDER_ID, DEFAULT_MODEL).let { flow ->
            // 使用更简洁的方式直接从 DataStore 获取
            var result = DEFAULT_MODEL
            val dataStoreValue = modelSettings.getProviderBaseUrl(PROVIDER_ID)
            // 简单实现：默认模型名
            DEFAULT_MODEL
        }
    }

    /**
     * 获取下一个 API Key（轮询机制）
     */
    private suspend fun getNextApiKey(): String? {
        val apiKeys = modelSettings.getProviderApiKeys(PROVIDER_ID).filter { it.isNotBlank() }
        if (apiKeys.isEmpty()) return null

        val rotationEnabled = modelSettings.isApiKeyRotationEnabled()
        if (!rotationEnabled || apiKeys.size == 1) {
            return apiKeys.first()
        }

        // 轮询：原子自增后取模
        val index = apiKeyIndex.getAndUpdate { (it + 1) % apiKeys.size }
        return apiKeys[index]
    }

    /**
     * 尝试所有 API Key 执行操作
     * 当某个 Key 被限流或失败时，自动尝试下一个
     */
    private suspend fun <T> tryAllApiKeys(
        operation: suspend (apiKey: String) -> T
    ): T? {
        val apiKeys = modelSettings.getProviderApiKeys(PROVIDER_ID).filter { it.isNotBlank() }
        if (apiKeys.isEmpty()) return null

        val startIndex = apiKeyIndex.get() % apiKeys.size.coerceAtLeast(1)
        var lastException: Exception? = null

        for (i in apiKeys.indices) {
            val keyIndex = (startIndex + i) % apiKeys.size
            val apiKey = apiKeys[keyIndex]
            try {
                val result = operation(apiKey)
                // 成功后更新索引，下次从下一个 Key 开始
                apiKeyIndex.set((keyIndex + 1) % apiKeys.size)
                return result
            } catch (e: RateLimitException) {
                lastException = e
                // 限流，继续尝试下一个 Key
                continue
            } catch (e: Exception) {
                lastException = e
                // 其他错误也尝试下一个 Key
                continue
            }
        }
        // 所有 Key 都失败，抛出最后一个异常
        throw lastException ?: Exception("所有 API Key 均调用失败")
    }

    /** 限流异常（用于触发 Key 轮询） */
    private class RateLimitException(message: String) : Exception(message)

    override suspend fun isAvailable(): Boolean {
        // 检查：1. 该 Provider 是否启用 2. 有至少一个 API Key
        if (!modelSettings.isProviderEnabled(PROVIDER_ID)) return false
        return modelSettings.getProviderApiKeys(PROVIDER_ID).any { it.isNotBlank() }
    }

    override suspend fun chat(messages: List<ModelMessage>): ModelResponse {
        if (!isAvailable()) {
            return ModelResponse.unavailable("DeepSeek 未启用或未配置 API Key", PROVIDER_ID)
        }

        val startTime = System.currentTimeMillis()
        return try {
            val result = tryAllApiKeys { apiKey ->
                executeChatRequest(apiKey, messages, stream = false)
            }
            val latency = System.currentTimeMillis() - startTime
            result?.copy(latencyMs = latency)
                ?: ModelResponse.error("DeepSeek 调用失败：无可用 API Key", PROVIDER_ID)
        } catch (e: RateLimitException) {
            ModelResponse.rateLimited(e.message ?: "API 调用频率超限", PROVIDER_ID)
        } catch (e: Exception) {
            ModelResponse.error("DeepSeek 调用异常：${e.message}", PROVIDER_ID)
        }
    }

    override suspend fun chatStream(messages: List<ModelMessage>): Flow<String> = flow {
        if (!isAvailable()) {
            throw IllegalStateException("DeepSeek 未启用或未配置 API Key")
        }

        try {
            val result = tryAllApiKeys { apiKey ->
                executeChatStream(apiKey, messages, this@flow)
            }
            if (result == null) {
                throw Exception("无可用 API Key")
            }
        } catch (e: Exception) {
            throw e
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 执行非流式对话请求
     */
    private suspend fun executeChatRequest(
        apiKey: String,
        messages: List<ModelMessage>,
        stream: Boolean
    ): ModelResponse = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        val modelName = DEFAULT_MODEL
        val requestBody = buildChatRequestBody(messages, modelName, stream)

        val request = Request.Builder()
            .url("${baseUrl}$CHAT_ENDPOINT")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        baseOkHttpClient.newCall(request).execute().use { response ->
            parseChatResponse(response)
        }
    }

    /**
     * 执行流式对话请求，将内容片段 emit 到 Flow 中
     */
    private suspend fun executeChatStream(
        apiKey: String,
        messages: List<ModelMessage>,
        flowCollector: kotlinx.coroutines.flow.FlowCollector<String>
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        val modelName = DEFAULT_MODEL
        val requestBody = buildChatRequestBody(messages, modelName, stream = true)

        val request = Request.Builder()
            .url("${baseUrl}$CHAT_ENDPOINT")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        streamingClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorCode = response.code
                val errorBody = response.body?.string() ?: ""
                when (errorCode) {
                    429 -> throw RateLimitException("API 调用频率超限，请稍后重试")
                    401, 403 -> throw Exception("API Key 无效或权限不足")
                    in 500..599 -> throw Exception("DeepSeek 服务端错误 (HTTP $errorCode)")
                    else -> throw Exception("请求失败 (HTTP $errorCode): $errorBody")
                }
            }

            val body = response.body ?: throw Exception("响应体为空")
            val reader = body.charStream().buffered()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                // SSE 格式：data: {json}
                if (currentLine.startsWith("data: ")) {
                    val data = currentLine.removePrefix("data: ").trim()
                    if (data == "[DONE]") break // 流结束标记

                    try {
                        val json = JSONObject(data)
                        val delta = json.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content", "") ?: ""

                        if (delta.isNotEmpty()) {
                            flowCollector.emit(delta)
                        }
                    } catch (_: Exception) {
                        // 忽略单条解析错误，继续处理
                    }
                }
            }
            true
        }
    }

    /**
     * 构建对话请求的 JSON 字符串
     */
    private fun buildChatRequestBody(
        messages: List<ModelMessage>,
        modelName: String,
        stream: Boolean
    ): String {
        val messagesArray = JSONArray()
        messages.forEach { msg ->
            messagesArray.put(JSONObject(msg.toApiMap()))
        }

        return JSONObject().apply {
            put("model", modelName)
            put("messages", messagesArray)
            put("temperature", 0.7)
            put("max_tokens", 2048)
            put("stream", stream)
        }.toString()
    }

    /**
     * 解析非流式对话响应
     */
    private fun parseChatResponse(response: Response): ModelResponse {
        val code = response.code
        val body = response.body?.string() ?: ""

        return when {
            code in 200..299 -> {
                try {
                    val json = JSONObject(body)
                    val content = json.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "") ?: ""

                    val usageJson = json.optJSONObject("usage")
                    val usage = if (usageJson != null) {
                        TokenUsage(
                            promptTokens = usageJson.optInt("prompt_tokens", 0),
                            completionTokens = usageJson.optInt("completion_tokens", 0)
                        )
                    } else {
                        TokenUsage.EMPTY
                    }

                    val responseId = json.optString("id", "")
                    ModelResponse.success(content, PROVIDER_ID, usage, responseId)
                } catch (e: Exception) {
                    ModelResponse.error("响应解析失败：${e.message}", PROVIDER_ID)
                }
            }
            code == 429 -> ModelResponse.rateLimited("API 调用频率超限，请稍后重试", PROVIDER_ID)
            code == 401 || code == 403 ->
                ModelResponse.error("API Key 无效或权限不足", PROVIDER_ID)
            code in 500..599 ->
                ModelResponse.unavailable("DeepSeek 服务端错误 (HTTP $code)", PROVIDER_ID)
            else -> ModelResponse.error("请求失败 (HTTP $code): $body", PROVIDER_ID)
        }
    }

    // ==================== 未支持的能力 ====================

    override suspend fun vision(image: Bitmap, prompt: String): String {
        throw UnsupportedOperationException("DeepSeek 文本模型不支持视觉能力，请使用 GPT-4V 等多模态模型")
    }

    override suspend fun transcribe(audio: ByteArray): String {
        throw UnsupportedOperationException("DeepSeek 文本模型不支持语音识别能力，请使用 Vosk 等语音模型")
    }

    override suspend fun synthesize(text: String): ByteArray {
        throw UnsupportedOperationException("DeepSeek 文本模型不支持语音合成能力，请使用系统 TTS")
    }

    override fun release() {
        // OkHttp 连接池由 Dagger 管理，无需在此释放
    }

    /**
     * Bitmap 转 Base64 字符串（预留，未来多模态支持时使用）
     */
    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

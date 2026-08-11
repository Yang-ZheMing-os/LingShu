package com.lingshu.agent.feature.model.providers

import android.graphics.Bitmap
import android.util.Base64
import com.lingshu.agent.feature.model.ModelCapability
import com.lingshu.agent.feature.model.ModelMessage
import com.lingshu.agent.feature.model.ModelProvider
import com.lingshu.agent.feature.model.ModelResponse
import com.lingshu.agent.feature.model.ModelSettings
import com.lingshu.agent.feature.model.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GPT-4V（GPT-4 Vision）视觉模型提供者
 *
 * 基于 OpenAI GPT-4V 多模态 API 实现。
 * 官方文档：https://platform.openai.com/docs/guides/vision
 *
 * 主要特性：
 * 1. 强大的视觉理解能力（图片描述、OCR、图表分析、视觉问答等）
 * 2. 支持文本对话（与 GPT-4 同等文本能力）
 * 3. 支持多图输入和混合图文对话
 * 4. 支持多 API Key 轮询，避免限流
 * 5. 完整错误处理与降级支持
 *
 * 注意：也可以通过兼容 OpenAI 格式的其他服务使用，如 Azure OpenAI 等
 */
@Singleton
class GPT4VisionProvider @Inject constructor(
    private val modelSettings: ModelSettings,
    private val baseOkHttpClient: OkHttpClient
) : ModelProvider {

    companion object {
        /** Provider 唯一标识 */
        const val PROVIDER_ID = "gpt4-vision"

        /** Provider 显示名称 */
        const val PROVIDER_NAME = "GPT-4V 视觉模型"

        /** OpenAI 默认 API 基础 URL */
        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1/"

        /** Azure OpenAI 风格基础 URL 模板（兼容格式） */
        private const val AZURE_STYLE_HINT = ".openai.azure.com"

        /** 默认视觉模型名称（GPT-4o 支持视觉，推荐使用） */
        private const val DEFAULT_MODEL = "gpt-4o"

        /** 对话端点路径（OpenAI 格式） */
        private const val CHAT_ENDPOINT = "chat/completions"

        /** 图片处理质量："low" / "high" / "auto" */
        private const val IMAGE_DETAIL = "auto"
    }

    /** 支持的能力：对话 + 视觉理解 */
    override val capabilities: Set<ModelCapability> = setOf(
        ModelCapability.CHAT,
        ModelCapability.VISION
    )

    override val providerId: String = PROVIDER_ID
    override val providerName: String = PROVIDER_NAME

    /** API Key 轮询索引 */
    private val apiKeyIndex = AtomicInteger(0)

    /** 视觉请求客户端（超时更长，图片处理耗时） */
    private val visionClient: OkHttpClient by lazy {
        baseOkHttpClient.newBuilder()
            .readTimeout(3, TimeUnit.MINUTES)
            .build()
    }

    /** 流式响应客户端 */
    private val streamingClient: OkHttpClient by lazy {
        baseOkHttpClient.newBuilder()
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    private suspend fun getBaseUrl(): String {
        return modelSettings.getProviderBaseUrl(PROVIDER_ID) ?: DEFAULT_BASE_URL
    }

    private suspend fun getModelName(): String {
        // 简化实现，返回默认值
        return DEFAULT_MODEL
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

        val index = apiKeyIndex.getAndUpdate { (it + 1) % apiKeys.size }
        return apiKeys[index]
    }

    /** 限流异常，用于触发 Key 轮询 */
    private class RateLimitException(message: String) : Exception(message)

    /**
     * 尝试所有 API Key 执行操作
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
                apiKeyIndex.set((keyIndex + 1) % apiKeys.size)
                return result
            } catch (e: RateLimitException) {
                lastException = e
                continue
            } catch (e: Exception) {
                lastException = e
                continue
            }
        }
        throw lastException ?: Exception("所有 API Key 均调用失败")
    }

    override suspend fun isAvailable(): Boolean {
        if (!modelSettings.isProviderEnabled(PROVIDER_ID)) return false
        return modelSettings.getProviderApiKeys(PROVIDER_ID).any { it.isNotBlank() }
    }

    override suspend fun chat(messages: List<ModelMessage>): ModelResponse {
        if (!isAvailable()) {
            return ModelResponse.unavailable("GPT-4V 未启用或未配置 API Key", PROVIDER_ID)
        }

        val startTime = System.currentTimeMillis()
        return try {
            val result = tryAllApiKeys { apiKey ->
                executeChatRequest(apiKey, messages, stream = false)
            }
            val latency = System.currentTimeMillis() - startTime
            result?.copy(latencyMs = latency)
                ?: ModelResponse.error("GPT-4V 调用失败：无可用 API Key", PROVIDER_ID)
        } catch (e: RateLimitException) {
            ModelResponse.rateLimited(e.message ?: "API 调用频率超限", PROVIDER_ID)
        } catch (e: Exception) {
            ModelResponse.error("GPT-4V 调用异常：${e.message}", PROVIDER_ID)
        }
    }

    override suspend fun chatStream(messages: List<ModelMessage>): Flow<String> = flow {
        if (!isAvailable()) {
            throw IllegalStateException("GPT-4V 未启用或未配置 API Key")
        }

        tryAllApiKeys { apiKey ->
            executeChatStream(apiKey, messages, this)
        } ?: throw Exception("无可用 API Key")
    }.flowOn(Dispatchers.IO)

    /**
     * 视觉理解能力实现
     *
     * 将 Bitmap 编码为 Base64，通过多模态消息格式发送给 GPT-4V
     *
     * @param image 输入图片 Bitmap
     * @param prompt 提示词，例如 "描述这张图片"、"提取图片中的文字"
     * @return 视觉理解结果文本
     */
    override suspend fun vision(image: Bitmap, prompt: String): String {
        if (!isAvailable()) {
            throw IllegalStateException("GPT-4V 未启用或未配置 API Key")
        }

        val base64Image = encodeBitmapToBase64(image)
        val dataUrl = "data:image/jpeg;base64,$base64Image"

        // 构造多模态消息
        val messages = listOf(
            ModelMessage.userWithImages(prompt, listOf(dataUrl))
        )

        val response = try {
            tryAllApiKeys { apiKey ->
                executeVisionRequest(apiKey, messages)
            }
        } catch (e: Exception) {
            throw Exception("视觉请求失败：${e.message}", e)
        } ?: throw Exception("视觉请求失败：无可用 API Key")

        if (!response.isSuccess) {
            throw Exception("视觉请求失败：${response.errorMessage}")
        }
        return response.content
    }

    // ==================== 内部请求方法 ====================

    /**
     * 执行非流式对话请求
     */
    private suspend fun executeChatRequest(
        apiKey: String,
        messages: List<ModelMessage>,
        stream: Boolean
    ): ModelResponse = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        val modelName = getModelName()
        val requestBody = buildChatRequestBody(messages, modelName, stream)

        val request = buildApiRequest(apiKey, baseUrl, requestBody)

        visionClient.newCall(request).execute().use { response ->
            parseChatResponse(response)
        }
    }

    /**
     * 执行视觉专用请求（使用更长超时的客户端）
     */
    private suspend fun executeVisionRequest(
        apiKey: String,
        messages: List<ModelMessage>
    ): ModelResponse = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        val modelName = getModelName()
        val requestBody = buildChatRequestBody(messages, modelName, stream = false)

        val request = buildApiRequest(apiKey, baseUrl, requestBody)

        visionClient.newCall(request).execute().use { response ->
            parseChatResponse(response)
        }
    }

    /**
     * 执行流式对话请求
     */
    private suspend fun executeChatStream(
        apiKey: String,
        messages: List<ModelMessage>,
        collector: kotlinx.coroutines.flow.FlowCollector<String>
    ) = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        val modelName = getModelName()
        val requestBody = buildChatRequestBody(messages, modelName, stream = true)

        val request = buildApiRequest(apiKey, baseUrl, requestBody, acceptSse = true)

        streamingClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val code = response.code
                val body = response.body?.string() ?: ""
                when (code) {
                    429 -> throw RateLimitException("API 调用频率超限，请稍后重试")
                    401, 403 -> throw Exception("API Key 无效或权限不足")
                    in 500..599 -> throw Exception("OpenAI 服务端错误 (HTTP $code)")
                    else -> throw Exception("请求失败 (HTTP $code): $body")
                }
            }

            val body = response.body ?: throw Exception("响应体为空")
            val reader = body.charStream().buffered()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                if (currentLine.startsWith("data: ")) {
                    val data = currentLine.removePrefix("data: ").trim()
                    if (data == "[DONE]") break

                    try {
                        val json = JSONObject(data)
                        val delta = json.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content", "") ?: ""
                        if (delta.isNotEmpty()) {
                            collector.emit(delta)
                        }
                    } catch (_: Exception) {
                        // 忽略单行解析错误
                    }
                }
            }
        }
    }

    /**
     * 构建统一的 API HTTP 请求
     */
    private fun buildApiRequest(
        apiKey: String,
        baseUrl: String,
        requestBody: String,
        acceptSse: Boolean = false
    ): Request {
        val builder = Request.Builder()
            .url("${baseUrl}$CHAT_ENDPOINT")
            .addHeader("Content-Type", "application/json")

        // 区分 Azure OpenAI 和原生 OpenAI
        // Azure 使用 api-key 头；原生使用 Authorization: Bearer
        if (baseUrl.contains(AZURE_STYLE_HINT, ignoreCase = true)) {
            builder.addHeader("api-key", apiKey)
        } else {
            builder.addHeader("Authorization", "Bearer $apiKey")
        }

        if (acceptSse) {
            builder.addHeader("Accept", "text/event-stream")
        }

        builder.post(requestBody.toRequestBody("application/json".toMediaType()))
        return builder.build()
    }

    /**
     * 构建对话请求 JSON（OpenAI 多模态格式兼容）
     */
    private fun buildChatRequestBody(
        messages: List<ModelMessage>,
        modelName: String,
        stream: Boolean
    ): String {
        val messagesArray = JSONArray()

        messages.forEach { msg ->
            val msgJson = JSONObject()
            msgJson.put("role", msg.role.name.lowercase())

            if (msg.images.isEmpty()) {
                // 纯文本消息
                msgJson.put("content", msg.content)
            } else {
                // 多模态消息：content 为数组
                val contentArray = JSONArray()

                // 添加文本部分
                contentArray.put(JSONObject().apply {
                    put("type", "text")
                    put("text", msg.content)
                })

                // 添加图片部分
                msg.images.forEach { imageUrl ->
                    val imageUrlWithDetail = if (imageUrl.startsWith("http")) {
                        // 网络图片：附带 detail 参数
                        JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", imageUrl)
                                put("detail", IMAGE_DETAIL)
                            })
                        }
                    } else {
                        // Base64 Data URL
                        JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", imageUrl)
                                put("detail", IMAGE_DETAIL)
                            })
                        }
                    }
                    contentArray.put(imageUrlWithDetail)
                }

                msgJson.put("content", contentArray)
            }
            messagesArray.put(msgJson)
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
     * 解析非流式响应
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
                    } else TokenUsage.EMPTY

                    val responseId = json.optString("id", "")
                    ModelResponse.success(content, PROVIDER_ID, usage, responseId)
                } catch (e: Exception) {
                    ModelResponse.error("响应解析失败：${e.message}", PROVIDER_ID)
                }
            }
            code == 429 -> ModelResponse.rateLimited(
                parseErrorMessage(body) ?: "API 调用频率超限，请稍后重试",
                PROVIDER_ID
            )
            code == 401 || code == 403 -> ModelResponse.error(
                parseErrorMessage(body) ?: "API Key 无效或权限不足",
                PROVIDER_ID
            )
            code in 500..599 -> ModelResponse.unavailable(
                "OpenAI 服务端错误 (HTTP $code)",
                PROVIDER_ID
            )
            else -> ModelResponse.error(
                "请求失败 (HTTP $code): ${parseErrorMessage(body) ?: body}",
                PROVIDER_ID
            )
        }
    }

    /**
     * 从 OpenAI 错误响应体中提取 error.message
     */
    private fun parseErrorMessage(body: String): String? {
        return try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")
        } catch (_: Exception) {
            null
        }
    }

    // ==================== 未支持的能力 ====================

    override suspend fun transcribe(audio: ByteArray): String {
        throw UnsupportedOperationException("GPT-4V 不支持语音识别能力，请使用 Whisper/Vosk 等语音模型")
    }

    override suspend fun synthesize(text: String): ByteArray {
        throw UnsupportedOperationException("GPT-4V 不支持语音合成能力，请使用系统 TTS")
    }

    override fun release() {
        // OkHttp 由 Dagger 管理
    }

    // ==================== 辅助方法 ====================

    /**
     * Bitmap → Base64（JPEG 85% 压缩）
     */
    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}

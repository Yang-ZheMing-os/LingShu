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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ollama 本地模型提供者
 *
 * 基于本地 Ollama 服务 REST API 实现。
 * 官方文档：https://github.com/ollama/ollama/blob/main/docs/api.md
 *
 * 主要特性：
 * 1. 本地运行，无需云端 API，隐私友好（数据不离开设备）
 * 2. 支持文本对话（支持 Llama、Qwen、Mistral 等开源模型）
 * 3. 支持视觉理解（使用多模态模型如 llava、bakllava）
 * 4. 支持流式响应，实现打字机效果
 * 5. 模型下载与管理（获取已安装模型列表、拉取新模型）
 *
 * 注意：需要用户在设备上安装并启动 Ollama 服务，默认监听端口 11434
 */
@Singleton
class OllamaProvider @Inject constructor(
    private val modelSettings: ModelSettings,
    private val baseOkHttpClient: OkHttpClient
) : ModelProvider {

    companion object {
        /** Provider 唯一标识 */
        const val PROVIDER_ID = "ollama"

        /** Provider 显示名称 */
        const val PROVIDER_NAME = "Ollama 本地模型"

        /** Ollama 默认本地服务地址 */
        private const val DEFAULT_BASE_URL = "http://localhost:11434/api/"

        /** 默认模型名称（Llama 3.1 8B，均衡性能） */
        private const val DEFAULT_MODEL = "llama3.1"

        /** 对话端点 */
        private const val CHAT_ENDPOINT = "chat"

        /** 生成端点（用于单轮补全） */
        private const val GENERATE_ENDPOINT = "generate"

        /** 模型列表端点 */
        private const val TAGS_ENDPOINT = "tags"

        /** 模型拉取端点 */
        private const val PULL_ENDPOINT = "pull"
    }

    /** 支持的能力：对话 + 视觉（多模态模型） */
    override val capabilities: Set<ModelCapability> = setOf(
        ModelCapability.CHAT,
        ModelCapability.VISION
    )

    override val providerId: String = PROVIDER_ID
    override val providerName: String = PROVIDER_NAME

    /** 自定义 OkHttp 客户端（本地连接超时更短，流式超时更长） */
    private val localClient: OkHttpClient by lazy {
        baseOkHttpClient.newBuilder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .build()
    }

    /** 流式专用客户端（超时更长） */
    private val streamingClient: OkHttpClient by lazy {
        baseOkHttpClient.newBuilder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    /**
     * 获取配置的 Base URL（支持自定义 Ollama 服务地址）
     */
    private suspend fun getBaseUrl(): String {
        return modelSettings.getProviderBaseUrl(PROVIDER_ID) ?: DEFAULT_BASE_URL
    }

    /**
     * 获取当前使用的模型名称
     */
    private suspend fun getModelName(): String {
        // 简化实现，直接返回默认模型
        // 实际可通过 modelSettings.getProviderModelNameFlow 获取
        return DEFAULT_MODEL
    }

    override suspend fun isAvailable(): Boolean {
        // 1. 检查 Provider 是否被用户禁用
        if (!modelSettings.isProviderEnabled(PROVIDER_ID)) return false
        // 2. 检查本地 Ollama 服务是否运行（通过 /api/tags 端点）
        return checkLocalServiceRunning()
    }

    /**
     * 检查本地 Ollama 服务是否正在运行
     * 通过请求 /api/tags 端点判断服务是否可用
     */
    private suspend fun checkLocalServiceRunning(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val baseUrl = getBaseUrl()
            val request = Request.Builder()
                .url("${baseUrl}$TAGS_ENDPOINT")
                .get()
                .build()

            localClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            // 连接失败说明服务未运行
            false
        }
    }

    override suspend fun chat(messages: List<ModelMessage>): ModelResponse {
        if (!modelSettings.isProviderEnabled(PROVIDER_ID)) {
            return ModelResponse.unavailable("Ollama 已被禁用", PROVIDER_ID)
        }
        if (!checkLocalServiceRunning()) {
            return ModelResponse.unavailable(
                "Ollama 服务未运行，请先在本机启动 Ollama 服务（默认端口 11434）",
                PROVIDER_ID
            )
        }

        val startTime = System.currentTimeMillis()
        return try {
            val result = executeChatRequest(messages, stream = false)
            val latency = System.currentTimeMillis() - startTime
            result.copy(latencyMs = latency)
        } catch (e: Exception) {
            ModelResponse.error("Ollama 调用异常：${e.message}", PROVIDER_ID)
        }
    }

    override suspend fun chatStream(messages: List<ModelMessage>): Flow<String> = flow {
        if (!modelSettings.isProviderEnabled(PROVIDER_ID)) {
            throw IllegalStateException("Ollama 已被禁用")
        }
        if (!checkLocalServiceRunning()) {
            throw IllegalStateException("Ollama 服务未运行，请先启动本地 Ollama 服务")
        }

        executeChatStream(messages, this)
    }.flowOn(Dispatchers.IO)

    /**
     * 执行非流式对话请求
     */
    private suspend fun executeChatRequest(
        messages: List<ModelMessage>,
        stream: Boolean
    ): ModelResponse = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        val modelName = getModelName()
        val requestBody = buildChatRequestBody(messages, modelName, stream)

        val request = Request.Builder()
            .url("${baseUrl}$CHAT_ENDPOINT")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        localClient.newCall(request).execute().use { response ->
            parseChatResponse(response)
        }
    }

    /**
     * 执行流式对话请求
     */
    private suspend fun executeChatStream(
        messages: List<ModelMessage>,
        collector: kotlinx.coroutines.flow.FlowCollector<String>
    ) = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        val modelName = getModelName()
        val requestBody = buildChatRequestBody(messages, modelName, stream = true)

        val request = Request.Builder()
            .url("${baseUrl}$CHAT_ENDPOINT")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        streamingClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Ollama 请求失败 (HTTP ${response.code})")
            }

            val body = response.body ?: throw Exception("响应体为空")
            val reader = body.charStream().buffered()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line?.trim() ?: continue
                if (currentLine.isEmpty()) continue

                try {
                    val json = JSONObject(currentLine)
                    // Ollama 流式格式：每一行是独立 JSON
                    // {"model":"...","created_at":"...","message":{"role":"assistant","content":"xxx"},"done":false}
                    val content = json.optJSONObject("message")
                        ?.optString("content", "") ?: ""

                    if (content.isNotEmpty()) {
                        collector.emit(content)
                    }

                    // done = true 表示流结束
                    if (json.optBoolean("done", false)) break
                } catch (_: Exception) {
                    // 忽略单行解析错误
                }
            }
        }
    }

    /**
     * 构建对话请求 JSON
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
            msgJson.put("content", msg.content)

            // 处理图片（Ollama 要求图片是 Base64 数组，去掉 data:image/xxx;base64, 前缀）
            if (msg.hasImages()) {
                val imagesArray = JSONArray()
                msg.images.forEach { imageUrl ->
                    if (imageUrl.startsWith("data:image/")) {
                        val base64Data = imageUrl.substringAfter("base64,", "")
                        if (base64Data.isNotBlank()) {
                            imagesArray.put(base64Data)
                        }
                    }
                }
                if (imagesArray.length() > 0) {
                    msgJson.put("images", imagesArray)
                }
            }
            messagesArray.put(msgJson)
        }

        val optionsJson = JSONObject().apply {
            put("temperature", 0.7f)
            put("num_predict", 2048)
        }

        return JSONObject().apply {
            put("model", modelName)
            put("messages", messagesArray)
            put("stream", stream)
            put("options", optionsJson)
        }.toString()
    }

    /**
     * 解析非流式聊天响应
     */
    private fun parseChatResponse(response: Response): ModelResponse {
        val code = response.code
        val body = response.body?.string() ?: ""

        return when {
            code in 200..299 -> {
                try {
                    val json = JSONObject(body)
                    val content = json.optJSONObject("message")
                        ?.optString("content", "") ?: ""

                    // Ollama 返回的 Token 使用统计
                    val promptEvalCount = json.optInt("prompt_eval_count", 0)
                    val evalCount = json.optInt("eval_count", 0)
                    val usage = TokenUsage(
                        promptTokens = promptEvalCount,
                        completionTokens = evalCount
                    )

                    ModelResponse.success(content, PROVIDER_ID, usage)
                } catch (e: Exception) {
                    ModelResponse.error("Ollama 响应解析失败：${e.message}", PROVIDER_ID)
                }
            }
            code in 400..499 -> ModelResponse.error("Ollama 请求错误：$body", PROVIDER_ID)
            code in 500..599 -> ModelResponse.unavailable("Ollama 服务端错误 (HTTP $code)", PROVIDER_ID)
            else -> ModelResponse.error("请求失败 (HTTP $code)", PROVIDER_ID)
        }
    }

    override suspend fun vision(image: Bitmap, prompt: String): String {
        if (!modelSettings.isProviderEnabled(PROVIDER_ID)) {
            throw IllegalStateException("Ollama 已被禁用")
        }
        if (!checkLocalServiceRunning()) {
            throw IllegalStateException("Ollama 服务未运行，请先启动本地 Ollama 服务")
        }

        return withContext(Dispatchers.IO) {
            val base64Image = encodeBitmapToBase64(image)

            val messages = listOf(
                ModelMessage.userWithImages(prompt, listOf("data:image/jpeg;base64,$base64Image"))
            )

            val response = executeChatRequest(messages, stream = false)
            if (!response.isSuccess) {
                throw Exception("视觉请求失败：${response.errorMessage}")
            }
            response.content
        }
    }

    /**
     * 获取本地已安装的模型列表
     *
     * @return 模型名称列表，例如 ["llama3.1:latest", "qwen2:7b", "llava:latest"]
     */
    suspend fun getInstalledModels(): List<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val baseUrl = getBaseUrl()
            val request = Request.Builder()
                .url("${baseUrl}$TAGS_ENDPOINT")
                .get()
                .build()

            localClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use emptyList<String>()
                    val json = JSONObject(body)
                    val models = json.optJSONArray("models")
                    val result = mutableListOf<String>()
                    for (i in 0 until (models?.length() ?: 0)) {
                        models?.optJSONObject(i)?.optString("name")?.let { result.add(it) }
                    }
                    result
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 拉取（下载）指定模型
     *
     * @param modelName 模型名称，如 "llama3.1", "qwen2:7b", "llava"
     * @param progressCallback 进度回调 (0.0 ~ 1.0)，可为 null
     * @return 是否成功下载完成
     */
    suspend fun pullModel(
        modelName: String,
        progressCallback: ((Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val baseUrl = getBaseUrl()
            val requestBody = JSONObject().apply {
                put("name", modelName)
                put("stream", true)
            }.toString()

            val request = Request.Builder()
                .url("${baseUrl}$PULL_ENDPOINT")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            streamingClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false

                val body = response.body ?: return@use false
                val reader = body.charStream().buffered()
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line?.trim() ?: continue
                    if (currentLine.isEmpty()) continue

                    try {
                        val json = JSONObject(currentLine)
                        val status = json.optString("status", "")
                        val total = json.optLong("total", 0L)
                        val completed = json.optLong("completed", 0L)

                        // 有进度数据时回调
                        if (total > 0 && progressCallback != null) {
                            progressCallback(completed.toFloat() / total.toFloat())
                        }

                        // 下载成功完成
                        if (status == "success") {
                            return@use true
                        }
                    } catch (_: Exception) {
                        // 忽略单行解析错误
                    }
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    // ==================== 未支持的能力 ====================

    override suspend fun transcribe(audio: ByteArray): String {
        throw UnsupportedOperationException("Ollama 不支持语音识别能力，请使用 Vosk 等语音模型")
    }

    override suspend fun synthesize(text: String): ByteArray {
        throw UnsupportedOperationException("Ollama 不支持语音合成能力，请使用系统 TTS")
    }

    override fun release() {
        // 本地连接无状态，无需额外释放
    }

    // ==================== 辅助方法 ====================

    /**
     * 将 Bitmap 编码为 Base64 字符串（JPEG 格式，85% 压缩率）
     */
    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

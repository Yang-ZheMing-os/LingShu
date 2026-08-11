package com.lingshu.agent.core.model.routing.providers

import android.graphics.Bitmap
import android.util.Base64
import com.lingshu.agent.core.model.routing.Message
import com.lingshu.agent.core.model.routing.ModelConfig
import com.lingshu.agent.core.model.routing.Response
import com.lingshu.agent.core.model.routing.Usage
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ollama 本地模型提供者
 * 基于本地 Ollama 服务 API 实现
 * API文档: https://github.com/ollama/ollama/blob/main/docs/api.md
 *
 * 主要特性：
 * - 本地运行，无需云端API
 * - 支持文本对话
 * - 支持视觉理解（使用支持多模态的本地模型如llava）
 * - 隐私友好（数据不离开设备）
 * - 完全免费（根据模型许可）
 *
 * 注意：需要用户在设备上安装并启动Ollama服务，默认端口11434
 */
class OllamaProvider(
    initialConfig: ModelConfig
) : BaseModelProvider(initialConfig) {

    companion object {
        /** Ollama默认本地服务地址 */
        private const val DEFAULT_BASE_URL = "http://localhost:11434/api"
        /** 聊天端点 */
        private const val CHAT_ENDPOINT = "/chat"
        /** 生成端点 */
        private const val GENERATE_ENDPOINT = "/generate"
        /** 模型列表端点 */
        private const val TAGS_ENDPOINT = "/tags"
        /** 模型拉取端点 */
        private const val PULL_ENDPOINT = "/pull"
        /** 默认模型名称 */
        private const val DEFAULT_MODEL = "llama3.1"
        /** 默认连接超时（本地模型连接更快） */
        private const val DEFAULT_TIMEOUT_MS = 120_000L
    }

    private val baseUrl: String
        get() = currentConfig.baseUrl ?: DEFAULT_BASE_URL

    private val modelName: String
        get() = currentConfig.modelName.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    private val timeoutMs: Long
        get() = currentConfig.timeoutMs.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_MS

    override suspend fun checkLocalServiceAvailable(): Boolean {
        return try {
            val url = URL("$baseUrl$TAGS_ENDPOINT")
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            connection.responseCode in 200..299
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun chat(messages: List<Message>): Response {
        if (!currentConfig.isEnabled) {
            return unavailableResponse("Ollama已禁用")
        }

        if (!isAvailable()) {
            return unavailableResponse("Ollama服务未运行，请先在本机启动Ollama服务（默认端口11434）")
        }

        return executeWithTiming {
            executeChatRequest(messages)
        }
    }

    override suspend fun vision(image: Bitmap, prompt: String): String {
        if (!currentConfig.isEnabled) {
            throw IllegalStateException("Ollama已禁用")
        }

        if (!isAvailable()) {
            throw IllegalStateException("Ollama服务未运行，请先在本机启动Ollama服务")
        }

        val base64Image = encodeBitmapToBase64(image)

        val url = URL("$baseUrl$CHAT_ENDPOINT")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs.toInt()
                readTimeout = timeoutMs.toInt()
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }

            val imagesJson = JSONArray().put(base64Image)
            val requestBody = """
                {
                    "model": "$modelName",
                    "messages": [
                        {
                            "role": "user",
                            "content": ${JSONObject.quote(prompt)},
                            "images": $imagesJson
                        }
                    ],
                    "stream": false,
                    "options": {
                        "temperature": ${currentConfig.temperature},
                        "num_predict": ${currentConfig.maxTokens}
                    }
                }
            """.trimIndent()

            connection.outputStream.use {
                it.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseBody = when (responseCode) {
                in 200..299 -> connection.inputStream.bufferedReader().readText()
                else -> connection.errorStream?.bufferedReader()?.readText() ?: ""
            }

            if (responseCode in 200..299) {
                val json = JSONObject(responseBody)
                val message = json.optJSONObject("message")
                return message?.optString("content", "") ?: ""
            } else {
                throw Exception("视觉请求失败，状态码: $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 获取本地已安装的模型列表
     * @return 模型名称列表
     */
    suspend fun getInstalledModels(): List<String> {
        return try {
            val url = URL("$baseUrl$TAGS_ENDPOINT")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout = 5_000
                }
                if (connection.responseCode in 200..299) {
                    val body = connection.inputStream.bufferedReader().readText()
                    val json = JSONObject(body)
                    val models = json.optJSONArray("models")
                    val result = mutableListOf<String>()
                    for (i in 0 until (models?.length() ?: 0)) {
                        val model = models?.optJSONObject(i)
                        model?.optString("name")?.let { result.add(it) }
                    }
                    result
                } else {
                    emptyList()
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 拉取/下载指定模型
     * @param modelName 模型名称（如 "llama3.1", "llava"）
     * @param progressCallback 进度回调 (0.0 - 1.0)
     * @return 是否成功
     */
    suspend fun pullModel(
        modelName: String,
        progressCallback: ((Float) -> Unit)? = null
    ): Boolean {
        return try {
            val url = URL("$baseUrl$PULL_ENDPOINT")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.apply {
                    requestMethod = "POST"
                    connectTimeout = 30_000
                    readTimeout = 600_000
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }

                val requestBody = """
                    {
                        "name": "$modelName",
                        "stream": true
                    }
                """.trimIndent()

                connection.outputStream.use {
                    it.write(requestBody.toByteArray(Charsets.UTF_8))
                }

                if (connection.responseCode in 200..299) {
                    val reader = connection.inputStream.bufferedReader()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.takeIf { it.isNotBlank() }?.let {
                            try {
                                val json = JSONObject(it)
                                val status = json.optString("status", "")
                                val total = json.optLong("total", 0L)
                                val completed = json.optLong("completed", 0L)
                                if (total > 0 && progressCallback != null) {
                                    progressCallback(completed.toFloat() / total.toFloat())
                                }
                                if (status == "success") {
                                    return true
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                    true
                } else {
                    false
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun executeChatRequest(messages: List<Message>): Response {
        val url = URL("$baseUrl$CHAT_ENDPOINT")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs.toInt()
                readTimeout = timeoutMs.toInt()
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }

            val requestBody = buildChatRequestBody(messages)
            connection.outputStream.use {
                it.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseBody = when (responseCode) {
                in 200..299 -> connection.inputStream.bufferedReader().readText()
                else -> connection.errorStream?.bufferedReader()?.readText() ?: ""
            }

            parseChatResponse(responseCode, responseBody)
        } catch (e: Exception) {
            errorResponse("Ollama请求异常: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun buildChatRequestBody(messages: List<Message>): String {
        val apiMessages = messages.joinToString(",", "[", "]") { msg ->
            val json = JSONObject()
            json.put("role", msg.role.name.lowercase())
            json.put("content", msg.content)

            if (msg.hasImages()) {
                val images = JSONArray()
                msg.imageUrls.forEach { url ->
                    if (url.startsWith("data:image/")) {
                        val data = url.substringAfter("base64,", "")
                        if (data.isNotBlank()) {
                            images.put(data)
                        }
                    }
                }
                if (images.length() > 0) {
                    json.put("images", images)
                }
            }
            json.toString()
        }

        return """
            {
                "model": "$modelName",
                "messages": $apiMessages,
                "stream": false,
                "options": {
                    "temperature": ${currentConfig.temperature},
                    "num_predict": ${currentConfig.maxTokens}
                }
            }
        """.trimIndent()
    }

    private fun parseChatResponse(responseCode: Int, responseBody: String): Response {
        return when (responseCode) {
            HttpURLConnection.HTTP_OK -> {
                try {
                    val json = JSONObject(responseBody)
                    val message = json.optJSONObject("message")
                    val content = message?.optString("content", "") ?: ""

                    val evalCount = json.optInt("eval_count", 0)
                    val promptEvalCount = json.optInt("prompt_eval_count", 0)
                    val usage = Usage(
                        promptTokens = promptEvalCount,
                        completionTokens = evalCount
                    )

                    successResponse(content, usage)
                } catch (e: Exception) {
                    errorResponse("响应解析失败: ${e.message}")
                }
            }
            in 400..499 -> errorResponse("请求错误: $responseBody")
            in 500..599 -> unavailableResponse("Ollama服务端错误")
            else -> errorResponse("请求失败，状态码: $responseCode")
        }
    }

    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}

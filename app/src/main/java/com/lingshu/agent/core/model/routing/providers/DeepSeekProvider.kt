package com.lingshu.agent.core.model.routing.providers

import android.graphics.Bitmap
import android.util.Base64
import com.lingshu.agent.core.model.routing.Message
import com.lingshu.agent.core.model.routing.ModelConfig
import com.lingshu.agent.core.model.routing.ModelType
import com.lingshu.agent.core.model.routing.Response
import com.lingshu.agent.core.model.routing.Usage
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek 模型提供者
 * 基于 DeepSeek OpenAI 兼容 API 实现
 * API文档: https://platform.deepseek.com/api-docs/
 *
 * 主要特性：
 * - 支持文本对话（主打中文理解与生成）
 * - 支持多API Key轮询
 * - 支持长上下文对话
 */
class DeepSeekProvider(
    initialConfig: ModelConfig
) : BaseModelProvider(initialConfig) {

    companion object {
        /** DeepSeek API默认基础URL */
        private const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        /** 对话端点 */
        private const val CHAT_ENDPOINT = "/chat/completions"
        /** 默认模型名称 */
        private const val DEFAULT_MODEL = "deepseek-chat"
    }

    private val baseUrl: String
        get() = currentConfig.baseUrl ?: DEFAULT_BASE_URL

    private val modelName: String
        get() = currentConfig.modelName.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    override suspend fun chat(messages: List<Message>): Response {
        if (!currentConfig.isAvailable) {
            return unavailableResponse("DeepSeek未配置或已禁用")
        }

        return executeWithTiming {
            tryAllApiKeys { apiKey ->
                executeChatRequest(apiKey, messages)
            }
        }
    }

    /**
     * 执行实际的Chat API请求
     */
    private fun executeChatRequest(apiKey: String, messages: List<Message>): Response {
        val url = URL("$baseUrl$CHAT_ENDPOINT")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = currentConfig.timeoutMs.toInt()
                readTimeout = currentConfig.timeoutMs.toInt()
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
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
            errorResponse("网络请求异常: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 构建对话请求体（JSON格式）
     */
    private fun buildChatRequestBody(messages: List<Message>): String {
        val jsonMessages = messages.joinToString(",", "[", "]") { msg ->
            JSONObject(msg.toApiMap()).toString()
        }

        return """
            {
                "model": "$modelName",
                "messages": $jsonMessages,
                "temperature": ${currentConfig.temperature},
                "max_tokens": ${currentConfig.maxTokens},
                "stream": false
            }
        """.trimIndent()
    }

    /**
     * 解析对话API响应
     */
    private fun parseChatResponse(responseCode: Int, responseBody: String): Response {
        return when (responseCode) {
            HttpURLConnection.HTTP_OK -> {
                try {
                    val json = JSONObject(responseBody)
                    val content = json
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "") ?: ""

                    val usageJson = json.optJSONObject("usage")
                    val usage = if (usageJson != null) {
                        Usage(
                            promptTokens = usageJson.optInt("prompt_tokens", 0),
                            completionTokens = usageJson.optInt("completion_tokens", 0)
                        )
                    } else {
                        Usage.EMPTY
                    }

                    val id = json.optString("id", "")
                    successResponse(content, usage, id)
                } catch (e: Exception) {
                    errorResponse("响应解析失败: ${e.message}")
                }
            }
            429 -> rateLimitedResponse("API调用频率超限，请稍后重试")
            401, 403 -> errorResponse("API Key无效或权限不足")
            in 500..599 -> unavailableResponse("DeepSeek服务端错误")
            else -> errorResponse("请求失败，状态码: $responseCode")
        }
    }

    /**
     * DeepSeek视觉能力：使用Base64编码图片，通过兼容的多模态模型实现
     * 注意：如果使用的具体模型不支持视觉，会抛出异常
     */
    override suspend fun vision(image: Bitmap, prompt: String): String {
        val base64Image = encodeBitmapToBase64(image)

        val message = org.json.JSONObject()
            .put("role", "user")
            .put("content", org.json.JSONArray().apply {
                put(org.json.JSONObject().put("type", "text").put("text", prompt))
                put(
                    org.json.JSONObject().put("type", "image_url")
                        .put("image_url", org.json.JSONObject().put("url", "data:image/jpeg;base64,$base64Image"))
                )
            })

        val messages = listOf(Message.userWithImages(prompt, listOf("data:image/jpeg;base64,$base64Image")))
        val response = chat(messages)

        if (!response.isSuccess) {
            throw Exception("视觉请求失败: ${response.errorMessage}")
        }
        return response.content
    }

    /**
     * 将Bitmap编码为Base64字符串
     */
    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}

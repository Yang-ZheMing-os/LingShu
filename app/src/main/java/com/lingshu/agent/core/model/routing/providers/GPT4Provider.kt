package com.lingshu.agent.core.model.routing.providers

import android.graphics.Bitmap
import android.util.Base64
import com.lingshu.agent.core.model.routing.Message
import com.lingshu.agent.core.model.routing.ModelConfig
import com.lingshu.agent.core.model.routing.Response
import com.lingshu.agent.core.model.routing.Usage
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * GPT-4 模型提供者
 * 基于 OpenAI API 实现
 * API文档: https://platform.openai.com/docs/api-reference
 *
 * 主要特性：
 * - 支持文本对话（GPT-4o / GPT-4 Turbo）
 * - 支持图像理解（视觉能力）
 * - 支持多API Key轮询
 * - 支持函数调用（Function Calling）
 */
class GPT4Provider(
    initialConfig: ModelConfig
) : BaseModelProvider(initialConfig) {

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private const val CHAT_ENDPOINT = "/chat/completions"
        private const val DEFAULT_MODEL = "gpt-4o"
    }

    private val baseUrl: String
        get() = currentConfig.baseUrl ?: DEFAULT_BASE_URL

    private val modelName: String
        get() = currentConfig.modelName.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    override suspend fun chat(messages: List<Message>): Response {
        if (!currentConfig.isAvailable) {
            return unavailableResponse("GPT-4未配置或已禁用")
        }

        return executeWithTiming {
            tryAllApiKeys { apiKey ->
                executeChatRequest(apiKey, messages)
            }
        }
    }

    override suspend fun vision(image: Bitmap, prompt: String): String {
        if (!currentConfig.isAvailable) {
            throw IllegalStateException("GPT-4未配置或已禁用")
        }

        val base64Image = encodeBitmapToBase64(image)
        val messageWithImage = Message.userWithImages(
            content = prompt,
            imageUrls = listOf("data:image/jpeg;base64,$base64Image")
        )
        val messages = listOf(messageWithImage)

        val response = chat(messages)
        if (!response.isSuccess) {
            throw Exception("视觉请求失败: ${response.errorMessage}")
        }
        return response.content
    }

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
            429 -> rateLimitedResponse("OpenAI API调用频率超限或额度不足")
            401, 403 -> errorResponse("API Key无效或权限不足")
            in 500..599 -> unavailableResponse("OpenAI服务端错误")
            else -> {
                val errorMsg = try {
                    JSONObject(responseBody).optJSONObject("error")?.optString("message") ?: ""
                } catch (e: Exception) {
                    ""
                }
                errorResponse("请求失败，状态码: $responseCode, $errorMsg")
            }
        }
    }

    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}

package com.lingshu.agent.core.model.routing.providers

import android.graphics.Bitmap
import android.util.Base64
import com.lingshu.agent.core.model.routing.Message
import com.lingshu.agent.core.model.routing.ModelConfig
import com.lingshu.agent.core.model.routing.Response
import com.lingshu.agent.core.model.routing.Role
import com.lingshu.agent.core.model.routing.Usage
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Claude 3.5 Sonnet 模型提供者
 * 基于 Anthropic API 实现
 * API文档: https://docs.anthropic.com/en/api/messages
 *
 * 主要特性：
 * - 支持长文本理解（200K上下文）
 * - 支持图像理解（视觉能力）
 * - 支持多API Key轮询
 * - 优秀的代码生成与分析能力
 */
class ClaudeProvider(
    initialConfig: ModelConfig
) : BaseModelProvider(initialConfig) {

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.anthropic.com/v1"
        private const val MESSAGES_ENDPOINT = "/messages"
        private const val DEFAULT_MODEL = "claude-3-5-sonnet-20240620"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val MAX_TOKENS_DEFAULT = 4096
    }

    private val baseUrl: String
        get() = currentConfig.baseUrl ?: DEFAULT_BASE_URL

    private val modelName: String
        get() = currentConfig.modelName.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    override suspend fun chat(messages: List<Message>): Response {
        if (!currentConfig.isAvailable) {
            return unavailableResponse("Claude未配置或已禁用")
        }

        return executeWithTiming {
            tryAllApiKeys { apiKey ->
                executeMessagesRequest(apiKey, messages)
            }
        }
    }

    override suspend fun vision(image: Bitmap, prompt: String): String {
        if (!currentConfig.isAvailable) {
            throw IllegalStateException("Claude未配置或已禁用")
        }

        val base64Image = encodeBitmapToBase64(image)
        val mediaType = "image/jpeg"

        val contentBlocks = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", prompt))
            put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", mediaType)
                            .put("data", base64Image)
                    )
            )
        }

        val message = Message(
            role = Role.USER,
            content = prompt,
            imageUrls = listOf("data:image/jpeg;base64,$base64Image")
        )

        return executeVisionRequest(base64Image, mediaType, prompt, message)
    }

    private fun executeMessagesRequest(apiKey: String, messages: List<Message>): Response {
        val url = URL("$baseUrl$MESSAGES_ENDPOINT")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = currentConfig.timeoutMs.toInt()
                readTimeout = currentConfig.timeoutMs.toInt()
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
                doOutput = true
            }

            val requestBody = buildMessagesRequestBody(messages)
            connection.outputStream.use {
                it.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseBody = when (responseCode) {
                in 200..299 -> connection.inputStream.bufferedReader().readText()
                else -> connection.errorStream?.bufferedReader()?.readText() ?: ""
            }

            parseMessagesResponse(responseCode, responseBody)
        } catch (e: Exception) {
            errorResponse("网络请求异常: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun buildMessagesRequestBody(messages: List<Message>): String {
        // Claude API 要求 messages 中不能包含 SYSTEM role，系统提示词单独放在 system 字段
        val systemMessages = messages.filter { it.role == Role.SYSTEM }
        val nonSystemMessages = messages.filter { it.role != Role.SYSTEM }

        val systemPrompt = systemMessages.joinToString("\n\n") { it.content }

        val apiMessages = nonSystemMessages.joinToString(",", "[", "]") { msg ->
            buildSingleMessageJson(msg)
        }

        val maxTokens = minOf(currentConfig.maxTokens, MAX_TOKENS_DEFAULT)

        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"model\": \"$modelName\",")
        if (systemPrompt.isNotBlank()) {
            sb.append("\"system\": ${JSONObject.quote(systemPrompt)},")
        }
        sb.append("\"messages\": $apiMessages,")
        sb.append("\"max_tokens\": $maxTokens,")
        sb.append("\"temperature\": ${currentConfig.temperature},")
        sb.append("\"stream\": false")
        sb.append("}")
        return sb.toString()
    }

    private fun buildSingleMessageJson(message: Message): String {
        val role = when (message.role) {
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
            Role.TOOL -> "user"
            else -> "user"
        }

        val json = JSONObject()
        json.put("role", role)

        if (message.hasImages()) {
            val content = JSONArray()
            content.put(JSONObject().put("type", "text").put("text", message.content))

            message.imageUrls.forEach { url ->
                if (url.startsWith("data:image/")) {
                    val parts = url.substring(5).split(";", limit = 2)
                    val mediaType = parts[0]
                    val base64Data = if (parts.size > 1) parts[1].removePrefix("base64,") else ""
                    content.put(
                        JSONObject()
                            .put("type", "image")
                            .put(
                                "source",
                                JSONObject()
                                    .put("type", "base64")
                                    .put("media_type", mediaType)
                                    .put("data", base64Data)
                            )
                    )
                }
            }
            json.put("content", content)
        } else {
            json.put("content", message.content)
        }

        return json.toString()
    }

    private fun parseMessagesResponse(responseCode: Int, responseBody: String): Response {
        return when (responseCode) {
            HttpURLConnection.HTTP_OK -> {
                try {
                    val json = JSONObject(responseBody)
                    val contentArray = json.optJSONArray("content")
                    val content = StringBuilder()
                    for (i in 0 until (contentArray?.length() ?: 0)) {
                        val block = contentArray?.optJSONObject(i)
                        if (block?.optString("type") == "text") {
                            content.append(block.optString("text", ""))
                        }
                    }

                    val usageJson = json.optJSONObject("usage")
                    val usage = if (usageJson != null) {
                        Usage(
                            promptTokens = usageJson.optInt("input_tokens", 0),
                            completionTokens = usageJson.optInt("output_tokens", 0)
                        )
                    } else {
                        Usage.EMPTY
                    }

                    val id = json.optString("id", "")
                    successResponse(content.toString(), usage, id)
                } catch (e: Exception) {
                    errorResponse("响应解析失败: ${e.message}")
                }
            }
            429 -> rateLimitedResponse("Anthropic API调用频率超限或额度不足")
            401, 403 -> errorResponse("API Key无效或权限不足")
            in 500..599 -> unavailableResponse("Anthropic服务端错误")
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

    private fun executeVisionRequest(
        base64Image: String,
        mediaType: String,
        prompt: String,
        message: Message
    ): String {
        val apiKeys = currentConfig.apiKeys.filter { it.isNotBlank() }
        if (apiKeys.isEmpty()) {
            throw IllegalStateException("未配置API Key")
        }

        var lastError: Exception? = null
        for (apiKey in apiKeys) {
            try {
                val url = URL("$baseUrl$MESSAGES_ENDPOINT")
                val connection = url.openConnection() as HttpURLConnection
                val response = try {
                    connection.apply {
                        requestMethod = "POST"
                        connectTimeout = currentConfig.timeoutMs.toInt()
                        readTimeout = currentConfig.timeoutMs.toInt()
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("x-api-key", apiKey)
                        setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
                        doOutput = true
                    }

                    val content = JSONArray().apply {
                        put(JSONObject().put("type", "text").put("text", prompt))
                        put(
                            JSONObject()
                                .put("type", "image")
                                .put(
                                    "source",
                                    JSONObject()
                                        .put("type", "base64")
                                        .put("media_type", mediaType)
                                        .put("data", base64Image)
                                )
                        )
                    }

                    val requestBody = """
                    {
                        "model": "$modelName",
                        "max_tokens": $MAX_TOKENS_DEFAULT,
                        "messages": [{"role": "user", "content": ${content.toString()}}]
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

                    parseMessagesResponse(responseCode, responseBody)
                } finally {
                    connection.disconnect()
                }

                if (response.isSuccess) {
                    return response.content
                } else if (!response.isRateLimited) {
                    throw Exception(response.errorMessage ?: "视觉请求失败")
                }
                lastError = Exception(response.errorMessage ?: "被限流")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: Exception("所有API Key均调用失败")
    }

    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}

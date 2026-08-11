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
import java.net.URLEncoder

/**
 * Gemini 模型提供者
 * 基于 Google Gemini API 实现
 * API文档: https://ai.google.dev/api
 *
 * 主要特性：
 * - 支持文本对话
 * - 支持图像理解（视觉能力）
 * - 支持语音识别（通过Audio输入转文本）
 * - 支持语音合成（TTS）
 * - 支持多API Key轮询
 */
class GeminiProvider(
    initialConfig: ModelConfig
) : BaseModelProvider(initialConfig) {

    companion object {
        private const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val DEFAULT_MODEL = "gemini-1.5-pro"

        /** 生成内容端点 */
        private const val GENERATE_CONTENT = ":generateContent"
        /** 流式生成端点 */
        private const val STREAM_GENERATE = ":streamGenerateContent"
        /** 嵌入端点 */
        private const val EMBED = ":embedContent"

        /** 音频MIME类型 */
        private const val AUDIO_MIME = "audio/wav"
        /** 输出音频MIME类型 */
        private const val OUTPUT_AUDIO_MIME = "audio/mp3"
    }

    private val baseUrl: String
        get() = currentConfig.baseUrl ?: DEFAULT_BASE_URL

    private val modelName: String
        get() = currentConfig.modelName.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    override suspend fun chat(messages: List<Message>): Response {
        if (!currentConfig.isAvailable) {
            return unavailableResponse("Gemini未配置或已禁用")
        }

        return executeWithTiming {
            tryAllApiKeys { apiKey ->
                executeGenerateContentRequest(apiKey, messages, null)
            }
        }
    }

    override suspend fun vision(image: Bitmap, prompt: String): String {
        if (!currentConfig.isAvailable) {
            throw IllegalStateException("Gemini未配置或已禁用")
        }

        val base64Image = encodeBitmapToBase64(image)
        val inlineData = mapOf(
            "mime_type" to "image/jpeg",
            "data" to base64Image
        )

        val apiKeys = currentConfig.apiKeys.filter { it.isNotBlank() }
        if (apiKeys.isEmpty()) {
            throw IllegalStateException("未配置API Key")
        }

        var lastError: Exception? = null
        for (apiKey in apiKeys) {
            try {
                val contents = JSONArray().apply {
                    put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "parts",
                                JSONArray().apply {
                                    put(JSONObject().put("text", prompt))
                                    put(JSONObject().put("inline_data", JSONObject(inlineData as Map<String, Any>)))
                                }
                            )
                    )
                }

                val response = callGenerateContent(apiKey, contents)
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

    override suspend fun transcribe(audio: ByteArray): String {
        if (!currentConfig.isAvailable) {
            throw IllegalStateException("Gemini未配置或已禁用")
        }

        val base64Audio = Base64.encodeToString(audio, Base64.NO_WRAP)
        val prompt = "请将以下音频内容转录为文字，尽量准确地识别所有语音内容。"

        val apiKeys = currentConfig.apiKeys.filter { it.isNotBlank() }
        if (apiKeys.isEmpty()) {
            throw IllegalStateException("未配置API Key")
        }

        var lastError: Exception? = null
        for (apiKey in apiKeys) {
            try {
                val contents = JSONArray().apply {
                    put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "parts",
                                JSONArray().apply {
                                    put(JSONObject().put("text", prompt))
                                    put(
                                        JSONObject().put(
                                            "inline_data",
                                            JSONObject()
                                                .put("mime_type", AUDIO_MIME)
                                                .put("data", base64Audio)
                                        )
                                    )
                                }
                            )
                    )
                }

                val response = callGenerateContent(apiKey, contents)
                if (response.isSuccess) {
                    return response.content
                } else if (!response.isRateLimited) {
                    throw Exception(response.errorMessage ?: "语音识别失败")
                }
                lastError = Exception(response.errorMessage ?: "被限流")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: Exception("所有API Key均调用失败")
    }

    override suspend fun synthesize(text: String): ByteArray {
        if (!currentConfig.isAvailable) {
            throw IllegalStateException("Gemini未配置或已禁用")
        }

        // 使用语音合成提示词，要求模型输出音频
        val prompt = "请用自然、清晰的语音朗读以下文本：\n\n$text"
        val modifiedPrompt = "$prompt\n\n请直接输出音频文件，不要输出任何文本说明。"

        val apiKeys = currentConfig.apiKeys.filter { it.isNotBlank() }
        if (apiKeys.isEmpty()) {
            throw IllegalStateException("未配置API Key")
        }

        var lastError: Exception? = null
        for (apiKey in apiKeys) {
            try {
                val result = callTtsRequest(apiKey, text)
                if (result != null) {
                    return result
                }
                lastError = Exception("语音合成失败")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: Exception("所有API Key均调用失败")
    }

    /**
     * 执行语音合成请求（使用Text-to-Speech专门模型）
     */
    private fun callTtsRequest(apiKey: String, text: String): ByteArray? {
        val encodedText = URLEncoder.encode(text, "UTF-8")
        val ttsUrl = "$baseUrl/models/audio-001:synthesizeText?key=$apiKey"

        val url = URL(ttsUrl)
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = currentConfig.timeoutMs.toInt()
                readTimeout = currentConfig.timeoutMs.toInt()
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }

            val requestBody = """
                {
                    "text": ${JSONObject.quote(text)},
                    "generationConfig": {
                        "temperature": ${currentConfig.temperature}
                    }
                }
            """.trimIndent()

            connection.outputStream.use {
                it.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val responseBody = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(responseBody)
                val audioContent = json.optString("audioContent", "")
                if (audioContent.isNotBlank()) {
                    return Base64.decode(audioContent, Base64.DEFAULT)
                }
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 执行通用的内容生成请求
     */
    private fun executeGenerateContentRequest(
        apiKey: String,
        messages: List<Message>,
        systemInstruction: Message?
    ): Response {
        // 构建Gemini格式的contents数组
        val contents = JSONArray()
        messages.forEach { msg ->
            if (msg.role == Role.SYSTEM) {
                // Gemini支持system_instruction字段，在其他地方处理
                return@forEach
            }
            val role = when (msg.role) {
                Role.USER -> "user"
                Role.ASSISTANT -> "model"
                Role.TOOL -> "user"
                else -> "user"
            }

            val parts = JSONArray()
            parts.put(JSONObject().put("text", msg.content))

            // 处理图片
            msg.imageUrls.forEach { url ->
                if (url.startsWith("data:image/")) {
                    val partsData = url.substring(5).split(";", limit = 2)
                    val mime = partsData[0]
                    val data = if (partsData.size > 1) partsData[1].removePrefix("base64,") else ""
                    parts.put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject()
                                .put("mime_type", mime)
                                .put("data", data)
                        )
                    )
                }
            }

            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", parts)
            )
        }

        return callGenerateContent(apiKey, contents, systemInstruction)
    }

    /**
     * 调用generateContent API
     */
    private fun callGenerateContent(
        apiKey: String,
        contents: JSONArray,
        systemInstruction: Message? = null
    ): Response {
        val url = URL("$baseUrl/models/$modelName$GENERATE_CONTENT?key=$apiKey")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = currentConfig.timeoutMs.toInt()
                readTimeout = currentConfig.timeoutMs.toInt()
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }

            val requestBodyJson = JSONObject()
            requestBodyJson.put("contents", contents)

            // 系统提示词
            systemInstruction?.let {
                requestBodyJson.put(
                    "system_instruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", it.content))
                    )
                )
            }

            // 生成配置
            requestBodyJson.put(
                "generationConfig",
                JSONObject()
                    .put("temperature", currentConfig.temperature.toDouble())
                    .put("maxOutputTokens", currentConfig.maxTokens)
            )

            connection.outputStream.use {
                it.write(requestBodyJson.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseBody = when (responseCode) {
                in 200..299 -> connection.inputStream.bufferedReader().readText()
                else -> connection.errorStream?.bufferedReader()?.readText() ?: ""
            }

            parseGenerateContentResponse(responseCode, responseBody)
        } catch (e: Exception) {
            errorResponse("网络请求异常: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseGenerateContentResponse(responseCode: Int, responseBody: String): Response {
        return when (responseCode) {
            HttpURLConnection.HTTP_OK -> {
                try {
                    val json = JSONObject(responseBody)
                    val candidates = json.optJSONArray("candidates")
                    val content = StringBuilder()

                    for (i in 0 until (candidates?.length() ?: 0)) {
                        val candidate = candidates?.optJSONObject(i)
                        val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
                        for (j in 0 until (parts?.length() ?: 0)) {
                            val part = parts?.optJSONObject(j)
                            val text = part?.optString("text", "") ?: ""
                            content.append(text)
                        }
                    }

                    val usageJson = json.optJSONObject("usageMetadata")
                    val usage = if (usageJson != null) {
                        Usage(
                            promptTokens = usageJson.optInt("promptTokenCount", 0),
                            completionTokens = usageJson.optInt("candidatesTokenCount", 0)
                        )
                    } else {
                        Usage.EMPTY
                    }

                    successResponse(content.toString(), usage)
                } catch (e: Exception) {
                    errorResponse("响应解析失败: ${e.message}")
                }
            }
            429 -> rateLimitedResponse("Google API调用频率超限或额度不足")
            401, 403 -> errorResponse("API Key无效或权限不足")
            in 500..599 -> unavailableResponse("Google服务端错误")
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

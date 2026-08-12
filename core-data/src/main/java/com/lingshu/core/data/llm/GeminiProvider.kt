package com.lingshu.core.data.llm

import com.lingshu.core.common.di.IoDispatcher
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gemini LLM Provider，通过 Google Generative Language REST API 调用。
 * 文档：https://ai.google.dev/api/rest/v1beta/models/generateContent
 *
 * 端点：
 *  - 生成： POST /v1beta/models/{model}:generateContent?key={apiKey}
 *  - 流式： POST /v1beta/models/{model}:streamGenerateContent?key={apiKey}
 *  - 嵌入： POST /v1beta/models/{model}:batchEmbedContents?key={apiKey}
 */
@Singleton
class GeminiProvider @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ILlmProvider {

    override val type: ModelProviderType = ModelProviderType.GEMINI

    private val moduleTag = "GeminiProvider"

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        private const val DEFAULT_EMBED_MODEL = "embedding-001"
    }

    private fun createClient(timeoutSeconds: Int): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong() * 3, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
    }

    private fun normalizeBaseUrl(baseUrl: String): String {
        var normalized = baseUrl.ifBlank { DEFAULT_BASE_URL }
        if (normalized.endsWith("/")) normalized = normalized.dropLast(1)
        // 移除可能的 /v1beta 后缀，统一在 URL 构造时添加
        if (normalized.endsWith("/v1beta")) normalized = normalized.dropLast("/v1beta".length)
        return normalized
    }

    /**
     * 构建 Gemini generateContent 请求体。
     * system 消息会作为 systemInstruction 传递，其余消息映射为 contents。
     * Gemini 角色映射：assistant -> model
     */
    private fun buildGenerateContentBody(
        messages: List<ChatMessage>,
        config: LlmConfig
    ): String {
        val json = JSONObject()
        val systemMessages = messages.filter { it.role == "system" }
        val dialogueMessages = messages.filter { it.role != "system" }

        // systemInstruction
        if (systemMessages.isNotEmpty()) {
            val sysInstruction = JSONObject()
            val parts = JSONArray()
            for (sys in systemMessages) {
                val part = JSONObject()
                part.put("text", sys.content)
                parts.put(part)
            }
            sysInstruction.put("parts", parts)
            json.put("systemInstruction", sysInstruction)
        }

        // contents
        val contents = JSONArray()
        for (msg in dialogueMessages) {
            val content = JSONObject()
            val role = if (msg.role == "assistant") "model" else msg.role
            content.put("role", role)
            val parts = JSONArray()
            val part = JSONObject()
            part.put("text", msg.content)
            parts.put(part)
            content.put("parts", parts)
            contents.put(content)
        }
        json.put("contents", contents)

        // generationConfig
        val genConfig = JSONObject()
        genConfig.put("temperature", config.temperature.toDouble())
        genConfig.put("topP", config.topP.toDouble())
        genConfig.put("maxOutputTokens", config.maxTokens)
        json.put("generationConfig", genConfig)

        return json.toString()
    }

    override suspend fun chat(
        messages: List<ChatMessage>,
        config: LlmConfig,
        traceId: String
    ): Result<String> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val totalMsgLength = messages.sumOf { it.content.length }

        LingShuLog.i(moduleTag, "[$traceId] chat start | model=${config.modelName} | " +
                "msgCount=${messages.size} | totalMsgLength=$totalMsgLength | " +
                "temp=${config.temperature} | maxTokens=${config.maxTokens}")

        if (config.apiKey.isBlank()) {
            return@withContext Result.error(
                code = ErrorCodes.API_KEY_INVALID,
                message = "Gemini API Key 未配置"
            )
        }

        val baseUrl = normalizeBaseUrl(config.baseUrl)
        val model = config.modelName.ifBlank { "gemini-1.5-flash" }
        val url = "$baseUrl/v1beta/models/$model:generateContent?key=${config.apiKey}"
        val requestBody = buildGenerateContentBody(messages, config)

        val client = createClient(config.timeoutSeconds)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(mediaType))
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            val response = client.newCall(request).execute()
            val httpCode = response.code
            val responseBodyStr = response.body?.string() ?: ""

            LingShuLog.d(moduleTag, "[$traceId] HTTP | code=$httpCode | bodyLen=${responseBodyStr.length}")

            if (!response.isSuccessful) {
                val truncated = if (responseBodyStr.length > 500) responseBodyStr.substring(0, 500) + "..." else responseBodyStr
                LingShuLog.e(moduleTag, "[$traceId] HTTP error | code=$httpCode | body=$truncated")
                response.close()
                return@withContext Result.error(
                    code = if (httpCode == 401 || httpCode == 403) ErrorCodes.API_KEY_INVALID else ErrorCodes.SERVER_NO_RESPONSE,
                    message = "HTTP $httpCode: ${parseGeminiError(responseBodyStr)}"
                )
            }

            response.close()
            val content = parseGenerateContentResponse(responseBodyStr)
            val elapsed = System.currentTimeMillis() - startTime

            LingShuLog.i(moduleTag, "[$traceId] chat success | httpCode=$httpCode | " +
                    "outputLength=${content.length} | elapsedMs=$elapsed")

            return@withContext Result.success(content)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] chat exception | elapsedMs=$elapsed | " +
                    "type=${e.javaClass.simpleName} | msg=${e.message}", e)
            return@withContext Result.error(
                code = ErrorCodes.NETWORK_UNAVAILABLE,
                message = e.message ?: "Gemini request failed",
                cause = e
            )
        }
    }

    override suspend fun chatStream(
        messages: List<ChatMessage>,
        config: LlmConfig,
        onToken: (String) -> Unit,
        traceId: String
    ): Result<String> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()

        LingShuLog.i(moduleTag, "[$traceId] chatStream start | model=${config.modelName} | msgCount=${messages.size}")

        if (config.apiKey.isBlank()) {
            return@withContext Result.error(
                code = ErrorCodes.API_KEY_INVALID,
                message = "Gemini API Key 未配置"
            )
        }

        val baseUrl = normalizeBaseUrl(config.baseUrl)
        val model = config.modelName.ifBlank { "gemini-1.5-flash" }
        // 使用 alt=sse 获取 Server-Sent Events 流
        val url = "$baseUrl/v1beta/models/$model:streamGenerateContent?alt=sse&key=${config.apiKey}"
        val requestBody = buildGenerateContentBody(messages, config)

        val client = createClient(config.timeoutSeconds)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody(mediaType))
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            val response = client.newCall(request).execute()
            val httpCode = response.code
            LingShuLog.d(moduleTag, "[$traceId] chatStream HTTP | code=$httpCode")

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                LingShuLog.e(moduleTag, "[$traceId] chatStream HTTP error | code=$httpCode | body=${errorBody.take(300)}")
                response.close()
                return@withContext Result.error(
                    code = if (httpCode == 401 || httpCode == 403) ErrorCodes.API_KEY_INVALID else ErrorCodes.SERVER_NO_RESPONSE,
                    message = "HTTP $httpCode: ${parseGeminiError(errorBody)}"
                )
            }

            val responseBody = response.body ?: run {
                response.close()
                return@withContext Result.error(
                    code = ErrorCodes.SERVER_NO_RESPONSE,
                    message = "Empty response body"
                )
            }

            val fullContent = StringBuilder()
            var tokenCount = 0

            responseBody.byteStream().bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: continue
                    if (!currentLine.startsWith("data: ")) continue
                    val jsonData = currentLine.removePrefix("data: ").trim()
                    if (jsonData.isBlank() || jsonData == "[DONE]") continue

                    try {
                        val json = JSONObject(jsonData)
                        val candidates = json.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val candidate = candidates.optJSONObject(0)
                            val contentObj = candidate?.optJSONObject("content")
                            val parts = contentObj?.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                val text = parts.optJSONObject(0)?.optString("text") ?: ""
                                if (text.isNotEmpty()) {
                                    fullContent.append(text)
                                    tokenCount++
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        onToken(text)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }

            response.close()
            val elapsed = System.currentTimeMillis() - startTime
            LingShuLog.i(moduleTag, "[$traceId] chatStream success | httpCode=$httpCode | " +
                    "streamTokens=$tokenCount | totalLength=${fullContent.length} | elapsedMs=$elapsed")

            return@withContext Result.success(fullContent.toString())
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] chatStream exception | elapsedMs=$elapsed | " +
                    "type=${e.javaClass.simpleName} | msg=${e.message}", e)
            return@withContext Result.error(
                code = ErrorCodes.NETWORK_UNAVAILABLE,
                message = e.message ?: "Gemini stream failed",
                cause = e
            )
        }
    }

    override suspend fun embeddings(
        texts: List<String>,
        config: LlmConfig,
        traceId: String
    ): Result<List<List<Float>>> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(moduleTag, "[$traceId] embeddings start | textCount=${texts.size}")

        if (config.apiKey.isBlank()) {
            return@withContext Result.error(
                code = ErrorCodes.API_KEY_INVALID,
                message = "Gemini API Key 未配置"
            )
        }

        val baseUrl = normalizeBaseUrl(config.baseUrl)
        val embedModel = config.modelName.ifBlank { DEFAULT_EMBED_MODEL }
        val url = "$baseUrl/v1beta/models/$embedModel:batchEmbedContents?key=${config.apiKey}"

        val json = JSONObject()
        val requests = JSONArray()
        for (text in texts) {
            val req = JSONObject()
            val content = JSONObject()
            val parts = JSONArray()
            val part = JSONObject()
            part.put("text", text)
            parts.put(part)
            content.put("parts", parts)
            req.put("content", content)
            req.put("taskType", "RETRIEVAL_DOCUMENT")
            requests.put(req)
        }
        json.put("requests", requests)

        val client = createClient(config.timeoutSeconds)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody(mediaType))
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            val response = client.newCall(request).execute()
            val httpCode = response.code
            val responseBodyStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                response.close()
                LingShuLog.e(moduleTag, "[$traceId] embeddings HTTP error | code=$httpCode | body=${responseBodyStr.take(300)}")
                return@withContext Result.error(
                    code = ErrorCodes.SERVER_NO_RESPONSE,
                    message = "HTTP $httpCode: ${parseGeminiError(responseBodyStr)}"
                )
            }

            response.close()
            val result = mutableListOf<List<Float>>()
            val respJson = JSONObject(responseBodyStr)
            val embeddingsArr = respJson.optJSONArray("embeddings")
            if (embeddingsArr != null) {
                for (i in 0 until embeddingsArr.length()) {
                    val values = embeddingsArr.optJSONObject(i)?.optJSONArray("values")
                    if (values != null) {
                        val floats = mutableListOf<Float>()
                        for (j in 0 until values.length()) {
                            floats.add(values.getDouble(j).toFloat())
                        }
                        result.add(floats)
                    }
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            LingShuLog.i(moduleTag, "[$traceId] embeddings success | count=${result.size} | " +
                    "dims=${result.firstOrNull()?.size ?: 0} | elapsedMs=$elapsed")

            return@withContext Result.success(result)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] embeddings exception | elapsedMs=$elapsed | msg=${e.message}", e)
            return@withContext Result.error(
                code = ErrorCodes.NETWORK_UNAVAILABLE,
                message = e.message ?: "Gemini embeddings failed",
                cause = e
            )
        }
    }

    override fun isAvailable(config: LlmConfig): Boolean {
        val apiKeyOk = config.apiKey.isNotBlank()
        val valid = apiKeyOk
        if (!valid) {
            LingShuLog.w(moduleTag, "isAvailable=false (missing apiKey)")
        }
        return valid
    }

    private fun parseGenerateContentResponse(responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            val candidates = json.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val candidate = candidates.optJSONObject(0)
                val content = candidate?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until parts.length()) {
                        sb.append(parts.optJSONObject(i)?.optString("text") ?: "")
                    }
                    return sb.toString()
                }
            }
            ""
        } catch (e: Exception) {
            LingShuLog.e(moduleTag, "parse response failed: ${e.message}", e)
            ""
        }
    }

    private fun parseGeminiError(responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            val error = json.optJSONObject("error")
            error?.optString("message") ?: responseBody.take(200)
        } catch (_: Exception) {
            responseBody.take(200)
        }
    }
}

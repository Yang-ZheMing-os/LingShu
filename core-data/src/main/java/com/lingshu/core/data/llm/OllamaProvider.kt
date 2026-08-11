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

@Singleton
class OllamaProvider @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ILlmProvider {

    override val type: ModelProviderType = ModelProviderType.OLLAMA

    private val moduleTag = "OllamaProvider"

    companion object {
        const val DEFAULT_BASE_URL = "http://10.0.2.2:11434"
        val SUPPORTED_MODELS = listOf(
            "qwen2.5:7b",
            "qwen2.5:3b",
            "qwen2.5-coder:7b",
            "qwen2.5-coder:3b",
            "llama3.1:8b",
            "llama3.1:8b-instruct-q4_0",
            "qwen2:7b",
            "mistral:7b",
            "codellama:7b",
            "nomic-embed-text"
        )
    }

    private fun createClient(timeoutSeconds: Int): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong() * 3, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
    }

    private fun normalizeBaseUrl(baseUrl: String): String {
        var normalized = baseUrl
        if (normalized.endsWith("/")) {
            normalized = normalized.dropLast(1)
        }
        return normalized
    }

    private fun buildChatRequestBody(messages: List<ChatMessage>, config: LlmConfig, stream: Boolean = false): String {
        val json = JSONObject()
        json.put("model", config.modelName)
        json.put("stream", stream)

        val messagesArray = JSONArray()
        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)
            msgObj.put("content", msg.content)
            messagesArray.put(msgObj)
        }
        json.put("messages", messagesArray)

        val options = JSONObject()
        options.put("temperature", config.temperature.toDouble())
        options.put("top_p", config.topP.toDouble())
        options.put("num_predict", config.maxTokens)
        json.put("options", options)

        return json.toString()
    }

    private fun buildEmbeddingsRequestBody(text: String, config: LlmConfig): String {
        val json = JSONObject()
        json.put("model", config.modelName)
        json.put("prompt", text)
        return json.toString()
    }

    override suspend fun chat(
        messages: List<ChatMessage>,
        config: LlmConfig,
        traceId: String
    ): Result<String> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val totalMsgLength = messages.sumOf { it.content.length }

        LingShuLog.i(moduleTag, "[$traceId] chat start | provider=$type | model=${config.modelName} | " +
                "baseUrl=${config.baseUrl} | msgCount=${messages.size} | totalMsgLength=$totalMsgLength | " +
                "temp=${config.temperature} | topP=${config.topP} | maxTokens=${config.maxTokens}")

        for (i in messages.indices) {
            LingShuLog.d(moduleTag, "[$traceId] msg[$i] role=${messages[i].role} length=${messages[i].content.length}")
        }

        val baseUrl = normalizeBaseUrl(config.baseUrl.ifBlank { DEFAULT_BASE_URL })
        val chatUrl = "$baseUrl/api/chat"
        val requestBody = buildChatRequestBody(messages, config, stream = false)

        val client = createClient(config.timeoutSeconds)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(chatUrl)
            .post(requestBody.toRequestBody(mediaType))
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            val response = client.newCall(request).execute()
            val httpCode = response.code
            val responseBodyStr = response.body?.string() ?: ""

            LingShuLog.d(moduleTag, "[$traceId] HTTP response | code=$httpCode | bodyLength=${responseBodyStr.length}")

            if (!response.isSuccessful) {
                val truncatedBody = if (responseBodyStr.length > 500) {
                    responseBodyStr.substring(0, 500) + "...(truncated)"
                } else {
                    responseBodyStr
                }
                LingShuLog.e(moduleTag, "[$traceId] HTTP error | code=$httpCode | errorBody=$truncatedBody")
                response.close()
                return@withContext Result.error(
                    code = if (httpCode in 500..599) ErrorCodes.SERVER_NO_RESPONSE else ErrorCodes.UNKNOWN_ERROR,
                    message = "HTTP $httpCode: ${parseOllamaErrorMessage(responseBodyStr)}"
                )
            }

            response.close()
            val content = parseOllamaChatResponse(responseBodyStr)
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
                message = e.message ?: "Ollama request failed (check if Ollama is running at $baseUrl)",
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
        val totalMsgLength = messages.sumOf { it.content.length }

        LingShuLog.i(moduleTag, "[$traceId] chatStream start | provider=$type | model=${config.modelName} | " +
                "baseUrl=${config.baseUrl} | msgCount=${messages.size} | totalMsgLength=$totalMsgLength")

        val baseUrl = normalizeBaseUrl(config.baseUrl.ifBlank { DEFAULT_BASE_URL })
        val chatUrl = "$baseUrl/api/chat"
        val requestBody = buildChatRequestBody(messages, config, stream = true)

        val client = createClient(config.timeoutSeconds)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(chatUrl)
            .post(requestBody.toRequestBody(mediaType))
            .addHeader("Content-Type", "application/json")
            .build()

        try {
            val response = client.newCall(request).execute()
            val httpCode = response.code
            LingShuLog.d(moduleTag, "[$traceId] chatStream HTTP response | code=$httpCode")

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                val truncatedBody = if (errorBody.length > 500) {
                    errorBody.substring(0, 500) + "...(truncated)"
                } else {
                    errorBody
                }
                LingShuLog.e(moduleTag, "[$traceId] chatStream HTTP error | code=$httpCode | errorBody=$truncatedBody")
                response.close()
                return@withContext Result.error(
                    code = ErrorCodes.SERVER_NO_RESPONSE,
                    message = "HTTP $httpCode: ${parseOllamaErrorMessage(errorBody)}"
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
                    if (currentLine.isBlank()) continue

                    try {
                        val json = JSONObject(currentLine)
                        val message = json.optJSONObject("message")
                        val content = message?.optString("content") ?: ""
                        if (content.isNotEmpty()) {
                            fullContent.append(content)
                            tokenCount++
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                onToken(content)
                            }
                        }
                        if (json.optBoolean("done", false)) {
                            break
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
                message = e.message ?: "Ollama stream failed",
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
        LingShuLog.i(moduleTag, "[$traceId] embeddings start | provider=$type | model=${config.modelName} | " +
                "textCount=${texts.size}")

        val baseUrl = normalizeBaseUrl(config.baseUrl.ifBlank { DEFAULT_BASE_URL })
        val embeddingsUrl = "$baseUrl/api/embeddings"

        val client = createClient(config.timeoutSeconds)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val result = mutableListOf<List<Float>>()

        try {
            for ((index, text) in texts.withIndex()) {
                val requestBody = buildEmbeddingsRequestBody(text, config)
                val request = Request.Builder()
                    .url(embeddingsUrl)
                    .post(requestBody.toRequestBody(mediaType))
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                val httpCode = response.code
                val responseBodyStr = response.body?.string() ?: ""

                LingShuLog.d(moduleTag, "[$traceId] embeddings[$index] HTTP | code=$httpCode")

                if (!response.isSuccessful) {
                    response.close()
                    val truncatedBody = if (responseBodyStr.length > 500) {
                        responseBodyStr.substring(0, 500) + "...(truncated)"
                    } else {
                        responseBodyStr
                    }
                    LingShuLog.e(moduleTag, "[$traceId] embeddings[$index] HTTP error | code=$httpCode | errorBody=$truncatedBody")
                    return@withContext Result.error(
                        code = ErrorCodes.SERVER_NO_RESPONSE,
                        message = "HTTP $httpCode: ${parseOllamaErrorMessage(responseBodyStr)}"
                    )
                }

                response.close()
                val embedding = parseOllamaEmbeddingsResponse(responseBodyStr)
                result.add(embedding)
            }

            val elapsed = System.currentTimeMillis() - startTime
            LingShuLog.i(moduleTag, "[$traceId] embeddings success | count=${result.size} | " +
                    "dims=${result.firstOrNull()?.size ?: 0} | elapsedMs=$elapsed")

            return@withContext Result.success(result)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] embeddings exception | elapsedMs=$elapsed | " +
                    "type=${e.javaClass.simpleName} | msg=${e.message}", e)
            return@withContext Result.error(
                code = ErrorCodes.NETWORK_UNAVAILABLE,
                message = e.message ?: "Ollama embeddings failed",
                cause = e
            )
        }
    }

    override fun isAvailable(config: LlmConfig): Boolean {
        val baseUrlOk = config.baseUrl.isNotBlank() || true
        val modelOk = config.modelName.isNotBlank()
        val valid = baseUrlOk && modelOk
        if (!valid) {
            LingShuLog.w(moduleTag, "isAvailable=false | baseUrl=${config.baseUrl} | model=${config.modelName}")
        }
        return valid
    }

    private fun parseOllamaChatResponse(responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            val message = json.optJSONObject("message")
            message?.optString("content", "") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseOllamaEmbeddingsResponse(responseBody: String): List<Float> {
        return try {
            val json = JSONObject(responseBody)
            val embeddingArr = json.getJSONArray("embedding")
            val floats = mutableListOf<Float>()
            for (i in 0 until embeddingArr.length()) {
                floats.add(embeddingArr.getDouble(i).toFloat())
            }
            floats
        } catch (e: Exception) {
            LingShuLog.e(moduleTag, "parse embeddings failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun parseOllamaErrorMessage(responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            json.optString("error") ?: responseBody.take(200)
        } catch (_: Exception) {
            responseBody.take(200)
        }
    }
}

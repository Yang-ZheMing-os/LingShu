package com.lingshu.core.data.llm

import com.lingshu.core.common.di.IoDispatcher
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

@Singleton
class DeepSeekProvider @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ILlmProvider {

    override val type: ModelProviderType = ModelProviderType.DEEPSEEK

    private val moduleTag = "DeepSeekProvider"

    companion object {
        private const val MAX_RETRIES = 3
        private const val INITIAL_DELAY_MS = 1000L
        private const val BACKOFF_MULTIPLIER = 2.0
        private const val MAX_DELAY_MS = 10000L
    }

    private fun createClient(timeoutSeconds: Int): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
    }

    private fun estimateTokens(messages: List<ChatMessage>): Int {
        var total = 0
        for (msg in messages) {
            total += msg.content.length / 4 + msg.role.length / 2 + 2
        }
        return total.coerceAtLeast(1)
    }

    private fun buildChatRequestBody(messages: List<ChatMessage>, config: LlmConfig, stream: Boolean = false): String {
        val json = JSONObject()
        json.put("model", config.modelName)
        json.put("temperature", config.temperature.toDouble())
        json.put("top_p", config.topP.toDouble())
        json.put("max_tokens", config.maxTokens)
        json.put("stream", stream)

        val messagesArray = JSONArray()
        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)
            msgObj.put("content", msg.content)
            messagesArray.put(msgObj)
        }
        json.put("messages", messagesArray)

        return json.toString()
    }

    private fun buildEmbeddingsRequestBody(texts: List<String>, config: LlmConfig): String {
        val json = JSONObject()
        json.put("model", config.modelName)
        val inputArray = JSONArray()
        for (text in texts) {
            inputArray.put(text)
        }
        json.put("input", inputArray)
        return json.toString()
    }

    private fun buildRequest(url: String, apiKey: String, body: String): Request {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = body.toRequestBody(mediaType)
        return Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()
    }

    private fun normalizeBaseUrl(baseUrl: String): String {
        var normalized = baseUrl
        if (!normalized.endsWith("/")) {
            normalized = "$normalized/"
        }
        return normalized
    }

    override suspend fun chat(
        messages: List<ChatMessage>,
        config: LlmConfig,
        traceId: String
    ): Result<String> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val estimatedTokens = estimateTokens(messages)
        val totalMsgLength = messages.sumOf { it.content.length }

        LingShuLog.i(moduleTag, "[$traceId] chat start | provider=$type | model=${config.modelName} | " +
                "msgCount=${messages.size} | totalMsgLength=$totalMsgLength | estimatedTokens=$estimatedTokens | " +
                "temp=${config.temperature} | topP=${config.topP} | maxTokens=${config.maxTokens}")

        for (i in messages.indices) {
            LingShuLog.d(moduleTag, "[$traceId] msg[$i] role=${messages[i].role} length=${messages[i].content.length}")
        }

        val baseUrl = normalizeBaseUrl(config.baseUrl)
        val chatUrl = "${baseUrl}chat/completions"
        val requestBody = buildChatRequestBody(messages, config, stream = false)

        val client = createClient(config.timeoutSeconds)
        val request = buildRequest(chatUrl, config.apiKey, requestBody)

        var retryCount = 0
        var lastError: Exception? = null
        var lastResponse: Response? = null

        while (retryCount <= MAX_RETRIES) {
            try {
                if (retryCount > 0) {
                    val delayMs = calculateBackoffDelay(retryCount)
                    LingShuLog.w(moduleTag, "[$traceId] retry attempt=$retryCount/${MAX_RETRIES} | delay=${delayMs}ms")
                    delay(delayMs)
                }

                lastResponse?.close()
                lastResponse = client.newCall(request).execute()

                val httpCode = lastResponse.code
                val responseBodyStr = lastResponse.body?.string() ?: ""

                LingShuLog.d(moduleTag, "[$traceId] HTTP response | code=$httpCode | bodyLength=${responseBodyStr.length} | " +
                        "attempt=${retryCount + 1}")

                if (lastResponse.isSuccessful) {
                    val content = parseChatResponse(responseBodyStr)
                    val elapsed = System.currentTimeMillis() - startTime
                    val outputTokens = content.length / 4 + 1

                    LingShuLog.i(moduleTag, "[$traceId] chat success | httpCode=$httpCode | " +
                            "inputTokensEst=$estimatedTokens | outputTokensEst=$outputTokens | " +
                            "elapsedMs=$elapsed | retries=$retryCount")

                    return@withContext Result.success(content)
                } else {
                    val errorBody = if (responseBodyStr.length > 500) {
                        responseBodyStr.substring(0, 500) + "...(truncated)"
                    } else {
                        responseBodyStr
                    }
                    LingShuLog.e(moduleTag, "[$traceId] HTTP error | code=$httpCode | attempt=${retryCount + 1} | " +
                            "errorBody=$errorBody")

                    if (httpCode in 500..599 || httpCode == 429) {
                        retryCount++
                        continue
                    }

                    val errorCode = when (httpCode) {
                        401 -> ErrorCodes.API_KEY_INVALID
                        408, 504 -> ErrorCodes.SERVER_NO_RESPONSE
                        else -> ErrorCodes.UNKNOWN_ERROR
                    }
                    return@withContext Result.error(
                        code = errorCode,
                        message = "HTTP $httpCode: ${parseErrorMessage(responseBodyStr)}",
                        cause = null
                    )
                }
            } catch (e: Exception) {
                lastError = e
                LingShuLog.e(moduleTag, "[$traceId] request exception | attempt=${retryCount + 1} | " +
                        "type=${e.javaClass.simpleName} | msg=${e.message}", e)
                retryCount++
            }
        }

        lastResponse?.close()
        val elapsed = System.currentTimeMillis() - startTime
        LingShuLog.e(moduleTag, "[$traceId] chat failed after retries | totalRetries=$retryCount | elapsedMs=$elapsed", lastError)

        return@withContext Result.error(
            code = ErrorCodes.SERVER_NO_RESPONSE,
            message = lastError?.message ?: "Request failed after $MAX_RETRIES retries",
            cause = lastError
        )
    }

    override suspend fun chatStream(
        messages: List<ChatMessage>,
        config: LlmConfig,
        onToken: (String) -> Unit,
        traceId: String
    ): Result<String> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val estimatedTokens = estimateTokens(messages)
        val totalMsgLength = messages.sumOf { it.content.length }

        LingShuLog.i(moduleTag, "[$traceId] chatStream start | provider=$type | model=${config.modelName} | " +
                "msgCount=${messages.size} | totalMsgLength=$totalMsgLength | estimatedTokens=$estimatedTokens")

        val baseUrl = normalizeBaseUrl(config.baseUrl)
        val chatUrl = "${baseUrl}chat/completions"
        val requestBody = buildChatRequestBody(messages, config, stream = true)

        val client = createClient(config.timeoutSeconds)
        val request = buildRequest(chatUrl, config.apiKey, requestBody)

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
                    code = if (httpCode == 401) ErrorCodes.API_KEY_INVALID else ErrorCodes.SERVER_NO_RESPONSE,
                    message = "HTTP $httpCode: ${parseErrorMessage(errorBody)}"
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
                    if (!currentLine.startsWith("data:")) continue

                    val dataStr = currentLine.removePrefix("data:").trim()
                    if (dataStr == "[DONE]") break

                    try {
                        val json = JSONObject(dataStr)
                        val choices = json.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val choice = choices.getJSONObject(0)
                            val delta = choice.optJSONObject("delta")
                            val content = delta?.optString("content") ?: ""
                            if (content.isNotEmpty()) {
                                fullContent.append(content)
                                tokenCount++
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    onToken(content)
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
                code = ErrorCodes.SERVER_NO_RESPONSE,
                message = e.message ?: "Stream failed",
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
                "textCount=${texts.size} | totalLength=${texts.sumOf { it.length }}")

        val baseUrl = normalizeBaseUrl(config.baseUrl)
        val embeddingsUrl = "${baseUrl}embeddings"
        val requestBody = buildEmbeddingsRequestBody(texts, config)

        val client = createClient(config.timeoutSeconds)
        val request = buildRequest(embeddingsUrl, config.apiKey, requestBody)

        try {
            val response = client.newCall(request).execute()
            val httpCode = response.code
            val responseBodyStr = response.body?.string() ?: ""

            LingShuLog.d(moduleTag, "[$traceId] embeddings HTTP | code=$httpCode | bodyLength=${responseBodyStr.length}")

            if (!response.isSuccessful) {
                val truncatedBody = if (responseBodyStr.length > 500) {
                    responseBodyStr.substring(0, 500) + "...(truncated)"
                } else {
                    responseBodyStr
                }
                LingShuLog.e(moduleTag, "[$traceId] embeddings HTTP error | code=$httpCode | errorBody=$truncatedBody")
                response.close()
                return@withContext Result.error(
                    code = if (httpCode == 401) ErrorCodes.API_KEY_INVALID else ErrorCodes.SERVER_NO_RESPONSE,
                    message = "HTTP $httpCode: ${parseErrorMessage(responseBodyStr)}"
                )
            }

            response.close()
            val result = parseEmbeddingsResponse(responseBodyStr)
            val elapsed = System.currentTimeMillis() - startTime

            LingShuLog.i(moduleTag, "[$traceId] embeddings success | httpCode=$httpCode | " +
                    "vectors=${result.size} | dims=${result.firstOrNull()?.size ?: 0} | elapsedMs=$elapsed")

            return@withContext Result.success(result)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] embeddings exception | elapsedMs=$elapsed | " +
                    "type=${e.javaClass.simpleName} | msg=${e.message}", e)
            return@withContext Result.error(
                code = ErrorCodes.SERVER_NO_RESPONSE,
                message = e.message ?: "Embeddings failed",
                cause = e
            )
        }
    }

    override fun isAvailable(config: LlmConfig): Boolean {
        val valid = config.apiKey.isNotBlank() && config.baseUrl.isNotBlank() && config.modelName.isNotBlank()
        if (!valid) {
            LingShuLog.w(moduleTag, "isAvailable=false | apiKeyBlank=${config.apiKey.isBlank()} | " +
                    "baseUrlBlank=${config.baseUrl.isBlank()} | modelBlank=${config.modelName.isBlank()}")
        }
        return valid
    }

    private fun calculateBackoffDelay(retryCount: Int): Long {
        val delay = (INITIAL_DELAY_MS * BACKOFF_MULTIPLIER.pow(retryCount - 1)).toLong()
        return delay.coerceAtMost(MAX_DELAY_MS)
    }

    private fun parseChatResponse(responseBody: String): String {
        val json = JSONObject(responseBody)
        val choices = json.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val choice = choices.getJSONObject(0)
        val message = choice.optJSONObject("message") ?: return ""
        return message.optString("content", "")
    }

    private fun parseEmbeddingsResponse(responseBody: String): List<List<Float>> {
        val json = JSONObject(responseBody)
        val data = json.optJSONArray("data") ?: return emptyList()
        val result = mutableListOf<List<Float>>()
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            val embeddingArr = item.getJSONArray("embedding")
            val floats = mutableListOf<Float>()
            for (j in 0 until embeddingArr.length()) {
                floats.add(embeddingArr.getDouble(j).toFloat())
            }
            result.add(floats)
        }
        return result
    }

    private fun parseErrorMessage(responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            val error = json.optJSONObject("error")
            error?.optString("message") ?: responseBody.take(200)
        } catch (_: Exception) {
            responseBody.take(200)
        }
    }
}

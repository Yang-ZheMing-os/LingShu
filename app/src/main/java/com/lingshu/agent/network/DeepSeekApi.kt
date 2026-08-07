package com.lingshu.agent.network

import com.lingshu.agent.feature.model.ModelMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * DeepSeek Chat API 请求体
 */
data class DeepSeekRequest(
    val model: String = "deepseek-chat",
    val messages: List<Map<String, Any>>,
    val temperature: Double = 0.7,
    val max_tokens: Int = 2048
)

/**
 * DeepSeek Chat API 响应体
 */
data class DeepSeekResponse(
    val id: String?,
    val choices: List<Choice>?,
    val error: DeepSeekError?
) {
    data class Choice(val message: Message?, val finish_reason: String?)
    data class Message(val role: String?, val content: String?)
    data class DeepSeekError(val message: String?, val type: String?, val code: String?)
}

/**
 * DeepSeek API Retrofit 服务接口
 */
interface DeepSeekService {
    @POST("chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: DeepSeekRequest
    ): DeepSeekResponse
}

/**
 * 指数退避重试拦截器
 * 对 5xx 服务端错误和 429 限流进行最多 3 次重试，
 * 延迟策略：1秒、2秒、4秒（指数退避）。
 */
class RetryInterceptor(private val maxRetries: Int = 3) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var response: Response? = null
        var lastException: Exception? = null

        for (retryCount in 0..maxRetries) {
            try {
                response = chain.proceed(chain.request())
                if (response.isSuccessful) return response

                val code = response.code
                if (code in 500..599 || code == 429) {
                    response.close()
                    if (retryCount < maxRetries) {
                        val delayMs = (2.0.pow(retryCount.toDouble()) * 1000).toLong()
                        Thread.sleep(delayMs)
                    }
                } else {
                    // 4xx 客户端错误不重试
                    return response
                }
            } catch (e: IOException) {
                lastException = e
                if (retryCount < maxRetries) {
                    val delayMs = (2.0.pow(retryCount.toDouble()) * 1000).toLong()
                    Thread.sleep(delayMs)
                }
            }
        }

        return response ?: throw lastException ?: IOException("Max retries exceeded")
    }
}

/**
 * DeepSeek API 客户端
 *
 * 直接通过 Retrofit 调用 DeepSeek Chat Completion API，
 * 不经过 ModelRouter 多 Provider 路由。
 *
 * OkHttp 配置：
 * - 连接超时：10 秒
 * - 读取超时：30 秒
 * - 重试拦截器：3 次指数退避
 */
@Singleton
class DeepSeekApi @Inject constructor() {

    companion object {
        private const val BASE_URL = "https://api.deepseek.com/v1/"
    }

    /** OkHttp 客户端：超时 + 重试拦截器 */
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor(maxRetries = 3))
            .build()
    }

    /** Retrofit 服务实例 */
    private val service: DeepSeekService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DeepSeekService::class.java)
    }

    /**
     * 发送非流式聊天请求
     *
     * @param messages 消息列表（ModelMessage）
     * @param apiKey DeepSeek API Key（Bearer Token）
     * @return AI 回复文本
     * @throws retrofit2.HttpException HTTP 状态码异常（如 401）
     * @throws java.net.SocketTimeoutException 网络超时
     * @throws java.io.IOException 网络连接异常
     */
    suspend fun chatCompletion(messages: List<ModelMessage>, apiKey: String): String =
        withContext(Dispatchers.IO) {
            val requestMessages = messages.map { it.toApiMap() }
            val request = DeepSeekRequest(messages = requestMessages)
            val response = service.chatCompletion("Bearer $apiKey", request)

            response.choices?.firstOrNull()?.message?.content
                ?: throw IOException(response.error?.message ?: "DeepSeek API 返回空内容")
        }
}

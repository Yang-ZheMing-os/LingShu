package com.lingshu.agent.core.model.routing.providers

import android.graphics.Bitmap
import com.lingshu.agent.core.model.routing.Message
import com.lingshu.agent.core.model.routing.ModelConfig
import com.lingshu.agent.core.model.routing.ModelProvider
import com.lingshu.agent.core.model.routing.ModelType
import com.lingshu.agent.core.model.routing.Response
import com.lingshu.agent.core.model.routing.ResponseStatus
import com.lingshu.agent.core.model.routing.Usage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * 基础模型提供者抽象类
 * 实现公共逻辑：API Key轮询、配置管理、响应封装等
 * 各具体模型Provider继承此类并实现实际API调用逻辑
 */
abstract class BaseModelProvider(
    initialConfig: ModelConfig
) : ModelProvider {

    /** 当前配置（volatile保证多线程可见性） */
    @Volatile
    protected var currentConfig: ModelConfig = initialConfig

    /** API Key轮询索引（原子整数保证线程安全） */
    private val apiKeyIndex = AtomicInteger(0)

    /** 配置更新互斥锁 */
    private val configMutex = Mutex()

    /** Provider级别的互斥锁（用于需要串行化的操作） */
    protected val providerMutex = Mutex()

    override val modelType: ModelType
        get() = currentConfig.modelType

    override val config: ModelConfig
        get() = currentConfig

    override fun updateConfig(newConfig: ModelConfig) {
        if (newConfig.modelType != currentConfig.modelType) {
            throw IllegalArgumentException("不能改变Provider的模型类型")
        }
        currentConfig = newConfig
        apiKeyIndex.set(0)
    }

    override suspend fun isAvailable(): Boolean {
        // 本地模型检查服务是否运行
        if (currentConfig.modelType.isLocal) {
            return checkLocalServiceAvailable()
        }
        // 云端模型检查是否有有效配置
        return currentConfig.isAvailable
    }

    /**
     * 检查本地模型服务是否可用
     * 子类（如OllamaProvider）重写此方法以实现具体检查逻辑
     */
    protected open suspend fun checkLocalServiceAvailable(): Boolean = true

    /**
     * 获取下一个API Key（轮询机制）
     * 支持多API Key轮询以避免限流
     * @param enableRotation 是否启用轮询（从配置中读取）
     * @return 当前要使用的API Key，如果没有可用Key则返回null
     */
    protected fun getNextApiKey(enableRotation: Boolean = true): String? {
        val apiKeys = currentConfig.apiKeys.filter { it.isNotBlank() }
        if (apiKeys.isEmpty()) return null

        if (!enableRotation || apiKeys.size == 1) {
            return apiKeys[0]
        }

        // 使用取模+自增实现轮询
        val index = apiKeyIndex.getAndUpdate { (it + 1) % apiKeys.size }
        return apiKeys[index]
    }

    /**
     * 获取当前API Key（不轮询）
     */
    protected fun getCurrentApiKey(): String? {
        val apiKeys = currentConfig.apiKeys.filter { it.isNotBlank() }
        if (apiKeys.isEmpty()) return null
        val index = apiKeyIndex.get() % apiKeys.size
        return apiKeys[index]
    }

    /**
     * 尝试所有API Key执行操作
     * 当某个Key被限流或失败时，自动尝试下一个Key
     * @param operation 执行操作的函数，参数为API Key，返回响应
     * @return 第一个成功的响应，或最后一个失败的响应
     */
    protected suspend fun tryAllApiKeys(
        operation: suspend (apiKey: String) -> Response
    ): Response {
        val apiKeys = currentConfig.apiKeys.filter { it.isNotBlank() }
        if (apiKeys.isEmpty()) {
            return Response.error("未配置API Key", modelType)
        }

        val startIndex = if (currentConfig.modelType.isLocal) {
            0
        } else {
            apiKeyIndex.get() % apiKeys.size
        }

        var lastResponse: Response? = null

        // 尝试所有API Key（从当前索引开始循环）
        for (i in apiKeys.indices) {
            val keyIndex = (startIndex + i) % apiKeys.size
            val apiKey = apiKeys[keyIndex]

            try {
                val response = operation(apiKey)

                // 成功或非限流错误，直接返回
                if (response.isSuccess || response.isError) {
                    // 成功时更新索引到下一个Key（下次从不同的Key开始）
                    if (response.isSuccess && apiKeys.size > 1) {
                        apiKeyIndex.set((keyIndex + 1) % apiKeys.size)
                    }
                    return response
                }

                // 被限流，记录并继续尝试下一个Key
                lastResponse = response
            } catch (e: Exception) {
                lastResponse = Response.error(
                    "调用异常: ${e.message}",
                    modelType
                )
            }
        }

        return lastResponse ?: Response.error("所有API Key均调用失败", modelType)
    }

    /**
     * 封装带计时的操作执行
     * @param block 实际执行的操作
     * @return 带延迟信息的Response
     */
    protected suspend inline fun executeWithTiming(
        crossinline block: suspend () -> Response
    ): Response {
        val startTime = System.currentTimeMillis()
        val response = block()
        val latency = System.currentTimeMillis() - startTime
        return if (response.latencyMs == 0L) {
            response.copy(latencyMs = latency)
        } else {
            response
        }
    }

    /**
     * 创建成功响应（便捷方法）
     */
    protected fun successResponse(
        content: String,
        usage: Usage = Usage.EMPTY,
        responseId: String = ""
    ): Response = Response.success(
        content = content,
        modelType = modelType,
        usage = usage,
        responseId = responseId
    )

    /**
     * 创建错误响应（便捷方法）
     */
    protected fun errorResponse(message: String): Response =
        Response.error(message, modelType)

    /**
     * 创建限流响应（便捷方法）
     */
    protected fun rateLimitedResponse(message: String = "请求过于频繁，请稍后重试"): Response =
        Response.rateLimited(message, modelType)

    /**
     * 创建不可用响应（便捷方法）
     */
    protected fun unavailableResponse(message: String = "模型服务暂时不可用"): Response =
        Response.unavailable(message, modelType)

    // ======== 默认未实现能力的兜底方法 ========

    /**
     * 默认视觉实现：抛出不支持异常
     * 支持视觉的子类需要重写此方法
     */
    override suspend fun vision(image: Bitmap, prompt: String): String {
        throw UnsupportedOperationException("模型 $modelType 不支持视觉能力")
    }

    /**
     * 默认语音识别实现：抛出不支持异常
     * 支持语音识别的子类需要重写此方法
     */
    override suspend fun transcribe(audio: ByteArray): String {
        throw UnsupportedOperationException("模型 $modelType 不支持语音识别能力")
    }

    /**
     * 默认语音合成实现：抛出不支持异常
     * 支持语音合成的子类需要重写此方法
     */
    override suspend fun synthesize(text: String): ByteArray {
        throw UnsupportedOperationException("模型 $modelType 不支持语音合成能力")
    }

    override fun release() {
        // 默认空实现，子类按需重写以释放资源
    }

    /**
     * 安全配置更新（带Mutex保护）
     */
    protected suspend fun safeUpdateConfig(block: (ModelConfig) -> ModelConfig) {
        configMutex.withLock {
            currentConfig = block(currentConfig)
        }
    }
}

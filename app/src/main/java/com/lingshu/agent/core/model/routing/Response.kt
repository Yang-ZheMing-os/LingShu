package com.lingshu.agent.core.model.routing

/**
 * 响应状态枚举
 */
enum class ResponseStatus {
    /** 请求成功 */
    SUCCESS,
    /** 请求失败 */
    ERROR,
    /** 请求被限流 */
    RATE_LIMITED,
    /** 模型不可用 */
    UNAVAILABLE
}

/**
 * 使用情况统计
 * 记录模型调用的Token消耗等信息
 * @property promptTokens 输入Token数量
 * @property completionTokens 输出Token数量
 * @property totalTokens 总Token数量
 */
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = promptTokens + completionTokens
) {
    companion object {
        val EMPTY = Usage(0, 0)
    }
}

/**
 * 模型响应数据类
 * 封装AI模型的返回结果
 * @property status 响应状态
 * @property content 响应文本内容
 * @property modelType 使用的模型类型
 * @property usage Token使用情况
 * @property errorMessage 错误信息（失败时）
 * @property responseId 响应唯一标识
 * @property latencyMs 响应延迟（毫秒）
 */
data class Response(
    val status: ResponseStatus,
    val content: String = "",
    val modelType: ModelType? = null,
    val usage: Usage = Usage.EMPTY,
    val errorMessage: String? = null,
    val responseId: String = "",
    val latencyMs: Long = 0
) {
    /**
     * 判断响应是否成功
     */
    val isSuccess: Boolean get() = status == ResponseStatus.SUCCESS

    /**
     * 判断响应是否失败
     */
    val isError: Boolean get() = status == ResponseStatus.ERROR

    /**
     * 判断是否被限流
     */
    val isRateLimited: Boolean get() = status == ResponseStatus.RATE_LIMITED

    /**
     * 获取有效内容，如果失败则返回空字符串
     */
    val safeContent: String get() = if (isSuccess) content else ""

    companion object {
        /**
         * 创建成功响应
         */
        fun success(
            content: String,
            modelType: ModelType? = null,
            usage: Usage = Usage.EMPTY,
            responseId: String = "",
            latencyMs: Long = 0
        ): Response = Response(
            status = ResponseStatus.SUCCESS,
            content = content,
            modelType = modelType,
            usage = usage,
            responseId = responseId,
            latencyMs = latencyMs
        )

        /**
         * 创建错误响应
         */
        fun error(
            errorMessage: String,
            modelType: ModelType? = null
        ): Response = Response(
            status = ResponseStatus.ERROR,
            modelType = modelType,
            errorMessage = errorMessage
        )

        /**
         * 创建限流响应
         */
        fun rateLimited(
            errorMessage: String = "请求过于频繁，请稍后重试",
            modelType: ModelType? = null
        ): Response = Response(
            status = ResponseStatus.RATE_LIMITED,
            modelType = modelType,
            errorMessage = errorMessage
        )

        /**
         * 创建不可用响应
         */
        fun unavailable(
            errorMessage: String = "模型服务暂时不可用",
            modelType: ModelType? = null
        ): Response = Response(
            status = ResponseStatus.UNAVAILABLE,
            modelType = modelType,
            errorMessage = errorMessage
        )
    }
}

package com.lingshu.agent.feature.model

/**
 * 模型响应状态枚举
 * 定义模型调用请求的各种可能状态
 */
enum class ResponseStatus {
    /** 请求成功 - 正常返回结果 */
    SUCCESS,

    /** 请求失败 - 发生业务错误或异常 */
    ERROR,

    /** 请求被限流 - API调用频率超限 */
    RATE_LIMITED,

    /** 模型不可用 - 服务端错误、网络问题等 */
    UNAVAILABLE
}

/**
 * Token使用情况统计
 * 记录模型调用的输入输出Token消耗，用于计费和统计
 *
 * @property promptTokens 输入（提示词）Token数量
 * @property completionTokens 输出（补全）Token数量
 * @property totalTokens 总Token数量（prompt + completion）
 */
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = promptTokens + completionTokens
) {
    companion object {
        /** 空使用情况（默认值） */
        val EMPTY = TokenUsage(0, 0)
    }
}

/**
 * 模型响应数据类
 * 统一封装AI模型的返回结果，包含状态、内容、使用情况等信息
 *
 * @property status 响应状态（成功/失败/限流/不可用）
 * @property content 响应文本内容（成功时有效）
 * @property providerId 使用的模型提供者ID
 * @property usage Token使用情况统计
 * @property errorMessage 错误信息（失败、限流、不可用时有效）
 * @property responseId 响应唯一标识（用于追踪和调试）
 * @property latencyMs 响应延迟（毫秒，从请求发出到收到结果）
 */
data class ModelResponse(
    val status: ResponseStatus,
    val content: String = "",
    val providerId: String? = null,
    val usage: TokenUsage = TokenUsage.EMPTY,
    val errorMessage: String? = null,
    val responseId: String = "",
    val latencyMs: Long = 0
) {
    /** 判断响应是否成功 */
    val isSuccess: Boolean get() = status == ResponseStatus.SUCCESS

    /** 判断响应是否失败 */
    val isError: Boolean get() = status == ResponseStatus.ERROR

    /** 判断是否被限流 */
    val isRateLimited: Boolean get() = status == ResponseStatus.RATE_LIMITED

    /** 判断模型是否不可用 */
    val isUnavailable: Boolean get() = status == ResponseStatus.UNAVAILABLE

    /**
     * 获取安全的内容字符串
     * 成功时返回内容，失败时返回空字符串，避免空指针
     */
    val safeContent: String get() = if (isSuccess) content else ""

    /**
     * 判断是否应该触发降级策略
     * 限流和不可用状态应该触发自动降级到下一个模型
     */
    val shouldFallback: Boolean get() = isRateLimited || isUnavailable

    companion object {
        /**
         * 创建成功响应
         *
         * @param content 响应文本内容
         * @param providerId 模型提供者ID
         * @param usage Token使用情况
         * @param responseId 响应唯一标识
         * @param latencyMs 响应延迟
         */
        fun success(
            content: String,
            providerId: String? = null,
            usage: TokenUsage = TokenUsage.EMPTY,
            responseId: String = "",
            latencyMs: Long = 0
        ): ModelResponse = ModelResponse(
            status = ResponseStatus.SUCCESS,
            content = content,
            providerId = providerId,
            usage = usage,
            responseId = responseId,
            latencyMs = latencyMs
        )

        /**
         * 创建错误响应
         *
         * @param errorMessage 错误信息
         * @param providerId 模型提供者ID
         */
        fun error(
            errorMessage: String,
            providerId: String? = null
        ): ModelResponse = ModelResponse(
            status = ResponseStatus.ERROR,
            providerId = providerId,
            errorMessage = errorMessage
        )

        /**
         * 创建限流响应
         *
         * @param errorMessage 限流提示信息
         * @param providerId 模型提供者ID
         */
        fun rateLimited(
            errorMessage: String = "请求过于频繁，请稍后重试",
            providerId: String? = null
        ): ModelResponse = ModelResponse(
            status = ResponseStatus.RATE_LIMITED,
            providerId = providerId,
            errorMessage = errorMessage
        )

        /**
         * 创建不可用响应
         *
         * @param errorMessage 不可用提示信息
         * @param providerId 模型提供者ID
         */
        fun unavailable(
            errorMessage: String = "模型服务暂时不可用",
            providerId: String? = null
        ): ModelResponse = ModelResponse(
            status = ResponseStatus.UNAVAILABLE,
            providerId = providerId,
            errorMessage = errorMessage
        )
    }
}

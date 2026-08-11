package com.lingshu.agent.feature.model

/**
 * 消息角色枚举
 * 定义对话中消息的发送者角色，用于区分不同来源的消息
 */
enum class MessageRole {
    /** 系统角色 - 用于设置模型行为的系统提示词（Persona、人设等） */
    SYSTEM,

    /** 用户角色 - 用户发送的输入消息 */
    USER,

    /** 助手角色 - AI模型返回的回复消息 */
    ASSISTANT,

    /** 工具角色 - 工具/函数调用返回的结果消息 */
    TOOL
}

/**
 * 消息数据类
 * 表示对话中的单条消息，支持文本和图片（多模态）
 *
 * @property role 消息角色（发送者）
 * @property content 消息文本内容
 * @property images 消息中附带的图片URL列表（用于多模态消息，如视觉理解任务）
 * @property timestamp 消息创建时间戳（毫秒）
 * @property id 消息唯一标识
 */
data class ModelMessage(
    val role: MessageRole,
    val content: String,
    val images: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val id: String = timestamp.toString()
) {
    companion object {
        /**
         * 创建系统消息
         * @param content 系统提示词内容
         */
        fun system(content: String): ModelMessage = ModelMessage(
            role = MessageRole.SYSTEM,
            content = content
        )

        /**
         * 创建用户消息（纯文本）
         * @param content 用户输入文本
         */
        fun user(content: String): ModelMessage = ModelMessage(
            role = MessageRole.USER,
            content = content
        )

        /**
         * 创建用户消息（带图片URL列表）
         * @param content 用户输入文本
         * @param images 图片URL列表
         */
        fun userWithImages(content: String, images: List<String>): ModelMessage = ModelMessage(
            role = MessageRole.USER,
            content = content,
            images = images
        )

        /**
         * 创建助手消息
         * @param content 助手回复文本
         */
        fun assistant(content: String): ModelMessage = ModelMessage(
            role = MessageRole.ASSISTANT,
            content = content
        )

        /**
         * 创建工具返回结果消息
         * @param content 工具执行结果
         */
        fun tool(content: String): ModelMessage = ModelMessage(
            role = MessageRole.TOOL,
            content = content
        )
    }

    /** 判断消息是否包含图片（多模态） */
    fun hasImages(): Boolean = images.isNotEmpty()

    /** 判断是否为用户消息 */
    fun isUser(): Boolean = role == MessageRole.USER

    /** 判断是否为助手消息 */
    fun isAssistant(): Boolean = role == MessageRole.ASSISTANT

    /** 判断是否为系统消息 */
    fun isSystem(): Boolean = role == MessageRole.SYSTEM

    /**
     * 转换为OpenAI兼容API请求的Map格式
     * 支持多模态图片内容格式
     */
    fun toApiMap(): Map<String, Any> {
        return if (images.isEmpty()) {
            // 纯文本消息格式
            mapOf(
                "role" to role.name.lowercase(),
                "content" to content
            )
        } else {
            // 多模态消息格式（content为数组，包含text和image_url）
            val contentList = buildList<Map<String, Any>> {
                add(mapOf("type" to "text", "text" to content))
                images.forEach { imageUrl ->
                    add(
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf("url" to imageUrl)
                        )
                    )
                }
            }
            mapOf(
                "role" to role.name.lowercase(),
                "content" to contentList
            )
        }
    }
}

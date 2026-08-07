package com.lingshu.agent.core.model.routing

/**
 * 消息角色枚举
 * 定义对话中消息的发送者角色
 */
enum class Role {
    /** 系统角色 - 用于设置模型行为的系统提示词 */
    SYSTEM,

    /** 用户角色 - 用户发送的消息 */
    USER,

    /** 助手角色 - AI模型返回的消息 */
    ASSISTANT,

    /** 工具角色 - 工具调用返回的结果 */
    TOOL;

    companion object {
        /**
         * 根据字符串获取角色
         */
        fun fromString(role: String?): Role {
            return values().find { it.name.equals(role, ignoreCase = true) } ?: USER
        }
    }
}

/**
 * 消息数据类
 * 表示对话中的单条消息
 * @property role 消息角色
 * @property content 消息文本内容
 * @property imageUrls 消息中附带的图片URL列表（用于多模态消息）
 * @property timestamp 消息时间戳（毫秒）
 */
data class Message(
    val role: Role,
    val content: String,
    val imageUrls: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * 创建系统消息
         * @param content 系统提示词内容
         */
        fun system(content: String): Message = Message(
            role = Role.SYSTEM,
            content = content
        )

        /**
         * 创建用户消息（纯文本）
         * @param content 用户输入文本
         */
        fun user(content: String): Message = Message(
            role = Role.USER,
            content = content
        )

        /**
         * 创建用户消息（带图片）
         * @param content 用户输入文本
         * @param imageUrls 图片URL列表
         */
        fun userWithImages(content: String, imageUrls: List<String>): Message = Message(
            role = Role.USER,
            content = content,
            imageUrls = imageUrls
        )

        /**
         * 创建助手消息
         * @param content 助手回复文本
         */
        fun assistant(content: String): Message = Message(
            role = Role.ASSISTANT,
            content = content
        )

        /**
         * 创建工具消息
         * @param content 工具返回结果
         */
        fun tool(content: String): Message = Message(
            role = Role.TOOL,
            content = content
        )
    }

    /**
     * 判断消息是否包含图片
     */
    fun hasImages(): Boolean = imageUrls.isNotEmpty()

    /**
     * 转换为API请求格式的Map
     * 适配大多数OpenAI兼容API格式
     */
    fun toApiMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "role" to role.name.lowercase(),
            "content" to content
        )
        if (imageUrls.isNotEmpty()) {
            map["content"] = buildList<Map<String, Any>> {
                add(mapOf("type" to "text", "text" to content))
                imageUrls.forEach { url ->
                    add(
                        mapOf(
                            "type" to "image_url",
                            "image_url" to mapOf("url" to url)
                        )
                    )
                }
            }
        }
        return map
    }
}

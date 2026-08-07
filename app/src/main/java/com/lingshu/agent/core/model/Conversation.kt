package com.lingshu.agent.core.model

/**
 * 对话会话状态枚举
 */
enum class ConversationStatus {
    // 进行中
    ACTIVE,
    // 已结束
    CLOSED,
    // 已归档
    ARCHIVED,
    // 置顶会话
    PINNED
}

/**
 * 对话会话数据模型
 * 表示一次完整的对话会话记录
 *
 * @property conversationId 会话唯一标识符
 * @property personaId 本次会话使用的人格 ID
 * @property title 会话标题（自动生成或用户自定义）
 * @property summary 对话摘要（AI 生成的简短总结）
 * @property messageCount 会话中的消息总数
 * @property lastMessage 最后一条消息的预览内容
 * @property lastMessageTime 最后一条消息的时间戳
 * @property status 会话状态
 * @property tags 会话标签列表
 * @property mood 会话整体情感倾向（可选，-1.0 消极 ~ 1.0 积极）
 * @property createdAt 会话创建时间戳
 * @property updatedAt 会话最后更新时间戳
 */
data class Conversation(
    val conversationId: String,
    val personaId: String,
    var title: String = "",
    var summary: String? = null,
    var messageCount: Int = 0,
    var lastMessage: String? = null,
    var lastMessageTime: Long = System.currentTimeMillis(),
    var status: ConversationStatus = ConversationStatus.ACTIVE,
    var tags: List<String> = emptyList(),
    val mood: Float? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 判断会话是否有未读消息（通过 lastMessageTime 与外部已读时间比较）
     */
    fun hasUnread(lastReadTime: Long): Boolean = lastMessageTime > lastReadTime

    /**
     * 更新会话的最新消息信息
     */
    fun updateWithNewMessage(messageContent: String): Conversation {
        return copy(
            messageCount = messageCount + 1,
            lastMessage = messageContent,
            lastMessageTime = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }
}

package com.lingshu.agent.core.model

enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}

data class Message(
    val id: String = System.currentTimeMillis().toString(),
    val conversationId: String = "",
    val role: MessageRole = MessageRole.USER,
    val content: String = "",
    val images: List<String> = emptyList(),
    val audioUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = true,
    val tokenCount: Int = 0,
    val feedback: Feedback? = null,
    val modelName: String? = null
) {
    enum class Feedback {
        LIKED,
        DISLIKED
    }

    fun isUserMessage(): Boolean = role == MessageRole.USER
    fun isAssistantMessage(): Boolean = role == MessageRole.ASSISTANT
    fun isSystemMessage(): Boolean = role == MessageRole.SYSTEM
}

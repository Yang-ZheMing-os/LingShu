package com.lingshu.agent.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lingshu.agent.core.model.MessageRole

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["timestamp"])
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val images: String,
    val audioUrl: String?,
    val timestamp: Long,
    val isRead: Boolean,
    val tokenCount: Int,
    val feedback: Feedback?,
    val modelName: String?
) {
    enum class Feedback {
        LIKED,
        DISLIKED
    }
}

fun MessageEntity.toMessage(): com.lingshu.agent.core.model.Message {
    return com.lingshu.agent.core.model.Message(
        id = id,
        conversationId = conversationId,
        role = role,
        content = content,
        images = if (images.isNotBlank()) images.split(",").map { it.trim() }.filter { it.isNotEmpty() } else emptyList(),
        audioUrl = audioUrl,
        timestamp = timestamp,
        isRead = isRead,
        tokenCount = tokenCount,
        modelName = modelName
    )
}

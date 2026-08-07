package com.lingshu.agent.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.lingshu.agent.core.model.ConversationStatus

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val personaId: String?,
    val status: ConversationStatus,
    val messageCount: Int,
    val lastMessagePreview: String?,
    val lastMessageTime: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val summary: String?,
    val pinned: Boolean,
    val mood: String?,
    val tags: String
)

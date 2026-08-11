package com.lingshu.agent.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "cloned_voices")
data class ClonedVoiceEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val modelFilePath: String,
    val sampleFilePath: String,
    val durationSeconds: Int,
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

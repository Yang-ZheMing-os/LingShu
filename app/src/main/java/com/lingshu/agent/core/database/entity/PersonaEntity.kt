package com.lingshu.agent.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey val id: String,
    val name: String,
    val systemPrompt: String,
    val traitsOpenness: Double,
    val traitsConscientiousness: Double,
    val traitsExtraversion: Double,
    val traitsAgreeableness: Double,
    val traitsNeuroticism: Double,
    val voiceId: String?,
    val temperature: Double,
    val memory: String,
    val createdAt: Long,
    val updatedAt: Long,
    val avatarUrl: String?,
    val tags: String,
    val rules: String,
    val exampleDialogues: String,
    val isActive: Boolean,
    val isDefault: Boolean
)

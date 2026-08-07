package com.lingshu.agent.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scripts")
data class ScriptEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val category: String,
    val sourceCode: String,
    val stepsJson: String,
    val icon: String?,
    val isFavorite: Boolean,
    val isBuiltin: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long?,
    val runCount: Int
)

package com.lingshu.agent.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ModCategoryEntity {
    PERSONA,
    SKILL,
    THEME,
    AUTOMATION,
    DATA
}

@Entity(tableName = "mods")
data class ModEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val author: String,
    val description: String,
    val category: ModCategoryEntity,
    val installPath: String,
    val manifestJson: String,
    val isEnabled: Boolean,
    val installedAt: Long,
    val updatedAt: Long,
    val isUpdateAvailable: Boolean,
    val installedSize: Long
)

package com.lingshu.core.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String,
    val personality: String,
    val avatarUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

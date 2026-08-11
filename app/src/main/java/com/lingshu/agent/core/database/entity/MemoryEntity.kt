package com.lingshu.agent.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val content: String,
    val category: String,
    val source: String,
    val personaId: String? = null,  // 人格ID标签：null=全局记忆(用户姓名/设备信息)，非null=绑定特定人格
    val timestamp: Long = System.currentTimeMillis()
)

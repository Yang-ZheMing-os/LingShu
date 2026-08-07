package com.lingshu.agent.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 纠正记录实体（模块6 纠正机制）
 *
 * 字段对齐规格书：
 * - id / originalInput / originalResponse / correction / applied / timestamp
 */
@Entity(tableName = "corrections")
data class CorrectionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val originalInput: String,       // 用户原始输入
    val originalResponse: String,    // AI 原始回复
    val correction: String,          // 用户纠正后的正确回答
    val applied: Boolean = false,    // 是否已应用纠正分析
    val timestamp: Long = System.currentTimeMillis()
)

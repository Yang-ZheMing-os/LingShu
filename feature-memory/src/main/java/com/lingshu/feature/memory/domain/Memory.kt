package com.lingshu.feature.memory.domain

data class Memory(
    val id: Long = 0,
    val content: String,
    val type: MemoryType,
    val source: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val importance: Int = 5
)

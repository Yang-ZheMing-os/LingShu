package com.lingshu.feature.rag.domain

data class Document(
    val id: String,
    val name: String,
    val size: Long,
    val chunkCount: Int,
    val uploadedAt: Long = System.currentTimeMillis()
)

data class Chunk(
    val text: String,
    val score: Float,
    val documentId: String
)

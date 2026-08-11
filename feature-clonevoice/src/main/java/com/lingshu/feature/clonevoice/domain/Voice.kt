package com.lingshu.feature.clonevoice.domain

data class Voice(
    val id: String,
    val name: String,
    val modelPath: String,
    val samplePath: String,
    val createdAt: Long = System.currentTimeMillis()
)

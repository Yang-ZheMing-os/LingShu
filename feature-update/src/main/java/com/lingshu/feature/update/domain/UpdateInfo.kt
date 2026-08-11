package com.lingshu.feature.update.domain

data class UpdateInfo(
    val version: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val fileSize: Long,
    val md5: String,
    val isRequired: Boolean
)

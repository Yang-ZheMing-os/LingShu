package com.lingshu.feature.clonevoice.domain

/**
 * 声音数据模型。
 *
 * - isSystemVoice=true 表示预置音色（基于系统 TTS 的 pitch/rate 调整，无样本文件）
 * - isSystemVoice=false 表示用户录制的自定义声音（保存了录音样本）
 * - voiceName 指向系统 TTS 的具体 Voice 名（null 表示使用默认 Voice + pitch/rate 调整）
 * - pitch/rate 范围 0.5~2.0，1.0 为正常
 */
data class Voice(
    val id: String,
    val name: String,
    val modelPath: String,
    val samplePath: String,
    val createdAt: Long = System.currentTimeMillis(),
    // 系统 TTS 的 Voice 名，null 表示使用默认 Voice
    val voiceName: String? = null,
    // 音调，0.5~2.0，1.0 为正常
    val pitch: Float = 1.0f,
    // 语速，0.5~2.0，1.0 为正常
    val rate: Float = 1.0f,
    // 是否为预置系统音色（true 时无样本文件，仅靠 pitch/rate 调整）
    val isSystemVoice: Boolean = false
)

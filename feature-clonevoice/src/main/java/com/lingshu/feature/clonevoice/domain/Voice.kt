package com.lingshu.feature.clonevoice.domain

/**
 * 声音数据模型。
 *
 * - isSystemVoice=true 表示预置音色（基于系统 TTS 的 pitch/rate 调整，无样本文件）
 * - isSystemVoice=false 表示用户导入的自定义音色预设 / 朋友分享的 .voicepreset
 * - voiceName 指向系统 TTS 或 Edge-TTS 的具体 Voice 名（null 表示使用默认 Voice + pitch/rate 调整）
 * - pitch/rate 范围 0.5~2.0，1.0 为正常
 * - tags / author 用于音色市场分享
 */
data class Voice(
    val id: String,
    val name: String,
    val modelPath: String,
    val samplePath: String,
    val createdAt: Long = System.currentTimeMillis(),
    // 系统 TTS / Edge-TTS 的 Voice 名，null 表示使用默认 Voice
    val voiceName: String? = null,
    // 音调，0.5~2.0，1.0 为正常
    val pitch: Float = 1.0f,
    // 语速，0.5~2.0，1.0 为正常
    val rate: Float = 1.0f,
    // 是否为预置系统音色（true 时无样本文件，仅靠 pitch/rate 调整）
    val isSystemVoice: Boolean = false,
    // ======== Day2-2：音色分享字段 ========
    val author: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList()
)

/**
 * .voicepreset 可分享 JSON 结构（Day2-2 新增）
 * 与 Voice 解耦：分享文件不包含本地绝对路径，导入时重新生成 id + 目录。
 */
data class VoicePresetFile(
    val formatVersion: Int = 1,
    val name: String,
    val author: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val voiceName: String? = null,
    val pitch: Float = 1.0f,
    val rate: Float = 1.0f,
    /** 可选：base64 编码的小片段样本（<200KB），用于试听分享 */
    val sampleBase64: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val EXT = "voicepreset"
        const val MIME = "application/json"
    }
}

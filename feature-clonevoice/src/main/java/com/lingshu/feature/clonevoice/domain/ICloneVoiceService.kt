package com.lingshu.feature.clonevoice.domain

import com.lingshu.core.common.error.Result
import java.io.File

interface ICloneVoiceService {
    // 录音/克隆（保留接口，供真实接入 GPT-SoVITS 时使用，UI 默认不展示该主流程）
    suspend fun cloneAudio(audioFile: File): Result<String>
    suspend fun setCurrentVoice(voiceId: String): Result<Unit>

    /**
     * 应用指定音色到 TTS 引擎并切换为当前音色。
     * 读取 voice 的 voiceName/pitch/rate，调用 OfflineTtsRouter.setVoiceConfig 生效。
     */
    suspend fun applyVoice(voiceId: String): Result<Unit>

    fun getCurrentVoice(): Voice?
    fun listVoices(): List<Voice>
    suspend fun deleteVoice(voiceId: String): Result<Unit>
    suspend fun previewVoice(voiceId: String, text: String): Result<Unit>

    // 录音相关：开始录音、停止录音并返回录音文件、查询录音状态
    suspend fun startRecording(outputFile: File): Result<Unit>
    suspend fun stopRecording(): Result<File>
    fun isRecording(): Boolean

    // ==================== Day2-2：音色库分享 ====================
    /** 从一个 .voicepreset 文件导入为本地音色（返回新 voiceId） */
    suspend fun importPreset(file: File): Result<String>
    /** 把某个本地音色导出成 .voicepreset，保存到指定 File */
    suspend fun exportPreset(voiceId: String, targetFile: File): Result<File>
    /** 直接用给定参数快速创建一个自定义音色（不录音，基于系统/Edge-TTS 的 pitch/rate 调校） */
    suspend fun createCustomPreset(
        name: String,
        author: String,
        description: String,
        tags: List<String>,
        voiceName: String?,
        pitch: Float,
        rate: Float
    ): Result<String>
}

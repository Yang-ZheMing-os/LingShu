package com.lingshu.feature.clonevoice.domain

import com.lingshu.core.common.error.Result
import java.io.File

interface ICloneVoiceService {
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
}

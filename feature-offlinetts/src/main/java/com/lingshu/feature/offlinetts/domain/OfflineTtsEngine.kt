package com.lingshu.feature.offlinetts.domain

import com.lingshu.core.common.error.Result
import java.io.File

enum class OfflineTtsProvider {
    SHERPA_ONNX,
    CHATTTS,
    ANDROID_TTS,
    EDGE_TTS_REMOTE_FALLBACK
}

data class OfflineTtsConfig(
    val provider: OfflineTtsProvider,
    val modelDir: String,
    val voiceId: String,
    val speed: Float = 1.0f,
    val temperature: Float = 0.8f,
    val topP: Float = 0.8f,
    val format: String = "wav",
    val sampleRate: Int = 24000
)

interface IOfflineTtsEngine {
    val provider: OfflineTtsProvider
    suspend fun load(config: OfflineTtsConfig, traceId: String = ""): Result<Unit>
    suspend fun synthesize(text: String, outputFile: File, traceId: String = ""): Result<File>
    suspend fun synthesizeStream(text: String, onPcmChunk: (ShortArray) -> Unit, traceId: String = ""): Result<Long>
    suspend fun unload()
    fun isLoaded(): Boolean
    fun getAvailableVoices(): List<String>
    suspend fun loadVoice(voiceId: String, modelFile: File, traceId: String = ""): Result<Unit>

    /**
     * 设置 TTS 音色配置（系统 Voice 名 + 音调 + 语速）。
     * - voiceName 为 null 时使用引擎默认 Voice
     * - pitch 范围 0.5~2.0，1.0 为正常
     * - rate 范围 0.5~2.0，1.0 为正常
     *
     * 默认空实现，由支持音色切换的引擎（如 AndroidTtsEngine）覆盖。
     */
    fun setVoiceConfig(voiceName: String?, pitch: Float, rate: Float) {}

    /**
     * 返回系统 TTS 可用音色详情列表（每项含 name/locale/gender 等）。
     * 默认返回空列表，由支持音色枚举的引擎覆盖。
     */
    fun getVoiceDetails(): List<Map<String, String>> = emptyList()
}

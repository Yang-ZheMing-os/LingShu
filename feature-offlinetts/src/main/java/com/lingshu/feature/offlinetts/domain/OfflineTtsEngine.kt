package com.lingshu.feature.offlinetts.domain

import com.lingshu.core.common.error.Result
import java.io.File

enum class OfflineTtsProvider {
    CHATTTS,
    BERT_VITS2,
    PIPER,
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
}

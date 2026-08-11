package com.lingshu.feature.offlinestt.domain

import com.lingshu.core.common.error.Result
import kotlinx.coroutines.flow.Flow
import java.io.File

enum class OfflineSttProvider {
    WHISPER_CPP,
    VOSK,
    SHERPA_ONNX
}

data class OfflineSttConfig(
    val provider: OfflineSttProvider,
    val modelDir: String,
    val modelName: String,
    val language: String = "zh",
    val beamSize: Int = 5,
    val useGpu: Boolean = true,
    val maxSegmentChars: Int = 500
)

interface IOfflineSttEngine {
    val provider: OfflineSttProvider
    suspend fun load(config: OfflineSttConfig, traceId: String = ""): Result<Unit>
    suspend fun transcribe(audioFile: File, traceId: String = ""): Result<String>
    suspend fun transcribeStream(
        audioStream: Flow<ShortArray>,
        onPartial: (String) -> Unit,
        traceId: String = ""
    ): Result<String>
    suspend fun unload()
    fun isLoaded(): Boolean
    fun estimateRequiredStorageMb(config: OfflineSttConfig): Long
}

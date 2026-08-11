package com.lingshu.feature.offlinestt.data

import android.content.Context
import com.lingshu.core.common.di.IoDispatcher
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.offlinestt.domain.IOfflineSttEngine
import com.lingshu.feature.offlinestt.domain.OfflineSttConfig
import com.lingshu.feature.offlinestt.domain.OfflineSttProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SherpaOnnxEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : IOfflineSttEngine {

    override val provider: OfflineSttProvider = OfflineSttProvider.SHERPA_ONNX

    private val moduleTag = "SherpaOnnxEngine"
    private var loaded = false
    private var currentConfig: OfflineSttConfig? = null

    // TODO: Sherpa-ONNX Android AAR 依赖
    // implementation 'com.github.k2-fsa:sherpa-onnx-android:1.10.x'
    // import com.k2fsa.sherpa.onnx.OnlineRecognizer
    // import com.k2fsa.sherpa.onnx.OfflineRecognizer
    // private var offlineRecognizer: OfflineRecognizer? = null
    // private var onlineRecognizer: OnlineRecognizer? = null

    override suspend fun load(
        config: OfflineSttConfig,
        traceId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(
            moduleTag,
            "[$traceId] load START (SHERPA_ONNX PLACEHOLDER) | modelDir=${config.modelDir} | " +
                    "model=${config.modelName} | lang=${config.language} | useGpu=${config.useGpu}"
        )

        try {
            val modelDir = File(config.modelDir, config.modelName)
            val estimated = estimateRequiredStorageMb(config)
            LingShuLog.d(moduleTag, "[$traceId] estimated storage: ${estimated}MB")

            // TODO: Sherpa-ONNX 初始化
            // val modelConfig = OfflineRecognizer.ModelConfig(
            //     encoder = File(modelDir, "encoder.onnx").absolutePath,
            //     decoder = File(modelDir, "decoder.onnx").absolutePath,
            //     joiner  = File(modelDir, "joiner.onnx").absolutePath,
            //     tokens  = File(modelDir, "tokens.txt").absolutePath,
            //     numThreads = 4,
            //     sampleRate = 16000,
            //     enableDnn = config.useGpu
            // )
            // val featConfig = OfflineRecognizer.FeatureConfig(sampleRate = 16000)
            // offlineRecognizer = OfflineRecognizer(
            //     modelConfig = modelConfig,
            //     featConfig = featConfig,
            //     lmConfig = null
            // )
            // onlineRecognizer = OnlineRecognizer(...)

            // 占位：抛出异常让 Router 降级
            loaded = true
            currentConfig = config

            val ms = System.currentTimeMillis() - startTime
            LingShuLog.i(
                moduleTag,
                "[$traceId] load PLACEHOLDER SUCCESS (no real ONNX runtime) | ms=$ms"
            )
            Result.success(Unit)
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] load FAILED after ${ms}ms", e)
            Result.error(ErrorCodes.MODEL_LOAD_FAILED, "Sherpa-ONNX load failed: ${e.message}", e)
        }
    }

    override suspend fun transcribe(
        audioFile: File,
        traceId: String
    ): Result<String> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(moduleTag, "[$traceId] transcribe START (SHERPA PLACEHOLDER)")

        if (!loaded) {
            return@withContext Result.error(ErrorCodes.STT_FAILED, "Sherpa engine not loaded")
        }

        try {
            // TODO: Sherpa-ONNX OfflineRecognizer.decode
            // val samples = loadAs16kHzMono(audioFile)
            // val stream = offlineRecognizer!!.createStream()
            // stream.acceptWaveform(samples, 16000)
            // offlineRecognizer.decode(stream)
            // val result = stream.getResult()

            val pseudo = "[sherpa-onnx-pseudo-result]"
            val ms = System.currentTimeMillis() - startTime
            LingShuLog.i(
                moduleTag,
                "[$traceId] transcribe PLACEHOLDER done | chars=${pseudo.length} | ms=$ms"
            )
            Result.success(pseudo)
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] transcribe FAILED ${ms}ms", e)
            Result.error(ErrorCodes.STT_FAILED, "Sherpa transcribe failed: ${e.message}", e)
        }
    }

    override suspend fun transcribeStream(
        audioStream: Flow<ShortArray>,
        onPartial: (String) -> Unit,
        traceId: String
    ): Result<String> = withContext(ioDispatcher) {
        LingShuLog.i(moduleTag, "[$traceId] transcribeStream START (SHERPA PLACEHOLDER)")
        if (!loaded) {
            return@withContext Result.error(ErrorCodes.STT_FAILED, "Sherpa engine not loaded")
        }
        // TODO: OnlineRecognizer stream
        // val stream = onlineRecognizer!!.createStream()
        // audioStream.collect { chunk ->
        //     stream.acceptWaveform(chunk, 16000)
        //     onlineRecognizer.decode(stream)
        //     onPartial(stream.getResult())
        // }
        val pseudoFinal = "[sherpa-onnx-stream-final]"
        onPartial(pseudoFinal)
        Result.success(pseudoFinal)
    }

    override suspend fun unload() {
        LingShuLog.i(moduleTag, "unload START")
        try {
            // TODO: offlineRecognizer?.release()
            // TODO: onlineRecognizer?.release()
        } catch (e: Exception) {
            LingShuLog.w(moduleTag, "unload error", e)
        }
        loaded = false
        currentConfig = null
        LingShuLog.i(moduleTag, "unload DONE")
    }

    override fun isLoaded(): Boolean = loaded

    override fun estimateRequiredStorageMb(config: OfflineSttConfig): Long {
        return when {
            config.modelName.contains("paraformer") -> 900
            config.modelName.contains("wenet") -> 500
            config.modelName.contains("whisper") -> 600
            config.modelName.contains("zipformer") -> 400
            else -> 500
        }
    }
}

package com.lingshu.feature.offlinestt.data

import com.lingshu.core.common.di.IoDispatcher
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.offlinestt.domain.IOfflineSttEngine
import com.lingshu.feature.offlinestt.domain.OfflineSttConfig
import com.lingshu.feature.offlinestt.domain.OfflineSttProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineSttRouter @Inject constructor(
    private val sherpaOnnxEngine: SherpaOnnxEngine,
    private val whisperCppEngine: WhisperCppEngine,
    private val voskEngine: VoskEngine,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : IOfflineSttEngine {

    override val provider: OfflineSttProvider = OfflineSttProvider.SHERPA_ONNX

    private val moduleTag = "OfflineSttRouter"

    private val fallbackOrder: List<OfflineSttProvider> = listOf(
        OfflineSttProvider.SHERPA_ONNX,
        OfflineSttProvider.WHISPER_CPP,
        OfflineSttProvider.VOSK
    )

    private val engineMap: Map<OfflineSttProvider, IOfflineSttEngine> by lazy {
        mapOf(
            OfflineSttProvider.SHERPA_ONNX to sherpaOnnxEngine,
            OfflineSttProvider.WHISPER_CPP to whisperCppEngine,
            OfflineSttProvider.VOSK to voskEngine
        )
    }

    private var activeProvider: OfflineSttProvider? = null
    private var loadedConfig: OfflineSttConfig? = null

    private fun buildFallbackChain(preferred: OfflineSttProvider): List<IOfflineSttEngine> {
        val chain = mutableListOf<IOfflineSttEngine>()
        val used = mutableSetOf<OfflineSttProvider>()

        engineMap[preferred]?.let {
            chain.add(it)
            used.add(preferred)
            LingShuLog.d(moduleTag, "fallbackChain: primary=$preferred")
        }

        for (fallback in fallbackOrder) {
            if (fallback in used) continue
            engineMap[fallback]?.let { e ->
                chain.add(e)
                used.add(fallback)
            }
        }

        for ((k, e) in engineMap) {
            if (k in used) continue
            chain.add(e)
        }

        LingShuLog.i(
            moduleTag,
            "fallbackChain built | preferred=$preferred | size=${chain.size} | " +
                    "order=${chain.joinToString(" -> ") { it.provider.name }}"
        )
        return chain
    }

    private fun isLastAttempt(idx: Int, total: Int): Boolean = idx >= total

    override suspend fun load(
        config: OfflineSttConfig,
        traceId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(
            moduleTag,
            "[$traceId] load ROUTE start | preferred=${config.provider} | " +
                    "model=${config.modelName} | fallbackOrder=${fallbackOrder.joinToString()}"
        )

        val chain = buildFallbackChain(config.provider)
        if (chain.isEmpty()) {
            return@withContext Result.error(
                ErrorCodes.MODEL_LOAD_FAILED,
                "No STT engine registered in OfflineSttRouter"
            )
        }

        var lastError: Result.Error? = null
        var attempt = 0

        for (engine in chain) {
            attempt++
            val isPrimary = attempt == 1
            if (!isPrimary) {
                LingShuLog.w(
                    moduleTag,
                    "[$traceId] load FALLBACK attempt=$attempt/${chain.size} | " +
                            "switch to ${engine.provider} | lastErr=${lastError?.code}:${lastError?.message?.take(60)}"
                )
            }

            val attemptConfig = config.copy(provider = engine.provider)
            val t = if (traceId.isEmpty()) traceId else "${traceId}-L$attempt"
            LingShuLog.i(
                moduleTag,
                "[$traceId] load DISPATCH attempt=$attempt | engine=${engine.provider} | " +
                        "modelDir=${attemptConfig.modelDir} | model=${attemptConfig.modelName}"
            )

            when (val result = engine.load(attemptConfig, t)) {
                is Result.Success -> {
                    activeProvider = engine.provider
                    loadedConfig = attemptConfig
                    val ms = System.currentTimeMillis() - startTime
                    val switched = !isPrimary
                    LingShuLog.i(
                        moduleTag,
                        "[$traceId] load ROUTE success | attempts=$attempt | " +
                                "final=${engine.provider} | switched=$switched | totalMs=$ms"
                    )
                    return@withContext result
                }
                is Result.Error -> {
                    lastError = result
                    LingShuLog.w(
                        moduleTag,
                        "[$traceId] load engine ${engine.provider} FAILED | " +
                                "code=${result.code} | msg=${result.message.take(120)}"
                    )
                    try {
                        engine.unload()
                    } catch (x: Exception) {
                        LingShuLog.w(moduleTag, "cleanup unload failed for ${engine.provider}", x)
                    }
                    if (isLastAttempt(attempt, chain.size)) break
                }
            }
        }

        val ms = System.currentTimeMillis() - startTime
        LingShuLog.e(
            moduleTag,
            "[$traceId] load ROUTE ALL FAILED | attempts=$attempt | lastCode=${lastError?.code} | ms=$ms"
        )
        lastError ?: Result.error(ErrorCodes.MODEL_LOAD_FAILED, "All offline STT providers failed to load")
    }

    override suspend fun transcribe(
        audioFile: File,
        traceId: String
    ): Result<String> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val primary = activeProvider
        LingShuLog.i(
            moduleTag,
            "[$traceId] transcribe ROUTE start | activeProvider=$primary | file=${audioFile.name}"
        )

        val activeEngine = primary?.let { engineMap[it] }
        if (activeEngine != null && activeEngine.isLoaded()) {
            val t = if (traceId.isEmpty()) traceId else "${traceId}-T0"
            when (val r = activeEngine.transcribe(audioFile, t)) {
                is Result.Success -> {
                    val ms = System.currentTimeMillis() - startTime
                    LingShuLog.i(
                        moduleTag,
                        "[$traceId] transcribe ROUTE success (active=$primary) | chars=${r.data.length} | ms=$ms"
                    )
                    return@withContext r
                }
                is Result.Error -> {
                    LingShuLog.w(
                        moduleTag,
                        "[$traceId] active transcribe failed (${primary.name}), " +
                                "fallback to router chain | code=${r.code}"
                    )
                }
            }
        }

        val chain = fallbackOrder.mapNotNull { engineMap[it] }
        var lastError: Result.Error? = null
        var attempt = 0
        for (engine in chain) {
            attempt++
            if (!engine.isLoaded()) {
                LingShuLog.d(moduleTag, "[$traceId] transcribe skip ${engine.provider}: not loaded")
                val lc = loadedConfig ?: continue
                try {
                    val c = lc.copy(provider = engine.provider)
                    when (val lr = engine.load(c, "$traceId-LL$attempt")) {
                        is Result.Success -> Unit
                        is Result.Error -> {
                            lastError = lr
                            continue
                        }
                    }
                } catch (e: Exception) {
                    lastError = Result.error(ErrorCodes.MODEL_LOAD_FAILED, e.message ?: "load error", e)
                    continue
                }
            }

            val t = if (traceId.isEmpty()) traceId else "${traceId}-T$attempt"
            LingShuLog.i(moduleTag, "[$traceId] transcribe DISPATCH attempt=$attempt -> ${engine.provider}")
            when (val r = engine.transcribe(audioFile, t)) {
                is Result.Success -> {
                    activeProvider = engine.provider
                    val ms = System.currentTimeMillis() - startTime
                    LingShuLog.i(
                        moduleTag,
                        "[$traceId] transcribe ROUTE success | attempts=$attempt | " +
                                "final=${engine.provider} | chars=${r.data.length} | ms=$ms"
                    )
                    return@withContext r
                }
                is Result.Error -> {
                    lastError = r
                    LingShuLog.w(
                        moduleTag,
                        "[$traceId] transcribe ${engine.provider} failed | code=${r.code}"
                    )
                }
            }
        }

        val ms = System.currentTimeMillis() - startTime
        LingShuLog.e(moduleTag, "[$traceId] transcribe ROUTE ALL FAILED | attempts=$attempt | ms=$ms")
        lastError ?: Result.error(ErrorCodes.STT_FAILED, "All offline STT providers failed")
    }

    override suspend fun transcribeStream(
        audioStream: Flow<ShortArray>,
        onPartial: (String) -> Unit,
        traceId: String
    ): Result<String> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val primary = activeProvider
        LingShuLog.i(moduleTag, "[$traceId] transcribeStream ROUTE start | active=$primary")

        val activeEngine = primary?.let { engineMap[it] }
        if (activeEngine != null && activeEngine.isLoaded()) {
            val t = if (traceId.isEmpty()) traceId else "${traceId}-S0"
            val r = runCatching {
                activeEngine.transcribeStream(audioStream, onPartial, t)
            }.getOrElse { ex ->
                Result.error(ErrorCodes.STT_FAILED, "active stream error: ${ex.message}", ex)
            }
            if (r.isSuccess) {
                val ms = System.currentTimeMillis() - startTime
                LingShuLog.i(moduleTag, "[$traceId] transcribeStream ROUTE success (active=$primary) | ms=$ms")
                return@withContext r
            }
            LingShuLog.w(moduleTag, "[$traceId] active stream failed: ${(r as? Result.Error)?.code}")
        }

        val chain = fallbackOrder.mapNotNull { engineMap[it] }
        var lastError: Result.Error? = null
        var attempt = 0
        for (engine in chain) {
            attempt++
            if (!engine.isLoaded()) {
                val lc = loadedConfig ?: continue
                try {
                    val c = lc.copy(provider = engine.provider)
                    when (val lr = engine.load(c, "$traceId-SLL$attempt")) {
                        is Result.Success -> Unit
                        is Result.Error -> {
                            lastError = lr
                            continue
                        }
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            val t = if (traceId.isEmpty()) traceId else "${traceId}-S$attempt"
            LingShuLog.i(moduleTag, "[$traceId] transcribeStream DISPATCH attempt=$attempt -> ${engine.provider}")
            val r = runCatching {
                engine.transcribeStream(audioStream, onPartial, t)
            }.getOrElse { ex ->
                Result.error(ErrorCodes.STT_FAILED, ex.message ?: "stream error", ex)
            }
            when (r) {
                is Result.Success -> {
                    activeProvider = engine.provider
                    val ms = System.currentTimeMillis() - startTime
                    LingShuLog.i(
                        moduleTag,
                        "[$traceId] transcribeStream ROUTE success | attempt=$attempt | " +
                                "final=${engine.provider} | ms=$ms"
                    )
                    return@withContext r
                }
                is Result.Error -> lastError = r
            }
        }

        val ms = System.currentTimeMillis() - startTime
        LingShuLog.e(moduleTag, "[$traceId] transcribeStream ROUTE ALL FAILED | ms=$ms")
        lastError ?: Result.error(ErrorCodes.STT_FAILED, "All offline STT stream providers failed")
    }

    override suspend fun unload() {
        LingShuLog.i(moduleTag, "unload ROUTE start | activeProvider=$activeProvider")
        var first: Throwable? = null
        for (engine in engineMap.values) {
            try {
                if (engine.isLoaded()) engine.unload()
            } catch (e: Exception) {
                LingShuLog.w(moduleTag, "unload ${engine.provider} failed", e)
                if (first == null) first = e
            }
        }
        activeProvider = null
        loadedConfig = null
        LingShuLog.i(moduleTag, "unload ROUTE done")
    }

    override fun isLoaded(): Boolean {
        val active = activeProvider?.let { engineMap[it] }
        return active?.isLoaded() == true || engineMap.values.any { it.isLoaded() }
    }

    override fun estimateRequiredStorageMb(config: OfflineSttConfig): Long {
        val primary = engineMap[config.provider]
        if (primary != null) return primary.estimateRequiredStorageMb(config)
        return engineMap.values.firstOrNull()?.estimateRequiredStorageMb(config) ?: 500L
    }
}

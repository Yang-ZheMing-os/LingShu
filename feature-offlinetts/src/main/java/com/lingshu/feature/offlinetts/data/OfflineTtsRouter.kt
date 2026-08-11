package com.lingshu.feature.offlinetts.data

import com.lingshu.core.common.di.IoDispatcher
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.offlinetts.domain.IOfflineTtsEngine
import com.lingshu.feature.offlinetts.domain.OfflineTtsConfig
import com.lingshu.feature.offlinetts.domain.OfflineTtsProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineTtsRouter @Inject constructor(
    private val chatTtsEngine: ChatTtsEngine,
    private val bertVits2Engine: BertVits2Engine,
    private val piperEngine: PiperEngine,
    private val edgeTtsEngine: EdgeTtsEngine,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : IOfflineTtsEngine {

    override val provider: OfflineTtsProvider = OfflineTtsProvider.CHATTTS

    private val moduleTag = "OfflineTtsRouter"

    private val fallbackOrder: List<OfflineTtsProvider> = listOf(
        OfflineTtsProvider.CHATTTS,
        OfflineTtsProvider.BERT_VITS2,
        OfflineTtsProvider.PIPER,
        OfflineTtsProvider.EDGE_TTS_REMOTE_FALLBACK
    )

    private val engineMap: Map<OfflineTtsProvider, IOfflineTtsEngine> by lazy {
        mapOf(
            OfflineTtsProvider.CHATTTS to chatTtsEngine,
            OfflineTtsProvider.BERT_VITS2 to bertVits2Engine,
            OfflineTtsProvider.PIPER to piperEngine,
            OfflineTtsProvider.EDGE_TTS_REMOTE_FALLBACK to edgeTtsEngine
        )
    }

    private var activeProvider: OfflineTtsProvider? = null
    private var loadedConfig: OfflineTtsConfig? = null

    private fun buildFallbackChain(preferred: OfflineTtsProvider): List<IOfflineTtsEngine> {
        val chain = mutableListOf<IOfflineTtsEngine>()
        val used = mutableSetOf<OfflineTtsProvider>()

        engineMap[preferred]?.let {
            chain.add(it)
            used.add(preferred)
        }

        for (fb in fallbackOrder) {
            if (fb in used) continue
            engineMap[fb]?.let { e ->
                chain.add(e)
                used.add(fb)
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

    override suspend fun load(
        config: OfflineTtsConfig,
        traceId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(
            moduleTag,
            "[$traceId] load ROUTE start | preferred=${config.provider} | " +
                    "modelDir=${config.modelDir} | voice=${config.voiceId}"
        )

        val chain = buildFallbackChain(config.provider)
        if (chain.isEmpty()) {
            return@withContext Result.error(
                ErrorCodes.MODEL_LOAD_FAILED,
                "No TTS engine registered in OfflineTtsRouter"
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
                    "[$traceId] load FALLBACK attempt=$attempt/${chain.size} " +
                            "-> ${engine.provider} | lastErr=${lastError?.code}:${lastError?.message?.take(60)}"
                )
            }

            val attemptConfig = config.copy(provider = engine.provider)
            val t = if (traceId.isEmpty()) traceId else "${traceId}-L$attempt"
            LingShuLog.i(
                moduleTag,
                "[$traceId] load DISPATCH attempt=$attempt | engine=${engine.provider} " +
                        "| voice=${attemptConfig.voiceId} | sampleRate=${attemptConfig.sampleRate}"
            )

            when (val r = engine.load(attemptConfig, t)) {
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
                    return@withContext r
                }
                is Result.Error -> {
                    lastError = r
                    LingShuLog.w(
                        moduleTag,
                        "[$traceId] load ${engine.provider} FAILED | " +
                                "code=${r.code} | msg=${r.message.take(120)}"
                    )
                    try { engine.unload() } catch (x: Exception) {
                        LingShuLog.w(moduleTag, "cleanup unload failed for ${engine.provider}", x)
                    }
                    if (attempt >= chain.size) break
                }
            }
        }

        val ms = System.currentTimeMillis() - startTime
        LingShuLog.e(
            moduleTag,
            "[$traceId] load ROUTE ALL FAILED | attempts=$attempt | " +
                    "lastCode=${lastError?.code} | ms=$ms"
        )
        lastError ?: Result.error(ErrorCodes.MODEL_LOAD_FAILED, "All TTS providers failed to load")
    }

    override suspend fun synthesize(
        text: String,
        outputFile: File,
        traceId: String
    ): Result<File> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val primary = activeProvider
        LingShuLog.i(
            moduleTag,
            "[$traceId] synthesize ROUTE start | chars=${text.length} | active=$primary"
        )

        val active = primary?.let { engineMap[it] }
        if (active != null && active.isLoaded()) {
            val t = if (traceId.isEmpty()) traceId else "${traceId}-T0"
            when (val r = active.synthesize(text, outputFile, t)) {
                is Result.Success -> {
                    val ms = System.currentTimeMillis() - startTime
                    LingShuLog.i(
                        moduleTag,
                        "[$traceId] synthesize ROUTE success (active=$primary) | ms=$ms"
                    )
                    return@withContext r
                }
                is Result.Error -> {
                    LingShuLog.w(
                        moduleTag,
                        "[$traceId] active synthesize failed (${primary.name}): ${r.code}; " +
                                "fallback chain"
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
                val lc = loadedConfig
                if (lc != null) {
                    val cfg = lc.copy(provider = engine.provider)
                    try {
                        when (val lr = engine.load(cfg, "$traceId-LL$attempt")) {
                            is Result.Success -> Unit
                            is Result.Error -> {
                                lastError = lr
                                continue
                            }
                        }
                    } catch (e: Exception) {
                        lastError = Result.error(ErrorCodes.MODEL_LOAD_FAILED, e.message ?: "", e)
                        continue
                    }
                } else {
                    LingShuLog.d(moduleTag, "[$traceId] synthesize skip ${engine.provider}: no loadedConfig")
                    continue
                }
            }

            val t = if (traceId.isEmpty()) traceId else "${traceId}-T$attempt"
            LingShuLog.i(moduleTag, "[$traceId] synthesize DISPATCH attempt=$attempt -> ${engine.provider}")
            when (val r = engine.synthesize(text, outputFile, t)) {
                is Result.Success -> {
                    activeProvider = engine.provider
                    val ms = System.currentTimeMillis() - startTime
                    LingShuLog.i(
                        moduleTag,
                        "[$traceId] synthesize ROUTE success | attempts=$attempt | " +
                                "final=${engine.provider} | ms=$ms"
                    )
                    return@withContext r
                }
                is Result.Error -> {
                    lastError = r
                    LingShuLog.w(moduleTag, "[$traceId] synthesize ${engine.provider} failed: ${r.code}")
                }
            }
        }

        val ms = System.currentTimeMillis() - startTime
        LingShuLog.e(moduleTag, "[$traceId] synthesize ROUTE ALL FAILED | attempts=$attempt | ms=$ms")
        lastError ?: Result.error(ErrorCodes.TTS_UNAVAILABLE, "All TTS providers failed to synthesize")
    }

    override suspend fun synthesizeStream(
        text: String,
        onPcmChunk: (ShortArray) -> Unit,
        traceId: String
    ): Result<Long> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        val primary = activeProvider
        LingShuLog.i(
            moduleTag,
            "[$traceId] synthesizeStream ROUTE start | chars=${text.length} | active=$primary"
        )

        val active = primary?.let { engineMap[it] }
        if (active != null && active.isLoaded()) {
            val t = if (traceId.isEmpty()) traceId else "${traceId}-S0"
            val r = runCatching {
                active.synthesizeStream(text, onPcmChunk, t)
            }.getOrElse { ex ->
                Result.error(ErrorCodes.TTS_UNAVAILABLE, "active stream error: ${ex.message}", ex)
            }
            if (r.isSuccess) {
                val ms = System.currentTimeMillis() - startTime
                LingShuLog.i(
                    moduleTag,
                    "[$traceId] synthesizeStream ROUTE success (active=$primary) | ms=$ms"
                )
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
                        is Result.Error -> { lastError = lr; continue }
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            val t = if (traceId.isEmpty()) traceId else "${traceId}-S$attempt"
            LingShuLog.i(
                moduleTag,
                "[$traceId] synthesizeStream DISPATCH attempt=$attempt -> ${engine.provider}"
            )
            val r = runCatching {
                engine.synthesizeStream(text, onPcmChunk, t)
            }.getOrElse { ex ->
                Result.error(ErrorCodes.TTS_UNAVAILABLE, ex.message ?: "stream error", ex)
            }
            when (r) {
                is Result.Success -> {
                    activeProvider = engine.provider
                    val ms = System.currentTimeMillis() - startTime
                    LingShuLog.i(
                        moduleTag,
                        "[$traceId] synthesizeStream ROUTE success | " +
                                "attempts=$attempt | final=${engine.provider} | ms=$ms"
                    )
                    return@withContext r
                }
                is Result.Error -> lastError = r
            }
        }

        val ms = System.currentTimeMillis() - startTime
        LingShuLog.e(
            moduleTag,
            "[$traceId] synthesizeStream ROUTE ALL FAILED | attempts=$attempt | ms=$ms"
        )
        lastError ?: Result.error(ErrorCodes.TTS_UNAVAILABLE, "All TTS stream providers failed")
    }

    override suspend fun unload() {
        LingShuLog.i(moduleTag, "unload ROUTE start | activeProvider=$activeProvider")
        for (e in engineMap.values) {
            try {
                if (e.isLoaded()) e.unload()
            } catch (x: Exception) {
                LingShuLog.w(moduleTag, "unload ${e.provider} failed", x)
            }
        }
        activeProvider = null
        loadedConfig = null
        LingShuLog.i(moduleTag, "unload ROUTE done")
    }

    override fun isLoaded(): Boolean {
        val a = activeProvider?.let { engineMap[it] }
        return a?.isLoaded() == true || engineMap.values.any { it.isLoaded() }
    }

    override fun getAvailableVoices(): List<String> {
        val out = linkedSetOf<String>()
        activeProvider?.let { p ->
            engineMap[p]?.takeIf { it.isLoaded() }?.let { out.addAll(it.getAvailableVoices()) }
        }
        for (e in engineMap.values) {
            runCatching { out.addAll(e.getAvailableVoices()) }
        }
        return out.toList()
    }

    override suspend fun loadVoice(
        voiceId: String,
        modelFile: File,
        traceId: String
    ): Result<Unit> {
        LingShuLog.i(
            moduleTag,
            "[$traceId] loadVoice ROUTE | voiceId=$voiceId | activeProvider=$activeProvider"
        )
        val primary = activeProvider?.let { engineMap[it] }
        if (primary != null && primary.isLoaded()) {
            val r = primary.loadVoice(voiceId, modelFile, traceId)
            if (r.isSuccess) return r
            LingShuLog.w(moduleTag, "[$traceId] primary loadVoice failed, try fallback chain")
        }
        var last: Result.Error? = null
        for (e in engineMap.values) {
            if (!e.isLoaded()) continue
            if (e === primary) continue
            when (val r = e.loadVoice(voiceId, modelFile, traceId)) {
                is Result.Success -> { activeProvider = e.provider; return r }
                is Result.Error -> last = r
            }
        }
        return last ?: Result.error(
            ErrorCodes.VOICE_CLONE_FAILED,
            "No TTS engine loaded to load voice $voiceId"
        )
    }
}

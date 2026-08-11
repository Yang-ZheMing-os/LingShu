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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoskEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : IOfflineSttEngine {

    override val provider: OfflineSttProvider = OfflineSttProvider.VOSK

    private val moduleTag = "VoskEngine"
    private var loaded = false
    private var nativeLibLoaded = false
    private var modelHandle: Long = 0L
    private var recognizerHandle: Long = 0L
    private var currentConfig: OfflineSttConfig? = null

    private external fun nativeVoskSetLogLevel(level: Int)
    private external fun nativeVoskModelNew(path: String): Long
    private external fun nativeVoskModelFree(model: Long)
    private external fun nativeVoskRecognizerNew(model: Long, sampleRate: Float): Long
    private external fun nativeVoskRecognizerFree(recognizer: Long)
    private external fun nativeVoskRecognizerAcceptWaveform(
        recognizer: Long,
        data: ShortArray,
        len: Int
    ): Boolean
    private external fun nativeVoskRecognizerResult(recognizer: Long): String
    private external fun nativeVoskRecognizerPartialResult(recognizer: Long): String
    private external fun nativeVoskRecognizerFinalResult(recognizer: Long): String

    private fun ensureNativeLibLoaded() {
        if (nativeLibLoaded) return
        try {
            System.loadLibrary("vosk")
            nativeLibLoaded = true
            nativeVoskSetLogLevel(-1)
            LingShuLog.i(moduleTag, "libvosk.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            LingShuLog.e(moduleTag, "libvosk.so not found", e)
            throw UnsupportedOperationException("libvosk.so not loaded")
        }
    }

    override suspend fun load(
        config: OfflineSttConfig,
        traceId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(
            moduleTag,
            "[$traceId] load START | modelDir=${config.modelDir} | model=${config.modelName} | " +
                    "lang=${config.language} | maxChars=${config.maxSegmentChars}"
        )

        try {
            ensureNativeLibLoaded()

            val modelDir = File(config.modelDir, config.modelName)
            if (!modelDir.exists() || !modelDir.isDirectory) {
                LingShuLog.e(moduleTag, "[$traceId] Vosk model dir not found: ${modelDir.absolutePath}")
                return@withContext Result.error(
                    ErrorCodes.MODEL_LOAD_FAILED,
                    "Vosk model directory not found: ${config.modelName}"
                )
            }

            val modelSizeMb = calculateDirSizeMb(modelDir)
            val estimated = estimateRequiredStorageMb(config)
            LingShuLog.i(
                moduleTag,
                "[$traceId] Vosk model size=${modelSizeMb}MB | estimated=${estimated}MB"
            )

            val initStart = System.currentTimeMillis()
            // TODO: Model + Recognizer 初始化
            // Model model = new Model(modelDir.getAbsolutePath());
            // Recognizer recognizer = new Recognizer(model, 16000.0f);
            // recognizer.setMaxAlternatives(1);
            // recognizer.setWords(false);

            // --- PSEUDO JNI ---
            // modelHandle = nativeVoskModelNew(modelDir.absolutePath)
            // if (modelHandle == 0L) throw RuntimeException("Vosk Model create failed")
            // recognizerHandle = nativeVoskRecognizerNew(modelHandle, 16000.0f)
            // if (recognizerHandle == 0L) throw RuntimeException("Vosk Recognizer create failed")
            modelHandle = 0xBEEFCAFEL
            recognizerHandle = 0xCAFEBABEL
            // --- END PSEUDO ---

            val initElapsed = System.currentTimeMillis() - initStart
            loaded = true
            currentConfig = config

            val total = System.currentTimeMillis() - startTime
            LingShuLog.i(
                moduleTag,
                "[$traceId] load SUCCESS | initMs=$initElapsed | totalMs=$total"
            )
            Result.success(Unit)
        } catch (e: UnsupportedOperationException) {
            Result.error(ErrorCodes.MODEL_LOAD_FAILED, "vosk native library not available", e)
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] load FAILED after ${ms}ms", e)
            Result.error(ErrorCodes.MODEL_LOAD_FAILED, "Failed to load vosk model: ${e.message}", e)
        }
    }

    override suspend fun transcribe(
        audioFile: File,
        traceId: String
    ): Result<String> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(
            moduleTag,
            "[$traceId] transcribe START | file=${audioFile.name} | " +
                    "sizeKB=${audioFile.length() / 1024}"
        )

        if (!loaded || recognizerHandle == 0L) {
            return@withContext Result.error(ErrorCodes.STT_FAILED, "Vosk engine not loaded")
        }

        try {
            val pcmSamples = loadAs16kHzMono(audioFile)
            val durationSec = pcmSamples.size / 16000.0
            LingShuLog.d(
                moduleTag,
                "[$traceId] audio loaded | samples=${pcmSamples.size} | durationSec=%.2f".format(durationSec)
            )

            val inferStart = System.currentTimeMillis()
            val chunkSize = 4000
            val sb = StringBuilder()
            var lastPartial = ""
            var processedSamples = 0

            for (offset in pcmSamples.indices step chunkSize) {
                val end = (offset + chunkSize).coerceAtMost(pcmSamples.size)
                val chunk = pcmSamples.copyOfRange(offset, end)

                // TODO: recognizer.acceptWaveForm(chunk, chunk.size)
                // boolean complete = recognizer.acceptWaveForm(chunkData, chunkData.length);
                // if (complete) {
                //     sb.append(new JSONObject(recognizer.getResult()).optString("text"));
                //     sb.append(" ");
                // } else {
                //     String partial = new JSONObject(recognizer.getPartialResult())
                //         .optString("partial");
                //     if (!partial.equals(lastPartial)) { ... }
                // }

                // --- PSEUDO ---
                val hasResult = (end - offset) == chunkSize && (end / chunkSize) % 5 == 0
                if (hasResult) {
                    val seg = "[vosk-seg-${end / chunkSize}]"
                    sb.append(seg).append(" ")
                    LingShuLog.d(moduleTag, "[$traceId] vosk result chunk: $seg")
                } else {
                    lastPartial = "[vosk-partial-${processedSamples / 1000}k]"
                }
                processedSamples += chunk.size
                // --- END PSEUDO ---
            }

            // TODO: String finalJson = recognizer.getFinalResult();
            // sb.append(new JSONObject(finalJson).optString("text"));

            sb.append("[vosk-final]")
            val inferElapsed = System.currentTimeMillis() - inferStart
            val rta = inferElapsed / (durationSec * 1000.0)
            val text = sb.toString().trim()

            LingShuLog.i(
                moduleTag,
                "[$traceId] transcribe INFER | inferMs=$inferElapsed | " +
                        "audioSec=%.2f | RTA=%.2fx | chars=%d".format(durationSec, rta, text.length)
            )
            LingShuLog.v(moduleTag, "[$traceId] text=$text")

            val total = System.currentTimeMillis() - startTime
            LingShuLog.i(moduleTag, "[$traceId] transcribe SUCCESS | totalMs=$total")
            Result.success(text)
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] transcribe FAILED after ${ms}ms", e)
            Result.error(ErrorCodes.STT_FAILED, "Vosk transcribe failed: ${e.message}", e)
        }
    }

    override suspend fun transcribeStream(
        audioStream: Flow<ShortArray>,
        onPartial: (String) -> Unit,
        traceId: String
    ): Result<String> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(moduleTag, "[$traceId] transcribeStream START")

        if (!loaded || recognizerHandle == 0L) {
            return@withContext Result.error(ErrorCodes.STT_FAILED, "Vosk engine not loaded")
        }

        val finalSb = StringBuilder()
        var totalSamples = 0L

        try {
            audioStream
                .flowOn(ioDispatcher)
                .collect { chunk ->
                    totalSamples += chunk.size

                    // TODO: acceptWaveform + partial/result
                    // boolean complete = recognizer.acceptWaveForm(chunk, chunk.length);
                    // if (complete) {
                    //     String t = new JSONObject(recognizer.getResult()).optString("text");
                    //     finalSb.append(t).append(" ");
                    //     onPartial(finalSb.toString());
                    // } else {
                    //     String p = new JSONObject(recognizer.getPartialResult())
                    //         .optString("partial");
                    //     if (p.isNotBlank()) onPartial(finalSb.toString() + p);
                    // }

                    // --- PSEUDO ---
                    val partial = "[vosk-stream-partial-${totalSamples / 8000}]"
                    if (totalSamples % 32000 == 0L) {
                        finalSb.append("[vosk-seg] ")
                    }
                    onPartial(finalSb.toString() + partial)
                    // --- END PSEUDO ---
                }

            // TODO: recognizer.getFinalResult()
            finalSb.append("[vosk-stream-final]")
            onPartial(finalSb.toString())

            val totalMs = System.currentTimeMillis() - startTime
            val audioSec = totalSamples / 16000.0
            val rta = if (audioSec > 0) totalMs / (audioSec * 1000.0) else 0.0
            LingShuLog.i(
                moduleTag,
                "[$traceId] transcribeStream SUCCESS | totalMs=$totalMs | " +
                        "audioSec=%.2f | RTA=%.2fx | chars=%d".format(audioSec, rta, finalSb.length)
            )
            Result.success(finalSb.toString().trim())
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] transcribeStream FAILED after ${ms}ms", e)
            Result.error(ErrorCodes.STT_FAILED, "Vosk stream transcribe failed: ${e.message}", e)
        }
    }

    override suspend fun unload() {
        LingShuLog.i(moduleTag, "unload START | loaded=$loaded")
        try {
            if (recognizerHandle != 0L) {
                // TODO: nativeVoskRecognizerFree(recognizerHandle)
                recognizerHandle = 0L
            }
            if (modelHandle != 0L) {
                // TODO: nativeVoskModelFree(modelHandle)
                modelHandle = 0L
            }
        } catch (e: Exception) {
            LingShuLog.w(moduleTag, "unload error", e)
        }
        loaded = false
        currentConfig = null
        LingShuLog.i(moduleTag, "unload DONE")
    }

    override fun isLoaded(): Boolean = loaded && recognizerHandle != 0L

    override fun estimateRequiredStorageMb(config: OfflineSttConfig): Long {
        return when {
            config.modelName.contains("small-cn") -> 45
            config.modelName.contains("cn-0.22") -> 1300
            config.modelName.contains("cn") -> 500
            else -> 100
        }
    }

    private fun calculateDirSizeMb(dir: File): Long {
        var size = 0L
        dir.walkTopDown().filter { it.isFile }.forEach { size += it.length() }
        return size / (1024 * 1024)
    }

    private fun loadAs16kHzMono(file: File): ShortArray {
        // TODO: 复用 WhisperCppEngine 中的解码/重采样逻辑，或提取到 AudioUtils
        val fake = ShortArray(16000 * 5)
        LingShuLog.w(moduleTag, "loadAs16kHzMono: using pseudo 5s silence, replace with real decoder")
        return fake
    }
}

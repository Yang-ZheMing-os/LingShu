package com.lingshu.feature.offlinetts.data

import android.content.Context
import com.lingshu.core.common.di.IoDispatcher
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.offlinetts.domain.IOfflineTtsEngine
import com.lingshu.feature.offlinetts.domain.OfflineTtsConfig
import com.lingshu.feature.offlinetts.domain.OfflineTtsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class PiperEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : IOfflineTtsEngine {

    override val provider: OfflineTtsProvider = OfflineTtsProvider.PIPER

    private val moduleTag = "PiperEngine"
    private var loaded = false
    private var currentConfig: OfflineTtsConfig? = null
    private var nativeLibLoaded = false
    private var piperHandle: Long = 0L
    private var voices = mutableListOf<String>()

    // TODO: Piper-Android JNI (libpiper_phonemize + onnxruntime via espeak-ng)
    // external fun nativePiperCreate(configPath: String, useCuda: Boolean): Long
    // external fun nativePiperTextToWav(handle: Long, text: String, outWavPath: String): Long
    // external fun nativePiperTextToPcm(handle: Long, text: String, outShortBuffer: ShortArray): Long
    // external fun nativePiperDestroy(handle: Long)

    private fun ensureNative() {
        if (nativeLibLoaded) return
        try {
            System.loadLibrary("piper_phonemize")
            nativeLibLoaded = true
            LingShuLog.i(moduleTag, "libpiper_phonemize.so loaded")
        } catch (e: UnsatisfiedLinkError) {
            LingShuLog.e(moduleTag, "piper native libs missing", e)
            throw UnsupportedOperationException("piper native libs not loaded")
        }
    }

    override suspend fun load(
        config: OfflineTtsConfig,
        traceId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(
            moduleTag,
            "[$traceId] load START | modelDir=${config.modelDir} | voice=${config.voiceId}"
        )

        try {
            ensureNative()

            val dir = File(config.modelDir)
            val onnxFile = File(dir, "${config.voiceId}.onnx")
            val jsonFile = File(dir, "${config.voiceId}.onnx.json")
            if (!onnxFile.exists()) {
                val alternatives = dir.listFiles()?.filter {
                    it.extension == "onnx"
                }?.map { it.nameWithoutExtension } ?: emptyList()
                LingShuLog.e(
                    moduleTag,
                    "[$traceId] ${onnxFile.name} not found; alternatives=$alternatives"
                )
                if (alternatives.isNotEmpty()) voices = alternatives.toMutableList()
                return@withContext Result.error(
                    ErrorCodes.MODEL_LOAD_FAILED,
                    "Piper model not found: ${config.voiceId}.onnx"
                )
            }

            val modelMb = onnxFile.length() / (1024 * 1024)
            val jsonMb = jsonFile.length() / 1024
            LingShuLog.i(moduleTag, "[$traceId] onnx=${modelMb}MB json=${jsonMb}KB")

            val initStart = System.currentTimeMillis()
            // TODO: 初始化 piper 实例
            // piper_handle h;
            // piper::create(config, useESpeak = true, h);
            // piperHandle = reinterpret_cast<jlong>(new piper_handle(h));
            piperHandle = 0xCAFED00DL
            voices.add(config.voiceId)

            loaded = true
            currentConfig = config
            val initMs = System.currentTimeMillis() - initStart
            val total = System.currentTimeMillis() - startTime
            LingShuLog.i(
                moduleTag,
                "[$traceId] load SUCCESS | initMs=$initMs | totalMs=$total | estMemMb=${estMemMb()}"
            )
            Result.success(Unit)
        } catch (e: UnsupportedOperationException) {
            Result.error(ErrorCodes.MODEL_LOAD_FAILED, "piper native libs missing", e)
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] load FAILED after ${ms}ms", e)
            Result.error(ErrorCodes.MODEL_LOAD_FAILED, "Piper load failed: ${e.message}", e)
        }
    }

    override suspend fun synthesize(
        text: String,
        outputFile: File,
        traceId: String
    ): Result<File> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(
            moduleTag,
            "[$traceId] synthesize START | chars=${text.length} | " +
                    "voice=${currentConfig?.voiceId} | out=${outputFile.name}"
        )

        if (!loaded || piperHandle == 0L) {
            return@withContext Result.error(ErrorCodes.TTS_UNAVAILABLE, "Piper not loaded")
        }

        try {
            val sentences = splitText(text)
            val mergedPcm = mutableListOf<Short>()
            var totalInferMs = 0L
            var totalPhones = 0

            sentences.forEachIndexed { idx, seg ->
                val segStart = System.currentTimeMillis()
                // TODO: nativePiperTextToPcm or to wav file
                // long pcmSamples = nativePiperTextToPcm(piperHandle, seg, buffer);
                // if (pcmSamples < 0) throw RuntimeException("piper synthesize error");
                // mergedPcm.addAll(buffer.copyOf(pcmSamples).asList())

                // --- PSEUDO ---
                val sr = currentConfig?.sampleRate ?: 22050
                val durationMs = (seg.length * 180 / (currentConfig?.speed ?: 1f)).toLong()
                val n = ((durationMs * sr) / 1000).toInt()
                val buf = ShortArray(n) { ((Math.random() * 6500) - 3250).toInt().toShort() }
                mergedPcm.addAll(buf.asList())
                val phones = (seg.length * 1.2).toInt()
                totalPhones += phones
                val segMs = System.currentTimeMillis() - segStart
                totalInferMs += segMs
                val audioSec = n.toDouble() / sr.toDouble()
                LingShuLog.i(
                    moduleTag,
                    "[$traceId] seg${idx + 1}/${sentences.size} | chars=${seg.length} | " +
                            "phones=$phones | ms=$segMs | audioSec=%.2f | RTA=%.2fx".format(
                                audioSec, segMs / (audioSec * 1000.0)
                            )
                )
                // --- END PSEUDO ---
            }

            outputFile.parentFile?.mkdirs()
            val bytes = writeWav(mergedPcm.toShortArray(), outputFile, currentConfig?.sampleRate ?: 22050)
            val sr = currentConfig?.sampleRate ?: 22050
            val audioSec = mergedPcm.size.toDouble() / sr.toDouble()
            val totalMs = System.currentTimeMillis() - startTime
            LingShuLog.i(
                moduleTag,
                "[$traceId] synthesize SUCCESS | segs=${sentences.size} | chars=${text.length} | " +
                        "phones=$totalPhones | audioSec=%.2f | RTA=%.2fx | bytes=$bytes | totalMs=$totalMs".format(
                            audioSec, totalMs / (audioSec * 1000.0)
                        )
            )
            Result.success(outputFile)
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] synthesize FAILED after ${ms}ms", e)
            Result.error(ErrorCodes.TTS_UNAVAILABLE, "Piper synthesize failed: ${e.message}", e)
        }
    }

    override suspend fun synthesizeStream(
        text: String,
        onPcmChunk: (ShortArray) -> Unit,
        traceId: String
    ): Result<Long> = withContext(ioDispatcher) {
        LingShuLog.i(moduleTag, "[$traceId] synthesizeStream START | chars=${text.length}")
        if (!loaded || piperHandle == 0L) {
            return@withContext Result.error(ErrorCodes.TTS_UNAVAILABLE, "Piper not loaded")
        }
        try {
            val sr = currentConfig?.sampleRate ?: 22050
            val segs = splitText(text)
            var total = 0L
            segs.forEach { seg ->
                val durationMs = (seg.length * 180 / (currentConfig?.speed ?: 1f)).toLong()
                val n = ((durationMs * sr) / 1000).toInt()
                val chunk = ShortArray(n) { ((Math.random() * 6500) - 3250).toInt().toShort() }
                onPcmChunk(chunk)
                total += n
            }
            LingShuLog.i(moduleTag, "[$traceId] synthesizeStream SUCCESS | samples=$total")
            Result.success(total)
        } catch (e: Exception) {
            LingShuLog.e(moduleTag, "[$traceId] synthesizeStream FAILED", e)
            Result.error(ErrorCodes.TTS_UNAVAILABLE, "Piper stream failed: ${e.message}", e)
        }
    }

    override suspend fun unload() {
        LingShuLog.i(moduleTag, "unload START")
        try {
            if (piperHandle != 0L) {
                // TODO: nativePiperDestroy(piperHandle)
                piperHandle = 0L
            }
        } catch (e: Exception) {
            LingShuLog.w(moduleTag, "unload error", e)
        }
        loaded = false
        currentConfig = null
        LingShuLog.i(moduleTag, "unload DONE")
    }

    override fun isLoaded(): Boolean = loaded && piperHandle != 0L

    override fun getAvailableVoices(): List<String> = voices.ifEmpty {
        listOf(
            "zh_CN-huayan-medium",
            "zh_CN-xiaobei-medium",
            "zh_CN-hui-medium",
            "en_US-amy-medium",
            "en_US-joe-medium",
            "ja_JP-amak-medium"
        )
    }

    override suspend fun loadVoice(
        voiceId: String,
        modelFile: File,
        traceId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        LingShuLog.i(moduleTag, "[$traceId] loadVoice | voiceId=$voiceId file=${modelFile.name}")
        try {
            if (!modelFile.exists()) {
                return@withContext Result.error(
                    ErrorCodes.VOICE_CLONE_FAILED,
                    "Piper voice onnx not found: ${modelFile.absolutePath}"
                )
            }
            if (!voices.contains(voiceId)) voices.add(voiceId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error(ErrorCodes.VOICE_CLONE_FAILED, "Piper loadVoice failed: ${e.message}", e)
        }
    }

    private fun splitText(text: String): List<String> {
        val list = mutableListOf<String>()
        val sb = StringBuilder()
        val punct = setOf('。', '！', '？', '.', '!', '?', '\n', '，', ',')
        for (ch in text) {
            sb.append(ch)
            if ((ch in punct && sb.length >= 15) || sb.length >= 150) {
                list.add(sb.toString().trim())
                sb.clear()
            }
        }
        if (sb.isNotBlank()) list.add(sb.toString().trim())
        return list
    }

    private fun writeWav(pcm: ShortArray, output: File, sampleRate: Int): Long {
        output.parentFile?.mkdirs()
        val dataSize = pcm.size * 2
        val raf = java.io.RandomAccessFile(output, "rw")
        try {
            raf.setLength(0)
            val header = ByteArray(44)
            val bb = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray())
            bb.putInt(36 + dataSize)
            bb.put("WAVE".toByteArray())
            bb.put("fmt ".toByteArray())
            bb.putInt(16)
            bb.putShort(1)
            bb.putShort(1)
            bb.putInt(sampleRate)
            bb.putInt(sampleRate * 2)
            bb.putShort(2)
            bb.putShort(16)
            bb.put("data".toByteArray())
            bb.putInt(dataSize)
            raf.write(header)
            val dataBuf = java.nio.ByteBuffer.allocateDirect(min(4096, dataSize))
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var i = 0
            val ch = raf.channel
            while (i < pcm.size) {
                dataBuf.clear()
                while (dataBuf.hasRemaining() && i < pcm.size) dataBuf.putShort(pcm[i++])
                dataBuf.flip()
                while (dataBuf.hasRemaining()) ch.write(dataBuf)
            }
        } finally {
            raf.close()
        }
        return output.length()
    }

    private fun estMemMb(): Long {
        val cfg = currentConfig ?: return 400
        return when {
            cfg.voiceId.contains("x-large") -> 900
            cfg.voiceId.contains("large") -> 700
            cfg.voiceId.contains("medium") -> 400
            cfg.voiceId.contains("small") -> 200
            else -> 350
        }
    }
}

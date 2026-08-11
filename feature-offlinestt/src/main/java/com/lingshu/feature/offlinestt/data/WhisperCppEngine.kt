package com.lingshu.feature.offlinestt.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WhisperCppEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : IOfflineSttEngine {

    override val provider: OfflineSttProvider = OfflineSttProvider.WHISPER_CPP

    private val moduleTag = "WhisperCppEngine"
    private var whisperContextPtr: Long = 0L
    private var loaded = false
    private var currentConfig: OfflineSttConfig? = null
    private var nativeLibLoaded = false

    // ===================== JNI 声明 =====================

    private fun ensureNativeLibLoaded() {
        if (nativeLibLoaded) return
        try {
            System.loadLibrary("whisper")
            nativeLibLoaded = true
            LingShuLog.i(moduleTag, "libwhisper.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            LingShuLog.e(moduleTag, "libwhisper.so not found, native engine unavailable", e)
            throw UnsupportedOperationException("libwhisper.so not loaded", e)
        }
    }

    /**
     * 初始化 whisper_context
     * 返回 whisper_context* 的 jlong 句柄，0 表示失败
     */
    private external fun nativeInitFromFileWithParams(
        modelPath: String,
        language: String,
        useGpu: Boolean,
        beamSize: Int
    ): Long

    /**
     * 执行完整推理
     * @param ctxPtr whisper_context* 句柄
     * @param samples PCM 16-bit short 数组（内部会转为 float）
     * @param sampleCount 有效采样数
     * @param language 语言代码 "zh" / "en" / "auto"
     * @param beamSize beam search 宽度
     * @return 0=成功，非 0=错误码
     */
    private external fun nativeFull(
        ctxPtr: Long,
        samples: ShortArray,
        sampleCount: Int,
        language: String,
        beamSize: Int
    ): Int

    /** 获取第 index 段的文本 */
    private external fun nativeFullGetSegmentText(ctxPtr: Long, index: Int): String

    /** 获取总段数 */
    private external fun nativeFullNSegments(ctxPtr: Long): Int

    /** 释放 whisper_context */
    private external fun nativeFree(ctxPtr: Long)

    // ===================== IOfflineSttEngine 实现 =====================

    override suspend fun load(
        config: OfflineSttConfig,
        traceId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(moduleTag, "[$traceId] load START | provider=$provider | " +
                "modelDir=${config.modelDir} | model=${config.modelName} | " +
                "lang=${config.language} | useGpu=${config.useGpu} | beam=${config.beamSize}")

        try {
            ensureNativeLibLoaded()

            val modelFile = File(config.modelDir, config.modelName)
            if (!modelFile.exists()) {
                LingShuLog.e(moduleTag, "[$traceId] model file not found: ${modelFile.absolutePath}")
                return@withContext Result.error(
                    ErrorCodes.MODEL_LOAD_FAILED,
                    "STT model file not found: ${config.modelName}"
                )
            }

            val fileSizeMb = modelFile.length() / (1024 * 1024)
            val md5 = calculateFileMd5(modelFile)
            LingShuLog.i(moduleTag, "[$traceId] model file | size=${fileSizeMb}MB | md5=$md5")

            val estimated = estimateRequiredStorageMb(config)
            LingShuLog.d(moduleTag, "[$traceId] estimated storage required: ~${estimated}MB")

            val initStart = System.currentTimeMillis()

            // ★ 真实 JNI 调用：初始化 whisper_context
            whisperContextPtr = nativeInitFromFileWithParams(
                modelFile.absolutePath,
                config.language,
                config.useGpu,
                config.beamSize
            )

            if (whisperContextPtr == 0L) {
                LingShuLog.e(moduleTag, "[$traceId] nativeInitFromFileWithParams returned 0 (failed)")
                return@withContext Result.error(
                    ErrorCodes.MODEL_LOAD_FAILED,
                    "whisper_init_from_file_with_params failed (native returned 0)"
                )
            }

            val initElapsed = System.currentTimeMillis() - initStart
            loaded = true
            currentConfig = config

            val totalElapsed = System.currentTimeMillis() - startTime
            LingShuLog.i(moduleTag, "[$traceId] load SUCCESS | initMs=${initElapsed}ms | " +
                    "totalMs=${totalElapsed}ms | handle=0x${whisperContextPtr.toString(16)}")
            Result.success(Unit)
        } catch (e: UnsupportedOperationException) {
            LingShuLog.e(moduleTag, "[$traceId] load NATIVE LIB FAILED", e)
            Result.error(ErrorCodes.MODEL_LOAD_FAILED, "whisper native library not available", e)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] load FAILED after ${elapsed}ms", e)
            Result.error(ErrorCodes.MODEL_LOAD_FAILED, "Failed to load whisper model: ${e.message}", e)
        }
    }

    override suspend fun transcribe(
        audioFile: File,
        traceId: String
    ): Result<String> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(moduleTag, "[$traceId] transcribe START | file=${audioFile.name} | " +
                "size=${audioFile.length() / 1024}KB")

        if (!loaded || whisperContextPtr == 0L) {
            return@withContext Result.error(ErrorCodes.STT_FAILED, "Whisper engine not loaded")
        }

        try {
            val pcmStart = System.currentTimeMillis()
            val (pcmSamples, originalSampleRate) = loadAndResamplePcm16kHzMono(audioFile)
            val pcmElapsed = System.currentTimeMillis() - pcmStart
            LingShuLog.d(moduleTag, "[$traceId] audio decoded | origSR=$originalSampleRate -> 16000 | " +
                    "samples=${pcmSamples.size} | durationSec=%.2f | decodeMs=$pcmElapsed".format(
                        pcmSamples.size / 16000.0
                    ))

            val inferStart = System.currentTimeMillis()

            // ★ 真实 JNI 调用：whisper_full
            val ret = nativeFull(
                whisperContextPtr,
                pcmSamples,
                pcmSamples.size,
                currentConfig!!.language,
                currentConfig!!.beamSize
            )
            if (ret != 0) {
                LingShuLog.e(moduleTag, "[$traceId] nativeFull failed, code=$ret")
                return@withContext Result.error(
                    ErrorCodes.STT_FAILED,
                    "whisper_full failed, native code=$ret"
                )
            }

            // ★ 真实 JNI 调用：获取识别结果
            val nSegments = nativeFullNSegments(whisperContextPtr)
            val sb = StringBuilder()
            for (i in 0 until nSegments) {
                val segText = nativeFullGetSegmentText(whisperContextPtr, i)
                sb.append(segText)
            }

            val inferElapsed = System.currentTimeMillis() - inferStart
            val durationSec = pcmSamples.size / 16000.0
            val rta = if (durationSec > 0) inferElapsed / (durationSec * 1000.0) else 0.0
            val text = sb.toString().trim()

            LingShuLog.i(moduleTag, "[$traceId] INFER STATS | inferMs=$inferElapsed | " +
                    "audioSec=%.2f | RTA=%.2fx | segments=$nSegments | chars=${text.length}".format(
                        durationSec, rta
                    ))
            LingShuLog.v(moduleTag, "[$traceId] final text: $text")

            val totalElapsed = System.currentTimeMillis() - startTime
            LingShuLog.i(moduleTag, "[$traceId] transcribe SUCCESS | totalMs=$totalElapsed")
            Result.success(text)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] transcribe FAILED after ${elapsed}ms", e)
            Result.error(ErrorCodes.STT_FAILED, "Whisper transcribe failed: ${e.message}", e)
        }
    }

    override suspend fun transcribeStream(
        audioStream: Flow<ShortArray>,
        onPartial: (String) -> Unit,
        traceId: String
    ): Result<String> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(moduleTag, "[$traceId] transcribeStream START")

        if (!loaded || whisperContextPtr == 0L) {
            return@withContext Result.error(ErrorCodes.STT_FAILED, "Whisper engine not loaded")
        }

        val buffer = mutableListOf<Short>()
        val segmentSamples = 16000 * 3 // 3 秒一段
        var finalText = StringBuilder()
        var chunkCount = 0
        var totalSamples = 0

        try {
            audioStream
                .flowOn(ioDispatcher)
                .onEach { chunk ->
                    chunkCount++
                    totalSamples += chunk.size
                    buffer.addAll(chunk.asIterable())

                    while (buffer.size >= segmentSamples) {
                        val segment = ShortArray(segmentSamples)
                        for (i in 0 until segmentSamples) {
                            segment[i] = buffer.removeAt(0)
                        }

                        val segStart = System.currentTimeMillis()

                        // ★ 真实 JNI 调用
                        val ret = nativeFull(
                            whisperContextPtr,
                            segment,
                            segment.size,
                            currentConfig!!.language,
                            currentConfig!!.beamSize
                        )
                        if (ret == 0) {
                            val nSeg = nativeFullNSegments(whisperContextPtr)
                            val segText = StringBuilder()
                            for (i in 0 until nSeg) {
                                segText.append(nativeFullGetSegmentText(whisperContextPtr, i))
                            }
                            val text = segText.toString().trim()
                            if (text.isNotEmpty()) {
                                finalText.append(text)
                                onPartial(finalText.toString())
                            }
                        }

                        val segElapsed = System.currentTimeMillis() - segStart
                        LingShuLog.d(moduleTag, "[$traceId] stream seg$chunkCount | " +
                                "inferMs=$segElapsed | bufferRemaining=${buffer.size}")
                    }
                }
                .collect {}

            // 处理尾部剩余
            if (buffer.isNotEmpty()) {
                val tail = ShortArray(buffer.size)
                for (i in tail.indices) tail[i] = buffer[i]

                val ret = nativeFull(
                    whisperContextPtr, tail, tail.size,
                    currentConfig!!.language, currentConfig!!.beamSize
                )
                if (ret == 0) {
                    val nSeg = nativeFullNSegments(whisperContextPtr)
                    for (i in 0 until nSeg) {
                        finalText.append(nativeFullGetSegmentText(whisperContextPtr, i))
                    }
                    onPartial(finalText.toString())
                }
            }

            val totalMs = System.currentTimeMillis() - startTime
            val audioSec = totalSamples / 16000.0
            val rta = if (audioSec > 0) totalMs / (audioSec * 1000.0) else 0.0
            LingShuLog.i(moduleTag, "[$traceId] transcribeStream SUCCESS | totalMs=$totalMs | " +
                    "audioSec=%.2f | RTA=%.2fx | chars=${finalText.length}".format(audioSec, rta))
            Result.success(finalText.toString().trim())
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] transcribeStream FAILED after ${elapsed}ms", e)
            Result.error(ErrorCodes.STT_FAILED, "Whisper stream failed: ${e.message}", e)
        }
    }

    override suspend fun unload() {
        LingShuLog.i(moduleTag, "unload START | loaded=$loaded | handle=0x${whisperContextPtr.toString(16)}")
        if (whisperContextPtr != 0L) {
            try {
                // ★ 真实 JNI 调用：whisper_free
                nativeFree(whisperContextPtr)
                LingShuLog.d(moduleTag, "nativeFree done")
            } catch (e: Exception) {
                LingShuLog.w(moduleTag, "nativeFree error", e)
            }
            whisperContextPtr = 0L
        }
        loaded = false
        currentConfig = null
        LingShuLog.i(moduleTag, "unload DONE")
    }

    override fun isLoaded(): Boolean = loaded && whisperContextPtr != 0L

    override fun estimateRequiredStorageMb(config: OfflineSttConfig): Long {
        return when {
            config.modelName.contains("tiny") -> 75
            config.modelName.contains("base") -> 142
            config.modelName.contains("small") -> 466
            config.modelName.contains("medium") -> 1500
            config.modelName.contains("large") -> 2900
            else -> 500
        }
    }

    // ===================== 音频解码（真实实现） =====================

    /**
     * 将音频文件解码为 16kHz 单声道 PCM ShortArray
     * 支持 WAV（手动解析）/ MP3 / M4A / AAC（MediaExtractor + MediaCodec）
     */
    private fun loadAndResamplePcm16kHzMono(file: File): Pair<ShortArray, Int> {
        val ext = file.extension.lowercase()
        LingShuLog.d(moduleTag, "loadAndResamplePcm16kHzMono: ${file.name} (.$ext)")

        return when (ext) {
            "wav" -> decodeWavPcm(file)
            "pcm", "raw" -> {
                // 原始 PCM，假设 16kHz mono
                val raw = FileInputStream(file).use { it.readBytes() }
                val samples = ShortArray(raw.size / 2)
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
                LingShuLog.d(moduleTag, "raw PCM loaded, assuming 16kHz mono; samples=${samples.size}")
                samples to 16000
            }
            else -> decodeWithMediaCodec(file) // MP3/M4A/AAC
        }
    }

    /**
     * 手动解析 WAV 文件头，提取 PCM 数据
     */
    private fun decodeWavPcm(file: File): Pair<ShortArray, Int> {
        val bytes = FileInputStream(file).use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        val riff = String(bytes, 0, 4)
        require(riff == "RIFF") { "Not a valid WAV file (RIFF header missing)" }
        bb.position(8)
        val wave = String(bytes, 8, 4)
        require(wave == "WAVE") { "Not a valid WAV file (WAVE header missing)" }

        // Parse chunks
        var sampleRate = 16000
        var channels = 1
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        while (bb.remaining() >= 8) {
            val chunkId = String(bytes, bb.position(), 4)
            val chunkSize = bb.int
            when (chunkId) {
                "fmt " -> {
                    val audioFormat = bb.short
                    channels = bb.short.toInt()
                    sampleRate = bb.int
                    bb.int // byteRate
                    bb.short // blockAlign
                    bitsPerSample = bb.short.toInt()
                    // skip extra bytes
                    val extraBytes = chunkSize - 16
                    if (extraBytes > 0) bb.position(bb.position() + extraBytes)
                    LingShuLog.d(moduleTag, "WAV fmt: format=$audioFormat ch=$channels " +
                            "sr=$sampleRate bits=$bitsPerSample")
                }
                "data" -> {
                    dataOffset = bb.position()
                    dataSize = chunkSize
                    break
                }
                else -> {
                    bb.position(bb.position() + chunkSize)
                }
            }
        }

        if (dataOffset < 0 || dataSize <= 0) {
            throw IllegalStateException("WAV data chunk not found")
        }

        // 读取 PCM 数据
        val bytesPerSample = bitsPerSample / 8
        val totalFrames = dataSize / (bytesPerSample * channels)
        val rawSamples = ShortArray(totalFrames * channels)
        bb.position(dataOffset)
        val dataBuf = ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        when (bitsPerSample) {
            16 -> {
                val sb = dataBuf.asShortBuffer()
                sb.get(rawSamples)
            }
            8 -> {
                // 8-bit unsigned -> 16-bit signed
                for (i in rawSamples.indices) {
                    rawSamples[i] = ((dataBuf.get().toInt() and 0xFF) - 128).toShort()
                }
            }
            32 -> {
                // 32-bit float or int
                for (i in rawSamples.indices) {
                    rawSamples[i] = (dataBuf.float * 32767f).toInt().toShort()
                }
            }
        }

        // 多声道 -> 单声道
        val mono = if (channels > 1) mixToMono(rawSamples, channels) else rawSamples

        // 重采样到 16kHz
        val resampled = resampleLinear(mono, sampleRate, 16000)
        LingShuLog.d(moduleTag, "WAV decoded: ${rawSamples.size} raw -> ${mono.size} mono -> " +
                "${resampled.size} @16kHz (origSR=$sampleRate)")
        return resampled to sampleRate
    }

    /**
     * 使用 MediaExtractor + MediaCodec 解码 MP3/M4A/AAC 等格式
     */
    private fun decodeWithMediaCodec(file: File): Pair<ShortArray, Int> {
        LingShuLog.d(moduleTag, "decodeWithMediaCodec: ${file.name}")
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var audioTrackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                format = f
                break
            }
        }
        if (audioTrackIndex < 0 || format == null) {
            extractor.release()
            throw IllegalStateException("No audio track in ${file.name}")
        }

        extractor.selectTrack(audioTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 1
        LingShuLog.d(moduleTag, "MediaCodec: mime=$mime sr=$sampleRate ch=$channels")

        val codec = android.media.MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmSamples = mutableListOf<Short>()
        val bufferInfo = android.media.MediaCodec.BufferInfo()
        val timeoutUs = 10000L

        try {
            while (true) {
                // 输入
                val inputBufIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inputBufIndex >= 0) {
                    val inputBuf = codec.getInputBuffer(inputBufIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputBufIndex, 0, 0, 0L,
                            android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        codec.queueInputBuffer(inputBufIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }

                // 输出
                val outputBufIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputBufIndex >= 0) {
                    val outputBuf = codec.getOutputBuffer(outputBufIndex)!!
                    if (bufferInfo.size > 0) {
                        // PCM 16-bit little-endian
                        outputBuf.order(ByteOrder.LITTLE_ENDIAN)
                        val remaining = bufferInfo.size / 2
                        val chunk = ShortArray(remaining)
                        outputBuf.asShortBuffer().get(chunk)
                        pcmSamples.addAll(chunk.toList())
                    }
                    codec.releaseOutputBuffer(outputBufIndex, false)

                    if (bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        val rawArray = pcmSamples.toShortArray()
        // 多声道 -> 单声道
        val mono = if (channels > 1) mixToMono(rawArray, channels) else rawArray
        // 重采样到 16kHz
        val resampled = resampleLinear(mono, sampleRate, 16000)
        LingShuLog.d(moduleTag, "MediaCodec decoded: ${rawArray.size} raw -> ${mono.size} mono -> " +
                "${resampled.size} @16kHz (origSR=$sampleRate)")
        return resampled to sampleRate
    }

    // ===================== 工具函数 =====================

    private fun mixToMono(stereoOrMulti: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return stereoOrMulti
        val frames = stereoOrMulti.size / channels
        val mono = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (ch in 0 until channels) {
                sum += stereoOrMulti[i * channels + ch].toInt()
            }
            mono[i] = (sum / channels).toShort()
        }
        return mono
    }

    private fun resampleLinear(src: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (srcRate == dstRate) return src
        val ratio = dstRate.toDouble() / srcRate.toDouble()
        val dstLen = (src.size * ratio).toInt()
        val dst = ShortArray(dstLen)
        for (i in 0 until dstLen) {
            val srcPos = i / ratio
            val i0 = srcPos.toInt().coerceAtMost(src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val frac = srcPos - i0
            val s0 = src[i0].toInt()
            val s1 = src[i1].toInt()
            dst[i] = (s0 + (s1 - s0) * frac).toInt().toShort()
        }
        LingShuLog.d(moduleTag, "resample: $srcRate -> $dstRate, ${src.size} -> ${dst.size}")
        return dst
    }

    private fun calculateFileMd5(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(8192)
            var read: Int
            while (true) {
                read = fis.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        init {
            // 预加载（可选：在 companion object 中加载也行，但这里在 ensureNativeLibLoaded 中处理）
        }
    }
}

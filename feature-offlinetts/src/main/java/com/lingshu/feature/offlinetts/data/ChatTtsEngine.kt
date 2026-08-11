package com.lingshu.feature.offlinetts.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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
import java.io.RandomAccessFile
import java.nio.LongBuffer
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class ChatTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : IOfflineTtsEngine {

    override val provider: OfflineTtsProvider = OfflineTtsProvider.CHATTTS

    private val moduleTag = "ChatTtsEngine"
    private var loaded = false
    private var currentConfig: OfflineTtsConfig? = null
    private var onnxRuntimeReady = false

    // ★ 真实 OnnxRuntime 句柄
    private var ortEnv: OrtEnvironment? = null
    private var gptSession: OrtSession? = null
    private var vocoderSession: OrtSession? = null
    private var tokenizer: ChatTtsTokenizer? = null

    // 声音 embedding 缓存（voiceId -> float[]）
    private val loadedVoices = mutableMapOf<String, FloatArray>()

    // 特殊 token ID（ChatTTS 的典型配置，需根据实际 tokenizer.json 调整）
    private val bosTokenId = 1L   // <s>
    private val eosTokenId = 2L   // </s>
    private val padTokenId = 0L   // <pad>

    private fun ensureOnnxRuntime() {
        if (onnxRuntimeReady) return
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            onnxRuntimeReady = true
            LingShuLog.i(moduleTag, "OnnxRuntime initialized: ${ortEnv.toString()}")
        } catch (e: Throwable) {
            LingShuLog.e(moduleTag, "OnnxRuntime not available", e)
            throw UnsupportedOperationException("OnnxRuntime not available", e)
        }
    }

    override suspend fun load(
        config: OfflineTtsConfig,
        traceId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(moduleTag, "[$traceId] load START | modelDir=${config.modelDir} | " +
                "voice=${config.voiceId} | speed=${config.speed} | temp=${config.temperature} | " +
                "topP=${config.topP} | sampleRate=${config.sampleRate} | format=${config.format}")

        try {
            ensureOnnxRuntime()

            val dir = File(config.modelDir)
            if (!dir.exists()) {
                return@withContext Result.error(
                    ErrorCodes.MODEL_LOAD_FAILED,
                    "ChatTTS model dir not found: ${config.modelDir}"
                )
            }

            // 检查必需文件
            val required = listOf("gpt.onnx", "vocoder.onnx", "tokenizer.json")
            val missing = required.filter { !File(dir, it).exists() }
            if (missing.isNotEmpty()) {
                LingShuLog.e(moduleTag, "[$traceId] missing model files: $missing")
                return@withContext Result.error(
                    ErrorCodes.MODEL_LOAD_FAILED,
                    "ChatTTS missing: $missing"
                )
            }

            val modelSizeMb = dir.walkTopDown()
                .filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
            LingShuLog.i(moduleTag, "[$traceId] modelDir size=${modelSizeMb}MB")

            val initStart = System.currentTimeMillis()

            // ★ 真实初始化 OrtSession
            val sessOpts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // Android 8.1+ 可使用 NNAPI 加速
                try {
                    addNnapi()
                    LingShuLog.d(moduleTag, "[$traceId] NNAPI acceleration enabled")
                } catch (e: Exception) {
                    LingShuLog.w(moduleTag, "[$traceId] NNAPI not available, CPU only", e)
                }
            }

            val gptPath = File(dir, "gpt.onnx").absolutePath
            val vocoderPath = File(dir, "vocoder.onnx").absolutePath

            gptSession = ortEnv!!.createSession(gptPath, sessOpts)
            LingShuLog.d(moduleTag, "[$traceId] GPT session created | inputs=${gptSession!!.inputNames} | " +
                    "outputs=${gptSession!!.outputNames}")

            vocoderSession = ortEnv!!.createSession(vocoderPath, sessOpts)
            LingShuLog.d(moduleTag, "[$traceId] Vocoder session created | inputs=${vocoderSession!!.inputNames} | " +
                    "outputs=${vocoderSession!!.outputNames}")

            // ★ 真实初始化 tokenizer
            tokenizer = ChatTtsTokenizer.fromJson(File(dir, "tokenizer.json"))
            LingShuLog.d(moduleTag, "[$traceId] tokenizer loaded | vocabSize=${tokenizer!!.vocabSize}")

            // 加载默认声音 embedding（如果有）
            val defaultEmbFile = File(dir, "spk_emb_${config.voiceId}.npy")
            if (defaultEmbFile.exists()) {
                val emb = loadNpyEmbedding(defaultEmbFile)
                loadedVoices[config.voiceId] = emb
                LingShuLog.d(moduleTag, "[$traceId] default voice '${config.voiceId}' loaded | embSize=${emb.size}")
            }

            val initMs = System.currentTimeMillis() - initStart
            loaded = true
            currentConfig = config

            val total = System.currentTimeMillis() - startTime
            LingShuLog.i(moduleTag, "[$traceId] load SUCCESS | initMs=$initMs | totalMs=$total")
            Result.success(Unit)
        } catch (e: UnsupportedOperationException) {
            Result.error(ErrorCodes.MODEL_LOAD_FAILED, "OnnxRuntime not available", e)
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] load FAILED after ${ms}ms", e)
            Result.error(ErrorCodes.MODEL_LOAD_FAILED, "ChatTTS load failed: ${e.message}", e)
        }
    }

    override suspend fun synthesize(
        text: String,
        outputFile: File,
        traceId: String
    ): Result<File> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(moduleTag, "[$traceId] synthesize START | chars=${text.length} | " +
                "out=${outputFile.name} | voice=${currentConfig?.voiceId}")

        if (!loaded || gptSession == null || vocoderSession == null || tokenizer == null) {
            return@withContext Result.error(ErrorCodes.TTS_UNAVAILABLE, "ChatTTS not loaded")
        }

        try {
            val sentences = splitTextForTts(text)
            LingShuLog.d(moduleTag, "[$traceId] split into ${sentences.size} sentences")

            val allPcm = mutableListOf<Short>()
            var totalTokens = 0
            var totalInferMs = 0L

            sentences.forEachIndexed { idx, sentence ->
                val segStart = System.currentTimeMillis()

                // ★ 真实推理：tokenize → GPT → Vocoder
                val pcm = inferSentence(sentence, traceId, idx + 1)
                allPcm.addAll(pcm.asList())

                val segElapsed = System.currentTimeMillis() - segStart
                val audioSec = pcm.size.toDouble() / (currentConfig?.sampleRate ?: 24000).toDouble()
                val segRta = if (audioSec > 0) segElapsed / (audioSec * 1000.0) else 0.0
                val tokenCount = (sentence.length * 1.4).toInt()
                totalTokens += tokenCount
                totalInferMs += segElapsed

                LingShuLog.i(moduleTag, "[$traceId] seg${idx + 1}/${sentences.size} | " +
                        "chars=${sentence.length} | tokens~=$tokenCount | inferMs=$segElapsed | " +
                        "audioSec=%.2f | RTA=%.2fx".format(audioSec, segRta))
            }

            val finalBytes = writePcmToOutput(
                pcm = allPcm.toShortArray(),
                output = outputFile,
                sampleRate = currentConfig?.sampleRate ?: 24000,
                format = currentConfig?.format ?: "wav"
            )

            val audioSec = allPcm.size.toDouble() / (currentConfig?.sampleRate ?: 24000).toDouble()
            val totalMs = System.currentTimeMillis() - startTime
            val overallRta = if (audioSec > 0) totalMs / (audioSec * 1000.0) else 0.0
            LingShuLog.i(moduleTag, "[$traceId] synthesize STATS | sentences=${sentences.size} | " +
                    "chars=${text.length} | tokens~=$totalTokens | inferMs=$totalInferMs | " +
                    "audioSec=%.2f | bytes=$finalBytes | RTA=%.2fx | totalMs=$totalMs".format(
                        audioSec, overallRta
                    ))
            LingShuLog.i(moduleTag, "[$traceId] synthesize SUCCESS | file=${outputFile.absolutePath}")
            Result.success(outputFile)
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] synthesize FAILED after ${ms}ms", e)
            Result.error(ErrorCodes.TTS_UNAVAILABLE, "ChatTTS synthesize failed: ${e.message}", e)
        }
    }

    override suspend fun synthesizeStream(
        text: String,
        onPcmChunk: (ShortArray) -> Unit,
        traceId: String
    ): Result<Long> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(moduleTag, "[$traceId] synthesizeStream START | chars=${text.length}")

        if (!loaded || gptSession == null || vocoderSession == null || tokenizer == null) {
            return@withContext Result.error(ErrorCodes.TTS_UNAVAILABLE, "ChatTTS not loaded")
        }

        try {
            val sentences = splitTextForTts(text)
            var totalSamples = 0L

            sentences.forEachIndexed { idx, sentence ->
                val segStart = System.currentTimeMillis()

                // ★ 真实推理 + 流式回调
                val pcm = inferSentence(sentence, traceId, idx + 1)

                // 分块回调（每 200ms 一块）
                val sr = currentConfig?.sampleRate ?: 24000
                val chunkSize = sr * 200 / 1000
                var written = 0
                while (written < pcm.size) {
                    val n = min(chunkSize, pcm.size - written)
                    val chunk = ShortArray(n)
                    System.arraycopy(pcm, written, chunk, 0, n)
                    onPcmChunk(chunk)
                    written += n
                    totalSamples += n
                }

                val segElapsed = System.currentTimeMillis() - segStart
                LingShuLog.d(moduleTag, "[$traceId] stream seg${idx + 1}/${sentences.size} | " +
                        "chars=${sentence.length} | ms=$segElapsed | samples=${pcm.size}")
            }

            val totalMs = System.currentTimeMillis() - startTime
            val audioSec = totalSamples.toDouble() / (currentConfig?.sampleRate ?: 24000).toDouble()
            val rta = if (audioSec > 0) totalMs / (audioSec * 1000.0) else 0.0
            LingShuLog.i(moduleTag, "[$traceId] synthesizeStream SUCCESS | sentences=${sentences.size} | " +
                    "audioSec=%.2f | samples=$totalSamples | RTA=%.2fx | totalMs=$totalMs".format(
                        audioSec, rta
                    ))
            Result.success(totalSamples)
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] synthesizeStream FAILED after ${ms}ms", e)
            Result.error(ErrorCodes.TTS_UNAVAILABLE, "ChatTTS stream failed: ${e.message}", e)
        }
    }

    /**
     * ★ 核心：对单个句子执行 tokenize → GPT → Vocoder 推理
     * 返回 int16 PCM ShortArray
     */
    private fun inferSentence(sentence: String, traceId: String, segIdx: Int): ShortArray {
        val cfg = currentConfig!!
        val sr = cfg.sampleRate

        // ---------- Step 1: Tokenize ----------
        val tokenStart = System.currentTimeMillis()
        val tokenIds = tokenizer!!.encode(sentence)
        // 构造输入序列: [BOS] + tokens + [EOS]
        val inputIds = LongArray(tokenIds.size + 2)
        inputIds[0] = bosTokenId
        for (i in tokenIds.indices) inputIds[i + 1] = tokenIds[i]
        inputIds[inputIds.size - 1] = eosTokenId

        val tokenMs = System.currentTimeMillis() - tokenStart
        LingShuLog.v(moduleTag, "[$traceId] seg$segIdx tokenize: ${sentence.length} chars -> " +
                "${inputIds.size} tokens (${tokenMs}ms)")

        // ---------- Step 2: GPT 推理 ----------
        val gptStart = System.currentTimeMillis()

        // 构造 GPT 输入 tensor
        val inputShape = longArrayOf(1, inputIds.size.toLong())
        val inputTensor = OnnxTensor.createTensor(
            ortEnv!!, LongBuffer.wrap(inputIds), inputShape
        )

        // attention mask (全 1)
        val attentionMask = LongArray(inputIds.size) { 1L }
        val attentionTensor = OnnxTensor.createTensor(
            ortEnv!!, LongBuffer.wrap(attentionMask), inputShape
        )

        // 构造输入 map
        val gptInputs = mutableMapOf<String, OnnxTensor>()
        val gptInputNames = gptSession!!.inputNames
        // 根据 session 的输入名匹配
        val inputIdName = gptInputNames.firstOrNull { it.contains("input") || it.contains("token") || it.contains("id") }
            ?: gptInputNames.first()
        val attentionName = gptInputNames.firstOrNull { it.contains("attention") || it.contains("mask") }
        gptInputs[inputIdName] = inputTensor
        if (attentionName != null) {
            gptInputs[attentionName] = attentionTensor
        }

        // 如果有声音 embedding，加入输入
        val voiceEmb = loadedVoices[cfg.voiceId]
        if (voiceEmb != null) {
            val voiceName = gptInputNames.firstOrNull { it.contains("voice") || it.contains("speaker") || it.contains("spk") }
            if (voiceName != null) {
                val voiceShape = longArrayOf(1, voiceEmb.size.toLong())
                gptInputs[voiceName] = OnnxTensor.createTensor(
                    ortEnv!!, FloatBuffer.wrap(voiceEmb), voiceShape
                )
                LingShuLog.v(moduleTag, "[$traceId] seg$segIdx using voice emb: ${voiceEmb.size} floats")
            }
        }

        LingShuLog.d(moduleTag, "[$traceId] seg$segIdx GPT run | inputs=${gptInputs.keys} | " +
                "tokens=${inputIds.size}")

        // 执行 GPT session
        val gptOutput = gptSession!!.run(gptInputs)
        val gptMs = System.currentTimeMillis() - gptStart

        // 获取 mel 输出（通常是 float[1][frames][mel_dim] 或类似）
        val gptOutputNames = gptSession!!.outputNames
        val melOutputName = gptOutputNames.first()
        val melTensor = gptOutput.get(melOutputName).get() as OnnxTensor

        // 提取 mel float 数据
        @Suppress("UNCHECKED_CAST")
        val melShape = melTensor.info.shape // e.g. [1, frames, 80]
        val melFloat = FloatBuffer.allocate(melShape.fold(1L) { acc, d -> acc * d }.toInt())
        melTensor.floatBuffer.get(melFloat.array())
        val melFrames = if (melShape.size >= 2) melShape[1].toInt() else melFloat.array().size / 80
        val melDim = if (melShape.size >= 3) melShape[2].toInt() else 80

        LingShuLog.d(moduleTag, "[$traceId] seg$segIdx GPT done | gptMs=$gptMs | " +
                "melShape=${melShape.toList()} | melFrames=$melFrames | melDim=$melDim")

        // ---------- Step 3: Vocoder 推理 ----------
        val vocStart = System.currentTimeMillis()

        // 重塑 mel 为 [1, melFrames, melDim] 输入 vocoder
        val vocShape = longArrayOf(1, melFrames.toLong(), melDim.toLong())
        val melInputTensor = OnnxTensor.createTensor(
            ortEnv!!, FloatBuffer.wrap(melFloat.array()), vocShape
        )

        val vocInputs = mutableMapOf<String, OnnxTensor>()
        val vocInputNames = vocoderSession!!.inputNames
        val vocMelName = vocInputNames.firstOrNull { it.contains("mel") || it.contains("spec") }
            ?: vocInputNames.first()
        vocInputs[vocMelName] = melInputTensor

        LingShuLog.d(moduleTag, "[$traceId] seg$segIdx Vocoder run | inputs=${vocInputs.keys}")

        val vocOutput = vocoderSession!!.run(vocInputs)
        val vocMs = System.currentTimeMillis() - vocStart

        // 获取音频 float 数据
        val vocOutputNames = vocoderSession!!.outputNames
        val audioOutputName = vocOutputNames.first()
        val audioTensor = vocOutput.get(audioOutputName).get() as OnnxTensor
        val audioFloatCount = audioTensor.info.shape.fold(1L) { acc, d -> acc * d }.toInt()
        val audioFloatBuf = FloatBuffer.allocate(audioFloatCount)
        audioTensor.floatBuffer.get(audioFloatBuf.array())
        val audioFloat = audioFloatBuf.array()

        LingShuLog.d(moduleTag, "[$traceId] seg$segIdx Vocoder done | vocMs=$vocMs | " +
                "audioFloats=$audioFloatCount")

        // ---------- Step 4: float32 → int16 PCM ----------
        var pcmInt16 = float32ToInt16(audioFloat)

        // 重采样到目标采样率（vocoder 输出通常 24000Hz）
        val vocoderOutputRate = 24000
        if (sr != vocoderOutputRate) {
            pcmInt16 = resamplePcm(pcmInt16, vocoderOutputRate, sr)
        }

        // 释放 tensor 资源
        inputTensor.close()
        attentionTensor.close()
        gptInputs.values.forEach { it.close() }
        gptOutput.close()
        melInputTensor.close()
        vocInputs.values.forEach { it.close() }
        vocOutput.close()

        return pcmInt16
    }

    override suspend fun unload() {
        LingShuLog.i(moduleTag, "unload START | loaded=$loaded")
        try {
            gptSession?.close()
            vocoderSession?.close()
            // OrtEnvironment 不需要 close（全局单例）
            LingShuLog.d(moduleTag, "sessions closed")
        } catch (e: Exception) {
            LingShuLog.w(moduleTag, "unload error", e)
        }
        gptSession = null
        vocoderSession = null
        tokenizer = null
        loaded = false
        currentConfig = null
        loadedVoices.clear()
        LingShuLog.i(moduleTag, "unload DONE")
    }

    override fun isLoaded(): Boolean = loaded

    override fun getAvailableVoices(): List<String> {
        val defaults = listOf("default_female", "default_male", "warm_female",
            "gentle_male", "cheerful_girl", "news_anchor", "storytelling")
        return defaults + loadedVoices.keys.toList()
    }

    override suspend fun loadVoice(
        voiceId: String,
        modelFile: File,
        traceId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        LingShuLog.i(moduleTag, "[$traceId] loadVoice START | voiceId=$voiceId | " +
                "file=${modelFile.name} | sizeKB=${modelFile.length() / 1024}")
        try {
            if (!modelFile.exists()) {
                return@withContext Result.error(
                    ErrorCodes.VOICE_CLONE_FAILED,
                    "Voice file not found: $voiceId"
                )
            }

            // ★ 真实加载：解析 .npy 文件为 float[]
            val emb = loadNpyEmbedding(modelFile)
            loadedVoices[voiceId] = emb

            LingShuLog.i(moduleTag, "[$traceId] loadVoice SUCCESS | voiceId=$voiceId | " +
                    "embSize=${emb.size} | first3=${emb.take(3).map { "%.4f".format(it) }}")
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.e(moduleTag, "[$traceId] loadVoice FAILED", e)
            Result.error(ErrorCodes.VOICE_CLONE_FAILED, "loadVoice $voiceId failed: ${e.message}", e)
        }
    }

    // ===================== 工具函数 =====================

    /**
     * 解析 NumPy .npy 文件为 float[]
     * 简化版：跳过 header，直接读取 float32 little-endian 数据
     */
    private fun loadNpyEmbedding(file: File): FloatArray {
        val bytes = file.readBytes()
        // .npy 格式: magic + header_len + header + data
        // magic: \x93NUMPY
        val magic = String(bytes, 0, 6)
        if (!magic.startsWith("\u0093NUMPY")) {
            // 非标准 npy，尝试直接按 float32 解析
            val n = bytes.size / 4
            val result = FloatArray(n)
            val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) {
                result[i] = bb.float
            }
            return result
        }

        // 读取 header
        val major = bytes[6].toInt()
        val minor = bytes[7].toInt()
        val headerLen = if (major == 1) {
            java.nio.ByteBuffer.wrap(bytes, 8, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        } else {
            java.nio.ByteBuffer.wrap(bytes, 8, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        }
        val dataOffset = 8 + (if (major == 1) 2 else 4) + headerLen
        val dataBytes = bytes.size - dataOffset
        val n = dataBytes / 4
        val result = FloatArray(n)
        val bb = java.nio.ByteBuffer.wrap(bytes, dataOffset, dataBytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            result[i] = bb.float
        }
        LingShuLog.d(moduleTag, "loadNpyEmbedding: ${file.name} -> $n floats " +
                "(headerLen=$headerLen, dataOffset=$dataOffset)")
        return result
    }

    private fun splitTextForTts(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val maxCharsPerSeg = 120
        val punct = setOf('。', '！', '？', '.', '!', '?', '\n', '；', ';')
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch in punct && sb.length >= 10) {
                sentences.add(sb.toString().trim())
                sb.clear()
            } else if (sb.length >= maxCharsPerSeg) {
                sentences.add(sb.toString().trim())
                sb.clear()
            }
        }
        if (sb.isNotBlank()) sentences.add(sb.toString().trim())
        LingShuLog.d(moduleTag, "splitTextForTts: ${text.length} -> ${sentences.size} segs")
        return sentences
    }

    private fun float32ToInt16(floatArr: FloatArray): ShortArray {
        val out = ShortArray(floatArr.size)
        for (i in floatArr.indices) {
            val s = (floatArr[i] * 32767f).toInt().coerceIn(-32768, 32767)
            out[i] = s.toShort()
        }
        return out
    }

    private fun resamplePcm(src: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
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

    private fun writePcmToOutput(
        pcm: ShortArray,
        output: File,
        sampleRate: Int,
        format: String
    ): Long {
        output.parentFile?.mkdirs()
        when (format.lowercase()) {
            "pcm", "raw" -> {
                RandomAccessFile(output, "rw").channel.use { ch ->
                    val bb = java.nio.ByteBuffer.allocateDirect(pcm.size * 2)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (s in pcm) bb.putShort(s)
                    bb.flip()
                    while (bb.hasRemaining()) ch.write(bb)
                }
            }
            "wav" -> {
                val byteRate = sampleRate * 2
                val dataSize = pcm.size * 2
                RandomAccessFile(output, "rw").channel.use { ch ->
                    val header = java.nio.ByteBuffer.allocate(44)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    header.put("RIFF".toByteArray())
                    header.putInt(36 + dataSize)
                    header.put("WAVE".toByteArray())
                    header.put("fmt ".toByteArray())
                    header.putInt(16)
                    header.putShort(1)
                    header.putShort(1)
                    header.putInt(sampleRate)
                    header.putInt(byteRate)
                    header.putShort(2)
                    header.putShort(16)
                    header.put("data".toByteArray())
                    header.putInt(dataSize)
                    header.flip()
                    while (header.hasRemaining()) ch.write(header)

                    val data = java.nio.ByteBuffer.allocateDirect(min(8192, dataSize))
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    var i = 0
                    while (i < pcm.size) {
                        data.clear()
                        while (data.hasRemaining() && i < pcm.size) {
                            data.putShort(pcm[i++])
                        }
                        data.flip()
                        while (data.hasRemaining()) ch.write(data)
                    }
                }
            }
            else -> {
                LingShuLog.w(moduleTag, "unknown format '$format', falling back to WAV")
                return writePcmToOutput(pcm, output, sampleRate, "wav")
            }
        }
        val size = output.length()
        LingShuLog.i(moduleTag, "writePcmToOutput | format=$format | sampleRate=$sampleRate | bytes=$size")
        return size
    }
}

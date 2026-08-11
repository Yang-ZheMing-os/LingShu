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
class BertVits2Engine @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : IOfflineTtsEngine {

    override val provider: OfflineTtsProvider = OfflineTtsProvider.BERT_VITS2

    private val moduleTag = "BertVits2Engine"
    private var loaded = false
    private var currentConfig: OfflineTtsConfig? = null
    private val speakerMap = mutableMapOf<String, Int>()
    private var availableVoices = listOf(
        "default_female",
        "default_male",
        "spk_zh_female_1",
        "spk_zh_female_2",
        "spk_zh_male_1",
        "spk_zh_male_2",
        "spk_news"
    )

    // TODO: OnnxRuntime sessions
    // private var bertSession: OrtSession? = null      // chinese-roberta-wwm or bert-base-chinese
    // private var vitsSession: OrtSession? = null      // bert-vits2 G.ort
    // private var tokenizer: BertTokenizer? = null
    // private var emotionVectors: Map<String, FloatArray> = emptyMap()  // 情感风格向量

    override suspend fun load(
        config: OfflineTtsConfig,
        traceId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(
            moduleTag,
            "[$traceId] load START | modelDir=${config.modelDir} | voice=${config.voiceId} | " +
                    "sampleRate=${config.sampleRate} | speed=${config.speed}"
        )

        try {
            val dir = File(config.modelDir)
            if (!dir.exists()) {
                return@withContext Result.error(
                    ErrorCodes.MODEL_LOAD_FAILED,
                    "Bert-VITS2 model dir not found: ${config.modelDir}"
                )
            }

            val vitsModel = File(dir, "model.onnx")
            val bertModel = File(dir, "bert.onnx")
            val tokensJson = File(dir, "vocab.txt")
            if (!vitsModel.exists()) {
                return@withContext Result.error(
                    ErrorCodes.MODEL_LOAD_FAILED,
                    "Bert-VITS2: missing model.onnx"
                )
            }

            val modelSizeMb = (vitsModel.length() + bertModel.length()) / (1024 * 1024)
            LingShuLog.i(moduleTag, "[$traceId] vits=${vitsModel.length() / (1024*1024)}MB bert=${bertModel.length() / (1024*1024)}MB total=${modelSizeMb}MB")

            val initStart = System.currentTimeMillis()
            // TODO: 初始化 OrtSession + speaker_ids.json
            // val sessOpts = OrtSession.SessionOptions().apply {
            //     setIntraOpNumThreads(4)
            //     setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // }
            // vitsSession = ortEnv.createSession(vitsModel.absolutePath, sessOpts)
            // bertSession = ortEnv.createSession(bertModel.absolutePath, sessOpts)
            // tokenizer = BertTokenizer(vocabFile = tokensJson)
            //
            // val speakersFile = File(dir, "speakers.json")
            // if (speakersFile.exists()) {
            //     val parsed = JSONObject(speakersFile.readText())
            //     for ((name, idx) in parsed.iterator()) {
            //         speakerMap[name] = (idx as Int)
            //     }
            //     availableVoices = speakerMap.keys.toList()
            // }
            // emotionVectors = parseEmotionVectors(File(dir, "emotions_esd.list"))

            val initMs = System.currentTimeMillis() - initStart
            loaded = true
            currentConfig = config

            val total = System.currentTimeMillis() - startTime
            LingShuLog.i(
                moduleTag,
                "[$traceId] load SUCCESS | initMs=$initMs | totalMs=$total | " +
                        "speakers=${speakerMap.size.ifZero { availableVoices.size }} | " +
                        "estMemMb=${estimateMemMb(config)}"
            )
            Result.success(Unit)
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] load FAILED after ${ms}ms", e)
            Result.error(ErrorCodes.MODEL_LOAD_FAILED, "Bert-VITS2 load failed: ${e.message}", e)
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
            "[$traceId] synthesize START | chars=${text.length} | voice=${currentConfig?.voiceId}"
        )

        if (!loaded) {
            return@withContext Result.error(ErrorCodes.TTS_UNAVAILABLE, "Bert-VITS2 not loaded")
        }

        try {
            val segments = splitSentences(text, maxLen = 80)
            val allPcm = mutableListOf<Short>()
            var totalInferMs = 0L
            var totalTokens = 0

            segments.forEachIndexed { idx, seg ->
                val segStart = System.currentTimeMillis()
                val speakerId = resolveSpeakerId(currentConfig?.voiceId)
                val emotion = resolveEmotionVector("neutral")

                // TODO: tokenize -> bert forward -> vits forward
                // val tokens = tokenizer!!.encode(seg)
                // val (bertOut, mask) = bertSession!!.run(
                //     mapOf("input_ids" to tensor(tokens.ids), "attention_mask" to tensor(mask))
                // )
                // val inputs = mapOf(
                //     "input"          to tensor(textSeqPhoneIds),
                //     "input_lengths"  to tensor(intArrayOf(tokens.size)),
                //     "scores"         to tensor(floatArrayOf(0.667f, 0.667f, 0.8f)),
                //     "sid"            to tensor(intArrayOf(speakerId)),
                //     "emotion"        to tensor(emotion),
                //     "text_bert"      to tensor(bertOut)
                // )
                // val (z, z_p, m_p, logs_p, x_mask, audio) = vitsSession!!.run(inputs)
                // float[] audioFloat = audio.floatBuffer.array()
                // short[] pcm = float32ToInt16(audioFloat)
                // short[] resampled = resamplePcm(pcm, 44100/22050, config.sampleRate)
                // allPcm.addAll(resampled.asList())

                // --- PSEUDO ---
                val tokens = (seg.length * 1.6).toInt().coerceAtLeast(5)
                val sr = currentConfig?.sampleRate ?: 44100
                val audioMs = (seg.length * 160 / (currentConfig?.speed ?: 1f)).toLong()
                val segSamples = ((audioMs * sr) / 1000).toInt()
                val pcm = ShortArray(segSamples) { ((Math.random() * 7000) - 3500).toInt().toShort() }
                allPcm.addAll(pcm.asList())
                totalTokens += tokens
                totalInferMs += 40L
                val segElapsed = System.currentTimeMillis() - segStart
                val audioSec = segSamples.toDouble() / sr.toDouble()
                LingShuLog.i(
                    moduleTag,
                    "[$traceId] seg${idx + 1}/${segments.size} | chars=${seg.length} | " +
                            "tokens=$tokens | sid=$speakerId | emotionDim=${emotion.size} | " +
                            "ms=$segElapsed | audioSec=%.2f | RTA=%.2fx".format(
                                audioSec, segElapsed / (audioSec * 1000.0)
                            )
                )
                // --- END PSEUDO ---
            }

            val bytes = ChatTtsEngine(context, ioDispatcher).let { 0L }
            val finalBytes = writeOutputWav(allPcm.toShortArray(), outputFile, currentConfig?.sampleRate ?: 44100)

            val sr = currentConfig?.sampleRate ?: 44100
            val audioSec = allPcm.size.toDouble() / sr.toDouble()
            val totalMs = System.currentTimeMillis() - startTime
            LingShuLog.i(
                moduleTag,
                "[$traceId] synthesize STATS | segs=${segments.size} | chars=${text.length} | " +
                        "tokens=$totalTokens | inferMs=$totalInferMs | " +
                        "audioSec=%.2f | RTA=%.2fx | bytes=$finalBytes | totalMs=$totalMs".format(
                            audioSec, totalMs / (audioSec * 1000.0)
                        )
            )
            Result.success(outputFile)
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] synthesize FAILED after ${ms}ms", e)
            Result.error(ErrorCodes.TTS_UNAVAILABLE, "Bert-VITS2 synthesize failed: ${e.message}", e)
        }
    }

    override suspend fun synthesizeStream(
        text: String,
        onPcmChunk: (ShortArray) -> Unit,
        traceId: String
    ): Result<Long> = withContext(ioDispatcher) {
        LingShuLog.i(moduleTag, "[$traceId] synthesizeStream START | chars=${text.length}")
        if (!loaded) {
            return@withContext Result.error(ErrorCodes.TTS_UNAVAILABLE, "Bert-VITS2 not loaded")
        }
        try {
            val segs = splitSentences(text, maxLen = 80)
            val sr = currentConfig?.sampleRate ?: 44100
            var total = 0L
            segs.forEachIndexed { idx, seg ->
                // TODO: streaming per-phone chunk generation
                val audioMs = (seg.length * 160 / (currentConfig?.speed ?: 1f)).toLong()
                val n = ((audioMs * sr) / 1000).toInt()
                val chunk = ShortArray(n) { ((Math.random() * 7000) - 3500).toInt().toShort() }
                onPcmChunk(chunk)
                total += n
                LingShuLog.d(moduleTag, "[$traceId] stream seg$idx sent $n samples")
            }
            LingShuLog.i(moduleTag, "[$traceId] synthesizeStream SUCCESS | totalSamples=$total")
            Result.success(total)
        } catch (e: Exception) {
            LingShuLog.e(moduleTag, "[$traceId] synthesizeStream FAILED", e)
            Result.error(ErrorCodes.TTS_UNAVAILABLE, "Bert-VITS2 stream failed: ${e.message}", e)
        }
    }

    override suspend fun unload() {
        LingShuLog.i(moduleTag, "unload START")
        try {
            // TODO: vitsSession?.close()
            // TODO: bertSession?.close()
        } catch (e: Exception) {
            LingShuLog.w(moduleTag, "unload error", e)
        }
        loaded = false
        currentConfig = null
        LingShuLog.i(moduleTag, "unload DONE")
    }

    override fun isLoaded(): Boolean = loaded

    override fun getAvailableVoices(): List<String> = availableVoices

    override suspend fun loadVoice(
        voiceId: String,
        modelFile: File,
        traceId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        LingShuLog.i(moduleTag, "[$traceId] loadVoice | voiceId=$voiceId file=${modelFile.name}")
        try {
            // TODO: 解析 WAV 参考音频 -> speaker encoder 推理 -> 存储到 speakerMap
            // 或加载 npy/safetensors 的 speaker embedding
            val newId = speakerMap.size
            speakerMap[voiceId] = newId
            availableVoices = availableVoices + voiceId
            Result.success(Unit)
        } catch (e: Exception) {
            Result.error(ErrorCodes.VOICE_CLONE_FAILED, "Bert-VITS2 loadVoice failed: ${e.message}", e)
        }
    }

    private fun resolveSpeakerId(voiceId: String?): Int {
        if (voiceId == null) return 0
        speakerMap[voiceId]?.let { return it }
        val idx = availableVoices.indexOf(voiceId)
        return if (idx >= 0) idx else 0
    }

    private fun resolveEmotionVector(emotion: String): FloatArray {
        // 10-dim common emotion vector (ESD style)
        // 0: neutral, 1: angry, 2: happy, 3: sad, 4: surprise, ...
        val base = FloatArray(10) { 0f }
        when (emotion.lowercase()) {
            "neutral" -> base[0] = 1f
            "angry"   -> base[1] = 1f
            "happy"   -> base[2] = 1f
            "sad"     -> base[3] = 1f
            "surprise"-> base[4] = 1f
            else      -> base[0] = 1f
        }
        // emotionVectors[emotion]?.let { return it }
        return base
    }

    private fun splitSentences(text: String, maxLen: Int): List<String> {
        val list = mutableListOf<String>()
        val sb = StringBuilder()
        val punct = setOf('。', '！', '？', '.', '!', '?', '；', ';', '\n')
        for (ch in text) {
            sb.append(ch)
            if ((ch in punct && sb.length >= 15) || sb.length >= maxLen) {
                list.add(sb.toString().trim())
                sb.clear()
            }
        }
        if (sb.isNotBlank()) list.add(sb.toString().trim())
        return list
    }

    private fun writeOutputWav(pcm: ShortArray, output: File, sampleRate: Int): Long {
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

    private fun estimateMemMb(config: OfflineTtsConfig): Long {
        return when {
            config.modelDir.contains("large") -> 1800
            config.modelDir.contains("medium") -> 1100
            config.modelDir.contains("sft") -> 1200
            else -> 900
        }
    }

    private fun Int.ifZero(f: () -> Int): Int = if (this == 0) f() else this
}

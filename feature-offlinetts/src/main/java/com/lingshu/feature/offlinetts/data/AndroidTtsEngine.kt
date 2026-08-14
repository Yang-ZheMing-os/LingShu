package com.lingshu.feature.offlinetts.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android 系统内置 TTS 引擎
 *
 * 基于 android.speech.tts.TextToSpeech 实现，无需额外模型文件、无需网络。
 * 模拟器和真机均自带（通常为 Google TTS / Pico 引擎）。
 * 作为 Sherpa-ONNX / ChatTTS / EdgeTTS 全部失败时的最终兜底方案。
 *
 * 注意：
 * - synthesizeToFile 输出的 WAV 格式由系统 TTS 引擎决定（通常 16kHz/16bit/mono）
 * - synthesizeStream 通过 synthesizeToFile + 读取文件模拟实现
 * - 支持通过 setVoiceConfig 设置系统 Voice / pitch / rate，实现音色变化
 */
@Singleton
class AndroidTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : IOfflineTtsEngine {

    override val provider: OfflineTtsProvider = OfflineTtsProvider.ANDROID_TTS

    private val moduleTag = "AndroidTtsEngine"

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var loaded = false

    @Volatile
    private var currentConfig: OfflineTtsConfig? = null

    // 当前音色配置：由 setVoiceConfig 设置，synthesize 前会统一应用
    // voiceName 为 null 表示使用引擎默认 Voice
    @Volatile
    private var currentVoiceName: String? = null

    @Volatile
    private var currentPitch: Float = 1.0f

    @Volatile
    private var currentRate: Float = 1.0f

    override suspend fun load(
        config: OfflineTtsConfig,
        traceId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(
            moduleTag,
            "[$traceId] load START (Android System TTS) | voice=${config.voiceId}"
        )

        // 若已加载且引擎可用，直接返回
        if (loaded && tts != null) {
            LingShuLog.d(moduleTag, "[$traceId] already loaded, reuse")
            currentConfig = config
            return@withContext Result.success(Unit)
        }

        val initLatch = CountDownLatch(1)
        val initResult = AtomicReference<Int>(TextToSpeech.ERROR)

        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                initResult.set(status)
                initLatch.countDown()
            }

            val ok = initLatch.await(10, TimeUnit.SECONDS)
            val status = initResult.get()
            if (!ok) {
                LingShuLog.e(moduleTag, "[$traceId] TTS init TIMEOUT (10s)")
                return@withContext Result.error(
                    ErrorCodes.MODEL_LOAD_FAILED,
                    "Android TTS init timeout"
                )
            }
            if (status != TextToSpeech.SUCCESS) {
                LingShuLog.e(moduleTag, "[$traceId] TTS init FAILED status=$status")
                return@withContext Result.error(
                    ErrorCodes.MODEL_LOAD_FAILED,
                    "Android TTS init failed: status=$status"
                )
            }

            // 设置语言（默认中文，失败则回退英文）
            val locale = resolveLocale(config.voiceId)
            val langResult = tts?.setLanguage(locale)
            if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                langResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                LingShuLog.w(moduleTag, "[$traceId] locale $locale not supported ($langResult), fallback to US")
                tts?.setLanguage(Locale.US)
            } else {
                LingShuLog.d(moduleTag, "[$traceId] locale set: $locale result=$langResult")
            }

            // 设置语速
            tts?.setSpeechRate(config.speed.coerceIn(0.5f, 2.0f))

            loaded = true
            currentConfig = config
            val ms = System.currentTimeMillis() - startTime
            LingShuLog.i(moduleTag, "[$traceId] load SUCCESS | engine=${tts?.defaultEngine} | ms=$ms")
            Result.success(Unit)
        } catch (e: Exception) {
            LingShuLog.e(moduleTag, "[$traceId] load EXCEPTION", e)
            Result.error(ErrorCodes.MODEL_LOAD_FAILED, "Android TTS load error: ${e.message}", e)
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
            "[$traceId] synthesize START | chars=${text.length} | out=${outputFile.absolutePath}"
        )

        if (!loaded || tts == null) {
            return@withContext Result.error(ErrorCodes.TTS_UNAVAILABLE, "Android TTS not loaded")
        }

        if (text.isBlank()) {
            return@withContext Result.error(ErrorCodes.TTS_UNAVAILABLE, "empty text")
        }

        // 合成前应用当前音色配置（voice/pitch/rate）
        applyCurrentVoiceConfig(traceId)

        val doneLatch = CountDownLatch(1)
        val errorRef = AtomicReference<String?>(null)

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                LingShuLog.d(moduleTag, "[$traceId] TTS onStart uttId=$utteranceId")
            }

            override fun onDone(utteranceId: String?) {
                LingShuLog.d(moduleTag, "[$traceId] TTS onDone uttId=$utteranceId")
                doneLatch.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                LingShuLog.e(moduleTag, "[$traceId] TTS onError uttId=$utteranceId")
                errorRef.set("synthesize failed for uttId=$utteranceId")
                doneLatch.countDown()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                LingShuLog.e(moduleTag, "[$traceId] TTS onError uttId=$utteranceId code=$errorCode")
                errorRef.set("synthesize failed code=$errorCode uttId=$utteranceId")
                doneLatch.countDown()
            }
        })

        try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            val uttId = "tts_${System.currentTimeMillis()}"
            val params = android.os.Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uttId)
            }
            val r = tts?.synthesizeToFile(text, params, outputFile, uttId)
            if (r != TextToSpeech.SUCCESS) {
                LingShuLog.e(moduleTag, "[$traceId] synthesizeToFile returned $r")
                return@withContext Result.error(
                    ErrorCodes.TTS_UNAVAILABLE,
                    "synthesizeToFile failed: $r"
                )
            }

            val ok = doneLatch.await(30, TimeUnit.SECONDS)
            val ms = System.currentTimeMillis() - startTime
            if (!ok) {
                LingShuLog.e(moduleTag, "[$traceId] synthesize TIMEOUT (30s) | ms=$ms")
                return@withContext Result.error(
                    ErrorCodes.TTS_UNAVAILABLE,
                    "Android TTS synthesize timeout"
                )
            }
            val err = errorRef.get()
            if (err != null) {
                LingShuLog.e(moduleTag, "[$traceId] synthesize FAILED: $err | ms=$ms")
                return@withContext Result.error(ErrorCodes.TTS_UNAVAILABLE, err)
            }

            val size = if (outputFile.exists()) outputFile.length() else 0L
            LingShuLog.i(
                moduleTag,
                "[$traceId] synthesize SUCCESS | bytes=$size | ms=$ms"
            )
            Result.success(outputFile)
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] synthesize EXCEPTION after ${ms}ms", e)
            Result.error(ErrorCodes.TTS_UNAVAILABLE, "Android TTS synthesize error: ${e.message}", e)
        }
    }

    override suspend fun synthesizeStream(
        text: String,
        onPcmChunk: (ShortArray) -> Unit,
        traceId: String
    ): Result<Long> = withContext(ioDispatcher) {
        // Android TTS 不支持直接 PCM 流回调，通过 synthesizeToFile 后读取文件模拟
        LingShuLog.d(moduleTag, "[$traceId] synthesizeStream via file fallback")
        val tmpFile = File.createTempFile("android_tts_stream", ".wav", context.cacheDir)
        try {
            val r = synthesize(text, tmpFile, "$traceId-STREAM")
            when (r) {
                is Result.Success -> {
                    val bytes = java.nio.file.Files.readAllBytes(tmpFile.toPath())
                    // 解析 WAV 为 PCM16
                    val pcm = wavBytesToPcm16(bytes)
                    if (pcm.isNotEmpty()) onPcmChunk(pcm)
                    LingShuLog.i(moduleTag, "[$traceId] synthesizeStream OK | samples=${pcm.size}")
                    Result.success(pcm.size.toLong())
                }
                is Result.Error -> r
            }
        } finally {
            tmpFile.delete()
        }
    }

    override suspend fun unload() {
        LingShuLog.i(moduleTag, "unload")
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            LingShuLog.w(moduleTag, "unload exception", e)
        }
        tts = null
        loaded = false
        currentConfig = null
    }

    override fun isLoaded(): Boolean = loaded && tts != null

    override fun getAvailableVoices(): List<String> {
        if (tts == null) return listOf("default")
        return try {
            val voices = tts?.voices
            if (voices != null && voices.isNotEmpty()) {
                voices.map { it.name }.take(20)
            } else {
                listOf("default")
            }
        } catch (e: Exception) {
            listOf("default")
        }
    }

    /**
     * 设置 TTS 音色配置。
     * - voiceName 为 null 时使用默认 Voice
     * - pitch 范围 0.5~2.0，1.0 为正常
     * - rate 范围 0.5~2.0，1.0 为正常
     *
     * 若引擎已加载则立即应用；否则仅保存，待 load/synthesize 时应用。
     */
    override fun setVoiceConfig(voiceName: String?, pitch: Float, rate: Float) {
        currentVoiceName = voiceName
        currentPitch = pitch.coerceIn(0.5f, 2.0f)
        currentRate = rate.coerceIn(0.5f, 2.0f)
        LingShuLog.i(
            moduleTag,
            "setVoiceConfig | voiceName=$voiceName | pitch=$currentPitch | rate=$currentRate | loaded=$loaded"
        )
        if (loaded && tts != null) {
            applyCurrentVoiceConfig("setVoiceConfig")
        }
    }

    /**
     * 返回系统 TTS 可用音色详情列表，筛选中英文 voice。
     * 每项包含 name / locale / gender / quality 等字段。
     * 模拟器或引擎不支持时返回空列表。
     */
    override fun getVoiceDetails(): List<Map<String, String>> {
        val engine = tts ?: return emptyList()
        return try {
            val voices = engine.voices ?: return emptyList()
            voices
                .filter { v ->
                    val lang = v.locale?.language?.lowercase().orEmpty()
                    lang == "zh" || lang == "en"
                }
                .take(50)
                .map { v ->
                    mapOf(
                        "name" to (v.name ?: ""),
                        "locale" to (v.locale?.toLanguageTag() ?: ""),
                        "gender" to voiceGenderToString(v),
                        "quality" to voiceQualityToString(v)
                    )
                }
        } catch (e: Exception) {
            LingShuLog.w(moduleTag, "getVoiceDetails failed", e)
            emptyList()
        }
    }

    override suspend fun loadVoice(
        voiceId: String,
        modelFile: File,
        traceId: String
    ): Result<Unit> {
        LingShuLog.d(moduleTag, "[$traceId] loadVoice voiceId=$voiceId (Android TTS uses system voices)")
        // Android 系统 TTS 通过 voice 切换
        if (tts != null) {
            val locale = resolveLocale(voiceId)
            tts?.setLanguage(locale)
        }
        return Result.success(Unit)
    }

    /**
     * 应用当前音色配置（voice/pitch/rate）到系统 TTS。
     * 任何一项失败都不影响其余项，整体容错。
     */
    private fun applyCurrentVoiceConfig(traceId: String) {
        val engine = tts ?: return
        try {
            // 1. 设置系统 Voice（voiceName 为 null 时跳过，保留默认）
            val voiceName = currentVoiceName
            if (voiceName != null) {
                val voices = runCatching { engine.voices }.getOrNull()
                val matched = voices?.firstOrNull { it.name == voiceName }
                if (matched != null) {
                    val r = runCatching { engine.setVoice(matched) }.getOrDefault(TextToSpeech.ERROR)
                    LingShuLog.d(moduleTag, "[$traceId] setVoice($voiceName) -> $r")
                } else {
                    LingShuLog.w(
                        moduleTag,
                        "[$traceId] voice '$voiceName' not found (total=${voices?.size ?: 0}), keep default"
                    )
                }
            }
            // 2. 设置音调
            val pitchR = runCatching {
                engine.setPitch(currentPitch.coerceIn(0.5f, 2.0f))
            }.getOrDefault(TextToSpeech.ERROR)
            LingShuLog.d(moduleTag, "[$traceId] setPitch($currentPitch) -> $pitchR")
            // 3. 设置语速
            val rateR = runCatching {
                engine.setSpeechRate(currentRate.coerceIn(0.5f, 2.0f))
            }.getOrDefault(TextToSpeech.ERROR)
            LingShuLog.d(moduleTag, "[$traceId] setSpeechRate($currentRate) -> $rateR")
        } catch (e: Exception) {
            LingShuLog.w(moduleTag, "[$traceId] applyCurrentVoiceConfig failed", e)
        }
    }

    /** 将 Voice 的性别特征转为可读字符串（API 21+ 通过 name 启发式判断）。 */
    private fun voiceGenderToString(voice: Voice): String {
        val n = voice.name?.lowercase().orEmpty()
        return when {
            n.contains("female") || n.contains("woman") || n.contains("女") -> "female"
            n.contains("male") || n.contains("man") || n.contains("男") -> "male"
            else -> "unknown"
        }
    }

    /** 将 Voice 质量转为可读字符串。 */
    private fun voiceQualityToString(voice: Voice): String {
        return when (voice.quality) {
            Voice.QUALITY_VERY_HIGH -> "very_high"
            Voice.QUALITY_HIGH -> "high"
            Voice.QUALITY_NORMAL -> "normal"
            Voice.QUALITY_LOW -> "low"
            Voice.QUALITY_VERY_LOW -> "very_low"
            else -> "unknown"
        }
    }

    /**
     * 根据 voiceId 解析 Locale。
     * voiceId 为 "default" 或空时返回简体中文。
     */
    private fun resolveLocale(voiceId: String): Locale {
        val v = voiceId.lowercase().trim()
        return when {
            v.isEmpty() || v == "default" || v == "zh" -> Locale.SIMPLIFIED_CHINESE
            v.startsWith("zh-cn") || v.startsWith("cn") -> Locale.SIMPLIFIED_CHINESE
            v.startsWith("zh-tw") || v.startsWith("tw") -> Locale.TRADITIONAL_CHINESE
            v.startsWith("zh-hk") || v.startsWith("hk") -> Locale("zh", "HK")
            v.startsWith("en") -> Locale.US
            v.startsWith("ja") -> Locale.JAPAN
            v.startsWith("ko") -> Locale.KOREA
            else -> Locale.SIMPLIFIED_CHINESE
        }
    }

    /**
     * 将 WAV 字节数组解析为 PCM16 采样数组。
     */
    private fun wavBytesToPcm16(bytes: ByteArray): ShortArray {
        if (bytes.size < 44) return ShortArray(0)
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4); bb.get(riff)
        if (String(riff) != "RIFF") return ShortArray(0)
        bb.position(22); val channels = bb.short.toInt()
        bb.position(24); val sampleRate = bb.int
        bb.position(34); val bits = bb.short.toInt()
        bb.position(40); val dataSize = bb.int
        if (bits != 16) return ShortArray(0)
        val start = 44
        val end = (start + dataSize).coerceAtMost(bytes.size)
        val samples = (end - start) / 2
        val pcm = ShortArray(samples)
        bb.position(start)
        for (i in 0 until samples) pcm[i] = bb.short
        return pcm
    }
}

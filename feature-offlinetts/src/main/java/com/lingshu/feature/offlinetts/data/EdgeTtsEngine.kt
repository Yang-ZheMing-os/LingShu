package com.lingshu.feature.offlinetts.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class EdgeTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : IOfflineTtsEngine {

    override val provider: OfflineTtsProvider = OfflineTtsProvider.EDGE_TTS_REMOTE_FALLBACK

    private val moduleTag = "EdgeTtsEngine"
    private var loaded = false
    private var currentConfig: OfflineTtsConfig? = null

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private const val EDGE_WS_URL =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"

        val VOICE_MAP = mapOf(
            "zh-CN-XiaoxiaoNeural"     to "zh-CN",
            "zh-CN-YunxiNeural"        to "zh-CN",
            "zh-CN-YunyangNeural"      to "zh-CN",
            "zh-CN-XiaoyiNeural"       to "zh-CN",
            "zh-CN-YunjianNeural"      to "zh-CN",
            "zh-HK-HiuGaaiNeural"      to "zh-HK",
            "zh-TW-HsiaoChenNeural"    to "zh-TW",
            "en-US-AriaNeural"         to "en-US",
            "en-US-GuyNeural"          to "en-US",
            "ja-JP-NanamiNeural"       to "ja-JP",
            "ko-KR-SunHiNeural"        to "ko-KR"
        )

        const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"
    }

    private fun resolveEdgeVoiceId(configVoiceId: String): String {
        val lower = configVoiceId.lowercase()
        return when {
            lower == "default_female" || lower == "xiaoxiao" -> "zh-CN-XiaoxiaoNeural"
            lower == "default_male" || lower == "yunxi" -> "zh-CN-YunxiNeural"
            lower.contains("news") || lower.contains("yang") -> "zh-CN-YunyangNeural"
            lower.contains("yunjian") -> "zh-CN-YunjianNeural"
            lower.contains("xiaoyi") -> "zh-CN-XiaoyiNeural"
            lower.startsWith("en") && lower.contains("female") -> "en-US-AriaNeural"
            lower.startsWith("en") -> "en-US-GuyNeural"
            VOICE_MAP.containsKey(configVoiceId) -> configVoiceId
            else -> DEFAULT_VOICE
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Throwable) {
            LingShuLog.w(moduleTag, "isNetworkAvailable check failed", e)
            true
        }
    }

    override suspend fun load(
        config: OfflineTtsConfig,
        traceId: String
    ): Result<Unit> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(
            moduleTag,
            "[$traceId] load START (REMOTE FALLBACK) | voice=${config.voiceId} -> " +
                    "${resolveEdgeVoiceId(config.voiceId)}"
        )

        if (!isNetworkAvailable()) {
            LingShuLog.e(moduleTag, "[$traceId] EdgeTts load ABORT: no network")
            return@withContext Result.error(
                ErrorCodes.NETWORK_UNAVAILABLE,
                "EdgeTts needs network (fallback engine); network unavailable"
            )
        }

        loaded = true
        currentConfig = config
        val ms = System.currentTimeMillis() - startTime
        LingShuLog.i(moduleTag, "[$traceId] load SUCCESS (no local model) | ms=$ms")
        Result.success(Unit)
    }

    override suspend fun synthesize(
        text: String,
        outputFile: File,
        traceId: String
    ): Result<File> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(
            moduleTag,
            "[$traceId] synthesize START (REMOTE) | chars=${text.length} | " +
                    "voice=${currentConfig?.voiceId}"
        )

        if (!loaded) {
            return@withContext Result.error(ErrorCodes.TTS_UNAVAILABLE, "EdgeTts not loaded")
        }
        if (!isNetworkAvailable()) {
            return@withContext Result.error(
                ErrorCodes.NETWORK_UNAVAILABLE,
                "EdgeTts network unavailable during synthesize"
            )
        }

        try {
            val voice = resolveEdgeVoiceId(currentConfig?.voiceId ?: DEFAULT_VOICE)
            val locale = VOICE_MAP[voice] ?: "zh-CN"
            val sr = currentConfig?.sampleRate ?: 24000
            val outputFormat = selectOutputFormat(sr, currentConfig?.format ?: "wav")
            LingShuLog.d(
                moduleTag,
                "[$traceId] edge params | voice=$voice | locale=$locale | " +
                        "outputFormat=$outputFormat | speed=${currentConfig?.speed} | " +
                        "temp=${currentConfig?.temperature}"
            )

            val audioBytes = edgeTtsSynthesizeViaHttp(
                text = text,
                voice = voice,
                locale = locale,
                outputFormat = outputFormat,
                speed = currentConfig?.speed ?: 1.0f,
                traceId = traceId
            )

            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(audioBytes)

            val audioSec = estimateAudioSec(audioBytes.size, sr, currentConfig?.format ?: "wav")
            val totalMs = System.currentTimeMillis() - startTime
            val rta = if (audioSec > 0) totalMs / (audioSec * 1000.0) else 0.0
            LingShuLog.i(
                moduleTag,
                "[$traceId] synthesize SUCCESS | bytes=${audioBytes.size} | " +
                        "audioSec=%.2f | RTA=%.2fx | totalMs=$totalMs".format(audioSec, rta)
            )
            Result.success(outputFile)
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] synthesize FAILED after ${ms}ms", e)
            Result.error(
                ErrorCodes.TTS_UNAVAILABLE,
                "EdgeTts (remote fallback) synthesize failed: ${e.message}",
                e
            )
        }
    }

    override suspend fun synthesizeStream(
        text: String,
        onPcmChunk: (ShortArray) -> Unit,
        traceId: String
    ): Result<Long> = withContext(ioDispatcher) {
        val startTime = System.currentTimeMillis()
        LingShuLog.i(
            moduleTag,
            "[$traceId] synthesizeStream START (REMOTE WS) | chars=${text.length}"
        )

        if (!loaded) {
            return@withContext Result.error(ErrorCodes.TTS_UNAVAILABLE, "EdgeTts not loaded")
        }
        if (!isNetworkAvailable()) {
            return@withContext Result.error(ErrorCodes.NETWORK_UNAVAILABLE, "EdgeTts network unavailable")
        }

        try {
            val voice = resolveEdgeVoiceId(currentConfig?.voiceId ?: DEFAULT_VOICE)
            val locale = VOICE_MAP[voice] ?: "zh-CN"
            val sr = currentConfig?.sampleRate ?: 24000

            val bytes = edgeTtsWebSocketStream(
                text = text,
                voice = voice,
                locale = locale,
                sampleRate = sr,
                traceId = traceId,
                onRawAudio = { rawBytes, _, _ ->
                    val pcm = decodeAudioBytesToPcm16(rawBytes, sr)
                    if (pcm.isNotEmpty()) onPcmChunk(pcm)
                }
            )

            val totalSamples = bytes / 2L
            val audioSec = totalSamples.toDouble() / sr.toDouble()
            val totalMs = System.currentTimeMillis() - startTime
            val rta = if (audioSec > 0) totalMs / (audioSec * 1000.0) else 0.0
            LingShuLog.i(
                moduleTag,
                "[$traceId] synthesizeStream SUCCESS | bytes=$bytes | " +
                        "samples=$totalSamples | audioSec=%.2f | RTA=%.2fx | totalMs=$totalMs".format(
                            audioSec, rta
                        )
            )
            Result.success(totalSamples)
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - startTime
            LingShuLog.e(moduleTag, "[$traceId] synthesizeStream FAILED after ${ms}ms", e)
            Result.error(
                ErrorCodes.TTS_UNAVAILABLE,
                "EdgeTts stream failed: ${e.message}",
                e
            )
        }
    }

    override suspend fun unload() {
        LingShuLog.i(moduleTag, "unload DONE (no local resources)")
        loaded = false
        currentConfig = null
    }

    override fun isLoaded(): Boolean = loaded

    override fun getAvailableVoices(): List<String> {
        return VOICE_MAP.keys.toList()
    }

    override suspend fun loadVoice(
        voiceId: String,
        modelFile: File,
        traceId: String
    ): Result<Unit> {
        LingShuLog.w(
            moduleTag,
            "[$traceId] loadVoice ignored for EdgeTts: remote voices don't need local file"
        )
        return Result.success(Unit)
    }

    private fun selectOutputFormat(sampleRate: Int, format: String): String {
        return when (format.lowercase()) {
            "mp3" -> {
                when {
                    sampleRate >= 48000 -> "audio-48khz-192kbitrate-mono-mp3"
                    sampleRate >= 24000 -> "audio-24khz-160kbitrate-mono-mp3"
                    else -> "audio-16khz-128kbitrate-mono-mp3"
                }
            }
            else -> {
                when {
                    sampleRate >= 48000 -> "riff-48khz-16bit-mono-pcm"
                    sampleRate >= 24000 -> "riff-24khz-16bit-mono-pcm"
                    else -> "riff-16khz-16bit-mono-pcm"
                }
            }
        }
    }

    private fun edgeTtsSynthesizeViaHttp(
        text: String,
        voice: String,
        locale: String,
        outputFormat: String,
        speed: Float,
        traceId: String
    ): ByteArray {
        LingShuLog.i(moduleTag, "[$traceId] edgeTts via HTTP/WebSocket | voice=$voice format=$outputFormat")
        val latch = CountDownLatch(1)
        val collected = mutableListOf<ByteArray>()
        var error: Throwable? = null

        val connId = UUID.randomUUID().toString()
        val url = "$EDGE_WS_URL?TrustedClientToken=$TRUSTED_CLIENT_TOKEN&ConnectionId=$connId"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        val ssml = buildSsml(text, voice, locale, speed)

        okHttpClient.newWebSocket(request, object : WebSocketListener() {
            var turnStartSent = false
            override fun onOpen(ws: WebSocket, response: Response) {
                LingShuLog.d(moduleTag, "[$traceId] Edge WS open")
                val configMsg = JSONObject().apply {
                    val ctx = JSONObject()
                    val sys = JSONObject().apply {
                        put("name", "SpeechSDK")
                        put("version", "1.32.0")
                        put("build", "Azure-SDK-For-Java")
                    }
                    ctx.put("system", sys)
                    put("context", ctx)
                }.toString()
                ws.send("Path: speech.config\r\nX-RequestId: $connId\r\nX-Timestamp: ${nowIso()}\r\nContent-Type: application/json\r\n\r\n$configMsg")

                val synthMsg =
                    "Path: ssml\r\nX-RequestId: $connId\r\nX-Timestamp: ${nowIso()}\r\n" +
                            "Content-Type: application/ssml+xml\r\n" +
                            "X-OutputFormat: $outputFormat\r\n\r\n$ssml"
                ws.send(synthMsg)
                turnStartSent = true
            }
            override fun onMessage(ws: WebSocket, text: String) {
                LingShuLog.v(moduleTag, "[$traceId] Edge WS text: ${text.take(200)}")
                if (text.contains("Path: turn.end")) {
                    ws.close(1000, "done")
                }
            }
            override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                val data = bytes.toByteArray()
                // Microsoft audio header: 2 byte header length + 2 bytes pattern, parse length
                val audio = parseEdgeAudioBinary(data)
                if (audio != null) collected.add(audio)
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                LingShuLog.d(moduleTag, "[$traceId] Edge WS closing code=$code reason=$reason")
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                LingShuLog.i(moduleTag, "[$traceId] Edge WS closed")
                latch.countDown()
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                LingShuLog.e(moduleTag, "[$traceId] Edge WS failure", t)
                error = t
                latch.countDown()
            }
        })

        val ok = latch.await(90, TimeUnit.SECONDS)
        error?.let { throw it }
        if (!ok) throw java.net.SocketTimeoutException("Edge TTS WS timeout")

        val total = collected.sumOf { it.size }
        LingShuLog.i(moduleTag, "[$traceId] Edge WS collected ${collected.size} chunks, total $total bytes")
        return collected.fold(ByteArray(0)) { a, b -> a + b }
    }

    private fun edgeTtsWebSocketStream(
        text: String,
        voice: String,
        locale: String,
        sampleRate: Int,
        traceId: String,
        onRawAudio: (bytes: ByteArray, sampleRate: Int, format: String) -> Unit
    ): Long {
        val total = AtomicLong(0L)
        val latch = CountDownLatch(1)
        var error: Throwable? = null

        val connId = UUID.randomUUID().toString()
        val url = "$EDGE_WS_URL?TrustedClientToken=$TRUSTED_CLIENT_TOKEN&ConnectionId=$connId"
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val ssml = buildSsml(text, voice, locale, currentConfig?.speed ?: 1f)
        val format = "riff-${sampleRate}hz-16bit-mono-pcm"

        okHttpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val config = JSONObject().apply {
                    val ctx = JSONObject()
                    val sys = JSONObject()
                        .put("name", "SpeechSDK").put("version", "1.32.0")
                        .put("build", "Azure-SDK-For-Java")
                    ctx.put("system", sys)
                    put("context", ctx)
                }.toString()
                ws.send("Path: speech.config\r\nX-RequestId: $connId\r\nX-Timestamp: ${nowIso()}\r\nContent-Type: application/json\r\n\r\n$config")
                ws.send(
                    "Path: ssml\r\nX-RequestId: $connId\r\nX-Timestamp: ${nowIso()}\r\n" +
                            "Content-Type: application/ssml+xml\r\nX-OutputFormat: $format\r\n\r\n$ssml"
                )
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (text.contains("Path: turn.end")) ws.close(1000, "done")
            }
            override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                val d = bytes.toByteArray()
                val audio = parseEdgeAudioBinary(d)
                if (audio != null) {
                    onRawAudio(audio, sampleRate, "wav")
                    total.addAndGet(audio.size.toLong())
                }
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { latch.countDown() }
            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                error = t; latch.countDown()
            }
        })

        val ok = latch.await(120, TimeUnit.SECONDS)
        error?.let { throw it }
        if (!ok) throw java.net.SocketTimeoutException("Edge TTS stream timeout")
        return total.get()
    }

    private fun buildSsml(text: String, voice: String, locale: String, speed: Float): String {
        val ratePct = ((speed - 1.0f) * 100).toInt().let { if (it >= 0) "+${it}%" else "${it}%" }
        val escaped = text.replace("&", "&amp;")
            .replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='$locale'>" +
                "<voice name='$voice'>" +
                "<prosody rate='$ratePct' pitch='+0Hz' volume='+0%'>$escaped</prosody>" +
                "</voice></speak>"
    }

    private fun parseEdgeAudioBinary(data: ByteArray): ByteArray? {
        try {
            if (data.size < 2) return null
            val bb = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val headerLen = bb.short.toInt() and 0xFFFF
            if (headerLen <= 0 || headerLen >= data.size) return null
            // header bytes -> skip, rest is audio
            return data.copyOfRange(headerLen, data.size)
        } catch (t: Throwable) {
            LingShuLog.w(moduleTag, "parseEdgeAudioBinary failed", t)
            return null
        }
    }

    private fun decodeAudioBytesToPcm16(audioBytes: ByteArray, targetSampleRate: Int): ShortArray {
        if (audioBytes.size < 44) {
            val out = ShortArray(audioBytes.size / 2)
            val bb = java.nio.ByteBuffer.wrap(audioBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in out.indices) out[i] = bb.short
            return out
        }
        val header = java.nio.ByteBuffer.wrap(audioBytes, 0, 44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4); header.get(riff)
        if (String(riff) != "RIFF") {
            val out = ShortArray(audioBytes.size / 2)
            val bb = java.nio.ByteBuffer.wrap(audioBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in out.indices) out[i] = bb.short
            return out
        }
        header.position(22); val channels = header.short
        header.position(24); val sampleRate = header.int
        header.position(34); val bits = header.short
        header.position(40); val dataSize = header.int
        val start = 44
        val end = (start + dataSize).coerceAtMost(audioBytes.size)
        val bytes = end - start
        val samples = bytes / (bits / 8) / channels
        val bb = java.nio.ByteBuffer.wrap(audioBytes, start, bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val pcm = ShortArray(samples)
        var i = 0
        while (i < samples) {
            var s = 0
            for (c in 0 until channels) {
                s += if (bits.toInt() == 16) bb.short.toInt() else ((bb.get().toInt() and 0xFF) - 128) * 256
            }
            s /= channels
            pcm[i] = s.toShort()
            i++
        }
        if (sampleRate == targetSampleRate) return pcm
        val ratio = targetSampleRate.toDouble() / sampleRate.toDouble()
        val dst = ShortArray((samples * ratio).toInt())
        for (d in dst.indices) {
            val s = d / ratio
            val i0 = s.toInt().coerceAtMost(samples - 1)
            val i1 = (i0 + 1).coerceAtMost(samples - 1)
            val f = s - i0
            dst[d] = (pcm[i0] + (pcm[i1] - pcm[i0]) * f).toInt().toShort()
        }
        return dst
    }

    private fun estimateAudioSec(bytes: Int, sampleRate: Int, format: String): Double {
        if (bytes <= 44) return 0.0
        val audioBytes = if (format.equals("wav", true)) bytes - 44 else bytes
        val bps = 2
        return (audioBytes / bps).toDouble() / sampleRate.toDouble()
    }

    private fun nowIso(): String {
        val df = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return df.format(java.util.Date())
    }
}

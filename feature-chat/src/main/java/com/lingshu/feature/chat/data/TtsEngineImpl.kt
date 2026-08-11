package com.lingshu.feature.chat.data

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.common.event.ITtsEngine
import com.lingshu.feature.offlinetts.data.OfflineTtsRouter
import com.lingshu.feature.offlinetts.domain.OfflineTtsConfig
import com.lingshu.feature.offlinetts.domain.OfflineTtsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class TtsEngineImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val offlineTtsRouter: OfflineTtsRouter
) : ITtsEngine, TextToSpeech.OnInitListener {

    private val moduleTag = "ChatTTS"
    private var tts: TextToSpeech? = null
    private var systemTtsReady = false
    private var mediaPlayer: MediaPlayer? = null
    private var edgeTtsLoaded = false

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.CHINESE)
            systemTtsReady = result == TextToSpeech.LANG_AVAILABLE ||
                    result == TextToSpeech.LANG_COUNTRY_AVAILABLE
            if (systemTtsReady) {
                LingShuLog.i(moduleTag, "系统 TTS 初始化成功")
            } else {
                LingShuLog.w(moduleTag, "系统 TTS 语言设置失败，将使用 EdgeTTS")
            }
        } else {
            LingShuLog.w(moduleTag, "系统 TTS 初始化失败(status=$status)，将使用 EdgeTTS")
            systemTtsReady = false
        }
    }

    private suspend fun ensureEdgeTtsLoaded(): Boolean {
        if (edgeTtsLoaded) return true
        val config = OfflineTtsConfig(
            provider = OfflineTtsProvider.EDGE_TTS_REMOTE_FALLBACK,
            modelDir = "",
            voiceId = "default_female",
            speed = 1.0f,
            sampleRate = 24000,
            format = "wav"
        )
        return when (val r = offlineTtsRouter.load(config, "tts_init")) {
            is Result.Success -> {
                edgeTtsLoaded = true
                LingShuLog.i(moduleTag, "EdgeTTS 加载成功（fallback 就绪）")
                true
            }
            is Result.Error -> {
                LingShuLog.e(moduleTag, "EdgeTTS 加载失败: ${r.message}")
                false
            }
        }
    }

    override suspend fun speak(text: String): Result<Unit> {
        // 优先使用系统 TTS
        if (systemTtsReady && tts != null) {
            return speakWithSystemTts(text)
        }

        // Fallback: EdgeTTS (微软免费在线 TTS)
        return speakWithEdgeTts(text)
    }

    private suspend fun speakWithSystemTts(text: String): Result<Unit> {
        val rc = suspendCancellableCoroutine { cont ->
            val params = HashMap<String, String>()
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params)
            if (result == TextToSpeech.SUCCESS) {
                LingShuLog.d(moduleTag, "系统 TTS 播放: ${text.take(40)}")
                cont.resume(TextToSpeech.SUCCESS)
            } else {
                LingShuLog.w(moduleTag, "系统 TTS 播放失败(rc=$result)，将使用 EdgeTTS")
                cont.resume(result ?: -1)
            }
        }
        return if (rc == TextToSpeech.SUCCESS) {
            Result.Success(Unit)
        } else {
            speakWithEdgeTts(text)
        }
    }

    private suspend fun speakWithEdgeTts(text: String): Result<Unit> {
        if (!ensureEdgeTtsLoaded()) {
            return Result.Error(
                code = ErrorCodes.TTS_UNAVAILABLE,
                message = "TTS 引擎不可用（系统 TTS 和 EdgeTTS 均失败）"
            )
        }

        val outFile = File(context.cacheDir, "tts_${System.currentTimeMillis()}.wav")
        return when (val r = offlineTtsRouter.synthesize(text, outFile, "tts_speak")) {
            is Result.Success -> {
                playAudioFile(r.data)
                LingShuLog.d(moduleTag, "EdgeTTS 播放成功: ${text.take(40)}")
                Result.Success(Unit)
            }
            is Result.Error -> {
                LingShuLog.e(moduleTag, "EdgeTTS 合成失败: ${r.message}")
                Result.Error(r.code, r.message, r.cause)
            }
        }
    }

    private fun playAudioFile(file: File) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { mp ->
                    mp.release()
                    file.delete()
                }
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        } catch (e: Exception) {
            LingShuLog.e(moduleTag, "播放音频文件失败", e)
            file.delete()
        }
    }

    override fun stop() {
        tts?.stop()
        mediaPlayer?.let {
            it.stop()
            it.release()
        }
        mediaPlayer = null
    }

    override fun isSpeaking(): Boolean {
        return (tts?.isSpeaking ?: false) || (mediaPlayer?.isPlaying ?: false)
    }

    override fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        mediaPlayer?.release()
        mediaPlayer = null
        systemTtsReady = false
    }
}

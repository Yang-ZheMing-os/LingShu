package com.lingshu.feature.chat.data

import android.content.Context
import android.speech.tts.TextToSpeech
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.common.event.ITtsEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class TtsEngineImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ITtsEngine, TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.CHINESE)
            isInitialized = result == TextToSpeech.LANG_AVAILABLE ||
                    result == TextToSpeech.LANG_COUNTRY_AVAILABLE
            if (isInitialized) {
                LingShuLog.d("ChatTTS", "TTS 初始化成功")
            } else {
                LingShuLog.w("ChatTTS", "TTS 语言设置失败")
            }
        } else {
            LingShuLog.e("ChatTTS", "TTS 初始化失败，status: $status")
        }
    }

    override suspend fun speak(text: String): Result<Unit> {
        return suspendCancellableCoroutine { continuation ->
            if (!isInitialized || tts == null) {
                continuation.resume(
                    Result.Error(
                        exception = IllegalStateException("TTS 未初始化"),
                        code = ErrorCodes.TTS_UNAVAILABLE
                    )
                )
                return@suspendCancellableCoroutine
            }

            val params = HashMap<String, String>()
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params)
            
            if (result == TextToSpeech.SUCCESS) {
                continuation.resume(Result.Success(Unit))
            } else {
                continuation.resume(
                    Result.Error(
                        exception = RuntimeException("TTS 播放失败"),
                        code = ErrorCodes.TTS_UNAVAILABLE
                    )
                )
            }
        }
    }

    override fun stop() {
        if (isInitialized && tts != null) {
            tts?.stop()
        }
    }

    override fun isSpeaking(): Boolean {
        return tts?.isSpeaking ?: false
    }

    override fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}

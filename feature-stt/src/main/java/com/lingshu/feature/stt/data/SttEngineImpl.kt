package com.lingshu.feature.stt.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.common.event.ISttEngine
import com.lingshu.core.common.event.SttResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.Timer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.schedule

@Singleton
class SttEngineImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ISttEngine {

    private var speechRecognizer: SpeechRecognizer? = null
    private var onResultCallback: ((SttResult) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var timeoutTimer: Timer? = null
    private var isListening = false

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            LingShuLog.d("SttEngine", "准备就绪，开始监听")
            startTimeoutTimer()
        }

        override fun onBeginningOfSpeech() {
            LingShuLog.d("SttEngine", "检测到语音开始")
            resetTimeoutTimer()
        }

        override fun onRmsChanged(rmsdB: Float) {
        }

        override fun onBufferReceived(buffer: ByteArray?) {
        }

        override fun onEndOfSpeech() {
            LingShuLog.d("SttEngine", "语音结束")
            cancelTimeoutTimer()
        }

        override fun onError(error: Int) {
            LingShuLog.w("SttEngine", "识别错误: $error")
            cancelTimeoutTimer()
            isListening = false

            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "录音错误"
                SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    ErrorCodes.getMessage(ErrorCodes.PERMISSION_DENIED)
                }
                SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                SpeechRecognizer.ERROR_NO_MATCH -> ErrorCodes.getMessage(ErrorCodes.STT_FAILED)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
                SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ErrorCodes.getMessage(ErrorCodes.STT_FAILED)
                else -> ErrorCodes.getMessage(ErrorCodes.STT_FAILED)
            }

            onErrorCallback?.invoke(errorMessage)
            cleanup()
        }

        override fun onResults(results: Bundle?) {
            cancelTimeoutTimer()
            isListening = false

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

            if (!matches.isNullOrEmpty() && confidences != null && confidences.isNotEmpty()) {
                val text = matches[0]
                val confidence = confidences[0]

                LingShuLog.d("SttEngine", "识别结果: $text, 置信度: $confidence")

                if (confidence >= CONFIDENCE_THRESHOLD) {
                    onResultCallback?.invoke(SttResult(text = text, confidence = confidence))
                } else {
                    onErrorCallback?.invoke(ErrorCodes.getMessage(ErrorCodes.STT_FAILED))
                }
            } else {
                onErrorCallback?.invoke(ErrorCodes.getMessage(ErrorCodes.STT_FAILED))
            }

            cleanup()
        }

        override fun onPartialResults(partialResults: Bundle?) {
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
        }
    }

    override fun startListening(onResult: (SttResult) -> Unit, onError: (String) -> Unit) {
        if (isListening) {
            LingShuLog.w("SttEngine", "已经在监听中，忽略重复调用")
            return
        }

        if (!isAvailable()) {
            onError(ErrorCodes.getMessage(ErrorCodes.MICROPHONE_UNAVAILABLE))
            return
        }

        onResultCallback = onResult
        onErrorCallback = onError

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            speechRecognizer?.setRecognitionListener(recognitionListener)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000)
            }

            speechRecognizer?.startListening(intent)
            isListening = true
            LingShuLog.d("SttEngine", "开始语音识别")
        } catch (e: Exception) {
            LingShuLog.e("SttEngine", "启动语音识别失败", e)
            isListening = false
            onError(ErrorCodes.getMessage(ErrorCodes.MICROPHONE_UNAVAILABLE))
            cleanup()
        }
    }

    override fun stopListening() {
        if (isListening) {
            speechRecognizer?.stopListening()
            cancelTimeoutTimer()
            LingShuLog.d("SttEngine", "停止监听")
        }
    }

    override fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    override fun cancel() {
        speechRecognizer?.cancel()
        cancelTimeoutTimer()
        isListening = false
        cleanup()
        LingShuLog.d("SttEngine", "取消识别")
    }

    private fun startTimeoutTimer() {
        cancelTimeoutTimer()
        timeoutTimer = Timer().apply {
            schedule(STT_TIMEOUT_MS) {
                LingShuLog.w("SttEngine", "识别超时")
                speechRecognizer?.cancel()
                isListening = false
                onErrorCallback?.invoke(ErrorCodes.getMessage(ErrorCodes.STT_FAILED))
                cleanup()
            }
        }
    }

    private fun resetTimeoutTimer() {
        startTimeoutTimer()
    }

    private fun cancelTimeoutTimer() {
        timeoutTimer?.cancel()
        timeoutTimer = null
    }

    private fun cleanup() {
        cancelTimeoutTimer()
        speechRecognizer?.destroy()
        speechRecognizer = null
        onResultCallback = null
        onErrorCallback = null
        isListening = false
    }

    companion object {
        private const val CONFIDENCE_THRESHOLD = 0.6f
        private const val STT_TIMEOUT_MS = 5000L
    }
}

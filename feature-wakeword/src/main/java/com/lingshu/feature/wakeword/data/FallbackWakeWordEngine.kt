package com.lingshu.feature.wakeword.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.lingshu.core.common.error.Result
import com.lingshu.core.common.error.ErrorCodes
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.feature.wakeword.domain.IWakeWordEngine
import com.lingshu.feature.wakeword.domain.WakeWordEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class FallbackWakeWordEngine(
    private val context: Context,
    private val keyword: String = "灵枢灵枢"
) : IWakeWordEngine {

    private val listeners = mutableListOf<(WakeWordEvent) -> Unit>()
    private var speechRecognizer: SpeechRecognizer? = null
    private var running = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override suspend fun start(): Result<Unit> {
        if (running) {
            return Result.Success(Unit)
        }

        return try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                val ex = IllegalStateException("Speech recognition not available")
                LingShuLog.e("FallbackWakeWord", "语音识别不可用", ex)
                return Result.Error(
                    code = ErrorCodes.STT_FAILED,
                    message = "语音识别不可用",
                    cause = ex
                )
            }

            initSpeechRecognizer()
            startListening()
            running = true
            LingShuLog.d("FallbackWakeWord", "降级唤醒词引擎启动成功，关键词: $keyword")
            Result.Success(Unit)
        } catch (e: Exception) {
            LingShuLog.e("FallbackWakeWord", "降级唤醒词引擎启动失败", e)
            Result.Error(code = ErrorCodes.STT_FAILED, message = e.message ?: "降级唤醒词引擎启动失败", cause = e)
        }
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    LingShuLog.v("FallbackWakeWord", "准备聆听")
                }

                override fun onBeginningOfSpeech() {
                    LingShuLog.v("FallbackWakeWord", "开始说话")
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    LingShuLog.v("FallbackWakeWord", "结束说话")
                }

                override fun onError(error: Int) {
                    if (running) {
                        val errorMessage = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "音频错误"
                            SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                            SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                            SpeechRecognizer.ERROR_NO_MATCH -> "无匹配结果"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
                            SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
                            else -> "未知错误: $error"
                        }
                        LingShuLog.w("FallbackWakeWord", "语音识别错误: $errorMessage")
                        scope.launch {
                            delay(1000)
                            startListening()
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        for (match in matches) {
                            if (match.contains(keyword, ignoreCase = true)) {
                                val event = WakeWordEvent(
                                    keyword = keyword,
                                    timestamp = System.currentTimeMillis()
                                )
                                notifyListeners(event)
                                LingShuLog.i("FallbackWakeWord", "检测到唤醒词: $match")
                                break
                            }
                        }
                    }
                    if (running) {
                        startListening()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partialMatches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!partialMatches.isNullOrEmpty()) {
                        for (match in partialMatches) {
                            if (match.contains(keyword, ignoreCase = true)) {
                                val event = WakeWordEvent(
                                    keyword = keyword,
                                    timestamp = System.currentTimeMillis()
                                )
                                notifyListeners(event)
                                LingShuLog.i("FallbackWakeWord", "检测到唤醒词(部分结果): $match")
                                break
                            }
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            LingShuLog.w("FallbackWakeWord", "启动监听失败", e)
            if (running) {
                scope.launch {
                    delay(2000)
                    startListening()
                }
            }
        }
    }

    override suspend fun stop() {
        running = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            LingShuLog.w("FallbackWakeWord", "停止监听时出错", e)
        }
        LingShuLog.d("FallbackWakeWord", "降级唤醒词引擎已停止")
    }

    override fun registerListener(listener: (WakeWordEvent) -> Unit) {
        listeners.add(listener)
    }

    override fun unregisterListener(listener: (WakeWordEvent) -> Unit) {
        listeners.remove(listener)
    }

    override fun isRunning(): Boolean = running

    private fun notifyListeners(event: WakeWordEvent) {
        listeners.forEach { it(event) }
    }
}

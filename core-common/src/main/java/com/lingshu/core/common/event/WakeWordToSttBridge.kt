package com.lingshu.core.common.event

import com.lingshu.core.common.di.DefaultDispatcher
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.common.error.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WakeWordToSttBridge @Inject constructor(
    private val bus: IAppEventBus,
    private val sttEngine: ISttEngine,
    private val ttsEngine: ITtsEngine,
    private val floatingService: IFloatingService,
    @DefaultDispatcher private val handler: CoroutineDispatcher
) {

    private val scope = CoroutineScope(SupervisorJob() + handler)
    private var collectJob: Job? = null
    private var sttActive = false
    private var finalizeJob: Job? = null
    private var lastResult: SttResult? = null

    fun start() {
        if (collectJob?.isActive == true) {
            LingShuLog.w("WakeWordToSttBridge", "Bridge 已在运行，忽略重复 start")
            return
        }
        LingShuLog.d("WakeWordToSttBridge", "启动 WakeWord→STT 桥梁")
        collectJob = scope.launch {
            bus.on<AppEvent.WakeWordDetected>().collect { event ->
                handleWakeWord(event)
            }
        }
    }

    fun stop() {
        LingShuLog.d("WakeWordToSttBridge", "停止 WakeWord→STT 桥梁")
        collectJob?.cancel()
        collectJob = null
        finalizeJob?.cancel()
        finalizeJob = null
        if (sttActive) {
            sttEngine.stopListening()
            sttActive = false
        }
        scope.cancel()
    }

    private suspend fun handleWakeWord(event: AppEvent.WakeWordDetected) {
        val traceId = event.traceId
        val stepTag = "[traceId=$traceId]"

        LingShuLog.i(
            "WakeWordToSttBridge",
            "$stepTag ======== 收到唤醒事件 ========"
        )
        LingShuLog.v(
            "WakeWordToSttBridge",
            "$stepTag 唤醒词=${event.keyword}, 时间戳=${event.timestamp}"
        )

        try {
            floatingService.updateState(FloatingState.LISTENING)
            LingShuLog.d(
                "WakeWordToSttBridge",
                "$stepTag [step-1] 悬浮窗状态已切换为 LISTENING"
            )
        } catch (e: Exception) {
            LingShuLog.w(
                "WakeWordToSttBridge",
                "$stepTag [step-1] 悬浮窗状态更新失败: ${e.message}",
                e
            )
        }

        LingShuLog.d(
            "WakeWordToSttBridge",
            "$stepTag [step-2] 开始播放提示音 TTS"
        )
        try {
            when (val ttsResult = ttsEngine.speak("我在")) {
                is Result.Success -> {
                    LingShuLog.d(
                        "WakeWordToSttBridge",
                        "$stepTag [step-2] TTS 提示音播放成功"
                    )
                }
                is Result.Error -> {
                    LingShuLog.w(
                        "WakeWordToSttBridge",
                        "$stepTag [step-2] TTS 提示音播放失败: code=${ttsResult.code}, 继续执行 STT"
                    )
                }
            }
        } catch (e: Exception) {
            LingShuLog.w(
                "WakeWordToSttBridge",
                "$stepTag [step-2] TTS 提示音异常: ${e.message}, 继续执行 STT",
                e
            )
        }

        if (!sttEngine.isAvailable()) {
            LingShuLog.e(
                "WakeWordToSttBridge",
                "$stepTag [step-3] STT 引擎不可用，跳过语音识别"
            )
            bus.emit(
                AppEvent.SttError(
                    code = "STT_UNAVAILABLE",
                    message = "STT engine not available",
                    traceId = traceId
                )
            )
            return
        }

        LingShuLog.d(
            "WakeWordToSttBridge",
            "$stepTag [step-3] 启动 STT 语音识别监听（总超时=${STT_TIMEOUT_MS}ms, 静音超时=${SILENCE_DELAY_MS}ms）"
        )
        sttActive = true
        lastResult = null
        finalizeJob?.cancel()

        try {
            withTimeout(STT_TIMEOUT_MS) {
                sttEngine.startListening(
                    onResult = { sttResult ->
                        scope.launch {
                            LingShuLog.v(
                                "WakeWordToSttBridge",
                                "$stepTag [step-4] STT 结果: text=${sttResult.text.take(50)}, confidence=${sttResult.confidence}"
                            )
                            lastResult = sttResult
                            bus.emit(
                                AppEvent.SttPartialResult(
                                    text = sttResult.text,
                                    traceId = traceId
                                )
                            )
                            scheduleFinalize(traceId, stepTag)
                        }
                    },
                    onError = { errorMsg ->
                        scope.launch {
                            LingShuLog.e(
                                "WakeWordToSttBridge",
                                "$stepTag [step-4] STT 错误: $errorMsg"
                            )
                            finalizeJob?.cancel()
                            finishStt(traceId, stepTag)
                            bus.emit(
                                AppEvent.SttError(
                                    code = "STT_RUNTIME_ERROR",
                                    message = errorMsg,
                                    traceId = traceId
                                )
                            )
                        }
                    }
                )

                delay(STT_TIMEOUT_MS)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            LingShuLog.w(
                "WakeWordToSttBridge",
                "$stepTag [step-4] STT 总超时(${STT_TIMEOUT_MS}ms)，尝试 finalize"
            )
            tryEmitFinalResult(traceId, stepTag)
            finishStt(traceId, stepTag)
        } catch (e: Exception) {
            LingShuLog.e(
                "WakeWordToSttBridge",
                "$stepTag [step-4] STT 流程异常: ${e.message}",
                e
            )
            finishStt(traceId, stepTag)
            bus.emit(
                AppEvent.SttError(
                    code = "STT_EXCEPTION",
                    message = e.message ?: "Unknown STT error",
                    traceId = traceId
                )
            )
        }

        LingShuLog.i(
            "WakeWordToSttBridge",
            "$stepTag ======== 唤醒→STT 流程结束 ========"
        )
    }

    private fun scheduleFinalize(traceId: String, stepTag: String) {
        finalizeJob?.cancel()
        finalizeJob = scope.launch {
            LingShuLog.v(
                "WakeWordToSttBridge",
                "$stepTag [step-5] 静默计时开始，${SILENCE_DELAY_MS}ms 后判定为最终结果"
            )
            delay(SILENCE_DELAY_MS)
            tryEmitFinalResult(traceId, stepTag)
            finishStt(traceId, stepTag)
        }
    }

    private suspend fun tryEmitFinalResult(traceId: String, stepTag: String) {
        val result = lastResult
        if (result != null && result.text.isNotBlank()) {
            LingShuLog.i(
                "WakeWordToSttBridge",
                "$stepTag [step-5] 发出 STT 最终结果: text=${result.text.take(50)}, confidence=${result.confidence}"
            )
            bus.emit(
                AppEvent.SttFinalResult(
                    text = result.text,
                    confidence = result.confidence,
                    traceId = traceId
                )
            )
        } else {
            LingShuLog.w(
                "WakeWordToSttBridge",
                "$stepTag [step-5] 无有效 STT 结果，跳过 FinalResult emit"
            )
        }
    }

    private fun finishStt(traceId: String, stepTag: String) {
        if (sttActive) {
            sttEngine.stopListening()
            sttActive = false
            LingShuLog.d(
                "WakeWordToSttBridge",
                "$stepTag [step-6] STT 监听已停止"
            )
        }
    }

    companion object {
        private const val STT_TIMEOUT_MS = 10_000L
        private const val SILENCE_DELAY_MS = 1_500L
    }
}

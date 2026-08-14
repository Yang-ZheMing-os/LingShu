package com.lingshu.core.common.event

import com.lingshu.core.common.di.IoDispatcher
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.common.error.Result
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SttToChatBridge @Inject constructor(
    private val bus: IAppEventBus,
    private val chatRepository: IChatRepository,
    @IoDispatcher private val handler: CoroutineDispatcher
) : StartableBridge {

    private val scope = CoroutineScope(SupervisorJob() + handler)
    private var collectJob: Job? = null

    override fun start() {
        if (collectJob?.isActive == true) {
            LingShuLog.w("SttToChatBridge", "Bridge 已在运行，忽略重复 start")
            return
        }
        LingShuLog.d("SttToChatBridge", "启动 STT→Chat 桥梁")
        collectJob = scope.launch {
            bus.on<AppEvent.SttFinalResult>().collect { event ->
                handleSttFinalResult(event)
            }
        }
    }

    override fun stop() {
        LingShuLog.d("SttToChatBridge", "停止 STT→Chat 桥梁")
        collectJob?.cancel()
        collectJob = null
        scope.cancel()
    }

    private suspend fun handleSttFinalResult(event: AppEvent.SttFinalResult) {
        val traceId = event.traceId
        val stepTag = "[traceId=$traceId]"

        LingShuLog.i(
            "SttToChatBridge",
            "$stepTag ======== 收到 STT 最终结果，准备转发到对话 ========"
        )
        LingShuLog.v(
            "SttToChatBridge",
            "$stepTag STT text=${event.text}, confidence=${event.confidence}"
        )

        if (event.text.isBlank()) {
            LingShuLog.w(
                "SttToChatBridge",
                "$stepTag [step-1] STT 文本为空，跳过对话转发"
            )
            return
        }

        if (event.confidence < MIN_CONFIDENCE) {
            LingShuLog.w(
                "SttToChatBridge",
                "$stepTag [step-1] STT 置信度过低(${event.confidence} < ${MIN_CONFIDENCE})，跳过对话转发"
            )
            return
        }

        LingShuLog.d(
            "SttToChatBridge",
            "$stepTag [step-1] 发出 UserMessageSent 事件"
        )
        bus.emit(
            AppEvent.UserMessageSent(
                content = event.text,
                traceId = traceId
            )
        )

        LingShuLog.d(
            "SttToChatBridge",
            "$stepTag [step-2] 发出 AiReplyStarted 事件，悬浮窗进入 THINKING"
        )
        bus.emit(AppEvent.AiReplyStarted(traceId = traceId))

        LingShuLog.d(
            "SttToChatBridge",
            "$stepTag [step-3] 调用 chatRepository.sendMessage（超时=${SEND_TIMEOUT_MS}ms）"
        )
        try {
            val chatResult = withTimeout(SEND_TIMEOUT_MS) {
                chatRepository.sendMessage(event.text)
            }

            when (chatResult) {
                is Result.Success -> {
                    val replyText = chatResult.data.content
                    LingShuLog.i(
                        "SttToChatBridge",
                        "$stepTag [step-4] 对话回复成功: reply=${replyText.take(80)}, isUser=${chatResult.data.isUser}"
                    )
                    bus.emit(
                        AppEvent.AiReplyFinished(
                            reply = replyText,
                            userInput = event.text,
                            fromVoiceSession = true,
                            traceId = traceId
                        )
                    )
                }
                is Result.Error -> {
                    LingShuLog.e(
                        "SttToChatBridge",
                        "$stepTag [step-4] 对话回复失败: code=${chatResult.code}, msg=${chatResult.message}"
                    )
                    bus.emit(
                        AppEvent.AiReplyError(
                            code = chatResult.code,
                            message = chatResult.cause?.message ?: chatResult.message,
                            traceId = traceId
                        )
                    )
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            LingShuLog.e(
                "SttToChatBridge",
                "$stepTag [step-4] 对话发送超时(${SEND_TIMEOUT_MS}ms)"
            )
            bus.emit(
                AppEvent.AiReplyError(
                    code = "CHAT_TIMEOUT",
                    message = "Chat sendMessage timeout after ${SEND_TIMEOUT_MS}ms",
                    traceId = traceId
                )
            )
        } catch (e: Exception) {
            LingShuLog.e(
                "SttToChatBridge",
                "$stepTag [step-4] 对话发送异常: ${e.message}",
                e
            )
            bus.emit(
                AppEvent.AiReplyError(
                    code = "CHAT_EXCEPTION",
                    message = e.message ?: "Unknown chat error",
                    traceId = traceId
                )
            )
        }

        LingShuLog.i(
            "SttToChatBridge",
            "$stepTag ======== STT→Chat 转发流程结束 ========"
        )
    }

    companion object {
        private const val MIN_CONFIDENCE = 0.3f
        private const val SEND_TIMEOUT_MS = 30_000L
    }
}

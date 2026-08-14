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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 订阅 [AppEvent.AiReplyFinished]（仅语音会话来源），调用 TTS 引擎播报 AI 回复。
 *
 * 语音唤醒链路：WakeWord → STT → SttToChatBridge → ChatRepository → AiReplyFinished(fromVoiceSession=true)
 * → 本 Bridge → TTS 播报。
 *
 * UI 手动发送路径由 ChatViewModel 自行调 speakMessage，emit 的 AiReplyFinished
 * fromVoiceSession=false，本 Bridge 跳过，避免重复播报。
 */
@Singleton
class AiReplyToTtsBridge @Inject constructor(
    private val bus: IAppEventBus,
    private val ttsEngine: ITtsEngine,
    @IoDispatcher private val handler: CoroutineDispatcher
) : StartableBridge {

    private val scope = CoroutineScope(SupervisorJob() + handler)
    private var collectJob: Job? = null

    override fun start() {
        if (collectJob?.isActive == true) {
            LingShuLog.w("AiReplyToTtsBridge", "Bridge 已在运行，忽略重复 start")
            return
        }
        LingShuLog.d("AiReplyToTtsBridge", "启动 AiReply→TTS 桥梁")
        collectJob = scope.launch {
            bus.on<AppEvent.AiReplyFinished>().collect { event ->
                handleAiReplyFinished(event)
            }
        }
    }

    override fun stop() {
        LingShuLog.d("AiReplyToTtsBridge", "停止 AiReply→TTS 桥梁")
        collectJob?.cancel()
        collectJob = null
        scope.cancel()
    }

    private suspend fun handleAiReplyFinished(event: AppEvent.AiReplyFinished) {
        val traceId = event.traceId
        val stepTag = "[traceId=$traceId]"

        // 只处理语音会话来源的回复，UI 路径由 ChatViewModel 自行播报
        if (!event.fromVoiceSession) {
            return
        }

        LingShuLog.i(
            "AiReplyToTtsBridge",
            "$stepTag ======== 收到语音会话 AI 回复，准备 TTS 播报 ========"
        )

        val reply = event.reply
        if (reply.isBlank()) {
            LingShuLog.w("AiReplyToTtsBridge", "$stepTag 回复内容为空，跳过 TTS 播报")
            return
        }

        try {
            when (val ttsResult = ttsEngine.speak(reply)) {
                is Result.Success -> {
                    LingShuLog.i(
                        "AiReplyToTtsBridge",
                        "$stepTag TTS 播报成功: text=${reply.take(80)}"
                    )
                }
                is Result.Error -> {
                    LingShuLog.w(
                        "AiReplyToTtsBridge",
                        "$stepTag TTS 播报失败: code=${ttsResult.code}, msg=${ttsResult.message}"
                    )
                }
            }
        } catch (e: Exception) {
            LingShuLog.e(
                "AiReplyToTtsBridge",
                "$stepTag TTS 播报异常: ${e.message}",
                e
            )
        }

        LingShuLog.i(
            "AiReplyToTtsBridge",
            "$stepTag ======== AiReply→TTS 播报流程结束 ========"
        )
    }
}

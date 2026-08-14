package com.lingshu.core.common.event

import com.lingshu.core.common.di.MainDispatcher
import com.lingshu.core.common.log.LingShuLog
import com.lingshu.core.common.event.StartableBridge
import com.lingshu.core.common.event.FloatingState
import com.lingshu.core.common.event.IFloatingService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FloatingStateSyncer @Inject constructor(
    private val bus: IAppEventBus,
    private val floatingService: IFloatingService,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher
) : StartableBridge {

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private var collectJob: Job? = null
    private var currentState: FloatingState = FloatingState.IDLE

    override fun start() {
        if (collectJob?.isActive == true) {
            LingShuLog.w("FloatingStateSyncer", "Syncer 已在运行，忽略重复 start")
            return
        }
        LingShuLog.d("FloatingStateSyncer", "启动 FloatingState 同步器，初始状态=$currentState")
        collectJob = scope.launch {
            bus.events.collect { event ->
                handleEvent(event)
            }
        }
    }

    override fun stop() {
        LingShuLog.d("FloatingStateSyncer", "停止 FloatingState 同步器")
        collectJob?.cancel()
        collectJob = null
        scope.cancel()
    }

    private fun handleEvent(event: AppEvent) {
        val traceId = event.traceId
        val newState = when (event) {
            is AppEvent.WakeWordDetected -> FloatingState.LISTENING.also {
                LingShuLog.v(
                    "FloatingStateSyncer",
                    "[traceId=$traceId] WakeWordDetected → LISTENING"
                )
            }
            is AppEvent.SttPartialResult -> FloatingState.LISTENING.also {
                LingShuLog.v(
                    "FloatingStateSyncer",
                    "[traceId=$traceId] SttPartialResult(text=${event.text.take(20)}) → LISTENING"
                )
            }
            is AppEvent.SttFinalResult -> FloatingState.THINKING.also {
                LingShuLog.v(
                    "FloatingStateSyncer",
                    "[traceId=$traceId] SttFinalResult → THINKING"
                )
            }
            is AppEvent.UserMessageSent -> FloatingState.THINKING.also {
                LingShuLog.v(
                    "FloatingStateSyncer",
                    "[traceId=$traceId] UserMessageSent → THINKING"
                )
            }
            is AppEvent.AiReplyStarted -> FloatingState.THINKING.also {
                LingShuLog.v(
                    "FloatingStateSyncer",
                    "[traceId=$traceId] AiReplyStarted → THINKING"
                )
            }
            is AppEvent.AiReplyChunk -> FloatingState.THINKING.also {
                LingShuLog.v(
                    "FloatingStateSyncer",
                    "[traceId=$traceId] AiReplyChunk(chunk=${event.chunk.take(20)}) → THINKING"
                )
            }
            is AppEvent.AiReplyFinished -> FloatingState.IDLE.also {
                LingShuLog.v(
                    "FloatingStateSyncer",
                    "[traceId=$traceId] AiReplyFinished → IDLE"
                )
            }
            is AppEvent.SttError -> FloatingState.ERROR.also {
                LingShuLog.v(
                    "FloatingStateSyncer",
                    "[traceId=$traceId] SttError(code=${event.code}) → ERROR"
                )
            }
            is AppEvent.AiReplyError -> FloatingState.ERROR.also {
                LingShuLog.v(
                    "FloatingStateSyncer",
                    "[traceId=$traceId] AiReplyError(code=${event.code}) → ERROR"
                )
            }
            is AppEvent.CommandExecuted -> {
                val result = if (event.success) FloatingState.IDLE else FloatingState.ERROR
                LingShuLog.v(
                    "FloatingStateSyncer",
                    "[traceId=$traceId] CommandExecuted(cmd=${event.command}, success=${event.success}) → $result"
                )
                result
            }
            is AppEvent.ModStateChanged -> currentState.also {
                LingShuLog.v(
                    "FloatingStateSyncer",
                    "[traceId=$traceId] ModStateChanged(modId=${event.modId}, enabled=${event.enabled}) → 保持 $it"
                )
            }
            is AppEvent.ProactiveTriggered -> FloatingState.THINKING.also {
                LingShuLog.v(
                    "FloatingStateSyncer",
                    "[traceId=$traceId] ProactiveTriggered(type=${event.type}) → THINKING"
                )
            }
            else -> currentState.also {
                LingShuLog.v(
                    "FloatingStateSyncer",
                    "[traceId=$traceId] ${event::class.java.simpleName} → 保持 $it"
                )
            }
        }

        if (newState != currentState) {
            updateState(newState, traceId)
        }
    }

    private fun updateState(newState: FloatingState, traceId: String) {
        try {
            val oldState = currentState
            floatingService.updateState(newState)
            currentState = newState
            LingShuLog.d(
                "FloatingStateSyncer",
                "[traceId=$traceId] 悬浮窗状态变更: $oldState → $newState"
            )
        } catch (e: Exception) {
            LingShuLog.e(
                "FloatingStateSyncer",
                "[traceId=$traceId] 更新悬浮窗状态失败: target=$newState, error=${e.message}",
                e
            )
        }
    }
}

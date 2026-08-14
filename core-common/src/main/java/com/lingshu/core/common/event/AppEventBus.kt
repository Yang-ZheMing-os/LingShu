package com.lingshu.core.common.event

import com.lingshu.core.common.log.LingShuLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlin.reflect.KClass

interface IAppEventBus {
    val events: Flow<AppEvent>
    suspend fun emit(event: AppEvent)
    fun <T : AppEvent> on(clazz: KClass<T>): Flow<T>
}

sealed class AppEvent(open val traceId: String) {
    data class WakeWordDetected(
        val keyword: String,
        val timestamp: Long,
        override val traceId: String = "ww_${System.currentTimeMillis()}"
    ) : AppEvent(traceId)

    data class SttPartialResult(
        val text: String,
        override val traceId: String
    ) : AppEvent(traceId)

    data class SttFinalResult(
        val text: String,
        val confidence: Float,
        override val traceId: String
    ) : AppEvent(traceId)

    data class SttError(
        val code: String,
        val message: String,
        override val traceId: String
    ) : AppEvent(traceId)

    data class UserMessageSent(
        val content: String,
        override val traceId: String
    ) : AppEvent(traceId)

    data class AiReplyStarted(
        override val traceId: String
    ) : AppEvent(traceId)

    data class AiReplyChunk(
        val chunk: String,
        override val traceId: String
    ) : AppEvent(traceId)

    data class AiReplyFinished(
        val reply: String,
        val userInput: String = "",
        val fromVoiceSession: Boolean = false,
        override val traceId: String
    ) : AppEvent(traceId)

    data class AiReplyError(
        val code: String,
        val message: String,
        override val traceId: String
    ) : AppEvent(traceId)

    data class CommandExecuted(
        val command: String,
        val success: Boolean,
        override val traceId: String
    ) : AppEvent(traceId)

    data class ModStateChanged(
        val modId: String,
        val enabled: Boolean,
        override val traceId: String
    ) : AppEvent(traceId)

    data class ProactiveTriggered(
        val type: String,
        val title: String,
        val content: String,
        override val traceId: String
    ) : AppEvent(traceId)
}

class AppEventBusImpl : IAppEventBus {

    private val _events = MutableSharedFlow<AppEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )

    override val events: Flow<AppEvent> = _events

    override suspend fun emit(event: AppEvent) {
        LingShuLog.v(
            "AppEventBus",
            "[emit] event=${event::class.simpleName}, traceId=${event.traceId}, payload=${formatPayload(event)}"
        )
        _events.emit(event)
    }

    override fun <T : AppEvent> on(clazz: KClass<T>): Flow<T> {
        LingShuLog.v(
            "AppEventBus",
            "[on] subscribe event=${clazz.simpleName}"
        )
        return _events
            .filter { clazz.isInstance(it) }
            .map {
                LingShuLog.v(
                    "AppEventBus",
                    "[on] deliver event=${it::class.simpleName}, traceId=${it.traceId}"
                )
                @Suppress("UNCHECKED_CAST")
                it as T
            }
    }

    private fun formatPayload(event: AppEvent): String {
        return when (event) {
            is AppEvent.WakeWordDetected -> "keyword=${event.keyword}, ts=${event.timestamp}"
            is AppEvent.SttPartialResult -> "text=${event.text.take(50)}"
            is AppEvent.SttFinalResult -> "text=${event.text.take(50)}, confidence=${event.confidence}"
            is AppEvent.SttError -> "code=${event.code}, msg=${event.message}"
            is AppEvent.UserMessageSent -> "content=${event.content.take(50)}"
            is AppEvent.AiReplyStarted -> "start"
            is AppEvent.AiReplyChunk -> "chunk=${event.chunk.take(50)}"
            is AppEvent.AiReplyFinished -> "reply=${event.reply.take(50)}"
            is AppEvent.AiReplyError -> "code=${event.code}, msg=${event.message}"
            is AppEvent.CommandExecuted -> "cmd=${event.command}, success=${event.success}"
            is AppEvent.ModStateChanged -> "modId=${event.modId}, enabled=${event.enabled}"
            is AppEvent.ProactiveTriggered -> "type=${event.type}, title=${event.title}"
        }
    }
}

inline fun <reified T : AppEvent> IAppEventBus.on(): Flow<T> = on(T::class)

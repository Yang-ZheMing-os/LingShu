package com.lingshu.core.common.event

import com.lingshu.core.common.error.Result
import kotlinx.coroutines.flow.Flow

interface IChatRepository {

    fun getMessages(): Flow<List<Message>>

    suspend fun sendMessage(content: String): Result<Message>

    /**
     * 流式发送消息：逐 token 回调，最后返回完整 AI 消息。
     * onToken 在主线程回调，可直接用于 UI 逐字刷新。
     */
    suspend fun sendMessageStream(
        content: String,
        onToken: (String) -> Unit
    ): Result<Message>

    suspend fun clearMessages()
}

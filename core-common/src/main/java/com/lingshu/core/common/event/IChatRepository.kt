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

    /**
     * 用 [newContent] 覆盖最后一条 AI 助手消息的正文。
     *
     * 用于控制命令执行后，把 LLM 原始啰嗦回复替换成规范短句（如"微信应用已打开"）。
     * 无 AI 消息时什么都不做。
     */
    suspend fun rewriteLastAssistantMessage(newContent: String)
}

package com.lingshu.core.common.event

import com.lingshu.core.common.error.Result
import kotlinx.coroutines.flow.Flow

interface IChatRepository {

    fun getMessages(): Flow<List<Message>>

    suspend fun sendMessage(content: String): Result<Message>

    suspend fun clearMessages()
}

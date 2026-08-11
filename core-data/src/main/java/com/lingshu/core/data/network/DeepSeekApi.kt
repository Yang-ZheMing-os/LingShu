package com.lingshu.core.data.network

import com.lingshu.core.data.network.model.ChatRequest
import com.lingshu.core.data.network.model.ChatResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface DeepSeekApi {

    @POST("chat/completions")
    suspend fun createChatCompletion(@Body request: ChatRequest): ChatResponse
}

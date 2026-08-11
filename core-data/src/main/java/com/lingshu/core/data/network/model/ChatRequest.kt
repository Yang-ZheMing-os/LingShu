package com.lingshu.core.data.network.model

data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Float = 0.7f,
    val max_tokens: Int = 2048,
    val stream: Boolean = false
) {
    data class Message(
        val role: String,
        val content: String
    )
}

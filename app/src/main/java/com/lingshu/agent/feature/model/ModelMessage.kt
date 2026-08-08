package com.lingshu.agent.feature.model

import com.lingshu.agent.core.model.MessageRole

enum class MessageRole

data class ModelMessage(
    val role: MessageRole = MessageRole.USER,
    val content: String = ""
)


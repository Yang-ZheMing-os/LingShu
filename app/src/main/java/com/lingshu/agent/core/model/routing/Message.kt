package com.lingshu.agent.core.model.routing

enum class Role { SYSTEM, USER, ASSISTANT }

data class Message(val role: Role = Role.USER, val content: String = "")

data class ChatResponse(val isSuccess: Boolean = true, val content: String? = null, val errorMessage: String? = null)


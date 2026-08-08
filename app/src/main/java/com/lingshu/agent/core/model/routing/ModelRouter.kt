package com.lingshu.agent.core.model.routing

import javax.inject.Inject

data class RoutingEvent

class ModelRouter @Inject constructor() {
    suspend fun chat(
        messages: List<Message> = emptyList(),
        preferredModel: ModelType? = null,
        taskType: TaskType = TaskType.CHAT
    ): ChatResponse = ChatResponse()
}


package com.lingshu.agent.core.model

enum class TriggerType {
    MANUAL,
    VOICE,
    TIME_SCHEDULED,
    APP_LAUNCH,
    SCREEN_STATE,
    WIFI_CONNECT,
    LOCATION,
    EVENT
}

enum class ScriptStatus {
    DRAFT,
    READY,
    RUNNING,
    PAUSED,
    ERROR,
    DISABLED
}

data class Script(
    val scriptId: String = System.currentTimeMillis().toString(),
    val name: String = "",
    val description: String = "",
    val content: String = "",
    val language: String = "javascript",
    val triggerType: TriggerType = TriggerType.MANUAL,
    val triggerConfig: Map<String, String> = emptyMap(),
    val status: ScriptStatus = ScriptStatus.READY,
    val tags: List<String> = emptyList(),
    val isSystem: Boolean = false,
    val isFavorite: Boolean = false,
    val executionCount: Int = 0,
    val successCount: Int = 0,
    val errorCount: Int = 0,
    val avgExecutionTimeMs: Long = 0,
    val lastExecutionAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun successRate(): Float = if (executionCount > 0) {
        successCount.toFloat() / executionCount.toFloat()
    } else 0f
}

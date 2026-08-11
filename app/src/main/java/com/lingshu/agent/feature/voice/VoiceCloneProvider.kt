package com.lingshu.agent.feature.voice

interface VoiceCloneProvider {

    suspend fun cloneVoice(name: String, sampleFilePath: String): CloneResult

    suspend fun synthesize(voiceId: String, text: String): String

    fun isAvailable(): Boolean

    fun getProviderName(): String
}

data class CloneResult(
    val success: Boolean,
    val voiceId: String = "",
    val modelFilePath: String = "",
    val errorMessage: String? = null
)

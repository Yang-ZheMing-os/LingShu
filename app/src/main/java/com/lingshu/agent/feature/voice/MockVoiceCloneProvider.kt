package com.lingshu.agent.feature.voice

import kotlinx.coroutines.delay
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockVoiceCloneProvider @Inject constructor() : VoiceCloneProvider {

    override suspend fun cloneVoice(name: String, sampleFilePath: String): CloneResult {
        delay(3000L)
        return CloneResult(
            success = true,
            voiceId = UUID.randomUUID().toString(),
            modelFilePath = "/mock/models/${name}_model.bin"
        )
    }

    override suspend fun synthesize(voiceId: String, text: String): String {
        delay(500L)
        return "/mock/output/${voiceId}_${System.currentTimeMillis()}.wav"
    }

    override fun isAvailable(): Boolean = true

    override fun getProviderName(): String = "MockVoiceCloneProvider"
}

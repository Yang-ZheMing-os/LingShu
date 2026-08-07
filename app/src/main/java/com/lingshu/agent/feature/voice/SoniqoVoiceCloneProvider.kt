package com.lingshu.agent.feature.voice

/**
 * Soniqo Speech SDK 声音克隆实现（骨架）
 *
 * 当前 SDK 未接入，isAvailable() 返回 false。
 * 后续接入 Soniqo Speech Android SDK 后在此类中实现真实克隆逻辑。
 */
class SoniqoVoiceCloneProvider : VoiceCloneProvider {

    override suspend fun cloneVoice(name: String, sampleFilePath: String): CloneResult {
        return CloneResult(
            success = false,
            errorMessage = "请在设置中配置 Soniqo Speech SDK"
        )
    }

    override suspend fun synthesize(voiceId: String, text: String): String {
        throw UnsupportedOperationException("请在设置中配置 Soniqo Speech SDK")
    }

    override fun isAvailable(): Boolean = false

    override fun getProviderName(): String = "Soniqo Speech"
}

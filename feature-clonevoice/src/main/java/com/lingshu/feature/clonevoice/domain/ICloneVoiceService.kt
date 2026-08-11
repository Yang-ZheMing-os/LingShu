package com.lingshu.feature.clonevoice.domain

import com.lingshu.core.common.error.Result
import java.io.File

interface ICloneVoiceService {
    suspend fun cloneAudio(audioFile: File): Result<String>
    suspend fun setCurrentVoice(voiceId: String): Result<Unit>
    fun getCurrentVoice(): Voice?
    fun listVoices(): List<Voice>
    suspend fun deleteVoice(voiceId: String): Result<Unit>
    suspend fun previewVoice(voiceId: String, text: String): Result<Unit>
}

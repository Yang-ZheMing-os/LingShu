package com.lingshu.core.common.event

import com.lingshu.core.common.error.Result

interface ITtsEngine {

    suspend fun speak(text: String): Result<Unit>

    fun stop()

    fun isSpeaking(): Boolean

    fun release()
}

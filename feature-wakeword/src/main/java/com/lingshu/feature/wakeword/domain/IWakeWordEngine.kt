package com.lingshu.feature.wakeword.domain

import com.lingshu.core.common.error.Result

interface IWakeWordEngine {
    suspend fun start(): Result<Unit>
    suspend fun stop()
    fun registerListener(listener: (WakeWordEvent) -> Unit)
    fun unregisterListener(listener: (WakeWordEvent) -> Unit)
    fun isRunning(): Boolean
}

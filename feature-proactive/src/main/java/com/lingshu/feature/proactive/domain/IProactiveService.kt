package com.lingshu.feature.proactive.domain

import com.lingshu.core.common.error.Result

interface IProactiveService {
    suspend fun start()
    suspend fun stop()
    suspend fun configure(config: ProactiveConfig)
    suspend fun getConfig(): ProactiveConfig
    suspend fun getStatus(): ProactiveStatus
    suspend fun checkAndNotify(): Result<Unit>
}

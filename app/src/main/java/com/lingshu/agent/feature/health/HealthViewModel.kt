package com.lingshu.agent.feature.health

sealed class HealthEvent

object MonitoringStarted

object MonitoringStopped

object AiAdviceGenerated

data class AiAdviceFallback

data class DashboardRefreshFailed

data class HistoryQueryFailed

data class HealthContext


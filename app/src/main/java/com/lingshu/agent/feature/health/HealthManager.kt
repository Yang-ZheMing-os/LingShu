package com.lingshu.agent.feature.health

sealed class HealthAnomalyEvent

data class HeartRateHigh

data class HeartRateLow

data class Spo2Low

data class SedentaryWarning

data class StressHigh

data class SleepInsufficient

data class ActivityInsufficient

enum class AnomalySeverity


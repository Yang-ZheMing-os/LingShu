package com.lingshu.agent.core.model

enum class SleepStage {
    AWAKE,
    LIGHT,
    DEEP,
    REM
}

data class SleepSegment(
    val stage: SleepStage,
    val startTime: Long,
    val endTime: Long,
    val durationMinutes: Int = ((endTime - startTime) / 60000).toInt()
)

data class HealthData(
    val id: String = System.currentTimeMillis().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "manual",
    val heartRate: Int? = null,
    val heartRateMin: Int? = null,
    val heartRateMax: Int? = null,
    val restingHeartRate: Int? = null,
    val steps: Int? = null,
    val calories: Int? = null,
    val activeMinutes: Int? = null,
    val distanceMeters: Float? = null,
    val floors: Int? = null,
    val sleepSegments: List<SleepSegment> = emptyList(),
    val sleepTotalMinutes: Int? = null,
    val sleepDeepMinutes: Int? = null,
    val sleepLightMinutes: Int? = null,
    val sleepRemMinutes: Int? = null,
    val sleepAwakeMinutes: Int? = null,
    val sleepEfficiency: Float? = null,
    val sleepScore: Int? = null,
    val spo2: Float? = null,
    val spo2Min: Float? = null,
    val spo2Average: Float? = null,
    val stressLevel: Int? = null,
    val bodyBattery: Int? = null,
    val hrvRmssd: Float? = null,
    val hrvSdnn: Float? = null,
    val systolicPressure: Int? = null,
    val diastolicPressure: Int? = null,
    val temperature: Float? = null,
    val respiratoryRate: Float? = null,
    val note: String? = null
) {
    fun hasHeartRateData(): Boolean = heartRate != null
    fun hasStepsData(): Boolean = steps != null
    fun hasSleepData(): Boolean = sleepSegments.isNotEmpty() || sleepTotalMinutes != null
    fun hasSpo2Data(): Boolean = spo2 != null

    companion object {
        fun empty() = HealthData()

        fun mock(): HealthData {
            val now = System.currentTimeMillis()
            val hourMs = 3600_000L
            return HealthData(
                id = "mock-" + now,
                timestamp = now,
                source = "mock",
                heartRate = 72,
                heartRateMin = 58,
                heartRateMax = 88,
                restingHeartRate = 62,
                steps = 8423,
                calories = 310,
                activeMinutes = 45,
                distanceMeters = 5.8f * 1000,
                floors = 8,
                sleepTotalMinutes = 435,
                sleepDeepMinutes = 98,
                sleepLightMinutes = 245,
                sleepRemMinutes = 72,
                sleepAwakeMinutes = 20,
                sleepEfficiency = 0.92f,
                sleepScore = 85,
                sleepSegments = listOf(
                    SleepSegment(SleepStage.LIGHT, now - 8 * hourMs, now - 7 * hourMs + 30 * 60000),
                    SleepSegment(SleepStage.DEEP, now - 7 * hourMs + 30 * 60000, now - 6 * hourMs + 20 * 60000),
                    SleepSegment(SleepStage.REM, now - 6 * hourMs + 20 * 60000, now - 5 * hourMs + 40 * 60000),
                    SleepSegment(SleepStage.LIGHT, now - 5 * hourMs + 40 * 60000, now - 4 * hourMs + 50 * 60000),
                    SleepSegment(SleepStage.DEEP, now - 4 * hourMs + 50 * 60000, now - 3 * hourMs + 30 * 60000),
                    SleepSegment(SleepStage.REM, now - 3 * hourMs + 30 * 60000, now - 2 * hourMs + 10 * 60000),
                    SleepSegment(SleepStage.LIGHT, now - 2 * hourMs + 10 * 60000, now - 30 * 60000),
                    SleepSegment(SleepStage.AWAKE, now - 30 * 60000, now)
                ),
                spo2 = 97f,
                spo2Min = 94f,
                spo2Average = 96.5f,
                stressLevel = 35,
                bodyBattery = 78,
                hrvRmssd = 42.3f,
                hrvSdnn = 68.7f,
                systolicPressure = 118,
                diastolicPressure = 76,
                temperature = 36.5f,
                respiratoryRate = 16f,
                note = "Mock data for demo"
            )
        }
    }
}

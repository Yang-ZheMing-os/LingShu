package com.lingshu.agent.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "health_data",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["dataType"]),
        Index(value = ["source"])
    ]
)
data class HealthDataEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val source: String,
    val dataType: String?,
    val heartRate: Int? = null,
    val heartRateMin: Int? = null,
    val heartRateMax: Int? = null,
    val restingHeartRate: Int? = null,
    val steps: Int? = null,
    val calories: Int? = null,
    val activeMinutes: Int? = null,
    val distanceMeters: Float? = null,
    val floors: Int? = null,
    val sleepSegments: String? = null,
    val sleepTotalMinutes: Int? = null,
    val sleepDeepMinutes: Int? = null,
    val sleepLightMinutes: Int? = null,
    val sleepRemMinutes: Int? = null,
    val sleepAwakeMinutes: Int? = null,
    val sleepEfficiency: Float? = null,
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
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_HEART_RATE = "HEART_RATE"
        const val TYPE_STEPS = "STEPS"
        const val TYPE_SLEEP = "SLEEP"
        const val TYPE_SPO2 = "SPO2"
        const val TYPE_STRESS = "STRESS"
        const val TYPE_VITALS = "VITALS"
        const val TYPE_ACTIVITY = "ACTIVITY"
        const val TYPE_CALORIES = "CALORIES"
    }
}

enum class HealthDataType {
    HEART_RATE,
    SPO2,
    STEPS,
    SLEEP,
    STRESS,
    ACTIVITY,
    CALORIES
}

enum class HealthDataSource {
    PHONE,
    WATCH_OS,
    SAMSUNG,
    HUAWEI,
    OURA,
    RING
}

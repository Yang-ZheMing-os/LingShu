package com.lingshu.agent.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lingshu.agent.core.database.entity.HealthDataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthDataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HealthDataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<HealthDataEntity>)

    @Query("DELETE FROM health_data WHERE timestamp < :cutoffTime")
    suspend fun deleteOlderThan(cutoffTime: Long)

    // ==================== Flow观察 ====================

    @Query("SELECT * FROM health_data ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(): Flow<HealthDataEntity?>

    @Query("SELECT * FROM health_data WHERE dataType = :dataType ORDER BY timestamp DESC LIMIT 1")
    fun observeLatestByType(dataType: String): Flow<HealthDataEntity?>

    @Query("SELECT * FROM health_data WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp ASC")
    fun observeByTimeRange(start: Long, end: Long): Flow<List<HealthDataEntity>>

    @Query("SELECT * FROM health_data WHERE dataType = :dataType AND timestamp BETWEEN :start AND :end ORDER BY timestamp ASC")
    fun observeByTypeAndTimeRange(dataType: String, start: Long, end: Long): Flow<List<HealthDataEntity>>

    // ==================== 一次性查询 ====================

    @Query("SELECT * FROM health_data WHERE id = :id")
    suspend fun getById(id: String): HealthDataEntity?

    @Query("SELECT * FROM health_data WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp ASC")
    suspend fun getByTimeRange(start: Long, end: Long): List<HealthDataEntity>

    @Query("SELECT * FROM health_data WHERE dataType = :dataType AND timestamp BETWEEN :start AND :end ORDER BY timestamp ASC")
    suspend fun getByTypeAndTimeRange(dataType: String, start: Long, end: Long): List<HealthDataEntity>

    @Query("SELECT * FROM health_data ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<HealthDataEntity>

    // ==================== 聚合查询 ====================

    @Query("SELECT COALESCE(SUM(steps), 0) FROM health_data WHERE timestamp BETWEEN :start AND :end")
    suspend fun sumSteps(start: Long, end: Long): Long

    @Query("SELECT COALESCE(SUM(calories), 0) FROM health_data WHERE timestamp BETWEEN :start AND :end")
    suspend fun sumCalories(start: Long, end: Long): Long

    @Query("SELECT COALESCE(SUM(activeMinutes), 0) FROM health_data WHERE timestamp BETWEEN :start AND :end")
    suspend fun sumActiveMinutes(start: Long, end: Long): Long

    @Query("SELECT COALESCE(AVG(heartRate), 0.0) FROM health_data WHERE heartRate IS NOT NULL AND timestamp BETWEEN :start AND :end")
    suspend fun avgHeartRate(start: Long, end: Long): Double

    @Query("SELECT COALESCE(MAX(heartRate), 0) FROM health_data WHERE heartRate IS NOT NULL AND timestamp BETWEEN :start AND :end")
    suspend fun maxHeartRate(start: Long, end: Long): Int

    @Query("SELECT COALESCE(MIN(heartRate), 0) FROM health_data WHERE heartRate IS NOT NULL AND timestamp BETWEEN :start AND :end")
    suspend fun minHeartRate(start: Long, end: Long): Int

    @Query("SELECT COALESCE(AVG(restingHeartRate), 0.0) FROM health_data WHERE restingHeartRate IS NOT NULL AND timestamp BETWEEN :start AND :end")
    suspend fun avgRestingHeartRate(start: Long, end: Long): Double

    @Query("SELECT COALESCE(AVG(spo2), 0.0) FROM health_data WHERE spo2 IS NOT NULL AND timestamp BETWEEN :start AND :end")
    suspend fun avgSpo2(start: Long, end: Long): Double

    @Query("SELECT COALESCE(MIN(spo2), 0.0) FROM health_data WHERE spo2 IS NOT NULL AND timestamp BETWEEN :start AND :end")
    suspend fun minSpo2(start: Long, end: Long): Double

    @Query("SELECT COALESCE(AVG(stressLevel), 0.0) FROM health_data WHERE stressLevel IS NOT NULL AND timestamp BETWEEN :start AND :end")
    suspend fun avgStress(start: Long, end: Long): Double

    @Query("SELECT COALESCE(AVG(sleepTotalMinutes), 0) FROM health_data WHERE sleepTotalMinutes IS NOT NULL AND timestamp BETWEEN :start AND :end")
    suspend fun avgSleepMinutes(start: Long, end: Long): Double

    @Query("SELECT COALESCE(AVG(sleepDeepMinutes), 0) FROM health_data WHERE sleepDeepMinutes IS NOT NULL AND timestamp BETWEEN :start AND :end")
    suspend fun avgDeepSleepMinutes(start: Long, end: Long): Double

    @Query("SELECT COALESCE(AVG(sleepEfficiency), 0.0) FROM health_data WHERE sleepEfficiency IS NOT NULL AND timestamp BETWEEN :start AND :end")
    suspend fun avgSleepEfficiency(start: Long, end: Long): Double

    @Query("SELECT COALESCE(AVG(hrvRmssd), 0.0) FROM health_data WHERE hrvRmssd IS NOT NULL AND timestamp BETWEEN :start AND :end")
    suspend fun avgHrvRmssd(start: Long, end: Long): Double

    // ==================== 按日聚合 ====================

    @Query("""
        SELECT ((timestamp / 86400000) * 86400000) AS dayStart, COALESCE(SUM(steps), 0) AS totalSteps
        FROM health_data
        WHERE timestamp BETWEEN :start AND :end
        GROUP BY dayStart
        ORDER BY dayStart ASC
    """)
    suspend fun dailySteps(start: Long, end: Long): List<DailyStepsAgg>

    @Query("""
        SELECT ((timestamp / 86400000) * 86400000) AS dayStart, COALESCE(AVG(heartRate), 0.0) AS avgHr
        FROM health_data
        WHERE heartRate IS NOT NULL AND timestamp BETWEEN :start AND :end
        GROUP BY dayStart
        ORDER BY dayStart ASC
    """)
    suspend fun dailyAvgHeartRate(start: Long, end: Long): List<DailyHeartRateAgg>

    @Query("""
        SELECT ((timestamp / 86400000) * 86400000) AS dayStart, COALESCE(AVG(sleepTotalMinutes), 0.0) AS sleepMin
        FROM health_data
        WHERE sleepTotalMinutes IS NOT NULL AND timestamp BETWEEN :start AND :end
        GROUP BY dayStart
        ORDER BY dayStart ASC
    """)
    suspend fun dailySleepMinutes(start: Long, end: Long): List<DailySleepAgg>
}

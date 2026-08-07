package com.lingshu.agent.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lingshu.agent.core.database.entity.CorrectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CorrectionDao {

    @Query("SELECT * FROM corrections ORDER BY timestamp DESC")
    fun getAllCorrections(): Flow<List<CorrectionEntity>>

    @Query("SELECT * FROM corrections ORDER BY timestamp DESC")
    suspend fun getAllCorrectionsSync(): List<CorrectionEntity>

    /** 获取未应用的纠正记录（用于每周自动分析） */
    @Query("SELECT * FROM corrections WHERE applied = 0 ORDER BY timestamp DESC")
    suspend fun getUnappliedCorrections(): List<CorrectionEntity>

    /** 统计未应用纠正数 */
    @Query("SELECT COUNT(*) FROM corrections WHERE applied = 0")
    suspend fun getUnappliedCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCorrection(correction: CorrectionEntity)

    @Query("UPDATE corrections SET applied = 1 WHERE id = :id")
    suspend fun markApplied(id: String)

    @Query("UPDATE corrections SET applied = 1")
    suspend fun markAllApplied()

    @Query("DELETE FROM corrections WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 获取一周内的纠正记录 */
    @Query("SELECT * FROM corrections WHERE timestamp > :sinceTimestamp ORDER BY timestamp DESC")
    suspend fun getCorrectionsSince(sinceTimestamp: Long): List<CorrectionEntity>
}

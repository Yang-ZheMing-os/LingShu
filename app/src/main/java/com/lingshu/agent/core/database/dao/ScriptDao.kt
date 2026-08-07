package com.lingshu.agent.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lingshu.agent.core.database.entity.ScriptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScriptDao {

    @Upsert
    suspend fun upsert(script: ScriptEntity)

    @Query("DELETE FROM scripts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM scripts ORDER BY isFavorite DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ScriptEntity>>

    @Query("SELECT * FROM scripts WHERE id = :id")
    suspend fun getById(id: String): ScriptEntity?

    @Query("UPDATE scripts SET runCount = runCount + 1, lastRunAt = :time, updatedAt = :time WHERE id = :id")
    suspend fun incrementRunCount(id: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE scripts SET isFavorite = :favorite, updatedAt = :time WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean, time: Long = System.currentTimeMillis())
}

package com.lingshu.agent.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lingshu.agent.core.database.entity.ModEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModDao {

    @Upsert
    suspend fun upsert(mod: ModEntity)

    @Query("DELETE FROM mods WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM mods ORDER BY installedAt DESC")
    fun observeAll(): Flow<List<ModEntity>>

    @Query("SELECT * FROM mods WHERE id = :id")
    suspend fun getById(id: String): ModEntity?

    @Query("UPDATE mods SET isEnabled = :enabled, updatedAt = :time WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, time: Long = System.currentTimeMillis())

    @Query("UPDATE mods SET isUpdateAvailable = :available, updatedAt = :time WHERE id = :id")
    suspend fun setUpdateAvailable(id: String, available: Boolean, time: Long = System.currentTimeMillis())
}

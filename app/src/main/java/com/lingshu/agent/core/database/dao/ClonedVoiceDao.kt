package com.lingshu.agent.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lingshu.agent.core.database.entity.ClonedVoiceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ClonedVoiceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ClonedVoiceEntity)

    @Update
    suspend fun update(entity: ClonedVoiceEntity)

    @Query("DELETE FROM cloned_voices WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM cloned_voices ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ClonedVoiceEntity>>

    @Query("SELECT * FROM cloned_voices ORDER BY createdAt DESC")
    suspend fun getAll(): List<ClonedVoiceEntity>

    @Query("SELECT * FROM cloned_voices WHERE id = :id")
    suspend fun getById(id: String): ClonedVoiceEntity?

    @Query("SELECT * FROM cloned_voices WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): ClonedVoiceEntity?

    @Query("SELECT * FROM cloned_voices WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<ClonedVoiceEntity?>

    /**
     * 同步获取当前激活的克隆声音（用于非协程环境）
     */
    @Query("SELECT * FROM cloned_voices WHERE isActive = 1 LIMIT 1")
    fun getActiveBlocking(): ClonedVoiceEntity?

    @Query("UPDATE cloned_voices SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE cloned_voices SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: String)
}

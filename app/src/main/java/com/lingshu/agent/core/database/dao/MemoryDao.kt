package com.lingshu.agent.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lingshu.agent.core.database.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    fun getAllMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    suspend fun getAllMemoriesSync(): List<MemoryEntity>

    /** 查询当前人格关联的记忆 + 全局记忆（personaId=null） */
    @Query("SELECT * FROM memories WHERE personaId = :personaId OR personaId IS NULL ORDER BY timestamp DESC")
    fun getMemoriesForPersona(personaId: String): Flow<List<MemoryEntity>>

    /** 仅查询当前人格关联的记忆（不含全局） */
    @Query("SELECT * FROM memories WHERE personaId = :personaId ORDER BY timestamp DESC")
    suspend fun getPersonaMemoriesSync(personaId: String): List<MemoryEntity>

    /** 查询全局记忆（不绑定任何人格） */
    @Query("SELECT * FROM memories WHERE personaId IS NULL ORDER BY timestamp DESC")
    fun getGlobalMemories(): Flow<List<MemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemories(memories: List<MemoryEntity>)

    @Delete
    suspend fun deleteMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM memories WHERE content = :content LIMIT 1")
    suspend fun getByContent(content: String): MemoryEntity?
}

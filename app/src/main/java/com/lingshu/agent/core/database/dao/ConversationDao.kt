package com.lingshu.agent.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.lingshu.agent.core.database.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM conversations ORDER BY pinned DESC, lastMessageTime DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Query("UPDATE conversations SET pinned = 1, updatedAt = :time WHERE id = :id")
    suspend fun pin(id: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET pinned = 0, updatedAt = :time WHERE id = :id")
    suspend fun unpin(id: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET lastMessagePreview = :preview, lastMessageTime = :time, updatedAt = :time WHERE id = :id")
    suspend fun updateLastMessage(id: String, preview: String?, time: Long = System.currentTimeMillis())
}

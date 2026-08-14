package com.lingshu.core.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentMessages(limit: Int): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Delete
    suspend fun deleteMessage(message: MessageEntity)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getMessageCount(): Int

    /** 按 id 更新消息的 content（用于控制命令执行后把 AI 回复改成规范短句） */
    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateMessageContent(id: Long, content: String)

    /** 获取最后一条非用户消息的 id（最新 AI 回复），无消息时返回 null */
    @Query("SELECT id FROM messages WHERE isUser = 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastAssistantMessageId(): Long?
}

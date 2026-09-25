package com.fall.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY id ASC")
    fun observeMessages(sessionId: Long): Flow<List<ChatMessageEntity>>

    @Insert
    suspend fun insertSession(session: ChatSessionEntity): Long

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity): Long

    /** 单事务批量插入（Agent 回合消息落库用）。 */
    @Insert
    suspend fun insertMessages(messages: List<ChatMessageEntity>): List<Long>

    /** 取最近 [limit] 条消息（时间倒序，供调用方倒转成正序），避免全表加载后内存截取。 */
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY id DESC LIMIT :limit")
    suspend fun getRecentMessages(sessionId: Long, limit: Int): List<ChatMessageEntity>

    @Query("UPDATE chat_sessions SET updatedAt = :now WHERE id = :sessionId")
    suspend fun touchSession(sessionId: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)
}
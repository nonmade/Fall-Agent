package com.fall.core.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = androidx.room.ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,          // system / user / assistant / tool（映射 ChatRole）
    val content: String,
    /** assistant 回合的 tool_calls 序列化（JSON Array<{id,name,arguments}>）；由 [com.fall.core.data.repository.ChatRepository.toLlmMessages] 反序列化重建，null 表示无。*/
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    /** assistant 回合的思考全文（reasoning_content），仅供 UI 展示；不回放给模型。 */
    val thinking: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
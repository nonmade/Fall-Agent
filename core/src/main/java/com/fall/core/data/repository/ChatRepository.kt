package com.fall.core.data.repository

import com.fall.core.data.local.ChatDao
import com.fall.core.data.local.ChatMessageEntity
import com.fall.core.data.local.ChatSessionEntity
import com.fall.core.data.local.FallDatabase
import com.fall.core.model.llm.ChatMessage
import com.fall.core.model.llm.ChatRole
import com.fall.core.model.llm.ToolCall
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.Flow

/** 会话与消息存储。 */
interface ChatRepository {
    /** 会话列表（按最近活跃排序）。 */
    val sessions: Flow<List<ChatSessionEntity>>

    /** 指定会话的消息（按时间正序）。 */
    fun messages(sessionId: Long): Flow<List<ChatMessageEntity>>

    /** 创建会话，返回 id。 */
    suspend fun createSession(title: String, initialUserMessage: String? = null): Long

    /** 追加消息并刷新会话活跃时间，返回消息 id。 */
    suspend fun appendMessage(message: ChatMessageEntity): Long

    /** 取最近 [limit] 条消息并映射为 LLM 请求格式（含可从头部拼装的 system）。 */
    suspend fun toLlmMessages(sessionId: Long, limit: Int = 20): List<ChatMessage>

    /** 批量落库 Agent 回合消息（assistant(thinking+tool_calls) + tool(result)），供会话回看。 */
    suspend fun appendAgentTurns(sessionId: Long, turns: List<ChatMessage>)

    /** 删除会话（级联删除其消息）。 */
    suspend fun deleteSession(sessionId: Long)
}

class RoomChatRepository(
    private val dao: ChatDao,
) : ChatRepository {

    constructor(db: FallDatabase) : this(db.chatDao())

    override val sessions: Flow<List<ChatSessionEntity>> = dao.observeSessions()

    override fun messages(sessionId: Long): Flow<List<ChatMessageEntity>> =
        dao.observeMessages(sessionId)

    override suspend fun createSession(title: String, initialUserMessage: String?): Long {
        val sessionId = dao.insertSession(ChatSessionEntity(title = title))
        initialUserMessage?.let {
            dao.insertMessage(
                ChatMessageEntity(sessionId = sessionId, role = "user", content = it)
            )
        }
        return sessionId
    }

    override suspend fun appendMessage(message: ChatMessageEntity): Long {
        val id = dao.insertMessage(message)
        dao.touchSession(message.sessionId)
        return id
    }

    override suspend fun toLlmMessages(sessionId: Long, limit: Int): List<ChatMessage> {
        // 多取若干条用于"窗口头部对齐"：按最近 N 条截窗时，窗口首条可能是 tool 消息，
        // 而它的前置 assistant(tool_calls) 被截掉了 —— OpenAI 兼容端点对这种孤儿 tool 消息直接 400。
        // 这里丢弃窗口头部的孤儿 tool 消息；若无孤儿则退回最近 limit 条，行为不变。
        val raw = dao.getRecentMessages(sessionId, limit + ORPHAN_HEADROOM).reversed() // SQL 倒序拉取 → 时间正序
        return raw.dropWhile { parseRole(it.role) == ChatRole.TOOL }
            .takeLast(limit)
            .map { entity ->
                ChatMessage(
                    role = parseRole(entity.role),
                    content = entity.content.takeIf { it.isNotBlank() },
                    // 落库的 assistant 回合（toolCallsJson）在此重建为 tool_calls 结构，保证 TOOL 结果消息有前置 assistant 配对
                    toolCalls = parseToolCalls(entity.toolCallsJson),
                    toolCallId = entity.toolCallId,
                    // 历史回放保留 thinking：DeepSeek 思考模式要求整段对话原样回传 reasoning_content，否则 400
                    thinking = entity.thinking,
                )
            }
    }

    override suspend fun deleteSession(sessionId: Long) {
        dao.deleteSession(sessionId)
    }

    override suspend fun appendAgentTurns(sessionId: Long, turns: List<ChatMessage>) {
        if (turns.isEmpty()) return
        val now = System.currentTimeMillis()
        // 单事务批量插入（assistant + tool 消息顺序即为协议顺序）
        dao.insertMessages(
            turns.map { msg ->
                ChatMessageEntity(
                    sessionId = sessionId,
                    role = msg.role.name.lowercase(),
                    content = msg.content ?: "",
                    toolCallsJson = msg.toolCalls?.takeIf { it.isNotEmpty() }?.let { serializeToolCalls(it) },
                    toolCallId = msg.toolCallId,
                    thinking = msg.thinking,
                    createdAt = now,
                )
            }
        )
        dao.touchSession(sessionId, now)
    }

    private companion object {

        /** 会话窗口头部对齐时多取的条数（为丢弃孤儿 tool 消息留余量，见 [toLlmMessages]）。 */
        const val ORPHAN_HEADROOM = 8

        private val gson = Gson()

        private fun parseRole(raw: String): ChatRole =
            runCatching { ChatRole.valueOf(raw.uppercase()) }.getOrDefault(ChatRole.USER)

        /**
         * 反序列化 [ChatMessageEntity.toolCallsJson]（JSON Array<{id,name,arguments}>）；
         * null / 空白 / 非法格式返回 null（表示无 tool_calls）。
         */
        private fun parseToolCalls(json: String?): List<ToolCall>? {
            if (json.isNullOrBlank()) return null
            val arr = runCatching { JsonParser.parseString(json).asJsonArray }.getOrNull() ?: return null
            val calls = arr.mapNotNull { el ->
                val obj = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
                ToolCall(
                    id = obj.getStringOrNull("id") ?: "",
                    name = obj.getStringOrNull("name") ?: "",
                    arguments = runCatching { obj.getAsJsonObject("arguments") }.getOrNull() ?: JsonObject(),
                )
            }
            return calls.ifEmpty { null }
        }

        private fun JsonObject.getStringOrNull(key: String): String? =
            if (has(key) && !get(key).isJsonNull) get(key).asString else null

        /** 序列化 tool_calls 为 JSON Array<{id,name,arguments}>，与 [parseToolCalls] 契约对齐。 */
        private fun serializeToolCalls(calls: List<ToolCall>): String {
            val arr = JsonArray()
            calls.forEach { call ->
                val o = JsonObject()
                o.addProperty("id", call.id)
                o.addProperty("name", call.name)
                o.add("arguments", call.arguments)
                arr.add(o)
            }
            return gson.toJson(arr)
        }
    }
}
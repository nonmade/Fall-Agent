package com.fall.core.data.remote

import com.fall.core.model.llm.ChatMessage
import com.fall.core.model.llm.ChatToolSpec
import com.fall.core.model.llm.LlmConfig
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** SSE 一行事件解析出的增量（思考 / char 文本或 tool_calls 分片）。 */
internal data class SseDelta(
    val reasoning: String?,
    val content: String?,
    val toolCallChunks: List<ToolCallChunk>,
)

/** tool_calls 流式分片（按 index 聚合：id 首片出现，name/arguments 可能被切分多段）。 */
internal data class ToolCallChunk(
    val index: Int,
    val id: String?,
    val name: String?,
    val arguments: String?,
)

/**
 * 协议层纯函数集合（与网络无关，便于单元测试）。
 */
internal object LlmProtocol {

    fun parseSseData(data: String, gson: Gson = Gson()): SseDelta? {
        val root = runCatching { gson.fromJson(data, JsonObject::class.java) }.getOrNull() ?: return null
        val choices = runCatching { root.getAsJsonArray("choices") }.getOrNull() ?: return null
        if (choices.isEmpty) return null
        val delta = runCatching { choices[0].asJsonObject.getAsJsonObject("delta") }.getOrNull() ?: return null

        val content = if (delta.has("content") && !delta.get("content").isJsonNull) {
            delta.get("content").asString
        } else null

        // 思考字段：DeepSeek reasoner / Qwen3 thinking 模型输出到 reasoning_content（单流，无 index）
        val reasoning = if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull) {
            delta.get("reasoning_content").asString
        } else null

        val chunks = mutableListOf<ToolCallChunk>()
        if (delta.has("tool_calls") && !delta.get("tool_calls").isJsonNull) {
            runCatching { delta.getAsJsonArray("tool_calls") }.getOrNull()?.forEach { element ->
                val item = runCatching { element.asJsonObject }.getOrNull() ?: return@forEach
                var index = 0
                if (item.has("index") && !item.get("index").isJsonNull) index = item.get("index").asInt
                val id = item.getStringOrNull("id")
                var name: String? = null
                var arguments: String? = null
                if (item.has("function")) {
                    val fn = runCatching { item.getAsJsonObject("function") }.getOrNull()
                    if (fn != null) {
                        name = fn.getStringOrNull("name")
                        arguments = fn.getStringOrNull("arguments")
                    }
                }
                chunks.add(ToolCallChunk(index, id, name, arguments))
            }
        }
        return SseDelta(reasoning, content, chunks)
    }

    private fun JsonObject.getStringOrNull(key: String): String? =
        if (has(key) && !get(key).isJsonNull) get(key).asString else null

    /** 构造 `/chat/completions` 请求体 JSON。 */
    fun buildRequestJson(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ChatToolSpec>,
        gson: Gson,
    ): String {
        val root = JsonObject()
        root.addProperty("model", config.model)
        root.addProperty("stream", true)

        val msgs = JsonArray()
        messages.forEach { message ->
            val msg = JsonObject()
            msg.addProperty("role", message.role.name.lowercase())
            val images = message.images.orEmpty().filter { it.isNotBlank() }
            if (images.isNotEmpty()) {
                // 多模态：USER 消息渲染为 content 块数组（text + image_url）。
                // 图片仅允许出现在 USER/developer 消息（DeepSeek 规范）；detail=original 保留原始
                // 像素比例，模型才能按「截图 WxH + 左上角原点」输出可用的 tap 坐标（缩图会变形坐标）。
                val blocks = JsonArray()
                message.content?.takeIf { it.isNotBlank() }?.let { text ->
                    blocks.add(JsonObject().apply {
                        addProperty("type", "text"); addProperty("text", text)
                    })
                }
                images.forEach { url ->
                    blocks.add(JsonObject().apply {
                        addProperty("type", "image_url")
                        val img = JsonObject()
                        img.addProperty("url", url)
                        img.addProperty("detail", "original")
                        add("image_url", img)
                    })
                }
                if (blocks.isEmpty()) return@forEach
                msg.add("content", blocks)
            } else {
                message.content?.let { msg.addProperty("content", it) }
            }
            // DeepSeek V4 思考模式：assistant 若收到过 reasoning_content，后续请求必须原样回传，
            // 否则 400（"The reasoning_content in the thinking mode must be passed back to the API"）。
            // 非思考端点（OpenAI 兼容库）对额外字段通常忽略，不影响兼容。
            message.thinking?.takeIf { it.isNotBlank() }?.let { msg.addProperty("reasoning_content", it) }
            if (!message.toolCalls.isNullOrEmpty()) {
                val calls = JsonArray()
                message.toolCalls.forEach { call ->
                    val callObj = JsonObject()
                    callObj.addProperty("id", call.id)
                    callObj.addProperty("type", "function")
                    val fn = JsonObject()
                    fn.addProperty("name", call.name)
                    // 规范要求 arguments 为 JSON 字符串（如 "{\"x\":1}"），不能内嵌对象
                    fn.addProperty("arguments", gson.toJson(call.arguments))
                    callObj.add("function", fn)
                    calls.add(callObj)
                }
                msg.add("tool_calls", calls)
            }
            message.toolCallId?.let { msg.addProperty("tool_call_id", it) }
            msgs.add(msg)
        }
        root.add("messages", msgs)

        if (tools.isNotEmpty()) {
            // tool_choice 仅在提供 tools 时发送（部分服务端对"裸 tool_choice"报 400）
            root.addProperty("tool_choice", "auto")
            val toolArr = JsonArray()
            tools.forEach { tool ->
                val item = JsonObject()
                item.addProperty("type", "function")
                val fn = JsonObject()
                fn.addProperty("name", tool.name)
                fn.addProperty("description", tool.description)
                fn.add("parameters", tool.parameters)
                item.add("function", fn)
                toolArr.add(item)
            }
            root.add("tools", toolArr)
        }
        return gson.toJson(root)
    }

    /** 解析工具参数 JSON 字符串；非法时回退为空对象。 */
    fun parseArguments(raw: String, gson: Gson = Gson()): JsonObject {
        if (raw.isBlank()) return JsonObject()
        val parsed = runCatching { JsonParser.parseString(raw) }.getOrNull()
        return if (parsed != null && parsed.isJsonObject) parsed.asJsonObject else JsonObject()
    }
}
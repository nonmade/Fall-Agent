package com.fall.core.model.llm

/** 对话角色（OpenAI Chat Completions 兼容）。 */
enum class ChatRole { SYSTEM, USER, ASSISTANT, TOOL }

/**
 * 一次模型工具调用（模型产出）。
 * [arguments] 为 JSON 对象（工具参数），如 `{"targetIndex":3}`。
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: com.google.gson.JsonObject = com.google.gson.JsonObject(),
)

/**
 * 一条对话消息。
 * - USER/SYSTEM：携带 [content]
 * - ASSISTANT：可携带 [content] 与 [toolCalls]（模型决定调用哪些工具）
 * - TOOL：携带 [toolCallId]（对应哪次调用的结果）与 [content]（结果内容）
 *
 * [images]（仅 USER 消息，多模态观察截图）：data URL（`data:image/jpeg;base64,...`）或
 * http(s) 链接；协议层将其渲染为 `content` 块数组（text + image_url），非空时该消息
 * 走多模态格式。仅为运行时传递，不随 Room 落库（历史回放不回放图片）。
 *
 * [thinking]（reasoning_content 思考全文）：展示/落库，同时**必须**随请求体原样回传给
 * 服务端（DeepSeek V4 思考模式要求 assistant 的 reasoning_content 回传，否则 400）。
 */
data class ChatMessage(
    val role: ChatRole,
    val content: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val thinking: String? = null,
    val images: List<String>? = null,
) {
    companion object {
        fun system(text: String) = ChatMessage(ChatRole.SYSTEM, content = text)
        fun user(text: String) = ChatMessage(ChatRole.USER, content = text)

        /** 携带截图的 user 消息（多模态观察注入，仅 USER 支持图片）。 */
        fun userWithImages(text: String, images: List<String>) =
            ChatMessage(ChatRole.USER, content = text, images = images)

        fun assistant(text: String?, toolCalls: List<ToolCall>? = null, thinking: String? = null) =
            ChatMessage(ChatRole.ASSISTANT, content = text, toolCalls = toolCalls, thinking = thinking)
        fun toolResult(callId: String, result: String) =
            ChatMessage(ChatRole.TOOL, content = result, toolCallId = callId)
    }
}
package com.fall.assistant.ui.screen

import com.fall.core.data.local.ChatMessageEntity
import org.json.JSONArray

/**
 * 一个工具调用步骤（含其所属回合的思考；思考仅挂在回合第一步上）。
 * [running] 为 true 表示正在执行（思考流式进行中，尚无工具名/结果）。
 */
data class ToolStep(
    /** 工具名；执行中（[running]）尚未产生工具名时为空字符串。 */
    val toolName: String = "",
    val arguments: String? = null,
    val result: String? = null,
    val thinking: String? = null,
    val running: Boolean = false,
)

/** 执行中的任务实时状态：流式回答 + 已产生的步骤（未落库，run() 返回后由库数据替换）。 */
data class PendingTask(
    val answerText: String = "",
    val steps: List<ToolStep> = emptyList(),
)

/**
 * 消息分组（PC Agent 风格单卡片回复）：
 * 一个任务 = 用户消息 + 其后的全部 assistant(思考/工具调用/文本) + tool(结果) 消息。
 * [Task] 渲染"用户气泡 + 一条 AI 回复卡片"；[Standard] 仅兜底孤立消息。
 */
sealed interface MessageGroup {
    val key: Long

    data class Standard(val message: ChatMessageEntity) : MessageGroup {
        override val key: Long get() = message.id
    }

    data class Task(
        val userMessage: ChatMessageEntity,
        val answerText: String,
        val steps: List<ToolStep>,
        /** 执行中实时数据；null 表示任务已完成（展示持久化数据）。 */
        val live: PendingTask? = null,
    ) : MessageGroup {
        override val key: Long get() = userMessage.id
    }
}

/**
 * 按"用户消息边界"把消息流聚合为任务组。
 * 最终回答 = 组内最后一条 assistant 的 content；工具步骤 = 每轮 assistant 的
 * tool_calls 与其后按序 tool 结果配对。
 * [pending] 非空时合并进最后一条任务组（即刚发送的那条），支撑执行中实时渲染。
 */
fun groupTasks(messages: List<ChatMessageEntity>, pending: PendingTask?): List<MessageGroup> {
    // 兼容旧数据：过滤掉历史上的「系统捕获截图」占位 user 消息（2026-09-21 前落库）。
    // 截图是执行中自动的感知流程，不应作为「用户消息边界」把同一任务的操作步骤拆成多张卡片；
    // 移除占位后其后的 assistant/tool 消息自然并入真正的用户消息任务组，步骤连续展示。
    val filtered = messages.filterNot { isSystemSnapshot(it) }
    val groups = mutableListOf<MessageGroup>()
    var i = 0
    while (i < filtered.size) {
        val m = filtered[i]
        if (m.role == "user") {
            val tail = mutableListOf<ChatMessageEntity>()
            var j = i + 1
            while (j < filtered.size && filtered[j].role != "user") {
                tail += filtered[j]
                j++
            }
            val (answer, steps) = buildTask(tail)
            groups += MessageGroup.Task(userMessage = m, answerText = answer, steps = steps)
            i = j
        } else {
            // 孤立消息（理论上不出现）：独立展示，避免信息丢失
            groups += MessageGroup.Standard(m)
            i++
        }
    }
    if (pending != null) {
        val last = groups.lastOrNull()
        if (last is MessageGroup.Task) {
            groups[groups.lastIndex] = last.copy(live = pending)
        }
    }
    return groups
}

/** 旧版「系统截图」占位消息识别（对应已废弃的 SCREENSHOT_USER_HINT_FOR_STORE，2026-09-21 前落库历史）。 */
private fun isSystemSnapshot(m: ChatMessageEntity): Boolean =
    m.role == "user" && (m.content.contains("系统捕获的屏幕截图") || m.content.contains("截图，未落库"))

/** 从一组 assistant/tool 消息提取最终回答与工具步骤。 */
private fun buildTask(tail: List<ChatMessageEntity>): Pair<String, List<ToolStep>> {
    var answer = ""
    val steps = mutableListOf<ToolStep>()
    tail.forEach { msg ->
        when (msg.role) {
            "assistant" -> {
                msg.content.takeIf { it.isNotBlank() }?.let { answer = it }
                if (!msg.toolCallsJson.isNullOrBlank()) {
                    parseToolCalls(msg.toolCallsJson).forEachIndexed { idx, call ->
                        steps += ToolStep(
                            toolName = call.name,
                            arguments = call.arguments,
                            thinking = if (idx == 0) msg.thinking?.takeIf { it.isNotBlank() } else null,
                        )
                    }
                }
            }
            "tool" -> {
                // 顺序回填：tool 结果对应第一条尚无结果的步骤（与协议顺序一致）
                val idx = steps.indexOfFirst { it.result == null }
                if (idx >= 0) steps[idx] = steps[idx].copy(result = msg.content)
            }
        }
    }
    return answer to steps
}

/** 工具调用解析（arguments 序列化为紧凑 JSON 供展示）。 */
private data class ParsedCall(val name: String, val arguments: String?)

private fun parseToolCalls(json: String?): List<ParsedCall> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.getJSONObject(i)
            val name = obj.optString("name")
            if (name.isBlank()) null
            else ParsedCall(
                name = name,
                arguments = obj.optJSONObject("arguments")?.toString(),
            )
        }
    }.getOrDefault(emptyList())
}

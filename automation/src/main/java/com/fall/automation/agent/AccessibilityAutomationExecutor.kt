package com.fall.automation.agent

import android.content.Context
import com.fall.automation.service.AccessibilityExecutorService
import com.fall.core.agent.AgentTools
import com.fall.core.agent.AutomationUnavailableException
import com.fall.core.agent.TaskContext
import com.fall.core.agent.ToolExecutor
import com.fall.core.model.llm.ChatToolSpec
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay
import java.io.File

/**
 * 前台自动化通道执行器：把 Agent 工具调用桥接到 [AccessibilityExecutorService]。
 * 免 root / 免 adb / App 进程内运行，UI 数据完整。
 * 服务未启用时抛 [AutomationUnavailableException]，由 [ToolRegistry] 降级到下一个通道。
 */
class AccessibilityAutomationExecutor(
    /** 应用上下文（record_note 落盘用；由 AppContainer 注入，可不传）。 */
    private val appContext: Context? = null,
    /** 本任务 id（record_note 按此分文件；由调用方每任务生成）。 */
    private val taskId: String = "",
) : ToolExecutor {

    override val supportedTools: Set<String> = setOf(
        AgentTools.NAME_GET_UI_LAYOUT,
        AgentTools.NAME_TAP_TARGET,
        AgentTools.NAME_TAP,
        AgentTools.NAME_INPUT_TEXT,
        AgentTools.NAME_SCROLL,
        AgentTools.NAME_PRESS_BACK,
        AgentTools.NAME_PRESS_HOME,
        AgentTools.NAME_RECORD_NOTE,
        AgentTools.NAME_WAIT,
        AgentTools.NAME_FINISH,
    )

    override val schema: List<ChatToolSpec> = listOf(
        AgentTools.getUiLayout(),
        AgentTools.tapTarget(),
        AgentTools.tap(),
        AgentTools.inputText(),
        AgentTools.scroll(),
        AgentTools.pressBack(),
        AgentTools.pressHome(),
        AgentTools.recordNote(),
        AgentTools.wait(),
        AgentTools.finish(),
    )

    override suspend fun execute(name: String, args: JsonObject): String {
        val svc = AccessibilityExecutorService.instance
            ?: throw AutomationUnavailableException(
                "无障碍服务未连接。请在系统设置→无障碍→已下载的应用→开启 Fall"
            )

        return when (name) {
            AgentTools.NAME_GET_UI_LAYOUT -> svc.layoutText()

            AgentTools.NAME_TAP_TARGET -> {
                val index = args.get("index").asInt
                if (svc.clickByIndex(index)) "已点击 index=$index" else "点击失败：index=$index 无效（请重新 get_ui_layout）"
            }

            AgentTools.NAME_TAP -> {
                val x = args.get("x").asInt
                val y = args.get("y").asInt
                if (svc.clickAt(x, y)) "已点击 ($x,$y)" else "点击 ($x,$y) 失败"
            }

            AgentTools.NAME_INPUT_TEXT -> svc.inputText(string(args, "text"))

            AgentTools.NAME_SCROLL -> {
                val d = string(args, "direction", "forward")
                if (svc.scroll(d)) "已滚动($d)" else "滚动失败"
            }

            AgentTools.NAME_PRESS_BACK -> if (svc.pressBack()) "已返回上一页" else "返回失败"

            AgentTools.NAME_PRESS_HOME -> if (svc.pressHome()) "已回到桌面" else "回桌面失败"

            AgentTools.NAME_WAIT -> {
                val ms = long(args, "ms", 1000).coerceIn(100, 10000)
                delay(ms)
                "等待 ${ms}ms 完成"
            }

            AgentTools.NAME_RECORD_NOTE -> recordNote(args)

            AgentTools.NAME_FINISH -> "任务完成，可结束循环"

            else -> "未知工具: $name"
        }
    }

    /** 记录一条数据到任务笔记（内存列表 + 落盘 notes/<taskId>.json）；无 context 时仅内存。 */
    private fun recordNote(args: JsonObject): String {
        val content = string(args, "content").ifBlank { return "记录失败：content 为空" }
        val tag = string(args, "tag").ifBlank { null }
        val line = if (tag != null) "已记录[$tag]：$content" else "已记录：$content"
        synchronized(notes) { notes += line }
        val ctx = appContext ?: return line
        runCatching {
            val dir = File(ctx.filesDir, "notes").apply { mkdirs() }
            val id = taskId.ifBlank { TaskContext.newTaskId() }
            val file = File(dir, "$id.json")
            val type = object : TypeToken<MutableList<JsonObject>>() {}.type
            val list = runCatching {
                noteGson.fromJson<MutableList<JsonObject>>(file.readText(), type)
            }.getOrNull() ?: mutableListOf()
            list += JsonObject().apply {
                addProperty("content", content)
                tag?.let { addProperty("tag", it) }
                addProperty("time", System.currentTimeMillis())
            }
            file.writeText(noteGson.toJson(list))
        }
        return line
    }

    private val notes = mutableListOf<String>()

    private companion object {
        val noteGson = Gson()
    }

    private fun string(obj: JsonObject, key: String, def: String = ""): String =
        if (obj.has(key) && !obj.get(key).isJsonNull) obj.get(key).asString else def

    private fun long(obj: JsonObject, key: String, def: Long): Long =
        if (obj.has(key) && !obj.get(key).isJsonNull) obj.get(key).asLong else def
}
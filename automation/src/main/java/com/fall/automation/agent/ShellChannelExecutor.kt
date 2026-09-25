package com.fall.automation.agent

import com.fall.automation.bridge.ShellBridge
import com.fall.automation.bridge.ShellException
import com.fall.core.agent.AgentTools
import com.fall.core.agent.ToolExecutor
import com.fall.core.model.llm.ChatToolSpec
import com.google.gson.JsonObject
import kotlinx.coroutines.delay

/** 主屏可交互节点（解析自 `uiautomator dump` 的紧凑布局文本）。 */
private data class MainNode(
    val index: Int,
    val text: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val clickable: Boolean,
    val editable: Boolean,
)

/**
 * ADB 通道执行器：把 Agent 工具调用桥接到 [ShellBridge]（app_process 守护进程，shell uid）。
 *
 * 感知走 `dumpMainUi`（uiautomator dump，免无障碍权限）；
 * 注入走守护进程主屏注入（tap/swipe/back/home/enter/剪贴板粘贴）。
 *
 * 守护进程不可达时抛 [ShellException]（[AutomationUnavailableException] 子类），
 * 由 [ToolRegistry] 按优先级链降级到无障碍通道。
 */
class ShellChannelExecutor(
    private val bridge: ShellBridge = ShellBridge(),
) : ToolExecutor {

    /** 最近一次 get_ui_layout 的节点缓存（tap_target 按 index 定位）。 */
    private var cachedNodes: List<MainNode> = emptyList()

    override val supportedTools: Set<String> = setOf(
        AgentTools.NAME_GET_UI_LAYOUT,
        AgentTools.NAME_TAP_TARGET,
        AgentTools.NAME_TAP,
        AgentTools.NAME_INPUT_TEXT,
        AgentTools.NAME_SCROLL,
        AgentTools.NAME_PRESS_BACK,
        AgentTools.NAME_PRESS_HOME,
        AgentTools.NAME_PRESS_ENTER,
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
        AgentTools.pressEnter(),
        AgentTools.wait(),
        AgentTools.finish(),
    )

    override suspend fun execute(name: String, args: JsonObject): String {
        if (name == AgentTools.NAME_FINISH) return "任务完成，结束循环"
        if (name == AgentTools.NAME_WAIT) {
            val ms = long(args, "ms", 1000).coerceIn(100, 10000)
            delay(ms)
            return "等待 ${ms}ms 完成"
        }

        return when (name) {
            AgentTools.NAME_GET_UI_LAYOUT -> dumpLayout()
            AgentTools.NAME_TAP_TARGET -> tapTarget(args)
            AgentTools.NAME_TAP -> tap(args)
            AgentTools.NAME_INPUT_TEXT -> inputText(string(args, "text"))
            AgentTools.NAME_SCROLL -> scroll(args)
            AgentTools.NAME_PRESS_BACK -> if (injectOk("backMain")) "已返回上一页" else "返回失败"
            AgentTools.NAME_PRESS_HOME -> if (injectOk("homeMain")) "已回到桌面" else "回桌面失败"
            AgentTools.NAME_PRESS_ENTER -> if (injectOk("enterMain")) "已按下回车" else "按回车失败"
            else -> "未知工具: $name"
        }
    }

    private suspend fun dumpLayout(): String {
        val layout = call("dumpMainUi").get("layout")?.asString
            ?: return "(布局获取失败)"
        cachedNodes = parseLayout(layout)
        // 附上坐标提示与可点击域，便于模型直接消费
        return layout
    }

    private suspend fun tapTarget(args: JsonObject): String {
        if (!args.has("index")) return "参数缺失：index"
        val index = args.get("index").asInt
        val node = cachedNodes.getOrNull(index)
            ?: return "index=$index 无效（请先 get_ui_layout 获取最新界面）"
        val cx = node.x + node.w / 2
        val cy = node.y + node.h / 2
        return if (injectOk("tapMain") { it.addProperty("x", cx); it.addProperty("y", cy) })
            "已点击 index=$index（${node.text.take(20)}）"
        else "点击失败：index=$index"
    }

    private suspend fun tap(args: JsonObject): String {
        val x = int(args, "x")
        val y = int(args, "y")
        return if (injectOk("tapMain") { it.addProperty("x", x); it.addProperty("y", y) })
            "已点击 ($x,$y)" else "点击 ($x,$y) 失败"
    }

    private suspend fun inputText(text: String): String {
        if (text.isBlank()) return "输入失败：文本为空"
        // 只负责输入，不自动验证/点提交按钮——结果由 AI 下一次 get_ui_layout 自行确认
        return call("inputTextMain") { it.addProperty("text", text) }.get("message")?.asString
            ?: "（无返回）"
    }

    private suspend fun scroll(args: JsonObject): String {
        val d = string(args, "direction", "forward")
        val direction = if (d == "forward") 1 else -1
        return if (injectOk("scrollMain") { it.addProperty("direction", direction); it.addProperty("distance", 240) })
            "已滚动($d)" else "滚动失败"
    }

    // ---------- RPC 封装 ----------

    /**
     * 统一入口：先确保通道可用（**已连接时零额外往返**），再发 RPC；
     * 失败时把 [ShellBridge.lastFailure] 的真实原因带进提示（避免"版本过旧"被报成"未连接"）。
     */
    private suspend fun ensureChannel() {
        if (bridge.ensureConnected()) return
        val reason = bridge.lastFailure()
        val detail = when {
            reason != null && reason.contains("鉴权") -> reason
            reason != null -> "（$reason）"
            else -> ""
        }
        throw ShellException(
            "ADB 自动化通道未连接$detail（已尝试自动降级到无障碍通道）。" +
                "若仍不可用，请到系统设置→无障碍开启『Fall 自动化』服务；不要重复尝试同一工具。",
        )
    }

    private suspend fun call(method: String, fill: (JsonObject) -> Unit = {}): JsonObject {
        val params = JsonObject().apply { fill(this) }
        ensureChannel()
        return bridge.call(method, params)
    }

    /**
     * 注入类方法（tap/swipe/scroll/key…）的成功判定：统一走 [ShellBridge.callOk]，
     * 兼容 `{"ok":true}` 与旧版 `"ok"` 两种形态。旧实现用 `call()` 按对象解析 `"ok"` 必抛异常
     * 且被上层吞掉 → 主屏注入全链路假成功。
     */
    private suspend fun injectOk(method: String, fill: (JsonObject) -> Unit = {}): Boolean {
        val params = JsonObject().apply { fill(this) }
        ensureChannel()
        return bridge.callOk(method, params)
    }

    /**
     * 任务收尾：释放本通道持有的 RPC 连接（本执行器按任务新建，连接亦随任务结束）。
     * 不释放会每任务泄漏一个 socket + reader 线程。
     */
    override suspend fun release() {
        runCatching { bridge.close() }
    }

    /** 解析 uiautomator dump 布局文本行：`[N] 文本 (x,y wxh) [可点击][输入框]`。 */
    private fun parseLayout(layout: String): List<MainNode> {
        val bounds = Regex("""\((\d+),(\d+)\s+(\d+)x(\d+)\)""")
        val out = mutableListOf<MainNode>()
        layout.lineSequence().forEach { line ->
            val idx = Regex("""\[(\d+)\]""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            val m = bounds.find(line) ?: return@forEach
            val (x, y, w, h) = m.destructured
            val head = line.substringBefore('(').trim()
            val text = head.removePrefix("[$idx]").trim()
            out += MainNode(
                index = idx,
                text = text,
                x = x.toInt(), y = y.toInt(),
                w = w.toInt(), h = h.toInt(),
                clickable = line.contains("[可点击]"),
                editable = line.contains("[输入框]"),
            )
        }
        return out
    }

    private fun string(obj: JsonObject, key: String, def: String = ""): String =
        if (obj.has(key) && !obj.get(key).isJsonNull) obj.get(key).asString else def

    private fun int(obj: JsonObject, key: String): Int =
        if (obj.has(key) && !obj.get(key).isJsonNull) obj.get(key).asInt else 0

    private fun long(obj: JsonObject, key: String, def: Long): Long =
        if (obj.has(key) && !obj.get(key).isJsonNull) obj.get(key).asLong else def
}
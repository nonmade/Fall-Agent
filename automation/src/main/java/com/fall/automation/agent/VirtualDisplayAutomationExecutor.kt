package com.fall.automation.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import com.fall.automation.bridge.ShellBridge
import com.fall.automation.bridge.ShellException
import com.fall.automation.ocr.OcrEngine
import com.fall.core.agent.AgentTools
import com.fall.core.agent.AutomationUnavailableException
import com.fall.core.agent.PerceptionMode
import com.fall.core.agent.TaskContext
import com.fall.core.agent.ToolExecutor
import com.fall.core.agent.ToolObservations
import com.fall.core.model.llm.ChatToolSpec
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay
import java.io.File

/**
 * 后台静默链执行器：把 Agent 工具桥接到 virtual display（app_process 守护进程）。
 *
 * - open_app 流程：createDisplay（一次任务一个 VD 会话，懒创建）→ startApp(displayId)。
 * - get_ui_layout（感知）按 [PerceptionMode] 分流：
 *   - [PerceptionMode.MULTIMODAL]：captureFrame 截帧 → 图片 data URL 包进 [ToolObservations]
 *     返回（经 AgentLoop 解码后以 USER 消息送入模型，模型直读截图，用 tap(x,y) 坐标操作）；
 *   - [PerceptionMode.OCR]：captureFrame 截帧 → 端侧 OCR（PP-OCRv4 onnx）→ 紧凑布局文本 + index 缓存。
 * - tap_target/tap/scroll/back/home/enter/input_text：定向 displayId 注入（输入=剪贴板+KEYCODE_PASTE）。
 *
 * **生命周期（2026-09-25 架构调整）**：本执行器持有任务级可变状态（VD 会话标记、节点缓存、笔记），
 * 因此**每个任务新建一个实例**（见 `AppContainer.newRuntime`），并由调用方在 `finally` 中调用
 * [release] 释放虚拟屏 —— 旧实现是应用级单例，导致并发任务互相污染、且未调 finish 的任务
 * 会把 VD 与其中的 App 残留给下一个任务。
 *
 * 守护进程不可达 / createDisplay 失败抛 [AutomationUnavailableException]，由 [ToolRegistry] 降级，
 * silent 链的失败路径是提示用户（VD 是后台静默的唯一可用通道）。
 */
class VirtualDisplayAutomationExecutor(
    context: Context,
    /** 本任务 id（record_note 按此分文件；由调用方生成，不再依赖全局 TaskContext）。 */
    private val taskId: String = "",
    private val bridge: ShellBridge = ShellBridge(),
    /** 感知模式读取器（每次 get_ui_layout 实时读取，设置页切换立即生效）。 */
    private val perceptionModeProvider: suspend () -> PerceptionMode =
        { PerceptionMode.MULTIMODAL },
) : ToolExecutor {

    private val appContext: Context = context.applicationContext

    /** 一次任务的 VD 会话（懒创建，任务收尾由 [release] 销毁）。 */
    private val displayLock = Any()
    private var displayReady = false

    /** 最近一次布局的节点缓存（tap_target 按 index 定位）。 */
    private var cachedNodes: List<OcrNode> = emptyList()

    /** 本任务已记录笔记（record_note），供最终总结/回注云上下文。 */
    private val notes = mutableListOf<String>()

    override val supportedTools: Set<String> = setOf(
        AgentTools.NAME_OPEN_APP,
        AgentTools.NAME_GET_UI_LAYOUT,
        AgentTools.NAME_TAP_TARGET,
        AgentTools.NAME_TAP,
        AgentTools.NAME_INPUT_TEXT,
        AgentTools.NAME_SCROLL,
        AgentTools.NAME_PRESS_BACK,
        AgentTools.NAME_PRESS_HOME,
        AgentTools.NAME_PRESS_ENTER,
        AgentTools.NAME_RECORD_NOTE,
        AgentTools.NAME_WAIT,
        AgentTools.NAME_FINISH,
    )

    override val schema: List<ChatToolSpec> = listOf(
        AgentTools.openApp(),
        AgentTools.getUiLayout(),
        AgentTools.tapTarget(),
        AgentTools.tap(),
        AgentTools.inputText(),
        AgentTools.scroll(),
        AgentTools.pressBack(),
        AgentTools.pressHome(),
        AgentTools.pressEnter(),
        AgentTools.recordNote(),
        AgentTools.wait(),
        AgentTools.finish(),
    )

    override suspend fun execute(name: String, args: JsonObject): String {
        if (name == AgentTools.NAME_FINISH) {
            // finish 只是"任务完成的信号"，资源释放统一由 release()（调用方 finally）负责，
            // 这样异常/取消路径也不会漏掉虚拟屏回收
            return "任务完成，已回收虚拟屏会话"
        }
        if (name == AgentTools.NAME_WAIT) {
            val ms = long(args, "ms", 1000).coerceIn(100, 10000)
            delay(ms)
            return "等待 ${ms}ms 完成"
        }
        return when (name) {
            AgentTools.NAME_OPEN_APP -> openApp(args)
            AgentTools.NAME_GET_UI_LAYOUT -> dumpLayout()
            AgentTools.NAME_TAP_TARGET -> tapTarget(args)
            AgentTools.NAME_TAP -> tap(args)
            AgentTools.NAME_INPUT_TEXT -> inputText(string(args, "text"))
            AgentTools.NAME_SCROLL -> scroll(string(args, "direction", "forward"))
            AgentTools.NAME_PRESS_BACK -> if (rpcOk("pressKey") { it.addProperty("keyCode", BACK) }) "已返回上一页" else "返回失败$INJECT_FAILED_HINT"
            AgentTools.NAME_PRESS_HOME -> pressHome()
            AgentTools.NAME_PRESS_ENTER -> if (rpcOk("pressKey") { it.addProperty("keyCode", ENTER) }) "已按下回车" else "按回车失败$INJECT_FAILED_HINT"
            AgentTools.NAME_RECORD_NOTE -> recordNote(args)
            else -> "未知工具: $name"
        }
    }

    /**
     * 任务收尾：销毁虚拟屏会话并清空任务级状态。
     *
     * 与任务严格同生命周期（调用方在 `finally` 里触发）：旧实现只在 `finish` 工具里销毁，
     * 而模型完全可以不调 finish（直接文本收尾 / 预算耗尽收尾 / 异常 / 用户取消）→ VD 与其中的
     * App 一直残留到下一个任务，造成"新任务看到上个任务的界面"。
     */
    override suspend fun release() {
        synchronized(displayLock) {
            displayReady = false
            cachedNodes = emptyList()
        }
        notes.clear()
        // 无条件尝试销毁（守护进程侧幂等）：即使本地标记丢失，也不允许服务端残留会话
        runCatching { bridge.callOk("destroyDisplay", timeoutMs = 5_000) }
        runCatching { bridge.close() }
    }

    // ---------- open_app ----------

    private suspend fun openApp(args: JsonObject): String {
        val packageName = string(args, "packageName").ifBlank { null }
        val label = string(args, "appLabel").ifBlank { null }
        if (packageName == null && label == null) return "参数缺失：packageName / appLabel 至少一个"
        val pkg = resolve(packageName, label) ?: return "未找到应用（${packageName ?: ""} ${label ?: ""}），请检查名称"

        // 显式组件：shell uid 下 `am start -p` 解析不了第三方包，改 `-n pkg/class`
        val component = runCatching {
            appContext.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToString()
        }.getOrNull()

        // 会话可能已被守护进程重启清掉（displayReady 是本地记忆）→ 首次失败时重建一次再试
        var resp = startAppOnce(pkg, component)
        if (resp == null) resp = startAppOnce(pkg, component)
        if (resp == null) {
            throw AutomationUnavailableException("虚拟屏会话不可用：createDisplay 后仍无 display（已重试）")
        }
        val msg = resp.get("message")?.asString ?: "（无返回）"
        val failed = msg.startsWith("Error") || msg.contains("Error:")
        delay(3000) // 等 App 冷启动（首屏渲染后才感知）
        return if (failed) "启动失败：$msg" else "已打开 ${label ?: pkg} 到虚拟屏（静默）"
    }

    /** 启动一次目标 App；"no display"（会话已被销毁）返回 null 让调用方重建会话。 */
    private suspend fun startAppOnce(pkg: String, component: String?): JsonObject? {
        ensureDisplay()
        return try {
            bridge.call("startApp", JsonObject().apply {
                addProperty("packageName", pkg)
                component?.let { c -> addProperty("component", c) }
            })
        } catch (e: ShellException) {
            if (markDisplayLostIfMissing(e.message)) null else throw unavailable("启动", e)
        }
    }

    private fun resolve(packageName: String?, label: String?): String? {
        val pm = appContext.packageManager
        if (!packageName.isNullOrBlank()) {
            if (pm.getLaunchIntentForPackage(packageName) != null) return packageName
        }
        val target = label ?: packageName ?: return null
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrNull() ?: return null
        apps.forEach { app ->
            val l = runCatching { app.loadLabel(pm).toString() }.getOrNull() ?: return@forEach
            if (l == target) return app.packageName
        }
        apps.forEach { app ->
            val l = runCatching { app.loadLabel(pm).toString() }.getOrNull() ?: return@forEach
            if (l.contains(target) || target.contains(l)) return app.packageName
        }
        return null
    }

    // ---------- get_ui_layout（按感知模式分流：多模态截图直读 / 端侧 OCR） ----------

    private suspend fun dumpLayout(): String {
        // [已停用] 空结果自动重试 repeat(3)（2026-09-19）
        // 原因：感知重试属于替 AI 决定"要不要再取一次"；界面未就绪时由 AI 自己 wait(500~3000)
        // 后再 get_ui_layout 重试——"只提供工具，时机与判断归 AI"。
        return dumpLayoutOnce()
    }

    private suspend fun dumpLayoutOnce(): String =
        if (perceptionModeProvider() == PerceptionMode.MULTIMODAL) {
            dumpLayoutMultimodal()
        } else {
            dumpLayoutOcr() // 现有端侧 OCR 方案保留
        }

    /** 多模态：截帧 → 图片包进 [ToolObservations]，model 直读截图后用 tap(x,y) 坐标操作。 */
    private suspend fun dumpLayoutMultimodal(): String {
        val frame = captureFrameOnce() ?: captureFrameOnce()
        ?: throw AutomationUnavailableException("虚拟屏感知失败：会话不可用（已尝试重建）")
        if (frame.jpegBase64.isBlank() || frame.width == 0) return DumpResult.EmptyResult
        // 多模态下不缓存 OCR 节点：tap_target（index）不可用，提示模型改用 tap 坐标
        cachedNodes = emptyList()
        val dataUrl = "data:image/jpeg;base64,${frame.jpegBase64}"
        val hint = buildString {
            append("（屏幕截图已提供于下一条消息）")
            append(" 截图尺寸 ${frame.width}x${frame.height}，坐标原点为左上角。")
            append(" 请依据截图自主判断并操作：点击用 tap(x,y)，滑动/翻页用 scroll(direction)，")
            append(" 返回用 press_back，输入用 input_text；需要指定位置的参数按截图实际像素坐标取值。")
            append(" 若操作无效果先 wait 再重新 get_ui_layout。")
        }
        return ToolObservations.encode(hint, dataUrl)
    }

    /** OCR：截帧 → 端侧识别 → index 缓存 + 紧凑布局文本（原有方案）。 */
    private suspend fun dumpLayoutOcr(): String {
        val frame = captureFrameOnce() ?: captureFrameOnce()
        ?: throw AutomationUnavailableException("虚拟屏感知失败：会话不可用（已尝试重建）")
        if (frame.jpegBase64.isBlank() || frame.width == 0) return DumpResult.EmptyResult
        val bytes = Base64.decode(frame.jpegBase64, Base64.DEFAULT)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return DumpResult.EmptyResult
        OcrEngine.ensureInitialized(appContext)
        if (!OcrEngine.isReady()) return "(OCR 引擎未就绪)"
        val lines = OcrEngine.recognize(bmp)
        bmp.recycle() // 每帧 native 位图必须回收
        if (lines.isEmpty()) return DumpResult.EmptyResult

        cachedNodes = lines.mapIndexed { i, l -> OcrNode(i, l.text, l.x, l.y, l.w, l.h) }
        return buildString {
            cachedNodes.forEach { n ->
                append("[${n.index}] ${n.text} (${n.x},${n.y} ${n.w}x${n.h})")
                append('\n')
            }
        }.trimEnd().ifEmpty { DumpResult.EmptyResult }
    }

    /**
     * [已停用] OcrLine.toNode 可点击/可输入类型猜测（2026-09-19）
     * 原因：按宽高比/关键词猜"可点击/可输入/提交词"是替 AI 判断元素语义；
     * OCR 模式同样遵循"只提供工具，决策归 AI"——只给文本+坐标，元素类型由 AI 结合截图自行判断。
     * （原 SUBMIT_WORDS/HINT_WORDS 启发式规则一并停用，见 companion。）
     */
    // private fun OcrLine.toNode(index: Int): OcrNode {
    //     val editable = w > h * 3 && (text.isBlank() || HINT_WORDS.any { text.startsWith(it) })
    //     val clickable = !editable && !text.isBlank() && (
    //         SUBMIT_WORDS.contains(text) || (text.length <= 8 && w > h * 1.2 && w * h > 900)
    //         )
    //     return OcrNode(index, text, x, y, w, h, editable, clickable)
    // }

    /** OCR 输出的紧凑节点（仅文本+坐标；可点击/可输入等语义由 AI 判断）。 */
    private data class OcrNode(
        val index: Int,
        val text: String,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
    )

    /** 截帧/识别无结果时统一透出的占位（不替 AI 决定下一步，仅如实告知当前无内容）。 */
    private object DumpResult {
        const val EmptyResult = "(虚拟屏暂无内容，可 wait 后重试)"
    }

    // ---------- record_note ----------

    /**
     * 记录一条数据：进入任务笔记内存列表 + 落盘 `filesDir/notes/<taskId>.json`（JSON 数组）。
     * taskId 由构造参数传入（调用方每任务生成；未传则用时间戳兜底）——旧实现读全局
     * `TaskContext.currentTaskId`，而聊天入口从未赋值 → 每条笔记都新建文件。
     */
    private fun recordNote(args: JsonObject): String {
        val content = string(args, "content").ifBlank { return "记录失败：content 为空" }
        val tag = string(args, "tag").ifBlank { null }
        val line = if (tag != null) "已记录[$tag]：$content" else "已记录：$content"
        synchronized(notes) { notes += line }
        persistNote(content, tag)
        return line
    }

    /** 追加到 notes/<taskId>.json（JSON 数组，每行 {content, tag, time}）。 */
    private fun persistNote(content: String, tag: String?) {
        runCatching {
            val dir = File(appContext.filesDir, "notes").apply { mkdirs() }
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
    }

    // ---------- 注入 ----------

    private suspend fun tapTarget(args: JsonObject): String {
        if (!args.has("index")) return "参数缺失：index"
        val index = args.get("index").asInt
        // 多模态感知下无 OCR 索引缓存：引导模型改用 tap 坐标
        if (cachedNodes.isEmpty() && perceptionModeProvider() == PerceptionMode.MULTIMODAL) {
            return "当前为多模态感知模式，get_ui_layout 只提供截图不提供索引：请根据截图用 tap(x,y) 直接点击坐标"
        }
        val node = cachedNodes.getOrNull(index)
            ?: return "index=$index 无效（请先 get_ui_layout 获取最新界面）"
        val cx = node.x + node.w / 2
        val cy = node.y + node.h / 2
        return if (tapAt(cx, cy)) "已点击 index=$index（${node.text.take(20)}）" else "点击失败：index=$index$INJECT_FAILED_HINT"
    }

    private suspend fun tap(args: JsonObject): String {
        val x = int(args, "x"); val y = int(args, "y")
        return if (tapAt(x, y)) "已点击 ($x,$y)" else "点击 ($x,$y) 失败$INJECT_FAILED_HINT"
    }

    private suspend fun tapAt(x: Int, y: Int): Boolean =
        rpcOk("tap") { it.addProperty("x", x); it.addProperty("y", y) }

    private suspend fun inputText(text: String): String {
        if (text.isBlank()) return "输入失败：文本为空"
        return try {
            // 只负责输入，不自动验证/点提交按钮——结果由 AI 下一次 get_ui_layout（截图/OCR）自行确认
            ensureChannel()
            bridge.inputTextDisplay(text)
        } catch (e: ShellException) {
            throw unavailable("虚拟屏输入", e)
        }
    }

    private suspend fun scroll(direction: String): String {
        val d = if (direction == "forward") 1 else -1
        return if (rpcOk("scroll") { it.addProperty("direction", d); it.addProperty("distance", 240) })
            "已滚动($direction)" else "滚动失败$INJECT_FAILED_HINT"
    }

    /**
     * 回桌面（2026-09-25 真机修复）。
     *
     * 真机实测：向虚拟屏注入 HOME 之后，该屏**不再有前台窗口**，此后 `injectInputEvent` 一律返回
     * false —— tap/scroll/press_back 全部报 "inject failed"，模型只能反复重试直到放弃
     * （已验证：`createDisplay` → `startApp 设置` → `pressKey HOME` → `tap` 必失败；
     * 在 VD 上 `startApp 桌面` 后 `tap` 立即恢复 ok）。
     *
     * 因此"回到桌面"的完整语义 = 注入 HOME + **把系统桌面拉到该虚拟屏**，让桌面既可见也可点；
     * 桌面起不来时如实告知，避免模型无谓重试。
     */
    private suspend fun pressHome(): String {
        if (!rpcOk("pressKey") { it.addProperty("keyCode", HOME) }) return "回桌面失败$INJECT_FAILED_HINT"
        return if (startHomeOnDisplay()) "已回到桌面" else "已按下 HOME，但虚拟屏桌面未就绪（后续操作请先 open_app）"
    }

    /** 在虚拟屏上启动系统桌面（HOME 组件）。失败返回 false（不阻断，仅如实告知）。 */
    private suspend fun startHomeOnDisplay(): Boolean {
        val pm = appContext.packageManager
        val component = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            // Android 13+ 只有 ResolveInfoFlags 重载可用，显式声明类型避免重载歧义
            val info: ResolveInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            info?.let { ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToString() }
        }.getOrNull() ?: return false
        return runCatching {
            ensureDisplay()
            // startApp 成功返回 {message:…}（无 ok 字段）→ 用 call 判定，勿用 callOk
            bridge.call("startApp", JsonObject().apply {
                addProperty("packageName", component.substringBefore('/'))
                addProperty("component", component)
            })
            true
        }.getOrDefault(false)
    }

    // ---------- RPC 封装 ----------

    /**
     * 通道可用性：已连接时零额外往返（旧实现在每次工具调用前都 ping 一次）。
     * 失败时把 [ShellBridge.lastFailure] 的真实原因带进提示 —— 否则"版本过旧"会被笼统报成
     * "未连接"，把操作者引向检查 Root 模式/重推 APK 的错误方向。
     */
    private suspend fun ensureChannel() {
        if (bridge.ensureConnected()) return
        val reason = bridge.lastFailure()
        val hint = when {
            reason != null && reason.contains("鉴权") ->
                "$reason。"
            else ->
                "落盘守护进程未连接${reason?.let { "（$it）" }.orEmpty()}。" +
                    "请确认已开启 Root 模式（会自动拉起 app_process 守护进程）且 /data/local/tmp/fall-shell.apk 已推送到设备；" +
                    "或关闭 Root 模式改用前台无障碍执行；不要重复尝试同一工具。"
        }
        throw AutomationUnavailableException("后台静默（虚拟屏）不可用：$hint")
    }

    /** 发一个"成功即 ok"的注入类 RPC（守恒：契约见 [ShellBridge.callOk]）。 */
    private suspend fun rpcOk(method: String, fill: (JsonObject) -> Unit = {}): Boolean {
        ensureChannel()
        val params = JsonObject().apply { fill(this) }
        return try {
            bridge.callOk(method, params)
        } catch (e: ShellException) {
            if (markDisplayLostIfMissing(e.message)) false else throw unavailable(method, e)
        }
    }

    /** 截一帧；会话已被销毁（no display）返回 null 让调用方重建。 */
    private suspend fun captureFrameOnce(): ShellBridge.FrameData? {
        ensureDisplay()
        return try {
            bridge.captureFrame()
        } catch (e: ShellException) {
            if (markDisplayLostIfMissing(e.message)) null else throw unavailable("感知", e)
        }
    }

    /**
     * VD 会话懒创建：**只在失败时**抛异常。
     * 旧实现写成 `if (resultObj == null && error == null) throw`，于是"服务端返回 error"被当作成功，
     * 还把 displayReady 置为 true，后续所有工具都命中"no display"。
     */
    private suspend fun ensureDisplay() {
        if (synchronized(displayLock) { displayReady }) return
        ensureChannel()
        val resp = try {
            bridge.call("createDisplay")
        } catch (e: ShellException) {
            throw unavailable("createDisplay", e)
        }
        val displayId = resp.get("displayId")
        if (displayId == null || displayId.isJsonNull) {
            throw AutomationUnavailableException("createDisplay 失败：无 displayId 返回")
        }
        synchronized(displayLock) { displayReady = true }
    }

    /** 守护进程报"no display"（会话被销毁/守护进程重启）→ 清本地标记，返回是否命中。 */
    private fun markDisplayLostIfMissing(message: String?): Boolean {
        if (message == null || !message.contains("no display", ignoreCase = true)) return false
        synchronized(displayLock) { displayReady = false }
        return true
    }

    private fun unavailable(action: String, e: ShellException) = AutomationUnavailableException(
        "虚拟屏 $action 失败（守护进程异常）：${e.message}",
    )

    private companion object {
        const val BACK = 4
        const val HOME = 3
        const val ENTER = 66

        /**
         * 注入失败时给模型的统一解释。
         * 本 ROM 实测（2026-09-25 真机）：目标 display 上**没有可接收事件的窗口**时
         * `injectInputEvent` 一律返回 false，daemon 只能报 "inject failed"；
         * 最常见场景就是"刚回桌面/应用已退出"，此时应先 open_app 或先 get_ui_layout 确认界面。
         */
        const val INJECT_FAILED_HINT =
            "（虚拟屏当前没有可接收操作的窗口，可能刚回桌面或应用已退出：请先 open_app 或 get_ui_layout 确认界面）"

        /** record_note 落盘 JSON 序列化器。 */
        val noteGson = com.google.gson.Gson()

        /**
         * [已停用] SUBMIT_WORDS / HINT_WORDS 启发式词表（2026-09-19）
         * 原因：SUBMIT_WORDS 原来用于两处——OCR 感知猜"可点击/提交词"（toNode）与 input_text 后自动点提交按钮；
         * HINT_WORDS 用于 OCR 感知猜"输入框"。两类都属于替 AI 判断元素语义/替 AI 提交，
         * 已随 toNode 停用一并废止。被 OCR 识别出的"搜索"等词仍会作为普通文本返回，由 AI 自行判断点击。
         */
        // val SUBMIT_WORDS = setOf("搜索", "确定", "完成", "下一步", "立即搜索", "确认", "登录", "发送")
        // val HINT_WORDS = listOf("请输入", "搜索", "手机号", "账号", "密码", "邮箱", "验证码")
    }

    private fun string(obj: JsonObject, key: String, def: String = ""): String =
        if (obj.has(key) && !obj.get(key).isJsonNull) obj.get(key).asString else def

    private fun int(obj: JsonObject, key: String): Int =
        if (obj.has(key) && !obj.get(key).isJsonNull) obj.get(key).asInt else 0

    private fun long(obj: JsonObject, key: String, def: Long): Long =
        if (obj.has(key) && !obj.get(key).isJsonNull) obj.get(key).asLong else def
}

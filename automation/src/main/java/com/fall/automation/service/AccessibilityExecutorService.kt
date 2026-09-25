package com.fall.automation.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo

/** 屏幕 UI 节点（供 Agent 消费的紧凑结构）。 */
data class UiNode(
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
 * 前台自动化通道：标准无障碍服务。
 * 提供 Agent 所需的「感知（dump）+ 注入（点击/输入/滚动/返回）」；
 * 免 root、免 adb，App 进程内运行，UI 数据完整（不受"后台虚拟屏无 UiAutomation"限制）。
 */
class AccessibilityExecutorService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AccessibilityExecutorService? = null
            private set

        /** 前台链可用性检测：本无障碍服务是否已在系统设置中启用。 */
        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? AccessibilityManager ?: return false
            val cn = ComponentName(context.packageName, AccessibilityExecutorService::class.java.name)
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val si = info.resolveInfo?.serviceInfo ?: return@any false
                    si.packageName == cn.packageName && si.name == cn.className
                }
        }

        /** 无障碍组件完整名（settings put secure enabled_accessibility_services 用）。 */
        fun componentName(context: Context): String =
            "${context.packageName}/${AccessibilityExecutorService::class.java.name}"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** dump 当前窗口的可交互节点列表（最多 [maxNodes] 个）。 */
    fun dump(maxNodes: Int = 60): List<UiNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = mutableListOf<UiNode>()
        collectNodes(root, out, maxNodes)
        return out
    }

    private fun collectNodes(node: AccessibilityNodeInfo?, out: MutableList<UiNode>, max: Int) {
        if (node == null || out.size >= max) return
        try {
            if (node.isVisibleToUser) {
                val text = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                val contentDesc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                val editable = node.isEditable || node.className?.toString()?.contains("EditText") == true
                val clickable = node.isClickable
                if (clickable || editable || text != null || contentDesc != null) {
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    val screenW = resources?.displayMetrics?.widthPixels ?: 0
                    val screenH = resources?.displayMetrics?.heightPixels ?: 0
                    // 过滤"整屏根节点"噪音（如 content-desc=标题栏 占满全屏），避免误导模型点屏幕中心
                    val coversScreen = bounds.width() >= screenW * 0.9 && bounds.height() >= screenH * 0.9
                    if (bounds.width() > 0 && bounds.height() > 0 && !coversScreen) {
                        val nodeText = text ?: contentDesc ?: ""
                        // 去重：与上一条 text/坐标/标记 完全相同则跳过（部分应用父/子节点重复暴露）
                        val dedup = out.lastOrNull()?.let {
                            it.text == nodeText && it.x == bounds.left && it.y == bounds.top &&
                                it.w == bounds.width() && it.h == bounds.height() &&
                                it.clickable == clickable && it.editable == editable
                        } == true
                        if (!dedup) {
                            out.add(
                                UiNode(
                                    index = out.size,
                                    text = nodeText,
                                    x = bounds.left,
                                    y = bounds.top,
                                    w = bounds.width(),
                                    h = bounds.height(),
                                    clickable = clickable,
                                    editable = editable,
                                )
                            )
                        }
                    }
                }
            }
            for (i in 0 until node.childCount) {
                if (out.size >= max) break
                collectNodes(node.getChild(i), out, max)
            }
        } catch (ignored: Throwable) {
        }
    }

    /** 渲染给模型的紧凑布局文本。 */
    fun layoutText(nodes: List<UiNode> = dump()): String {
        if (nodes.isEmpty()) return "(当前屏幕无可交互元素)"
        return buildString {
            nodes.forEach { n ->
                append("[${n.index}] ")
                if (n.text.isNotBlank()) append(n.text)
                append(" (${n.x},${n.y} ${n.w}x${n.h})")
                when {
                    n.editable -> append(" [输入框]")
                    n.clickable -> append(" [可点击]")
                }
                append('\n')
            }
        }.trimEnd()
    }

    /** 按 index 点击（定位坐标后注入手势）。 */
    fun clickByIndex(index: Int): Boolean {
        val nodes = dump(index + 1)
        if (index < 0 || index >= nodes.size) return false
        val n = nodes[index]
        return clickAt(n.x + n.w / 2, n.y + n.h / 2)
    }

    /** 屏幕坐标点击（dispatchGesture）。 */
    fun clickAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * 向输入框输入文本（单次、零验证）：统一走「剪贴板粘贴一次」。
     * 不做 SET_TEXT / 生效验证 / 重试 / 自动点提交——输入是否成功由 AI 下一次 get_ui_layout 自行确认。
     * 背景：修复"输入两次"——旧实现依赖"生效验证"，而 a11y 树在 IME/自定义输入框下更新慢，
     * 验证读到旧值误判失败后会对已生效的粘贴再插一份（PASTE 是光标处插入而非替换），框里出现两遍文本；
     * 且验证本质是替 AI 判断结果，判断应归 AI。
     */
    fun inputText(text: String): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return "输入失败：无剪贴板服务"
        var pasteNode = findEditable()
            ?: return "输入失败：找不到输入框（请先点击输入框）"
        // 粘贴的必要前置：确保输入框已聚焦（未聚焦时点击一次）；焦点点击会重建 a11y 树，
        // 粘贴前刷新一次节点避免对 stale 节点注入——这是取"当前可注入节点"，不是验证写入结果。
        ensureFocused(pasteNode)
        SystemClock.sleep(400)
        pasteNode = findEditable() ?: pasteNode
        cm.setPrimaryClip(ClipData.newPlainText("fall", text))
        if (pasteNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            SystemClock.sleep(300) // 输入框稳定，便于模型下一步 get_ui_layout 直接读到结果
            return "已粘贴输入：$text"
        }
        return "输入失败：粘贴失败（请检查输入框是否聚焦，随后 get_ui_layout 确认界面）"
    }

    /**
     * [已停用] ACTION_SET_TEXT 路径与写入生效验证（2026-09-19）
     * 原因：输入统一改为「剪贴板粘贴一次、零验证」——是否写入由 AI 下一次 get_ui_layout 判断。
     * SET_TEXT 在 B 站等自定义输入框上会"假成功"（返回 true 但未写入），
     * 而"生效验证"读到的 a11y 树更新慢，验证旧值误判失败后又去粘贴追加，是此前"输入两次"的直接根因。
     * 若要恢复 SET_TEXT 优先，需连 inputShows 一并恢复，并确保验证不再驱动重试/追加。
     */
    // private fun performSetText(node: AccessibilityNodeInfo, text: String): Boolean = try {
    //     val bundle = Bundle().apply {
    //         putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    //     }
    //     node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    // } catch (t: Throwable) {
    //     false
    // }

    /**
     * [已停用] 输入框内容校验（判定"目标文本是否已写入"）（2026-09-19）
     * 原因：见 performSetText——校验依赖 a11y text 属性，B 站等自定义输入框空输入时 text 暴露的是 hint，
     * 粘贴后刷新又慢，导致"实际已写入但校验读旧值判失败"，进而触发一次多余的追加粘贴。
     * 校验本身属于替 AI 判断写入结果，已废止；结果校验交给 AI 的 get_ui_layout。
     */
    // private fun inputShows(node: AccessibilityNodeInfo, text: String): Boolean {
    //     val current = node.text?.toString()?.trim().orEmpty()
    //     if (current.isEmpty()) return false
    //     val hint = node.hintText?.toString()?.trim()
    //     if (current == hint) return false
    //     return current.contains(text) || text.contains(current)
    // }

    /** 输入框未聚焦时，按屏幕坐标注入一次点击以获取焦点（粘贴依赖焦点）。 */
    private fun ensureFocused(node: AccessibilityNodeInfo) {
        if (node.isFocused) return
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) clickAt(rect.centerX(), rect.centerY())
    }

    private fun findEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var result: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            try {
                if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
                    result = node
                    break
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let(queue::add)
                }
            } catch (ignored: Throwable) {
            }
        }
        return result
    }

    /** 滚动（手势滑动）。 direction: forward=向下滚动 / backward=向上。 */
    fun scroll(direction: String): Boolean {
        val dm = resources?.displayMetrics ?: return false
        val x = dm.widthPixels / 2
        val fromY = (dm.heightPixels * 0.7).toInt()
        val toY = if (direction == "forward") (dm.heightPixels * 0.3).toInt() else (dm.heightPixels * 0.9).toInt()
        val path = Path().apply {
            moveTo(x.toFloat(), fromY.toFloat())
            lineTo(x.toFloat(), toY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 200))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
}
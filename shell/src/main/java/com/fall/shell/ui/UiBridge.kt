package com.fall.shell.ui

import android.content.Context
import android.os.Looper
import android.util.SparseArray
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.fall.shell.core.Refl
import org.json.JSONArray
import org.json.JSONObject

/**
 * UI 感知（多级降级）：
 * 1. 反射 UiAutomation + getWindowsOnAllDisplays 按 displayId 取窗口（ShadowAuto 同款，
 *    但部分 ROM bootclasspath 不含 UiAutomation 类 → 自动降级）
 * 2. AccessibilityManager.getWindows()（Public API，shell 身份在多数系统可用）
 * 产出 `simple` 布局（targets/inputs + display-local 坐标）。
 */
class UiBridge(private val context: Context) {

    init {
        println("[fall-shell] UiBridge v1"); System.out.flush()
    }

    private var automation: Any? = null

    /** 反射构造并连接 UiAutomation；类不存在则返回 null（走降级通道）。 */
    private fun ensure(): Any? {
        automation?.let { return it }
        val clazz = runCatching { Class.forName("android.accessibilityservice.UiAutomation") }.getOrNull() ?: return null
        val ctor = runCatching {
            clazz.getDeclaredConstructor(Looper::class.java).apply { isAccessible = true }
        }.getOrNull() ?: return null
        val instance = ctor.newInstance(Looper.getMainLooper())
        val connect = Refl.methodOf(clazz, "connect") ?: Refl.anyMethod(clazz, "connect", 0)
        Refl.invoke(instance, connect)
        automation = instance
        return instance
    }

    /** 按 displayId dump simple 布局。 */
    fun dump(displayId: Int): JSONObject {
        val root = JSONObject()
        root.put("displayId", displayId)
        root.put("mode", "simple")
        root.put("coordinateSpace", "display-local")

        val inputs = JSONArray()
        val targets = JSONArray()
        val visited = java.util.HashSet<Long>()
        var index = 0

        fun visit(node: AccessibilityNodeInfo?) {
            node ?: return
            try {
                if (!node.isVisibleToUser) return
                if (!visited.add(node.sourceNodeId())) return

                val clickable = node.isClickable
                val editable = node.isEditable || node.classNameContains("EditText")
                val text = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                val desc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }

                if (clickable || editable || text != null || desc != null) {
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        val item = JSONObject()
                            .put("index", index)
                            .put("text", text ?: desc ?: "")
                            .put("editable", editable)
                            .put("clickable", clickable)
                            .put("x", bounds.left)
                            .put("y", bounds.top)
                            .put("w", bounds.width())
                            .put("h", bounds.height())
                        if (editable) inputs.put(item) else targets.put(item)
                        index++
                    }
                }

                for (i in 0 until node.childCount) {
                    visit(node.getChild(i))
                }
                runCatching { node.recycle() }
            } catch (ignored: Throwable) {
            }
        }

        val m = ensure()
        val windows = if (m != null) {
            windowsOnDisplay(displayId, m)
        } else {
            fallbackWindows()
        }
        windows.forEach { w ->
            val rootNode = w?.root
            if (rootNode != null) visit(rootNode)
        }

        root.put("inputs", inputs)
        root.put("targets", targets)
        return root
    }

    /** 全窗口按 displayId 筛选；老版本退回 `getWindows()`。 */
    private fun windowsOnDisplay(displayId: Int, automation: Any): List<AccessibilityWindowInfo?> {
        val clazz = automation.javaClass
        val allDisplays = Refl.invoke(automation, Refl.methodOf(clazz, "getWindowsOnAllDisplays"))
        if (allDisplays is SparseArray<*>) {
            @Suppress("UNCHECKED_CAST")
            (allDisplays[displayId] as? List<AccessibilityWindowInfo?>)?.let { return it }
        }
        val wins = Refl.invoke(automation, Refl.methodOf(clazz, "getWindows"))
        @Suppress("UNCHECKED_CAST")
        return (wins as? List<AccessibilityWindowInfo?>) ?: emptyList()
    }

    /** 降级通道：Public API 获取窗口（无 UiAutomation 的系统，尽力而为）。AccessibilityManager 为 @SystemApi，
     * 编译不可见，走反射。 */
    private fun fallbackWindows(): List<AccessibilityWindowInfo?> {
        val service = try {
            context.getSystemService(Context.ACCESSIBILITY_SERVICE)
        } catch (t: Throwable) {
            null
        } ?: return emptyList()
        val getWindows = Refl.anyMethod(service.javaClass, "getWindows", 0) ?: return emptyList()
        val windows = try {
            Refl.invoke(service, getWindows) as? List<*>
        } catch (t: Throwable) {
            null
        } ?: return emptyList()
        return windows.mapNotNull { it as? AccessibilityWindowInfo }
    }

    /**
     * adb 通道感知：执行系统自带 `uiautomator dump` 抓取**主屏** UI 树并解析为
     * 与 [layoutText] 同格式的紧凑文本（无需无障碍权限；仅主屏、耗时约 1s）。
     */
    fun dumpMainScreenByUiautomator(): String {
        val dumpFile = "/data/local/tmp/fall_um.xml"
        val p = ProcessBuilder("sh", "-c", "uiautomator dump $dumpFile").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        val file = java.io.File(dumpFile)
        if (!file.exists()) return "(uiautomator dump 失败：文件未生成)"
        val text = runCatching { file.readText() }.getOrNull()
        if (text.isNullOrBlank()) return "(uiautomator dump 为空)"
        return parseUiAutomatorXml(text)
    }

    private fun parseUiAutomatorXml(xml: String): String {
        val out = StringBuilder()
        var index = 0
        var text: String? = null
        var clickable = false
        var bounds = ""
        var editable = false
        var closingSelf = false

        // uiautomator dump 常把同一节点输出两遍（父/子结构重复），去重
        var lastBody: String? = null

        fun flush() {
            if (text == null && !clickable && !editable) return
            if (bounds.isBlank()) return
            val m = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""").find(bounds) ?: return
            val (x, y, w, h) = m.destructured
            val body = buildString {
                append("${text.orEmpty()} ($x,$y ${w.toInt() - x.toInt()}x${h.toInt() - y.toInt()})")
                when {
                    editable -> append(" [输入框]")
                    clickable -> append(" [可点击]")
                }
            }
            if (body == lastBody) return
            lastBody = body
            out.append("[${index++}] $body").append('\n')
        }

        val r = Regex("""<node[^>]*/?>""")
        r.findAll(xml).forEach { m ->
            val tag = m.value
            if (!tag.startsWith("<node")) return@forEach
            // 上一节点结束
            flush()
            text = null; clickable = false; editable = false; bounds = ""; closingSelf = false
            text = Regex("""text="([^"]*)"""").find(tag)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() && it != "null" }
            clickable = Regex("""clickable="true"""").containsMatchIn(tag)
            editable = Regex("""editable="true"""").containsMatchIn(tag) ||
                Regex("""class="[^"]*EditText"""").containsMatchIn(tag)
            bounds = Regex("""bounds="([^"]*)"""").find(tag)?.groupValues?.get(1).orEmpty()
            closingSelf = tag.trimEnd().endsWith("/>")
            if (closingSelf) flush()
        }
        flush()
        return out.toString().trimEnd().ifEmpty { "(主屏无可交互元素)" }
    }
}

private fun AccessibilityNodeInfo.sourceNodeId(): Long =
    runCatching {
        val field = AccessibilityNodeInfo::class.java.getDeclaredField("mSourceNodeId").apply { isAccessible = true }
        field.getLong(this)
    }.getOrDefault(0L)

private fun AccessibilityNodeInfo.classNameContains(part: String): Boolean =
    runCatching {
        val field = AccessibilityNodeInfo::class.java.getDeclaredField("mClassName").apply { isAccessible = true }
        (field.get(this)?.toString() ?: "").contains(part)
    }.getOrDefault(false)
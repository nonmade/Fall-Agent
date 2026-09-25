package com.fall.shell.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Point
import android.view.Display
import android.view.KeyEvent

/**
 * 主屏（默认 display 0）注入与剪贴板桥。
 *
 * 背景：本 ROM bootclasspath 不含 UiAutomation，感知走 `uiautomator dump`（主屏）；
 * 注入优先反射 [InputBridge]（shell uid 持有 INJECT_EVENTS），失败降级到 `input` 系统命令。
 *
 * 文本输入（兼容中文）：应用内剪贴板 + KEYCODE_PASTE；纯 ASCII 可直走 `input text`。
 */
class MainScreenBridge(private val context: Context) {

    private val inject = InputBridge()

    private val displaySize: Point
        get() = runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val p = Point()
            dm.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(p)
            p
        }.getOrDefault(Point(720, 1280))

    /** 主屏点击（坐标 display-local，与 uiautomator dump 坐标系一致）。 */
    fun tap(x: Int, y: Int): Boolean =
        if (inject.tap(0, x, y)) true else shellCmd("input tap $x $y")

    /** 主屏滑动。 */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 120): Boolean =
        if (inject.swipe(0, x1, y1, x2, y2, durationMs)) true
        else shellCmd("input swipe $x1 $y1 $x2 $y2 $durationMs")

    /** 主屏滚动。direction: 正=向下滚（内容上移），负=向上滚。 */
    fun scroll(direction: Int, distance: Int = 240): Boolean {
        val x = displaySize.x / 2
        val fromY = (displaySize.y * 0.7).toInt()
        val toY = if (direction > 0) (fromY - distance).coerceAtLeast(0)
        else (fromY + distance).coerceAtMost(displaySize.y)
        return swipe(x, fromY, x, toY, 200)
    }

    /** 主屏按键。 */
    fun key(keyCode: Int): Boolean =
        if (inject.key(0, keyCode)) true else shellCmd("input keyevent $keyCode")

    /** 写入系统剪贴板（shell 身份，com.android.shell 免受限）。 */
    fun setClipboard(text: String): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("fall", text))
        true
    }.getOrDefault(false)

    /**
     * 向当前聚焦输入框输入文本：
     * - 纯 ASCII 优先 `input text`（失败则降级剪贴板粘贴）；
     * - 含中文时剪贴板 + KEYCODE_PASTE（IME 无关，主流输入框均响应）。
     * 执行前需已点击输入框使其聚焦（由 Agent 的 tap_target 完成）。
     */
    fun inputTextToFocused(text: String): String {
        if (text.isBlank()) return "输入失败：文本为空"
        if (text.all { it.code < 128 }) {
            if (shellCmd("input text ${shellQuote(text)}")) return "已输入：$text"
        }
        if (!setClipboard(text)) return "输入失败：剪贴板不可用"
        return if (key(KeyEvent.KEYCODE_PASTE)) "已输入：$text"
        else "输入失败：粘贴失败（请检查输入框是否聚焦）"
    }

    /**
     * 把任意文本安全地作为**单个 shell 参数**传递：整体单引号包裹 + 内部单引号转义（`'\''`），
     * 再把空格换成 `input text` 约定的 `%s`。
     *
     * 安全前提（2026-09-25）：文本来自模型输出（input_text 工具参数），而本方法最终由
     * 守护进程以 shell/root 身份执行 `sh -c`。旧实现只转义空格与双引号且不加引号包裹，
     * `;` `$` `` ` `` `|` `&` 等会被 sh 解释 → 模型输出即可在 uid 0 下执行任意命令（命令注入）。
     * 单引号包裹后，除 `'` 之外的所有字符（含 `$` 反引号换行）都是字面量。
     */
    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''").replace(" ", "%s") + "'"

    private fun shellCmd(cmd: String): Boolean = runCatching {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        p.exitValue() == 0
    }.getOrDefault(false)

    companion object {
        const val KEY_ENTER = KeyEvent.KEYCODE_ENTER // 66
        const val SCROLL_DOWN = 1
        const val SCROLL_UP = -1
    }
}
package com.fall.shell

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Base64
import android.view.KeyEvent
import com.fall.shell.capture.MainFrameCapture
import com.fall.shell.core.ShellContext
import com.fall.shell.display.FrameProvider
import com.fall.shell.display.ImageReaderFrameProvider
import com.fall.shell.display.VirtualDisplaySession
import com.fall.shell.input.InputBridge
import com.fall.shell.input.MainScreenBridge
import com.fall.shell.rpc.JsonRpc
import com.fall.shell.rpc.JsonRpcServer
import com.fall.shell.stream.VdStreamServer
import com.fall.shell.ui.UiBridge
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import java.io.ByteArrayOutputStream

/**
 * 装配层：持有 context / 虚拟屏会话 / 感知 / 注入，并把 JSON-RPC 方法路由到具体能力。
 *
 * 方法：
 * ping / status / createDisplay / destroyDisplay / startApp / stopApp /
 * dumpUi / tap / swipe / scroll / pressKey / back / home
 * + 主屏通道（本 ROM 走 uiautomator dump 感知）：dumpMainUi / tapMain / swipeMain /
 *   scrollMain / backMain / homeMain / enterMain / keyMain / setClipboard / inputTextMain
 */
class ShellServer(
    private val port: Int,
    /** RPC 握手 token（见 `JsonRpcServer` 的鉴权说明）；null = 开发期不鉴权。 */
    private val token: String? = null,
    /** `hello` 免鉴权自报内容（构建标记/协议版本/APK 哈希，见 `DaemonProtocol`）。 */
    private val helloInfo: JSONObject = JSONObject(),
) {

    private var context: Context? = null

    /** 当前虚拟屏会话。**多客户端并发**（执行器 / 预览页 / ADB 通道各持一个 ShellBridge）：
     *  会话引用与"创建/销毁"必须原子，否则一方销毁后另一方拿到已释放会话或拿到 null。 */
    private val displayLock = Any()

    @Volatile
    private var display: VirtualDisplaySession? = null

    private var appRunning: Boolean = false

    private val input = InputBridge()
    private val ui: UiBridge by lazy { UiBridge(checkNotNull(context)) }
    private val main: MainScreenBridge by lazy { MainScreenBridge(checkNotNull(context)) }

    /** 送显链路（台阶 2/3）：独立端口推流，生命周期跟 RPC captureStreamStart/Stop。 */
    @Volatile
    private var streamServer: VdStreamServer? = null

    /** 当前推流格式（格式与既有会话不一致时需停旧开新，避免客户端收到错格式字节流）。 */
    @Volatile
    private var streamFormat: VdStreamServer.StreamFormat? = null

    fun run() {
        context = ShellContext.create()
        println("[fall-shell] context ready")
        JsonRpcServer(port, token, helloInfo, ::dispatch).run()
    }

    private fun dispatch(id: Any?, method: String, params: JSONObject): JSONObject {
        println("[fall-shell] rpc << $method")
        return when (method) {
            // 结果形态统一：所有成功返回都是 JSON 对象（旧版 ping 返回字符串 "pong"）
            "ping" -> JsonRpc.result(id, JSONObject().put("ok", true).put("pong", true))
            "status" -> statusJson(id)
            "createDisplay" -> createDisplay(id, params)
            "destroyDisplay" -> destroyDisplay(id)
            "mainMetrics" -> mainMetrics(id)
            "startApp" -> startApp(id, params)
            "stopApp" -> stopApp(id)
            "dumpUi" -> dumpUi(id)
            "dumpMainUi" -> dumpMainUi(id)
            "debugClass" -> debugClass(id, params)
            "tap" -> actionOnDisplay(id) { d -> input.tap(d, params.optInt("x"), params.optInt("y")) }
            "longPress" -> actionOnDisplay(id) { d ->
                input.longPress(d, params.optInt("x"), params.optInt("y"), params.optLong("durationMs", 600))
            }
            "swipe" -> actionOnDisplay(id) { d ->
                input.swipe(
                    d,
                    params.optInt("fromX"), params.optInt("fromY"),
                    params.optInt("toX"), params.optInt("toY"),
                    params.optLong("durationMs", 120),
                )
            }
            "scroll" -> actionOnDisplay(id) { d ->
                input.scroll(d, params.optInt("direction", InputBridge.SCROLL_DOWN), params.optInt("distance", 240))
            }
            "pressKey" -> actionOnDisplay(id) { d -> input.key(d, params.optInt("keyCode", InputBridge.ENTER)) }
            "back" -> actionOnDisplay(id) { d -> input.key(d, InputBridge.BACK) }
            "home" -> actionOnDisplay(id) { d -> input.key(d, InputBridge.HOME) }
            "tapMain" -> actionOnMain(id) { main.tap(params.optInt("x"), params.optInt("y")) }
            "swipeMain" -> actionOnMain(id) {
                main.swipe(
                    params.optInt("fromX"), params.optInt("fromY"),
                    params.optInt("toX"), params.optInt("toY"),
                    params.optLong("durationMs", 200),
                )
            }
            "scrollMain" -> actionOnMain(id) {
                main.scroll(params.optInt("direction", MainScreenBridge.SCROLL_DOWN), params.optInt("distance", 240))
            }
            "backMain" -> actionOnMain(id) { main.key(InputBridge.BACK) }
            "homeMain" -> actionOnMain(id) { main.key(InputBridge.HOME) }
            "enterMain" -> actionOnMain(id) { main.key(MainScreenBridge.KEY_ENTER) }
            "keyMain" -> actionOnMain(id) { main.key(params.optInt("keyCode")) }
            "setClipboard" -> JsonRpc.result(
                id,
                JSONObject().put("ok", main.setClipboard(params.optString("text"))),
            )
            "inputTextMain" -> JsonRpc.result(
                id,
                JSONObject().put("message", main.inputTextToFocused(params.optString("text"))),
            )
            "captureFrame" -> captureFrame(id, params)
            "captureMainFrame" -> captureMainFrame(id, params)
            "captureStreamStart" -> captureStreamStart(id, params)
            "captureStreamStop" -> captureStreamStop(id)
            "inputText" -> inputTextDisplay(id, params)
            "vdInfo" -> vdInfo(id)
            "vdWake" -> vdWake(id)
            "vdResize" -> vdResize(id, params)
            "vdFreshRate" -> vdFreshRate(id, params)
            "setPanelRefresh" -> setPanelRefresh(id, params)
            "encProbe" -> encProbe(id)
            "encProbe2" -> encProbe2(id)
            "encProbe3" -> encProbe3(id)
            "encProbe5" -> encProbe5(id)
            "encProbe6" -> encProbe6(id, params)
            "encProbe7" -> encProbe7(id, params)
            "vdCfgProbe" -> vdCfgProbe(id)
            "mirrorProbe" -> mirrorProbe(id)
            "sfProbe" -> sfProbe(id, params)
            else -> JsonRpc.error(id, -32601, "unknown method: $method")
        }
    }

    /** 取当前会话；不存在时报错（与创建/销毁原子，避免读到中间态）。 */
    private fun requireDisplay(): VirtualDisplaySession = synchronized(displayLock) {
        display ?: throw IllegalStateException("no display, call createDisplay first")
    }

    private fun statusJson(id: Any?): JSONObject = JsonRpc.result(
        id,
        JSONObject()
            .put("running", true)
            .put("displayId", display?.displayId ?: JSONObject.NULL)
            .put("appRunning", appRunning),
    )

    private fun createDisplay(id: Any?, params: JSONObject): JSONObject {
        // 阶段 1：width/height/dpi 缺省或 <=0 时由 shell 解析实体屏真实值（root 增强模式）
        // refreshRate 缺省 0 → 自动挑面板支持的最高 ≤120Hz（原生分辨率 + 高刷）
        val d = synchronized(displayLock) {
            display?.destroy()
            VirtualDisplaySession.create(
                checkNotNull(context),
                params.optInt("width", 0),
                params.optInt("height", 0),
                params.optInt("dpi", 0),
                params.optDouble("refreshRate", 0.0).toFloat(),
            ).also {
                display = it
                appRunning = false
            }
        }
        return JsonRpc.result(
            id,
            JSONObject()
                .put("displayId", d.displayId)
                .put("width", d.width)
                .put("height", d.height)
                .put("dpi", d.dpi)
                .put("refreshRate", d.refreshRate),
        )
    }

    /** 实体屏规格（app 端日志/诊断用）。 */
    private fun mainMetrics(id: Any?): JSONObject {
        val spec = VirtualDisplaySession.resolveRealSpec(checkNotNull(context))
        return JsonRpc.result(
            id,
            JSONObject().put("width", spec.width).put("height", spec.height).put("dpi", spec.dpi),
        )
    }

    private fun destroyDisplay(id: Any?): JSONObject {
        synchronized(displayLock) {
            display?.destroy()
            display = null
            appRunning = false
        }
        return okResult(id, "destroyed")
    }

    private fun startApp(id: Any?, params: JSONObject): JSONObject {
        val pkg = params.optString("packageName").takeIf { it.isNotBlank() }
            ?: return JsonRpc.error(id, -32602, "packageName required")
        val d = requireDisplay()
        // 显式组件（pkg/cls）优先：shell uid 下按包名起隐式 Intent 解析不到第三方 App
        val component = params.optString("component").takeIf { it.isNotBlank() }
        val message = d.startApp(pkg, component)
        if (message.contains("Error")) {
            return JsonRpc.error(id, -32000, message)
        }
        appRunning = true
        return JsonRpc.result(id, JSONObject().put("message", message))
    }

    private fun stopApp(id: Any?): JSONObject {
        if (appRunning) {
            val d = requireDisplay()
            runCatching { d.startApp("com.android.launcher3") }.getOrNull()
            appRunning = false
        }
        return okResult(id, "stopped")
    }

    private fun dumpUi(id: Any?): JSONObject {
        val d = requireDisplay()
        return JsonRpc.result(id, ui.dump(d.displayId))
    }

    /** adb 通道感知：uiautomator dump 主屏（无需无障碍权限）。 */
    private fun dumpMainUi(id: Any?): JSONObject =
        JsonRpc.result(id, JSONObject().put("layout", ui.dumpMainScreenByUiautomator()))

    /** 截帧回调。防并发：captureReader 是本会话唯一消费方，RPC 串行分发，但预览轮询与 OCR 可交错到达，加锁双保险。 */
    private val frameLock = Any()

    /** 取帧提供方（台阶 0/1 后支持 GPU 直读；默认 ImageReader，语义不变零回归）。 */
    private val frameProvider: FrameProvider?
        get() = display?.let { ImageReaderFrameProvider(it) }

    /**
     * captureFrame：截取虚拟屏当前帧 → JPEG(base64)。
     * params: quality(默认 70)、scale(0.5 则先缩放再压缩，减小行体积)。
     * 返回: { width, height, data(b64) }。
     * 取帧走 [FrameProvider] 抽象（默认 ImageReader；GPU 直读 spike 通过后可替换实现，协议不变）。
     */
    private fun captureFrame(id: Any?, params: JSONObject): JSONObject {
        val provider = frameProvider ?: return JsonRpc.error(id, -32000, "no display, call createDisplay first")
        // 取**独立副本**：抓帧缓冲已改为复用，而这里要在锁外做 JPEG 压缩，
        // 直接持复用缓冲会被推流路径的下一帧覆盖（AI 截图花屏/叠影）。
        val bmp = synchronized(frameLock) { provider.grabCopy() }
            ?: return JsonRpc.error(id, -32000, "capture failed: no frame")
        val quality = params.optInt("quality", 70).coerceIn(30, 100)
        val scale = params.optDouble("scale", 1.0).toFloat().coerceIn(0.25f, 1f)
        val out = if (scale >= 1f) bmp
        else Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        val stream = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        if (out !== bmp) out.recycle()
        val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        val result = JsonRpc.result(
            id,
            JSONObject().put("width", bmp.width).put("height", bmp.height).put("data", b64),
        )
        bmp.recycle() // 副本由本方法独占，压完即回收（避免每帧 13MB 泄漏）
        return result
    }

    /**
     * captureMainFrame：截取物理主屏当前帧（阶段 2 spike 方案 A，screencap 子进程）。
     * params: scale(默认 1)、quality(默认 70)。返回 { width, height, data(b64) }。
     */
    private fun captureMainFrame(id: Any?, params: JSONObject): JSONObject {
        val triple = MainFrameCapture.grab(
            scale = params.optDouble("scale", 1.0).toFloat(),
            quality = params.optInt("quality", 70),
        ) ?: return JsonRpc.error(id, -32000, "capture failed: no frame")
        return JsonRpc.result(
            id,
            JSONObject()
                .put("width", triple.first)
                .put("height", triple.second)
                .put("data", triple.third),
        )
    }

    /**
     * inputText：向虚拟屏当前聚焦输入框输入文本（剪贴板 + KEYCODE_PASTE 定向 display）。
     * 与主屏通道同策略，仅注入目标改为虚拟屏 displayId。
     */
    private fun inputTextDisplay(id: Any?, params: JSONObject): JSONObject {
        val text = params.optString("text")
        if (text.isBlank()) return JsonRpc.error(id, -32602, "text required")
        val d = requireDisplay()
        if (!main.setClipboard(text)) return JsonRpc.error(id, -32000, "clipboard unavailable")
        val pasted = input.key(d.displayId, KeyEvent.KEYCODE_PASTE)
        return JsonRpc.result(id, JSONObject().put("message", if (pasted) "已输入：$text" else "输入失败：粘贴失败"))
    }

    /**
     * captureStreamStart：启动送显推流（豆包式纯虚拟屏 台阶 2/3）。
     * params: format("jpeg"|"h264"，默认 jpeg)、port(可选，默认 43111)。
     * 返回: { ok, port }。重复启动返回既有会话的端口。
     */
    private fun captureStreamStart(id: Any?, params: JSONObject): JSONObject {
        val format = when (params.optString("format", "jpeg")) {
            "h264" -> VdStreamServer.StreamFormat.H264
            else -> VdStreamServer.StreamFormat.JPEG
        }
        val existing = streamServer
        if (existing != null && streamFormat == format) {
            return JsonRpc.result(id, JSONObject().put("ok", true).put("port", existing.port).put("format", format.name))
        }
        // 格式变化（如 H.264 客户端连接失败后降级 JPEG）→ 停旧流开新流，避免错格式字节流
        if (existing != null) {
            runCatching { existing.close() }
            streamServer = null
            streamFormat = null
        }
        val provider = frameProvider ?: return JsonRpc.error(id, -32000, "no display, call createDisplay first")
        val port = params.optInt("port", 43111)
        // 镜像直喂 0-copy：把源 VD displayId/dpi 与 DisplayManager 传给流（h264 走 VirtualDisplayConfig 镜像）
        val src = display
        // mirror=false 强制走 EGL 上传路径（对比 0-copy 镜像的排障/验证开关）
        val useMirror = params.optBoolean("mirror", true)
        // 送显帧率：固定按 VD 请求刷新率（原生分辨率 + 120Hz，不做面板降档节流），
        // fps 参数可显式覆盖（诊断用）
        val forcedFps = params.optDouble("fps", 0.0).toFloat()
        val streamHz = (if (forcedFps > 0f) forcedFps else src?.refreshRate
            ?: VirtualDisplaySession.DEFAULT_REFRESH_RATE).coerceIn(30f, 120f)
        println("[fall-shell] stream refreshHz=$streamHz (vd=${src?.refreshRate})")
        val server = VdStreamServer(
            provider,
            port,
            mirrorDisplayId = if (useMirror) (src?.displayId ?: -1) else -1,
            mirrorDpi = src?.dpi ?: 320,
            vdDm = context?.getSystemService(android.content.Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager,
            refreshRateHz = streamHz,
        )
        return try {
            server.start(format)
            streamServer = server
            streamFormat = format
            JsonRpc.result(id, JSONObject().put("ok", true).put("port", port).put("format", format.name))
        } catch (e: Throwable) {
            runCatching { server.close() }
            JsonRpc.error(id, -32000, "captureStreamStart failed: ${e.message}")
        }
    }

    /** captureStreamStop：停流并释放编码器/socket（预览关闭即停，省电）。 */
    private fun captureStreamStop(id: Any?): JSONObject {
        runCatching { streamServer?.close() }
        streamServer = null
        streamFormat = null
        return okResult(id, "stopped")
    }

    /** vdInfo（诊断）：返回 VD 旋转 / 真实尺寸 / 捕获缓冲尺寸 / resize 失败标记。 */
    private fun vdInfo(id: Any?): JSONObject {
        val d = display
        val m = android.util.DisplayMetrics()
        d?.rawDisplay?.let { runCatching { it.getRealMetrics(m) } }
        return JsonRpc.result(
            id,
            JSONObject()
                .put("displayId", if (d != null) d.displayId else JSONObject.NULL)
                .put("rotation", d?.rotation ?: -1)
                .put("realMetrics", if (m.widthPixels > 0) "${m.widthPixels}x${m.heightPixels}" else "n/a")
                .put("capSize", if (d != null) "${d.captureWidth}x${d.captureHeight}" else "n/a")
                .put("logicalSize", if (d != null) "${d.width}x${d.height}" else "n/a")
                .put("refreshRate", d?.actualRefreshRate ?: -1f)
                .put("requestedRefreshRate", d?.refreshRate ?: -1f)
                .put("freshAgoMs", d?.freshAgoMs ?: -1L)
                .put("resizeFailed", d?.isResizeFailed ?: false)
                .put("appRunning", appRunning),
        )
    }

    /**
     * vdFreshRate（诊断）：紧循环抓帧 [params.durationMs] 毫秒，统计**真实新帧**速率 →
     * 反映虚拟屏实际合成节奏（面板 60Hz 牵引时约 60，120Hz 时约 120），
     * 用于验证「原生分辨率 + 120Hz」是否真正落地。
     */
    private fun vdFreshRate(id: Any?, params: JSONObject): JSONObject {
        val d = display ?: return JsonRpc.error(id, -32000, "no display")
        val durationMs = params.optLong("durationMs", 2000).coerceIn(300, 8000)
        // 先抓一帧建立基准（保证 subsequent 抓帧的「新帧」判定有意义）
        synchronized(frameLock) { d.grabBitmapOrLast() }
        var fresh = 0
        var total = 0
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < durationMs) {
            synchronized(frameLock) { d.grabBitmap() }
            total++
            if (d.grabWasFresh) fresh++
        }
        val spanMs = (System.currentTimeMillis() - t0).coerceAtLeast(1)
        val fps = fresh * 1000.0 / spanMs
        return JsonRpc.result(
            id,
            JSONObject()
                .put("freshFrames", fresh)
                .put("totalGrabs", total)
                .put("spanMs", spanMs)
                .put("freshFps", (fps * 10).roundToInt() / 10.0)
                .put("vdRefreshRate", d.actualRefreshRate)
                .put("requestedRefreshRate", d.refreshRate),
        )
    }

    /**
     * setPanelRefresh（root，诊断/高刷增强）：设置实体主屏的「用户偏好刷新率」，
     * 从而抬高虚拟屏合成节奏与预览页帧率上限（预期：VD 模式 120Hz + 面板 120Hz → 实测 ~120fps）。
     *
     * 走 `cmd display set-user-preferred-display-mode w h hz`（system_server 侧会写 Settings.Global，
     * **shell uid 会被 SettingsProvider 拒绝**，只有 root 身份（本守护进程）可通过）。
     * params: hz(默认 120)、clear=true 清除偏好回到系统自动。
     */
    private fun setPanelRefresh(id: Any?, params: JSONObject): JSONObject {
        val spec = VirtualDisplaySession.resolveRealSpec(checkNotNull(context))
        val hz = params.optDouble("hz", 120.0)
        val cmd = if (params.optBoolean("clear", false) || hz <= 0) {
            "cmd display clear-user-preferred-display-mode"
        } else {
            "cmd display set-user-preferred-display-mode ${spec.width} ${spec.height} $hz"
        }
        val out = runCatching {
            val p = ProcessBuilder("sh", "-c", "$cmd 2>&1").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            text
        }.getOrDefault("exec failed")
        val dm = context?.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
        val now = dm?.displays?.firstOrNull { it.displayId == android.view.Display.DEFAULT_DISPLAY }?.refreshRate ?: -1f
        println("[fall-shell] setPanelRefresh cmd=$cmd -> panelHz=$now out=${out.take(120)}")
        System.out.flush()
        return JsonRpc.result(
            id,
            JSONObject().put("cmd", cmd).put("output", out.trim().take(200)).put("panelHz", now),
        )
    }

    /** vdWake（诊断/修复）：强制唤醒 VD 显示（休眠期 SF 停投帧 → capture 全程 no frame）。 */
    private fun vdWake(id: Any?): JSONObject {
        val d = display
        if (d == null) return JsonRpc.error(id, -32000, "no display")
        val ok = d.wakeDisplay()
        return JsonRpc.result(id, JSONObject().put("ok", ok))
    }

    /** vdResize（诊断）：强制调整 VD 逻辑尺寸 + 捕获缓冲（横屏适配验证用）。 */
    private fun vdResize(id: Any?, params: JSONObject): JSONObject {
        val d = display
        if (d == null) return JsonRpc.error(id, -32000, "no display")
        val w = params.optInt("width")
        val h = params.optInt("height")
        val ok = d.forceResize(w, h)
        return JsonRpc.result(
            id,
            JSONObject()
                .put("ok", ok)
                .put("size", if (d != null) "${d.captureWidth}x${d.captureHeight}" else "n/a")
                .put("logical", if (d != null) "${d.width}x${d.height}" else "n/a"),
        )
    }

    /**
     * encProbe2（诊断）：端到端实时链路验证。
     * 在守护进程内：起真实 h264 推流（VdStreamServer 真实会话）→ 自连收流（真实帧协议）→
     * MediaCodec 硬解 → Surface 采样亮度，判定"编→流→解"全链路是否产出可见画面。
     */
    private fun encProbe2(id: Any?): JSONObject {
        val out = JSONObject()
        val d = display
        if (d == null) return JsonRpc.error(id, -32000, "no display")
        val probePort = 43121
        try {
            val provider = ImageReaderFrameProvider(d)
            val server = VdStreamServer(provider, probePort)
            server.start(VdStreamServer.StreamFormat.H264)
            val frames = ArrayList<ByteArray>()
            Thread({
                try {
                    // 自连与 accept 有竞态：重试连接
                    var sock: java.net.Socket? = null
                    val t0 = System.currentTimeMillis()
                    while (System.currentTimeMillis() - t0 < 6000) {
                        sock = runCatching { java.net.Socket("127.0.0.1", probePort) }.getOrNull()
                        if (sock != null) break
                        Thread.sleep(100)
                    }
                    val s = sock ?: throw IllegalStateException("connect failed")
                    s.use { conn ->
                        val input = conn.getInputStream()
                        val dump = java.io.BufferedOutputStream(java.io.FileOutputStream("/data/local/tmp/probe2.bin"))
                        // 首帧 config（type=0）：读 [4B len][1B type][payload]
                        val lenHead = ByteArray(4)
                        readFull(input, lenHead)
                        val cfgType = input.read()
                        val cfgLen = java.nio.ByteBuffer.wrap(lenHead).int - 1
                        if (cfgLen in 1..65535) {
                            val cfg = ByteArray(cfgLen)
                            readFull(input, cfg)
                            dump.write(lenHead); dump.write(cfgType); dump.write(cfg); dump.flush()
                        }
                        // 收集 40 帧或 10s
                        val deadline = System.currentTimeMillis() + 12_000
                        while (System.currentTimeMillis() < deadline && frames.size < 40) {
                            val head = ByteArray(4)
                            if (!readFull(input, head)) break
                            val len = java.nio.ByteBuffer.wrap(head).int - 1
                            if (len <= 0 || len > 16 * 1024 * 1024) {
                                println("[fall-shell] encProbe2 bad len=$len head=${head.joinToString(" ") { "%02X".format(it) }}")
                                break
                            }
                            val type = input.read()
                            if (type < 0) break
                            val body = ByteArray(len)
                            if (!readFull(input, body)) break
                            dump.write(head); dump.write(type); dump.write(body)
                            if (frames.isEmpty()) println("[fall-shell] encProbe2 firstFrame type=$type size=$len prefix=${body.take(8).joinToString(" ") { "%02X".format(it) }}")
                            dump.flush()
                            if (type == 1) frames.add(body)
                        }
                        dump.flush(); runCatching { dump.close() }
                    }
                } catch (e: Throwable) {
                    println("[fall-shell] encProbe2 client: ${e.message}")
                }
            }, "encProbe2-client").apply { isDaemon = true; start() }
            try {
                Thread.sleep(13_000)
            } catch (e: InterruptedException) {
            }
            server.close()

            out.put("recvFrames", frames.size)
            // SPS/PPS/IDR 检出
            var sps = 0
            var idr = 0
            for (b in frames) {
                if (containsNal(b, 0x67)) sps++
                if (containsNal(b, 0x65)) idr++
            }
            out.put("hasSps", sps > 0)
            out.put("hasIdr", idr > 0)
            out.put("spsFrames", sps).put("idrFrames", idr)

            // 解码验证：byte-stream 逐段入队（与 app 侧一致）
            if (frames.isNotEmpty()) {
                val decFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, provider.width, provider.height)
                    .apply { setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, provider.width * provider.height * 3 / 2) }
                val reader = android.media.ImageReader.newInstance(provider.width, provider.height, android.graphics.PixelFormat.RGBA_8888, 1)
                val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                var initOk = false
                try {
                    dec.configure(decFormat, reader.surface, null, 0)
                    dec.start()
                    initOk = true
                } catch (e: Throwable) {
                    out.put("decoderInit", "err:${e.message}")
                }
                if (initOk) {
                    var pts = 0L
                    var queued = 0L
                    for (b in frames) {
                        val idx = dec.dequeueInputBuffer(5_000_000)
                        if (idx < 0) continue
                        val buf = dec.getInputBuffer(idx) ?: continue
                        buf.clear()
                        buf.put(b)
                        dec.queueInputBuffer(idx, 0, b.size, pts, 0)
                        pts += 16_666
                        queued++
                    }
                    // 排空解码器输出（surface 模式渲染后必须释放），等渲染落面
                    val dInfo = MediaCodec.BufferInfo()
                    val renderEnd = System.currentTimeMillis() + 3000
                    var released = 0
                    while (System.currentTimeMillis() < renderEnd) {
                        val oidx = runCatching { dec.dequeueOutputBuffer(dInfo, 20_000) }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
                        if (oidx >= 0) {
                            dec.releaseOutputBuffer(oidx, true)
                            released++
                        }
                        Thread.sleep(50)
                    }
                    out.put("decoderReleased", released)
                    // 采样最终画面（解码器 surface 可能为 opaque 格式，采集失败仅作参考）
                    runCatching {
                        var nonBlack = false
                        var brightRatio = 0f
                        var centerRgb = ""
                        val img = reader.acquireLatestImage()
                        if (img != null) {
                            val bmp = android.graphics.Bitmap.createBitmap(
                                img.width, img.height, android.graphics.Bitmap.Config.ARGB_8888,
                            ).also {
                                it.copyPixelsFromBuffer(img.planes[0].buffer)
                            }
                            img.close()
                            var bright = 0
                            var total = 0
                            var y = 0
                            while (y < bmp.height) {
                                var x = 0
                                while (x < bmp.width) {
                                    val p = bmp.getPixel(x, y)
                                    if ((((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3 > 30) bright++
                                    total++
                                    x += 8
                                }
                                y += 8
                            }
                            brightRatio = bright.toFloat() / total
                            nonBlack = brightRatio > 0.02f
                            centerRgb = "#%06X".format(bmp.getPixel(bmp.width / 2, bmp.height / 2) and 0xFFFFFF)
                            bmp.recycle()
                        }
                        out.put("surfaceNonBlack", nonBlack)
                        out.put("brightRatio", (brightRatio * 1000).toInt() / 10f)
                        out.put("centerRgb", centerRgb)
                    }
                    out.put("decoderInit", "ok")
                    out.put("queued", queued)
                    dec.stop()
                    dec.release()
                    reader.close()
                }
            }
        } catch (e: Throwable) {
            out.put("ok", false).put("error", e.toString())
            return JsonRpc.result(id, out)
        }
        out.put("ok", true)
        return JsonRpc.result(id, out)
    }

    private fun readFull(input: java.io.InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    /** 判断 annex-b 片段内是否含某 NAL type（首字节 nal_type）。 */
    private fun containsNal(bytes: ByteArray, nalType: Int): Boolean {
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == 0x00.toByte() && bytes[i + 1] == 0x00.toByte() &&
                bytes[i + 2] == 0x00.toByte() && bytes[i + 3] == 0x01.toByte()
            ) {
                val t = (bytes[i + 4].toInt() and 0x1F)
                if (t == nalType) return true
            }
        }
        return false
    }

    /**
     * encProbe3（诊断）：用真实 H264Encoder（含 EGL/纹理上传路径）编码**纯色 Bitmap**，
     * 解码采样中心像素 → 判定"Bitmap→GL→编码"是否输出可见内容（黑即纹理/着色器失效）。
     */
    private fun encProbe3(id: Any?): JSONObject {
        val out = JSONObject()
        try {
            val w = 640
            val h = 360
            val red = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(red).drawColor(android.graphics.Color.RED)
            val enc = com.fall.shell.stream.VdStreamServer.H264Encoder(w, h)
            val bytes = ArrayList<ByteArray>()
            runCatching {
                enc.start()
                repeat(6) {
                    enc.pushFrame(red)
                    enc.drain { b -> bytes.add(b); true }
                }
                Thread.sleep(300)
                enc.drain { b -> bytes.add(b); true }
            }.getOrElse { e -> out.put("encErr", e.toString()) }
            runCatching { enc.release() }

            out.put("outBuffers", bytes.size)
            var sps = 0
            var idr = 0
            for (b in bytes) {
                if (containsNal(b, 0x67)) sps++
                if (containsNal(b, 0x65)) idr++
            }
            out.put("hasSps", sps > 0).put("hasIdr", idr > 0)

            if (bytes.isNotEmpty()) {
                val reader = android.media.ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 1)
                val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                dec.configure(
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, w * h * 3 / 2)
                    }, reader.surface, null, 0,
                )
                dec.start()
                var pts = 0L
                for (b in bytes) {
                    val idx = dec.dequeueInputBuffer(5_000_000)
                    if (idx < 0) continue
                    val buf = dec.getInputBuffer(idx) ?: continue
                    buf.clear()
                    buf.put(b)
                    dec.queueInputBuffer(idx, 0, b.size, pts, 0)
                    pts += 16_666
                }
                Thread.sleep(1500)
                val img = reader.acquireLatestImage()
                if (img != null) {
                    val bmp = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888).also {
                        it.copyPixelsFromBuffer(img.planes[0].buffer)
                    }
                    img.close()
                    val c = bmp.getPixel(bmp.width / 2, bmp.height / 2)
                    out.put("centerRgb", "#%06X".format(c and 0xFFFFFF))
                    bmp.recycle()
                }
                dec.stop()
                dec.release()
                reader.close()
            }
            red.recycle()
        } catch (e: Throwable) {
            out.put("ok", false).put("error", e.toString())
            return JsonRpc.result(id, out)
        }
        out.put("ok", true)
        return JsonRpc.result(id, out)
    }

    /**
     * encProbe5（诊断）：ByteBuffer+YUV 输入编码器（绕过 EGL/着色器），验证 SPS/IDR 与解码画面。
     */
    private fun encProbe5(id: Any?): JSONObject {
        val out = JSONObject()
        try {
            val w = 640
            val h = 360
            val candidates = listOf(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            out.put("candidates", candidates)
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            var useFormat = candidates.first()
            runCatching {
                enc.configure(
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, candidates[0])
                        setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
            }.getOrElse {
                out.put("semiPlanarConfErr", it.toString().take(120))
                runCatching {
                    enc.configure(
                        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                            setInteger(MediaFormat.KEY_COLOR_FORMAT, candidates[1])
                            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
                    )
                    useFormat = candidates[1]
                }.getOrElse { e2 ->
                    out.put("flexibleConfErr", e2.toString().take(160))
                    runCatching { enc.release() }
                    out.put("ok", false)
                    return JsonRpc.result(id, out)
                }
            }
            enc.start()
            out.put("useFormat", useFormat)
            // NV12（semiplanar）或 I420（planar）纯红帧
            val ySize = w * h
            val uvSize = w * h / 2
            val frame = ByteArray(ySize + uvSize)
            java.util.Arrays.fill(frame, 0, ySize, 76.toByte())
            val semi = useFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                useFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            if (semi) {
                var i = ySize
                while (i + 1 < frame.size) {
                    frame[i] = 84.toByte()
                    frame[i + 1] = 255.toByte()
                    i += 2
                }
            } else {
                java.util.Arrays.fill(frame, ySize, ySize + uvSize / 2, 84.toByte()) // U
                java.util.Arrays.fill(frame, ySize + uvSize / 2, frame.size, 255.toByte()) // V
            }
            val info = MediaCodec.BufferInfo()
            val bytes = ArrayList<ByteArray>()
            runCatching {
                for (k in 0 until 8) {
                    val idx = enc.dequeueInputBuffer(20_000)
                    if (idx < 0) continue
                    val ibuf = enc.getInputBuffer(idx) ?: continue
                    ibuf.clear()
                    ibuf.put(frame)
                    enc.queueInputBuffer(idx, 0, frame.size, k * 33_333L, 0)
                }
                // drain 至多 5s
                val end = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < end && bytes.size < 30) {
                    val idx = enc.dequeueOutputBuffer(info, 10_000)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) continue
                    if (idx < 0) continue
                    val buf = enc.getOutputBuffer(idx) ?: run { enc.releaseOutputBuffer(idx, false); continue }
                    val b = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(b)
                    bytes.add(b)
                    enc.releaseOutputBuffer(idx, false)
                }
            }.getOrElse { e -> out.put("encErr(configure/start?)", e.toString()) }
            runCatching { enc.stop() }
            runCatching { enc.release() }

            var sps = 0
            var idr = 0
            for (b in bytes) {
                if (containsNal(b, 0x67)) sps++
                if (containsNal(b, 0x65)) idr++
            }
            out.put("outBuffers", bytes.size).put("hasSps", sps > 0)
                .put("hasIdr", idr > 0)
                .put("frameSizes", bytes.take(6).map { it.size })
            runCatching {
                val f = java.io.FileOutputStream("/data/local/tmp/probe5.bin")
                bytes.forEach { f.write(it) }
                f.close()
            }

            if (bytes.isNotEmpty()) {
                val reader = android.media.ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 1)
                val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                dec.configure(
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, w * h * 3 / 2)
                    }, reader.surface, null, 0,
                )
                dec.start()
                var pts = 0L
                for (b in bytes) {
                    val idx = dec.dequeueInputBuffer(5_000_000)
                    if (idx < 0) continue
                    val buf = dec.getInputBuffer(idx) ?: continue
                    buf.clear()
                    buf.put(b)
                    dec.queueInputBuffer(idx, 0, b.size, pts, 0)
                    pts += 33_333
                }
                Thread.sleep(1500)
                val img = reader.acquireLatestImage()
                if (img != null) {
                    val bmp = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888).also {
                        it.copyPixelsFromBuffer(img.planes[0].buffer)
                    }
                    img.close()
                    out.put("centerRgb", "#%06X".format(bmp.getPixel(w / 2, h / 2) and 0xFFFFFF))
                    bmp.recycle()
                }
                dec.stop()
                dec.release()
                reader.close()
            }
        } catch (e: Throwable) {
            out.put("ok", false).put("error", e.toString())
            return JsonRpc.result(id, out)
        }
        out.put("ok", true)
        return JsonRpc.result(id, out)
    }

    /**
     * encProbe6（诊断，B 方案：修复 daemon EGL 黑帧）：分步验证 EGL 绘制链路，定位黑帧断点。
     * 步骤：glClear 基线读回 → shader 编译/link 状态 → 纹理上传 glError → 绘制后读回。
     * 可选 w/h；64×64 与 VD 全尺寸（如 1216×2688）各自跑一轮。
     */
    private fun encProbe6(id: Any?, params: JSONObject): JSONObject {
        val out = JSONObject()
        val sizes = if (params.has("w") && params.has("h")) {
            listOf(params.optInt("w") to params.optInt("h"))
        } else {
            listOf(64 to 64, 1216 to 2688)
        }
        out.put("sizes", sizes.map { "${it.first}x${it.second}" })
        val res = JSONArray()
        for ((w, h) in sizes) {
            val r = JSONObject().put("size", "${w}x$h")
            runCatching {
                val step = JSONObject()
                step.put("w", w).put("h", h)
                // ---- EGL 上下文 + 输出面（用 ImageReader 当 consumer，可直接读回像素） ----
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                val ver = IntArray(2)
                EGL14.eglInitialize(display, ver, 0, ver, 1)
                val attribs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    0x3142, 1, // EGL_RECORDABLE_ANDROID
                    EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfig = IntArray(1)
                val chooseOk = EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfig, 0)
                step.put("chooseOk", chooseOk).put("numConfig", numConfig[0])
                val ctx = EGL14.eglCreateContext(
                    display, configs[0], EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
                )
                val reader = android.media.ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
                val surf = EGL14.eglCreateWindowSurface(display, configs[0], reader.surface, intArrayOf(EGL14.EGL_NONE), 0)
                step.put("makeCurrent", EGL14.eglMakeCurrent(display, surf, surf, ctx))
                fun readCenter(): String {
                    val px = java.nio.ByteBuffer.allocateDirect(4)
                    GLES20.glReadPixels(w / 2, h / 2, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
                    return "#%02X%02X%02X".format(px.get(0).toInt() and 0xFF, px.get(1).toInt() and 0xFF, px.get(2).toInt() and 0xFF)
                }

                // ---- 基线：glClear 红 —— 证明 EGL→consumer 通路本身可用 ----
                GLES20.glClearColor(1f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                EGL14.eglSwapBuffers(display, surf)
                GLES20.glFinish()
                step.put("baselineClearRed", readCenter())

                // ---- 着色器编译/link ----
                val vs = compileGles(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_SRC)
                val fs = compileGles(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SRC)
                val vsStatus = IntArray(1)
                val fsStatus = IntArray(1)
                GLES20.glGetShaderiv(vs, GLES20.GL_COMPILE_STATUS, vsStatus, 0)
                GLES20.glGetShaderiv(fs, GLES20.GL_COMPILE_STATUS, fsStatus, 0)
                step.put("vsCompile", vsStatus[0] == GLES20.GL_TRUE)
                    .put("vsLog", GLES20.glGetShaderInfoLog(vs).take(200))
                    .put("fsCompile", fsStatus[0] == GLES20.GL_TRUE)
                    .put("fsLog", GLES20.glGetShaderInfoLog(fs).take(200))
                val prog = GLES20.glCreateProgram()
                GLES20.glAttachShader(prog, vs)
                GLES20.glAttachShader(prog, fs)
                GLES20.glBindAttribLocation(prog, 0, "aPos")
                GLES20.glBindAttribLocation(prog, 1, "aTex")
                GLES20.glLinkProgram(prog)
                val linkStatus = IntArray(1)
                GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
                step.put("linkOk", linkStatus[0] == GLES20.GL_TRUE)
                    .put("linkLog", GLES20.glGetProgramInfoLog(prog).take(200))

                // ---- 纹理上传 ----
                val texIds = IntArray(1)
                GLES20.glGenTextures(1, texIds, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                // 纯红纹理（RGBA，A=255 不透明；对照用：若 A=0 该 ROM 可能把帧投递成全透明黑）
                val redTex = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder())
                val fb = ByteArray(w * h * 4) { if (it % 4 == 0 || it % 4 == 3) 255.toByte() else 0.toByte() }
                redTex.put(fb)
                redTex.position(0)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, redTex)
                step.put("texUploadGlErr", GLES20.glGetError())
                val boundTex = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, boundTex, 0)
                step.put("texBind", boundTex[0] == texIds[0])
                step.put("isTexture", GLES20.glIsTexture(texIds[0]))
                val maxTex = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTex, 0)
                step.put("maxTexSize", maxTex[0])

                // ---- 绘制 ----
                if (linkStatus[0] == GLES20.GL_TRUE) {
                    GLES20.glUseProgram(prog)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)
                    GLES20.glViewport(0, 0, w, h)
                    GLES20.glClearColor(0f, 0f, 0f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    val vb = java.nio.ByteBuffer.allocateDirect(16 * 4).order(java.nio.ByteOrder.nativeOrder())
                    val verts = floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
                    vb.asFloatBuffer().put(verts)
                    vb.position(0)
                    GLES20.glEnableVertexAttribArray(0)
                    GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, vb)
                    GLES20.glEnableVertexAttribArray(1)
                    vb.position(2)
                    GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, vb)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GLES20.glFinish()
                    step.put("afterDrawGlErr", GLES20.glGetError())
                    step.put("afterDrawReadCenter", readCenter())

                    // 直接时序：draw(红, back=红) → swap#1 → consumer 应收到红
                    EGL14.eglSwapBuffers(display, surf)
                    GLES20.glFinish()
                    val img1 = reader.acquireLatestImage()
                    if (img1 != null) {
                        val bmp = Bitmap.createBitmap(img1.width, img1.height, Bitmap.Config.ARGB_8888).also {
                            it.copyPixelsFromBuffer(img1.planes[0].buffer)
                        }
                        img1.close()
                        step.put("consumer1", "#%06X".format(bmp.getPixel(w / 2, h / 2) and 0xFFFFFF))
                        bmp.recycle()
                    } else {
                        step.put("consumer1", "no-image")
                    }
                    // 第二轮：clear(蓝) → swap#2 → consumer 应收到红（若投递正常）
                    GLES20.glClearColor(0f, 0f, 1f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    EGL14.eglSwapBuffers(display, surf)
                    GLES20.glFinish()
                    val img2 = reader.acquireLatestImage()
                    if (img2 != null) {
                        val bmp2 = Bitmap.createBitmap(img2.width, img2.height, Bitmap.Config.ARGB_8888).also {
                            it.copyPixelsFromBuffer(img2.planes[0].buffer)
                        }
                        img2.close()
                        step.put("consumer2", "#%06X".format(bmp2.getPixel(w / 2, h / 2) and 0xFFFFFF))
                        bmp2.recycle()
                    } else {
                        step.put("consumer2", "no-image")
                    }
                }

                EGL14.eglMakeCurrent(display, surf, surf, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surf)
                EGL14.eglDestroyContext(display, ctx)
                EGL14.eglTerminate(display)
                reader.close()
                res.put(step)
            }.getOrElse { e ->
                res.put(JSONObject().put("size", "${w}x$h").put("error", e.toString().take(200)))
            }
        }
        out.put("steps", res)
        out.put("ok", true)
        return JsonRpc.result(id, out)
    }

    private fun compileGles(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    private companion object {
        val VERTEX_SHADER_SRC = """
            attribute vec2 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() {
                vTex = aTex;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """
        val FRAGMENT_SHADER_SRC = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D uTex;
            void main() {
                gl_FragColor = texture2D(uTex, vTex);
            }
        """
    }

    /**
     * encProbe7（诊断，0-copy 探索）：SurfaceControl.captureDisplay(DisplayCaptureArgs.setSurface=encoder input
     * surface) 直接把 VD 合成帧写进编码器（绕过 ImageReader→Bitmap→EGL 全拷贝链）。
     * 报表：反射 API 可用性、是否收到编码帧、帧体大小（内容）。
     */
    private fun encProbe7(id: Any?, params: JSONObject): JSONObject {
        val out = JSONObject()
        val d = display ?: return JsonRpc.error(id, -32000, "no display")
        try {
            val w = d.captureWidth
            val h = d.captureHeight
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            val inputSurface = codec.createInputSurface()
            codec.start()

            val sc = Class.forName("android.view.SurfaceControl")
            // 枚举可用的 capture 相关 API（各 ROM 版本差异大）
            val apiList = JSONArray()
            sc.declaredMethods.filter { it.name.contains("capture", true) }
                .forEach { m -> apiList.put(m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")") }
            out.put("captureApis", apiList)
            var builderCls: Class<*>? = null
            try {
                builderCls = Class.forName("android.view.SurfaceControl\$DisplayCaptureArgs\$Builder")
            } catch (e: Throwable) {
                builderCls = null
            }
            out.put("hasBuilder", builderCls != null)
            if (builderCls == null) {
                codec.stop(); codec.release()
                return JsonRpc.result(id, out)
            }
            val getToken = sc.declaredMethods.firstOrNull { it.name == "getPhysicalDisplayToken" && it.parameterCount == 1 }
                ?.apply { isAccessible = true }
            out.put("foundGetToken", getToken != null)
            val token = getToken?.invoke(null, d.displayId) as Any?
            out.put("displayToken", token != null)
            if (token == null) {
                codec.stop(); codec.release()
                return JsonRpc.result(id, out)
            }
            val builderCtor = builderCls.getDeclaredConstructor(Any::class.java).apply { isAccessible = true }
            val setSurface = builderCls.declaredMethods.firstOrNull { it.name == "setSurface" && it.parameterCount == 1 }
                    ?.apply { isAccessible = true }
            val setSize = builderCls.declaredMethods.firstOrNull { it.name == "setSize" && it.parameterCount == 2 }
                    ?.apply { isAccessible = true }
            val setAllowProtected = builderCls.declaredMethods.firstOrNull { it.name == "setAllowProtected" && it.parameterCount == 1 }
                    ?.apply { isAccessible = true }
            val setCaptureSecure = builderCls.declaredMethods.firstOrNull { it.name == "setCaptureSecureLayers" && it.parameterCount == 1 }
                    ?.apply { isAccessible = true }
            out.put(
                "foundMethods",
                listOf(
                    setSurface != null, setSize != null,
                    setAllowProtected != null, setCaptureSecure != null,
                ),
            )
            if (setSurface == null) {
                codec.stop(); codec.release()
                return JsonRpc.result(id, out)
            }
            val buildM = builderCls.declaredMethods.firstOrNull { it.name == "build" }?.apply { isAccessible = true }
            val captureM = sc.declaredMethods.firstOrNull { it.name == "captureDisplay" && it.parameterCount == 1 }
                    ?.apply { isAccessible = true }
            out.put("foundCapture", captureM != null)
            if (captureM == null) {
                codec.stop(); codec.release()
                return JsonRpc.result(id, out)
            }

            // 连续多次 capture：合成是异步的，需反复提交
            val okCalls = JSONArray()
            for (i in 0 until 10) {
                val b = builderCtor.newInstance(token)
                setSize?.invoke(b, w, h)
                setSurface.invoke(b, inputSurface)
                setAllowProtected?.invoke(b, true)
                setCaptureSecure?.invoke(b, true)
                val args = buildM?.invoke(b)
                okCalls.put(runCatching { captureM.invoke(null, args); true }.getOrElse { false.toString() + ":" + it.toString().take(80) })
                Thread.sleep(80)
            }
            out.put("captures", okCalls)

            // 收集编码器输出
            val info = MediaCodec.BufferInfo()
            val sizes = JSONArray()
            var frames = 0
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                val idx = runCatching { codec.dequeueOutputBuffer(info, 100) }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) continue
                if (idx >= 0) {
                    if (info.size > 0 && frames < 8) sizes.put(info.size)
                    frames++
                    codec.releaseOutputBuffer(idx, false)
                }
            }
            out.put("encoderFrames", frames).put("sizes", sizes)
            codec.stop(); codec.release()
        } catch (e: Throwable) {
            out.put("ok", false).put("error", e.toString().take(200))
            return JsonRpc.result(id, out)
        }
        out.put("ok", true)
        return JsonRpc.result(id, out)
    }

    /**
     * vdCfgProbe（诊断，VirtualDisplayConfig 方案探测）：反射 Android 13+ VirtualDisplayConfig$Builder
     * 是否存在及暴露哪些方法（setSurface / setDisplayIdToMirror / 双输出相关），判断"镜像+编码器直连"
     * 0-copy 通路是否可行；另列 DisplayManager 的 createVirtualDisplay 重载。
     */
    private fun vdCfgProbe(id: Any?): JSONObject {
        val out = JSONObject()
        runCatching {
            val bCls = runCatching {
                Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            }.getOrNull()
            out.put("hasVirtualDisplayConfig", bCls != null)
            if (bCls != null) {
                val methods = JSONArray()
                bCls.declaredMethods
                    .filter { it.name.startsWith("set") }
                    .forEach { m ->
                        methods.put(m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")")
                    }
                out.put("builderMethods", methods)
                val ctors = JSONArray()
                bCls.declaredConstructors.forEach { c ->
                    ctors.put("(" + c.parameterTypes.joinToString(",") { it.simpleName } + ")")
                }
                out.put("builderCtors", ctors)
                val allMethods = JSONArray()
                bCls.declaredMethods.forEach { m ->
                    allMethods.put(m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")")
                }
                out.put("allMethods", allMethods)
            }
        }
        runCatching {
            val dmClass = Class.forName("android.hardware.display.DisplayManager")
            val creates = JSONArray()
            dmClass.declaredMethods.filter { it.name == "createVirtualDisplay" }
                .forEach { m ->
                    creates.put(
                        "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")",
                    )
                }
            out.put("createVirtualDisplay_overloads", creates)
        }
        out.put("ok", true)
        return JsonRpc.result(id, out)
    }

    /**
     * mirrorProbe（诊断，VirtualDisplayConfig 镜像 0-copy 验证）：构建第二个 VD
     * `setDisplayIdToMirror(VD_A.displayId)` + `setSurface(encoder input surface)` →
     * SF 把 VD_A 合成结果直接镜像投递到编码器（GPU 0-copy），感知链（ImageReader）不受影响。
     * 报表：VD_B 创建是否成功、编码器收到帧数、帧体大小。
     */
    private fun mirrorProbe(id: Any?): JSONObject {
        val out = JSONObject()
        val a = display ?: return JsonRpc.error(id, -32000, "no display")
        try {
            val w = a.captureWidth
            val h = a.captureHeight
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            val inputSurface = codec.createInputSurface()
            codec.start()

            val bCls = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val bCtor = bCls.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val b = bCtor.newInstance("fall-mirror", w, h, a.dpi)
            val setSurface = bCls.getDeclaredMethod("setSurface", android.view.Surface::class.java).apply { isAccessible = true }
            val setMirror = bCls.getDeclaredMethod("setDisplayIdToMirror", Int::class.javaPrimitiveType).apply { isAccessible = true }
            val setFlags = bCls.declaredMethods.firstOrNull { it.name == "setFlags" && it.parameterCount == 1 }
                ?.apply { isAccessible = true }
            val build = bCls.getDeclaredMethod("build").apply { isAccessible = true }
            setSurface.invoke(b, inputSurface)
            setMirror.invoke(b, a.displayId)
            // PUBLIC(1) | PRESENTATION(2)
            setFlags?.invoke(b, 3)
            val config = build.invoke(b)

            val dm = context?.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                    ?: return JsonRpc.error(id, -32000, "no display mgr")
            var vdB: Any? = null
            val createErr = runCatching {
                val createM = android.hardware.display.DisplayManager::class.java
                    .methods.firstOrNull { it.name == "createVirtualDisplay" && it.parameterCount == 1 }
                    ?: return@runCatching "no ctor"
                vdB = createM.invoke(dm, config)
            }.exceptionOrNull()?.toString()?.take(160)
            out.put("vdB", if (vdB != null) "created:" + vdB.javaClass.simpleName else "null($createErr)")

            // 收编码器输出（给镜像一段时间投帧）
            val info = MediaCodec.BufferInfo()
            val sizes = JSONArray()
            var frames = 0
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                val idx = runCatching { codec.dequeueOutputBuffer(info, 100) }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    Thread.sleep(20)
                    continue
                }
                if (idx >= 0) {
                    if (info.size > 0 && frames < 8) sizes.put(info.size)
                    frames++
                    codec.releaseOutputBuffer(idx, false)
                }
            }
            out.put("encoderFrames", frames).put("sizes", sizes)

            // 清理
            runCatching { vdB?.let { it.javaClass.getMethod("release").invoke(it) } }
            codec.stop(); codec.release()
        } catch (e: Throwable) {
            out.put("ok", false).put("error", e.toString().take(200))
            return JsonRpc.result(id, out)
        }
        out.put("ok", true)
        return JsonRpc.result(id, out)
    }

    /**
     * sfProbe（诊断，SurfaceFlinger 显示捕获路径探索）：
     * 1) 全量枚举 SurfaceControl 方法（不按名字过滤，避免漏看）；
     * 2) 反射检测 DisplayCaptureArgs(captureDisplay 参数) 与旧版 8 参 captureDisplay(API21)；
     * 3) 若可用：把目标 surface 设为 encoder input surface，循环 capture N 次，上报编码器收到帧数/大小。
     *    期望：captureDisplay 每次主动取当前帧（不受“静止不投帧”限制）→ 全时段 0-copy。
     */
    private fun sfProbe(id: Any?, params: JSONObject): JSONObject {
        val out = JSONObject()
        val d = display ?: return JsonRpc.error(id, -32000, "no display")
        try {
            val w = d.captureWidth
            val h = d.captureHeight
            val sc = Class.forName("android.view.SurfaceControl")

            // 1) 全量枚举（含 static/签名）
            val scMethods = JSONArray()
            sc.declaredMethods.forEach { m ->
                if (m.name.contains("capture") || m.name.contains("display") || m.name.contains("Display") || m.name.contains("Token")) {
                    scMethods.put(m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")")
                }
            }
            out.put("scMethods", scMethods)

            // 2) 相关类
            val hasArgsCls = runCatching {
                Class.forName("android.view.SurfaceControl\$DisplayCaptureArgs")
            }.isSuccess
            val hasBuilderCls = runCatching {
                Class.forName("android.view.SurfaceControl\$DisplayCaptureArgs\$Builder")
            }.isSuccess
            out.put("hasArgs", hasArgsCls).put("hasBuilder", hasBuilderCls)

            // 旧版 8 参 captureDisplay（API 21）：(IBinder, int,int,int,int, boolean, float, Surface)
            val oldCapture = sc.declaredMethods.firstOrNull {
                it.name == "captureDisplay" && it.parameterTypes.size == 8
            }?.apply { isAccessible = true }
            out.put("hasOldCapture8", oldCapture != null)

            val getToken = sc.declaredMethods.firstOrNull { it.name == "getPhysicalDisplayToken" && it.parameterCount == 1 }
                ?.apply { isAccessible = true }
            val token = getToken?.invoke(null, d.displayId) as Any?
            out.put("displayToken", token != null)
            if (token == null) return JsonRpc.result(id, out)

            // 3) 编码器 + capture 直写
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            val inputSurface = codec.createInputSurface()
            codec.start()

            var captureFn: (() -> Boolean)? = null
            if (hasBuilderCls) {
                val bCls = Class.forName("android.view.SurfaceControl\$DisplayCaptureArgs\$Builder")
                val bCtor = bCls.declaredConstructors.first { it.parameterTypes.size == 1 }?.apply { isAccessible = true }
                val setSize = bCls.declaredMethods.firstOrNull { it.name == "setSize" && it.parameterCount == 2 }?.apply { isAccessible = true }
                val setSurf = bCls.declaredMethods.firstOrNull { it.name == "setSurface" && it.parameterCount == 1 }?.apply { isAccessible = true }
                val setAllowProtected = bCls.declaredMethods.firstOrNull { it.name == "setAllowProtected" && it.parameterCount == 1 }?.apply { isAccessible = true }
                val setSecure = bCls.declaredMethods.firstOrNull { it.name == "setCaptureSecureLayers" && it.parameterCount == 1 }?.apply { isAccessible = true }
                val buildM = bCls.declaredMethods.firstOrNull { it.name == "build" }?.apply { isAccessible = true }
                val captureM = sc.declaredMethods.firstOrNull { it.name == "captureDisplay" && it.parameterCount == 1 }?.apply { isAccessible = true }
                out.put("hasBuilderPath", listOf(bCtor != null, setSize != null, setSurf != null, buildM != null, captureM != null))
                if (bCtor != null && setSize != null && setSurf != null && buildM != null && captureM != null) {
                    captureFn = {
                        val b = bCtor.newInstance(token)
                        setSize.invoke(b, w, h)
                        setSurf.invoke(b, inputSurface)
                        runCatching { setAllowProtected?.invoke(b, true) }
                        runCatching { setSecure?.invoke(b, true) }
                        runCatching { captureM.invoke(null, buildM.invoke(b)) }
                        true
                    }
                }
            }
            if (captureFn == null && oldCapture != null) {
                captureFn = {
                    runCatching { oldCapture.invoke(null, token, w, h, 0, 0, false, 1f, inputSurface) }
                    true
                }
            }
            out.put("captureFnReady", captureFn != null)

            val info = MediaCodec.BufferInfo()
            val sizes = JSONArray()
            var frames = 0
            val deadline = System.currentTimeMillis() + 2500
            var nCalls = 0
            if (captureFn != null) {
                while (System.currentTimeMillis() < deadline) {
                    captureFn()
                    nCalls++
                    // 收
                    while (true) {
                        val idx = codec.dequeueOutputBuffer(info, 0)
                        when {
                            idx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                            idx >= 0 -> {
                                if (info.size > 0 && frames < 8) sizes.put(info.size)
                                frames++
                                codec.releaseOutputBuffer(idx, false)
                            }
                            else -> break
                        }
                    }
                    Thread.sleep(30)
                }
            }
            out.put("captureCalls", nCalls).put("encoderFrames", frames).put("sizes", sizes)
            codec.stop(); codec.release()
        } catch (e: Throwable) {
            out.put("ok", false).put("error", e.toString().take(200))
            return JsonRpc.result(id, out)
        }
        out.put("ok", true)
        return JsonRpc.result(id, out)
    }

    /**
     * encProbe（诊断）：在守护进程内跑一遍"surface-input AVC 编码器 + EGL 渲染"最小链路，
     * 回报每帧输出大小/flags（CODEC_CONFIG 是否出现）/首字节前缀/swap/glError，
     * 用于定位实时档黑屏是 EGL 失效还是编码器不产参数集。
     */
    private fun encProbe(id: Any?): JSONObject {
        val w = 640
        val h = 360
        val out = JSONObject()
        try {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            var eglDisplay: EGLDisplay? = null
            var eglSurface: EGLSurface? = null
            var eglContext: EGLContext? = null
            try {
                val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                val version = IntArray(2)
                EGL14.eglInitialize(display, version, 0, version, 1)
                val attribs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    0x3142, 1, // EGL_RECORDABLE_ANDROID
                    EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfig = IntArray(1)
                val chooseOk = EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfig, 0)
                val ctx = EGL14.eglCreateContext(
                    display, configs[0], EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
                )
                val surf = EGL14.eglCreateWindowSurface(
                    display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0,
                )
                EGL14.eglMakeCurrent(display, surf, surf, ctx)
                eglDisplay = display; eglSurface = surf; eglContext = ctx

                val swaps = JSONArray()
                repeat(3) { i ->
                    GLES20.glClearColor(if (i % 2 == 0) 1f else 0f, 0f, if (i % 2 == 0) 0f else 1f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    EGLExt.eglPresentationTimeANDROID(display, surf, System.nanoTime())
                    swaps.put(EGL14.eglSwapBuffers(display, surf))
                }
                val glErr = GLES20.glGetError()

                val info = MediaCodec.BufferInfo()
                val buffers = JSONArray()
                var configCount = 0
                repeat(80) {
                    val idx = codec.dequeueOutputBuffer(info, 10_000)
                    if (idx < 0) return@repeat
                    val buf = codec.getOutputBuffer(idx) ?: run {
                        codec.releaseOutputBuffer(idx, false)
                        return@repeat
                    }
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(bytes)
                    val flags = info.flags
                    if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) configCount++
                    val obj = JSONObject()
                        .put("size", bytes.size)
                        .put("flags", flags)
                        .put("csd", (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0)
                        .put("prefix", bytes.take(8).joinToString(" ") { "%02X".format(it) })
                    buffers.put(obj)
                    codec.releaseOutputBuffer(idx, false)
                }
                out.put("ok", true)
                    .put("chooseOk", chooseOk).put("numConfig", numConfig[0])
                    .put("swaps", swaps).put("glError", glErr)
                    .put("configCount", configCount).put("buffers", buffers)
            } finally {
                runCatching { if (eglDisplay != null && eglSurface != null) EGL14.eglDestroySurface(eglDisplay, eglSurface) }
                runCatching { if (eglDisplay != null && eglContext != null) EGL14.eglDestroyContext(eglDisplay, eglContext) }
                runCatching { if (eglDisplay != null) EGL14.eglTerminate(eglDisplay) }
                runCatching { codec.stop() }
                runCatching { codec.release() }
                runCatching { surface.release() }
            }
        } catch (e: Throwable) {
            out.put("ok", false).put("error", e.toString())
        }
        return JsonRpc.result(id, out)
    }

    private fun debugClass(id: Any?, params: JSONObject): JSONObject {
        val name = params.optString("name")
        return try {
            val c = Class.forName(name)
            JsonRpc.result(
                id,
                JSONObject()
                    .put("found", true)
                    .put("loader", c.classLoader?.javaClass?.name ?: "boot/null")
                    .put("class", c.name),
            )
        } catch (e: Throwable) {
            JsonRpc.result(id, JSONObject().put("found", false).put("error", e.toString()))
        }
    }

    private fun actionOnDisplay(id: Any?, block: (Int) -> Boolean): JSONObject {
        val d = requireDisplay()
        val ok = block(d.displayId)
        return if (ok) okResult(id) else JsonRpc.error(id, -32000, "inject failed")
    }

    private fun actionOnMain(id: Any?, block: () -> Boolean): JSONObject {
        val ok = block()
        return if (ok) okResult(id) else JsonRpc.error(id, -32000, "main inject failed")
    }

    /**
     * 统一的成功返回形态：**所有**成功结果都是 JSON 对象。
     * 旧版注入类方法返回字符串 `result:"ok"`，而客户端 `ShellBridge.call()` 按对象解析 →
     * 抛 `IllegalStateException` 且被 `runCatching` 吞掉，表现为"预览页手势/ADB 通道静默失效"。
     * 现在统一为 `{"ok":true}`，客户端用 [com.fall.automation.bridge.ShellBridge.callOk] 判定。
     */
    private fun okResult(id: Any?, message: String? = null): JSONObject = JsonRpc.result(
        id,
        JSONObject().apply {
            put("ok", true)
            message?.let { put("message", it) }
        },
    )
}
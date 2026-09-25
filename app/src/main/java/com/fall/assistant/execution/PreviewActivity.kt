package com.fall.assistant.execution

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.fall.assistant.FallApplication
import com.fall.assistant.core.DebugLog
import com.fall.assistant.ui.theme.FallTheme
import com.fall.automation.bridge.FrameStreamClient
import com.fall.automation.bridge.ShellBridge
import com.fall.automation.bridge.closeFrameStream
import com.fall.automation.bridge.openFrameStream
import com.fall.core.data.repository.PreviewQuality
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.ArrayDeque
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 虚拟屏围观页（豆包式纯虚拟屏）：
 * 全屏沉浸呈现虚拟屏（虚拟屏分辨率=实体屏，1:1 占满整页），屏幕周边一圈亮边框提示"这是虚拟屏"。
 * 呈现通道按设置档位 [PreviewQuality] 选择，帧率固定 60fps：
 * - 低/高（默认）：socket JPEG 推流（60fps），流建立失败回退 captureFrame 轮询止血；
 * - 实时（实验）：H.264 硬编推流 + TextureView 硬解（60fps）。
 * 状态条/输入条改为悬浮控件（平时自动隐藏，暂停/接管时浮现）。
 * 手势注入与首触暂停语义各档一致（整页换算；VD=实体屏时 1:1）。
 * 悬浮胶囊不收起：预览页在前台时再点胶囊 → 结束预览回到主屏（[dismissIfForeground]）。
 */
class PreviewActivity : ComponentActivity() {

    private val bridge by lazy { ShellBridge() }
    private var pollJob: Job? = null
    private var frame by mutableStateOf<Bitmap?>(null)
    private var vdW by mutableStateOf(720)
    private var vdH by mutableStateOf(1280)
    private var quality = PreviewQuality.LOW

    /** 是否处于 H.264 实时档（TextureView 硬解）：由管线实际打开格式驱动，非静态设置档位。 */
    private var h264Active by mutableStateOf(false)

    // ---- 暂停/接管悬浮卡片（独立悬浮窗）----
    // SurfaceView 会把本窗口内容"挖洞"遮掉，Compose 卡片会被视频挡住 → 卡片改用
    // TYPE_APPLICATION_OVERLAY 悬浮窗（与执行胶囊同族），并置 FLAG_NOT_TOUCH_MODAL
    // 让卡片外的触摸仍能落到预览页（接管时照常点/滑虚拟屏）。
    private var pauseCard: android.view.View? = null
    private var pauseStep: android.widget.TextView? = null
    private var pauseJob: kotlinx.coroutines.Job? = null

    /** 实时档是否已真正渲染出画面（首帧落面）：未落面前用快照图垫底，避免"打开预览黑屏"。 */
    private var live by mutableStateOf(false)

    // 实时档解码通道（TextureView surface 就绪后创建；流帧回调喂 decoder）
    @Volatile
    private var decoder: H264SurfaceDecoder? = null
    @Volatile
    private var streamClient: FrameStreamClient? = null

    /** 挂起的解码 surface：surface 先于流就绪时暂存，流配置到位后建解码器。 */
    @Volatile
    private var pendingSurface: Surface? = null

    /** H.264 参数集/首帧缓冲：解码器未就绪时暂存，建好时回放（防丢 SPS/PPS 导致整流黑屏）。
     *  用并发容器：写入在流读线程、消费在 UI/解码线程，`ArrayDeque` 并发访问会抛异常或丢帧。 */
    private val h264Backlog = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

    /** H.264 连续打开失败标记：置位后不再优先尝试 h264，直接走 JPEG（防重试风暴）。 */
    @Volatile
    private var h264Broken = false

    /** H.264 是否真的渲染出过画面（TextureView onSurfaceTextureUpdated 置位），看门狗判定依据。 */
    @Volatile
    private var h264Live = false

    /** h264 最近一次置为 active 的时刻（elapsedRealtime），用于看门狗宽限计时。 */
    @Volatile
    private var h264Since = 0L

    /** 最近一次收到帧的时刻（elapsedRealtime）；长时间无新帧 → 向 VD 注入 WAKEUP 保活唤醒。 */
    @Volatile
    private var lastFrameAt = 0L
    @Volatile
    private var lastWakeAt = 0L

    override fun onResume() {
        super.onResume()
        foreground = true
        instance = this
        observePause()
        if (pollJob != null) return
        when (quality) {
            PreviewQuality.REALTIME -> startPipeline(preferH264 = true)
            else -> startPipeline(preferH264 = false)
        }
    }

    override fun onPause() {
        super.onPause()
        foreground = false
        hidePauseCard()
        pauseJob?.cancel()
        pauseJob = null
        stopPipeline()
        pollJob?.cancel()
        pollJob = null
        // 用户离开预览页 → 恢复 Agent 静默执行
        if (ExecutionHub.state.value.active && ExecutionHub.paused.value) {
            ExecutionHub.requestResume()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        stopPipeline()
        runCatching { bridge.close() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 预览期间屏幕常亮（用户曾反馈"全黑"；防预览页被系统息屏/调暗误判为黑屏）
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 预览页按高刷显示（面板支持时 120Hz）：实时画面更顺滑，虚拟屏合成节奏也随之跟上高刷
        runCatching {
            window.attributes = window.attributes.apply { preferredRefreshRate = 120f }
        }
        // 同步读取档位（DataStore 已有缓存，首读极快；避免首帧闪切 UI）
        quality = runBlocking {
            (application as FallApplication).container.settingsRepository.settings.first().previewQuality
        }
        setContent {
            FallTheme {
                FullscreenPreviewScreen(
                    frame = frame,
                    vdW = vdW,
                    vdH = vdH,
                    realtime = h264Active,
                    live = live,
                    onSurfaceReady = ::onSurfaceReady,
                    onSurfaceReleased = ::onSurfaceReleased,
                    injectTap = ::injectTap,
                    injectSwipe = ::injectSwipe,
                    injectLong = ::injectLong,
                )
            }
        }
    }

    /**
     * 统一预览管线（逐级降级防黑屏）：
     * 首选流格式（实时档 H.264 → JPEG；低/高档 JPEG）→ 流失败/断开回退 captureFrame 轮询止血。
     * 流断开（如横屏旋转导致 shell 侧尺寸变化重启会话）后按节流自动重开，拿到新尺寸配置。
     * H.264 打开成功时置 [h264Active]，UI 切换 TextureView 硬解；否则 Canvas 渲染 JPEG 帧。
     */
    private fun startPipeline(preferH264: Boolean) {
        pollJob = lifecycleScope.launch(Dispatchers.Default) {
            // 打开预览立即有画面（消除"点进悬浮窗黑屏一下"）：
            // ① 先用上次会话的快照垫底（内存缓存，零延迟）② 再抓一张最新帧覆盖（~百毫秒）
            restoreCachedSnapshot()
            fetchFrame()
            openAndAttach(preferH264)
            var lastReopen = 0L
            var lastH264Retry = 0L
            while (isActive) {
                val clientAlive = streamClient?.isRunning == true
                if (clientAlive) {
                    lastReopen = 0L
                    // JPEG 兜底中：定期重试 h264 实时档（h264 一次失败常是瞬时原因——切 VD/守护进程重启；
                    // JPEG 在原生分辨率下只有 ~15fps，长期停留会让用户觉得"帧数很低"）
                    if (preferH264 && streamClient?.format == FrameStreamClient.Format.JPEG) {
                        val nowRt = SystemClock.elapsedRealtime()
                        if (nowRt - lastH264Retry > H264_RETRY_MS) {
                            lastH264Retry = nowRt
                            h264Broken = false
                            DebugLog.i("Preview", "JPEG 兜底中 → 重试 h264 实时档")
                            onStreamLost()
                            openAndAttach(preferH264)
                            continue
                        }
                    }
                    // 长无新帧 → VD 可能断帧（SF 停投）→ 注入 KEYCODE_WAKEUP 唤醒（无 UI 副作用）
                    val nowAw = SystemClock.elapsedRealtime()
                    if (lastFrameAt > 0 && nowAw - lastFrameAt > KEEPALIVE_AFTER_MS && nowAw - lastWakeAt > KEEPALIVE_INTERVAL_MS) {
                        lastWakeAt = nowAw
                        DebugLog.i("Preview", "预览 >${KEEPALIVE_AFTER_MS}ms 无新帧，注入 WAKEUP 保活")
                        runCatching { bridge.callOk("pressKey", JsonObject().apply { addProperty("keyCode", 224) }) }
                    }
                    // H.264 看门狗：开流成功但对端点持续无画面 → 编码/解码链路实质失败，
                    // 强制降级 JPEG（60fps 推流仍保底可见，避免用户对着永久黑屏）
                    if (h264Active && !h264Live) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - h264Since > H264_WATCHDOG_MS) {
                            DebugLog.i("Preview", "h264 看门狗：>${H264_WATCHDOG_MS}ms 无可见画面，降级 JPEG")
                            h264Broken = true
                            onStreamLost()
                            continue
                        }
                    }
                } else {
                    if (streamClient != null) onStreamLost()
                    fetchFrame() // 流未建立/断开 → 轮询止血
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReopen > REOPEN_INTERVAL_MS) {
                        lastReopen = now
                        openAndAttach(preferH264)
                    }
                }
                delay(if (ExecutionHub.paused.value) 90 else 350)
            }
        }
    }

    /** 按格式顺序开流并挂接：H.264 优先（可用时），失败降级 JPEG。 */
    private suspend fun openAndAttach(preferH264: Boolean) {
        if (streamClient?.isRunning == true) return
        val formats = if (preferH264 && !h264Broken) listOf("h264", "jpeg") else listOf("jpeg")
        for (f in formats) {
            val c = runCatching { openStream(f) }.getOrNull()
            if (c == null) {
                if (f == "h264") {
                    h264Broken = true
                    DebugLog.w(
                        "Preview",
                        "h264 开流失败（RPC 或 config 超时）→ 本次降级 JPEG（原生分辨率下仅 ~15fps）；" +
                            "${H264_RETRY_MS / 1000}s 后自动重试 h264",
                    )
                }
                continue
            }
            streamClient = c
            if (c.width > 0) vdW = c.width
            if (c.height > 0) vdH = c.height
            val isH264 = c.format == FrameStreamClient.Format.H264
            h264Active = isH264
            live = false
            if (isH264) {
                DebugLog.i("Preview", "h264 流已建立 ${c.width}x${c.height} csd=${c.csd0 != null}/${c.csd1 != null}")
                h264Live = false
                h264Since = SystemClock.elapsedRealtime()
                runOnUiThread { tryCreateDecoder() }
            } else {
                DebugLog.i("Preview", "jpeg 流已建立 ${c.width}x${c.height}")
            }
            h264Broken = false
            return
        }
        h264Active = false
    }

    /** 按格式开流：H.264 帧喂解码器（未就绪时入缓冲）；JPEG 帧解码为 Bitmap 供 Canvas 渲染。
     *  H.264 用短超时（编码器不可用时不发 config 会阻塞读，快速失败降级 JPEG）。 */
    private suspend fun openStream(format: String): FrameStreamClient? = runCatching {
        bridge.openFrameStream(
            format = format,
            timeoutMs = if (format == "h264") 4_000 else 10_000,
            onFrame = if (format == "h264") ::onH264Frame else ::onJpegFrame,
        )
    }.getOrNull()

    /** H.264 帧：解码器未就绪时暂存（含首个 SPS/PPS/IDR，防整流黑屏），就绪后回放再续推。 */
    private var h264FramesFed = 0L

    /** 送显帧率统计（每 5s 汇总一行日志，真机核验帧率用）。 */
    private val fpsCounter = java.util.concurrent.atomic.AtomicLong()
    private var fpsWindowStart = SystemClock.elapsedRealtime()

    private fun countFrame() {
        fpsCounter.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        val span = now - fpsWindowStart
        if (span >= 5_000) {
            val n = fpsCounter.getAndSet(0)
            fpsWindowStart = now
            DebugLog.i("Preview", "流帧率 ${n * 1000 / span} fps（${span}ms 内 $n 帧）")
        }
    }

    private fun onH264Frame(data: ByteArray) {
        if (data.isEmpty()) return
        countFrame()
        lastFrameAt = SystemClock.elapsedRealtime()
        val d = decoder
        if (d == null) {
            if (h264Backlog.size < H264_BACKLOG_MAX) h264Backlog.add(data)
            return
        }
        flushH264Backlog(d)
        runCatching { d.queue(data) }.onFailure {
            DebugLog.e("Preview", "h264 queue 失败: ${it.message}")
        }
        if (++h264FramesFed == 1L) DebugLog.i("Preview", "h264 首帧已喂解码器 (${data.size}B)")
    }

    /** JPEG 帧：解码为 Bitmap 并更新画面逻辑尺寸（旋转/配置变化后自愈映射基准）。 */
    private fun onJpegFrame(data: ByteArray) {
        countFrame()
        lastFrameAt = SystemClock.elapsedRealtime()
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
        if (bmp != null) {
            frame = bmp
            lastSnapshot = bmp
            if (bmp.width > 0) vdW = bmp.width
            if (bmp.height > 0) vdH = bmp.height
        }
    }

    /** 打开预览时的垫底快照：优先用上一会话缓存的帧（零延迟，先出画面再走实时流）。 */
    private fun restoreCachedSnapshot() {
        val cached = lastSnapshot ?: return
        if (!cached.isRecycled) {
            frame = cached
            if (cached.width > 0) vdW = cached.width
            if (cached.height > 0) vdH = cached.height
            DebugLog.i("Preview", "快照垫底 ${cached.width}x${cached.height}（消除黑屏窗口）")
        }
    }

    private fun flushH264Backlog(d: H264SurfaceDecoder) {
        while (true) {
            val bytes = h264Backlog.poll() ?: break
            if (!runCatching { d.queue(bytes) }.getOrDefault(false)) break
        }
    }

    private suspend fun fetchFrame() {
        // 不再限定 ExecutionHub active：任务结束/暂停后打开预览也应能回显 VD 最后一帧
        val frameResult = runCatching { bridge.captureFrame(scale = 1.0f, quality = 60) }.getOrNull() ?: return
        if (frameResult.jpegBase64.isNotBlank()) {
            val bytes = Base64.decode(frameResult.jpegBase64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                frame = bmp
                lastSnapshot = bmp
                if (frameResult.width > 0) vdW = frameResult.width
                if (frameResult.height > 0) vdH = frameResult.height
            }
        }
    }

    /** 流断开清理：释放解码通道，等下一轮自动重开。 */
    private fun onStreamLost() {
        streamClient = null
        decoder?.release()
        decoder = null
        pendingSurface?.release()
        pendingSurface = null
        h264Active = false
        h264Live = false
        live = false
        h264Backlog.clear()
    }

    /** SurfaceView surface 就绪入口（可能早于流）：挂起 surface，等流配置到位再建解码器。 */
    private fun onSurfaceReady(surface: Surface) {
        pendingSurface = surface
        tryCreateDecoder()
    }

    /** SurfaceView surface 销毁（退后台/窗口重建）：释放解码通道，避免向已死 surface 继续渲染。 */
    private fun onSurfaceReleased() {
        decoder?.release()
        decoder = null
        pendingSurface = null
        h264Live = false
        live = false
    }

    // ---- 暂停/接管悬浮卡片实现 ----

    /** 订阅执行状态：暂停时弹出接管控件，恢复时收起（含最近步骤文本刷新）。 */
    private fun observePause() {
        if (pauseJob != null) return
        pauseJob = lifecycleScope.launch {
            ExecutionHub.paused.collect { paused ->
                if (paused) showPauseCard() else hidePauseCard()
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showPauseCard() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            DebugLog.w("Preview", "无悬浮窗权限，接管控件不可见（请开启悬浮窗权限）")
            return
        }
        if (pauseCard != null) return
        val title = android.widget.TextView(this).apply {
            setText("⏸ 已暂停 · 正在接管虚拟屏")
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
        }
        val btnResume = android.widget.Button(this).apply {
            setText("继续执行")
            setOnClickListener { ExecutionHub.requestResume() }
        }
        val btnStop = android.widget.Button(this).apply {
            setText("停止")
            setOnClickListener { ExecutionHub.requestStop() }
        }
        val input = android.widget.EditText(this).apply {
            hint = "输入到虚拟屏"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF9AA0A6.toInt())
            textSize = 14f
            setSingleLine(true)
        }
        val btnInject = android.widget.Button(this).apply {
            setText("注入")
            setOnClickListener {
                val t = input.text?.toString().orEmpty()
                if (t.isNotBlank()) {
                    injectText(t)
                    input.setText("")
                }
            }
        }
        val step = android.widget.TextView(this).apply {
            setTextColor(0xFF9AA0A6.toInt())
            textSize = 12f
            visibility = android.view.View.GONE
        }
        pauseStep = step

        val head = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(title)
            addView(btnResume)
            addView(btnStop)
        }
        val inputRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(
                input,
                android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(btnInject)
        }
        val card = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xF2212121.toInt())
            }
            addView(head, android.widget.LinearLayout.LayoutParams(-1, -2))
            addView(inputRow, android.widget.LinearLayout.LayoutParams(-1, -2))
            addView(step, android.widget.LinearLayout.LayoutParams(-1, -2))
        }
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢触摸（卡片外照常操作虚拟屏）+ 可聚焦（输入框可输入/弹输入法）
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            y = dp(8)
            horizontalMargin = dp(8).toFloat() / resources.displayMetrics.widthPixels
        }
        val wm = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        runCatching { wm.addView(card, lp) }.onSuccess {
            pauseCard = card
            updatePauseStep()
        }.onFailure {
            DebugLog.w("Preview", "接管控件悬浮窗添加失败: ${it.message}")
        }
    }

    private fun hidePauseCard() {
        pauseCard?.let { v ->
            runCatching {
                val wm = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
                wm.removeView(v)
            }
        }
        pauseCard = null
        pauseStep = null
    }

    private fun updatePauseStep() {
        val step = pauseStep ?: return
        val latest = ExecutionHub.state.value.steps.lastOrNull()
        if (latest.isNullOrBlank()) {
            step.visibility = android.view.View.GONE
        } else {
            step.visibility = android.view.View.VISIBLE
            step.text = "最近步骤：$latest"
        }
    }

    /** 解码器输出首帧被释放（= 首帧已提交渲染）→ 视为"有画面"（看门狗解除 + 撤下快照垫底）。 */
    private fun onFrameRendered() {
        if (!h264Live) {
            DebugLog.i("Preview", "h264 首帧已落面（${SystemClock.elapsedRealtime() - h264Since}ms）")
            h264Live = true
            live = true
        }
    }

    /** 有流配置 + 有 surface 时建硬解（可重入；已建则跳过）。 */
    private fun tryCreateDecoder() {
        if (decoder != null) return
        val surface = pendingSurface ?: return
        val c = streamClient
            ?: return // 流未就绪（无 csd/真实分辨率）→ 等开流后重试
        val d = H264SurfaceDecoder(
            width = c.width.takeIf { it > 0 } ?: vdW,
            height = c.height.takeIf { it > 0 } ?: vdH,
        )
        d.onFirstFrame = { runOnUiThread { onFrameRendered() } }
        runCatching { d.start(surface) }.onFailure {
            d.release()
            DebugLog.e("Preview", "h264 硬解启动失败: ${it.message}")
            // 硬解不可用（本 ROM 编解码器差异）→ 关 h264 流，降级 JPEG（openAndAttach 在 h264Broken 下走 JPEG）
            h264Broken = true
            h264Active = false
            onStreamLost()
            return
        }
        decoder = d
        flushH264Backlog(d)
        DebugLog.i("Preview", "h264 解码器已建 ${if (c.width > 0) c.width else vdW}x${if (c.height > 0) c.height else vdH}，待渲染画面")
    }

    private fun stopPipeline() {
        streamClient?.let {
            runCatching {
                lifecycleScope.launch { bridge.closeFrameStream(it) }
            }
        }
        streamClient = null
        decoder?.release()
        decoder = null
        pendingSurface?.release()
        pendingSurface = null
        h264Active = false
        live = false
        h264Backlog.clear()
    }

    private fun injectTap(x: Int, y: Int) = dispatch("tap") { it.addProperty("x", x); it.addProperty("y", y) }

    private fun injectSwipe(x1: Int, y1: Int, x2: Int, y2: Int) = dispatch("swipe") {
        it.addProperty("fromX", x1); it.addProperty("fromY", y1)
        it.addProperty("toX", x2); it.addProperty("toY", y2)
        it.addProperty("durationMs", 120)
    }

    private fun injectLong(x: Int, y: Int) = dispatch("longPress") {
        it.addProperty("x", x); it.addProperty("y", y); it.addProperty("durationMs", 600)
    }

    private fun injectText(text: String) {
        lifecycleScope.launch { runCatching { bridge.inputTextDisplay(text) } }
    }

    /**
     * 注入一个手势到虚拟屏。成功判定走 [ShellBridge.callOk]（兼容 `{"ok":true}` 与旧版 `"ok"`）——
     * 旧实现用 `call()` 解析 `"ok"` 字符串必抛异常且被 `runCatching` 静默吞掉，
     * 表现为"预览页能看能点但屏幕毫无反应"。失败必须留日志，不能再静默。
     */
    private fun dispatch(method: String, fill: (JsonObject) -> Unit) {
        lifecycleScope.launch {
            val params = JsonObject().apply { fill(this) }
            runCatching { bridge.callOk(method, params) }
                .onSuccess { ok -> if (!ok) DebugLog.w("Preview", "注入未生效（守护进程返回非 ok）: $method") }
                .onFailure { DebugLog.w("Preview", "注入异常: $method → ${it.message}") }
        }
    }

    companion object {
        @Volatile
        private var foreground = false

        @Volatile
        private var instance: PreviewActivity? = null

        /** 最近一帧快照（跨预览会话保留）：下次打开预览先垫底显示，消除"点进去黑屏一下"。 */
        @Volatile
        private var lastSnapshot: Bitmap? = null

        /** 流断开后自动重开间隔（防止热循环打爆 shell）。 */
        private const val REOPEN_INTERVAL_MS = 2_000L

        /** JPEG 兜底后重试 h264 实时档的间隔。 */
        private const val H264_RETRY_MS = 20_000L

        /** H.264 未就绪期缓冲上限（≈2s@60fps；surface 创建通常远快于此）。 */
        private const val H264_BACKLOG_MAX = 120

        /** H.264 看门狗宽限：开流这么久仍无画面落面 → 判定编码/解码不可用，降级 JPEG。 */
        private const val H264_WATCHDOG_MS = 8_000L

        /** 预览期间无新帧超过此时长 → 触发一次 VD WAKEUP 保活。 */
        private const val KEEPALIVE_AFTER_MS = 3_000L

        /** 保活注入最小间隔。 */
        private const val KEEPALIVE_INTERVAL_MS = 5_000L

        /** 预览页在前台时结束它（回到主屏原本的界面），返回是否已结束。 */
        fun dismissIfForeground(): Boolean {
            val a = instance ?: return false
            if (!foreground) return false
            a.finish()
            return true
        }
    }
}

/**
 * 全屏虚拟屏 UI（各档统一）：
 * 沉浸全屏隐藏系统栏；画面按内容比例 FIT 居中（黑色衬底，横屏内容自动把预览页转成横屏 → 原生分辨率 1:1 大画面）；
 * 屏幕周边一圈灰色细边框提示虚拟屏边界；状态/输入条平时隐藏，暂停/接管时浮现（见 Activity 的悬浮卡片）。
 * [realtime] 时用 SurfaceView 承接硬解输出（解码帧由 SF 直接合成，省一次 HWUI 全屏纹理合成）；
 * **SurfaceView 会"挖洞"遮住窗口层内容**，故：① 首帧落面前把它移出画面（快照位图垫底可见），
 * ② 画面四周留出内边距让边框线不被遮，③ 暂停/接管卡片走独立悬浮窗（见 [showPauseCard]）。
 */
@Composable
private fun FullscreenPreviewScreen(
    frame: Bitmap?,
    vdW: Int,
    vdH: Int,
    realtime: Boolean,
    live: Boolean,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceReleased: () -> Unit,
    injectTap: (Int, Int) -> Unit,
    injectSwipe: (Int, Int, Int, Int) -> Unit,
    injectLong: (Int, Int) -> Unit,
) {
    val context = LocalContext.current

    // 沉浸全屏：隐藏系统栏，虚拟屏占满整页
    LaunchedEffect(Unit) {
        val window = (context as? ComponentActivity)?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    val currentVdW by rememberUpdatedState(vdW)
    val currentVdH by rememberUpdatedState(vdH)

    // 内容横屏（游戏等）→ 预览页转横屏，画面以原生分辨率 1:1 撑满（避免竖屏页压缩成小条）
    LaunchedEffect(currentVdW, currentVdH) {
        val vw = currentVdW
        val vh = currentVdH
        if (vw <= 0 || vh <= 0 || vw == vh) return@LaunchedEffect
        val activity = context as? ComponentActivity ?: return@LaunchedEffect
        val target = if (vw > vh) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (activity.requestedOrientation != target) activity.requestedOrientation = target
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val pageW = with(density) { maxWidth.toPx() }
        val pageH = with(density) { maxHeight.toPx() }
        // 画面四周留出内边距：让虚拟屏边界线（贴边 4dp）不被 SurfaceView 的"挖洞"遮掉；
// 绘制与手势映射共用 previewFitRect，保证"看到的"与"点到的"一致（含暂停接管时的手动点击）
        val insetPx = with(density) { SCREEN_INSET_DP.dp.toPx() }
        val fit = previewFitRect(pageW, pageH, currentVdW, currentVdH, insetPx)
        val currentFit by rememberUpdatedState(fit) // 手势映射与绘制共用同一矩形（pointerInput 捕获初值）

        if (realtime) {
            // 实时档：SurfaceView 承接硬解输出（解码帧由 SurfaceFlinger 直接合成，省掉 TextureView
            // 经 HWUI 的一次全屏纹理合成 → 预览帧率翻倍、功耗更低）。
            // 注意：SurfaceView 会把窗口层内容"挖洞"遮掉，因此首帧落面前先把它移到画面外
            // （此时由快照位图垫底，用户看到的仍是虚拟屏画面而非黑屏），落面后再移入。
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                onSurfaceReady(holder.surface)
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = Unit

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                // surface 销毁（退后台/重建）→ 必须释放解码器，避免向死 surface 渲染
                                onSurfaceReleased()
                            }
                        })
                    }
                },
                modifier = Modifier
                    // 注意：fit 是像素，必须经 density 转 dp（旧代码直接 .dp 会让偏移放大 2.75 倍 →
                    // 直播画面比快照更靠右下，表现为"切到实时画面时向右下漂移"）
                    .offset(
                        x = with(density) { fit.x.toDp() },
                        y = if (live) with(density) { fit.y.toDp() } else (maxHeight + 200.dp),
                    )
                    .size(with(density) { fit.w.toDp() }, with(density) { fit.h.toDp() }),
            )
        }

        // 画面层：实时档首帧落面之前、以及 JPEG 档，都用位图绘制；
        // 有快照就立即出画面（打开预览不再黑屏一下），首帧落面后自动让位给实时画面。
        val bmp = frame
        val showBitmap = bmp != null && (!realtime || !live)
        if (showBitmap) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawImage(
                    image = bmp.asImageBitmap(),
                    dstOffset = IntOffset(fit.x.roundToInt(), fit.y.roundToInt()),
                    dstSize = IntSize(fit.w.roundToInt(), fit.h.roundToInt()),
                )
            }
        } else if (bmp == null && !realtime) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize(),
            ) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = "虚拟屏未启动",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text(
                    text = "运行静默任务后，虚拟屏画面会显示在这里\n（也可点按画面接管操控）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
            }
        }

        // 手势层（点按/滑动/长按 → FIT 矩形换算注入；首触暂停）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val vw = currentVdW
                        val vh = currentVdH
                        val box = currentFit
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (vw <= 0 || vh <= 0) return@awaitEachGesture
                        handleGesture(down, box, vw, vh, injectTap, injectSwipe, injectLong)
                    }
                }
        )

        // 简约边框：贴着屏幕边缘一圈较粗的灰色边框，仅用于标注「这是虚拟屏」
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(VD_BORDER_DP.dp, Color(0xFF9AA0A6), RoundedCornerShape(12.dp)),
        )

        // 注：暂停/接管控件（继续/停止/输入注入/最近步骤）改由 Activity 的**独立悬浮窗**承载
        //（Compose 卡片会被 SurfaceView 的"挖洞"遮住，见 showPauseCard()）。
    }
}

/** FIT 矩形（黑边偏移 + 内容尺寸，页面坐标 px）。 */
private data class FitBox(val x: Float, val y: Float, val w: Float, val h: Float)

/** 虚拟屏标注边框粗细（dp），贴屏幕边缘。 */
private const val VD_BORDER_DP = 4

/** 直播画面四周内边距（dp）：等于边框粗细——让贴边边框线不被 SurfaceView 的"挖洞"遮掉，
 *  画面本身仍按原生比例铺满（仅缩进 4dp ≈ 0.6%，视觉上就是原生大小）。 */
private const val SCREEN_INSET_DP = VD_BORDER_DP

/** 等比例 FIT：内容按 [vw]x[vh] 缩放置中于 [pageW]x[pageH]。 */
private fun fitRect(pageW: Float, pageH: Float, vw: Float, vh: Float): FitBox {
    if (vw <= 0f || vh <= 0f || pageW <= 0f || pageH <= 0f) return FitBox(0f, 0f, pageW, pageH)
    val scale = min(pageW / vw, pageH / vh)
    val w = vw * scale
    val h = vh * scale
    return FitBox((pageW - w) / 2f, (pageH - h) / 2f, w, h)
}

/**
 * 预览页画面矩形（px）：在整页内再留 [insetPx] 内边距（让贴边边框线不被 SurfaceView 挖洞遮掉）。
 * **绘制与手势映射必须共用本函数**，否则会出现"看到的画面"与"点到的位置"错位。
 */
private fun previewFitRect(pageW: Float, pageH: Float, vdW: Int, vdH: Int, insetPx: Float): FitBox {
    val inner = fitRect(
        (pageW - insetPx * 2f).coerceAtLeast(1f),
        (pageH - insetPx * 2f).coerceAtLeast(1f),
        vdW.toFloat(),
        vdH.toFloat(),
    )
    return FitBox(inner.x + insetPx, inner.y + insetPx, inner.w, inner.h)
}

/** 手势处理公共逻辑（各档复用）：首触暂停；点击/滑动/长按经 FIT 矩形换算 display-local 坐标注入。 */
private suspend fun AwaitPointerEventScope.handleGesture(
    down: PointerInputChange,
    fit: FitBox,
    vw: Int,
    vh: Int,
    injectTap: (Int, Int) -> Unit,
    injectSwipe: (Int, Int, Int, Int) -> Unit,
    injectLong: (Int, Int) -> Unit,
) {
    ExecutionHub.requestPause()
    val start = down.position
    var last = start
    var dragging = false
    var longFired = false
    val downMs = System.currentTimeMillis()
    val slopPx = 20f

    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull() ?: break
        if (change.pressed) {
            last = change.position
            if (!dragging && (last - start).getDistance() > slopPx) {
                dragging = true
            } else if (!dragging && !longFired && System.currentTimeMillis() - downMs > 450) {
                longFired = true
                val p = toVdFit(start, fit, vw, vh) ?: break
                injectLong(p.x.toInt(), p.y.toInt())
            }
        } else {
            if (!longFired) {
                when {
                    dragging -> {
                        val p1 = toVdFit(start, fit, vw, vh) ?: break
                        val p2 = toVdFit(last, fit, vw, vh) ?: break
                        injectSwipe(p1.x.toInt(), p1.y.toInt(), p2.x.toInt(), p2.y.toInt())
                    }
                    System.currentTimeMillis() - downMs < 450 -> {
                        val p = toVdFit(start, fit, vw, vh) ?: break
                        injectTap(p.x.toInt(), p.y.toInt())
                    }
                    else -> {
                        val p = toVdFit(start, fit, vw, vh) ?: break
                        injectLong(p.x.toInt(), p.y.toInt())
                    }
                }
            }
            break
        }
    }
}

/** FIT 矩形换算：画布坐标 → display-local 坐标；越出画面（黑边/边框内缩区）返回 null（忽略该手势）。
 *  [fit] 由 [previewFitRect] 算出并与绘制共用，保证点到的位置与看到的一致。 */
private fun toVdFit(p: Offset, fit: FitBox, vdW: Int, vdH: Int): Offset? {
    if (vdW <= 0 || vdH <= 0) return null
    if (p.x < fit.x || p.x > fit.x + fit.w || p.y < fit.y || p.y > fit.y + fit.h) return null
    val vx = (p.x - fit.x) / fit.w * vdW
    val vy = (p.y - fit.y) / fit.h * vdH
    return Offset(vx.coerceIn(0f, (vdW - 1).toFloat()), vy.coerceIn(0f, (vdH - 1).toFloat()))
}
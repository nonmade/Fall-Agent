package com.fall.shell.display

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.Surface
import com.fall.shell.core.Refl

/**
 * 虚拟屏会话：创建带触控/独立焦点/受信 flag 的 VirtualDisplay 并在其上启动目标 App。
 *
 * 关键点（移植 ShadowAuto 方案）：
 * - 反射自建 DisplayManager(context)（显式传入 shell 身份包装 Context，避免系统缓存实例携带
 *   base 包名导致 "packageName must match the calling uid"）
 * - flag 组合逐级降级（全量 -> 去 TRUSTED -> 去 FOCUS -> 基础）
 * - 输出 Surface 用 ImageReader（满足非空校验，同时为后续截图/OCR 预留）
 *
 * 横屏适配（2026-09-22，游戏等横屏 App 修复）：
 * ImageReader 缓冲尺寸在创建时固定（竖版物理分辨率）。横屏 App 请求把 VD 旋转到横屏后，
 * SurfaceFlinger 会把旋转后内容压进固定竖版缓冲 → 截帧是"缩小横条/横置"画面，预览与 AI
 * 识别都拿不到完整分辨率。因此 [grabBitmap] 检测到显示旋转/尺寸变化时，用公开 API 把
 * VD 连同输出缓冲同步 resize 到旋转后逻辑尺寸（截 1:1 完整画面）；resize 不可用的 ROM
 * 退化为按 [rotation] 旋回正向（兜底）。
 */
class VirtualDisplaySession private constructor(
    private val virtualDisplay: Any,
    private val dm: DisplayManager,
    @Volatile private var captureReader: ImageReader,
    /** 创建时请求的刷新率（Hz）；面板不支持时由 framework 取最近可用值。 */
    val refreshRate: Float,
) {
    /** 连续 grab null 计数（断帧保活用）。 */
    private var nullStreak = 0
    private val inputBridge = com.fall.shell.input.InputBridge()

    /** 最近一次成功抓到的帧（断帧期回退重发：静止画面 SF 停投新帧时仍可持续输出图片）。 */
    @Volatile
    private var lastFrame: Bitmap? = null

    /** 最近一次抓到「真实新帧」的时刻（elapsedRealtime）；null 回放不计入 → 判断内容是否动态。 */
    @Volatile
    private var lastFreshAt = 0L

    /** 最近一次 [grabBitmap] 是否拿到了真实新帧（非 lastFrame 回放）。
     *  注意不能用 `freshAgoMs == 0` 判断：抓帧本身耗时 ≥1ms，毫秒精度下该条件几乎不成立。 */
    @Volatile
    private var lastGrabFresh = false

    private val grabLock = Any()

    /** 抓帧缓冲双槽（复用；见 [obtainScratch]）。 */
    private var scratchA: Bitmap? = null
    private var scratchB: Bitmap? = null
    private var scratchFlip = false

    private val display: Display? = runCatching {
        virtualDisplay.javaClass.getMethod("getDisplay").invoke(virtualDisplay) as? Display
    }.getOrNull()

    val displayId: Int = display?.displayId ?: -1

    /** 供诊断 RPC 读取的 Display 引用（旋转/真实尺寸）。 */
    val rawDisplay: Display? get() = display

    /** VD 当前实际刷新率（Display.getRefreshRate()）；验证 120Hz 是否落地用。 */
    val actualRefreshRate: Float get() = runCatching { display?.refreshRate ?: 0f }.getOrDefault(0f)

    /** 距最近一次「真实新帧」的毫秒数（从未抓到 → Long.MAX_VALUE）：判断内容是否动态（镜像 0-copy 是否值得等）。 */
    val freshAgoMs: Long
        get() = if (lastFreshAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - lastFreshAt

    /** 最近一次抓帧是否拿到真实新帧（送显用它判定「要不要推这一帧」，回放重复帧可节流）。 */
    val grabWasFresh: Boolean get() = lastGrabFresh

    /** 当前捕获缓冲尺寸（诊断用）。 */
    val captureWidth: Int get() = capW
    val captureHeight: Int get() = capH

    /** VD resize 是否已被判定为不支持（诊断用）。 */
    val isResizeFailed: Boolean get() = resizeFailed

    /**
     * 强制唤醒 VD 显示（黑屏/休眠期 SF 停投帧的兜底）。
     * 走隐藏 API DisplayManager.setDisplayPowerMode(displayId, STATE_ON)，root 身份可用。
     */
    fun wakeDisplay(): Boolean {
        val modeOn = 2 // android.view.Display.STATE_ON
        return runCatching {
            val m = dm.javaClass.methods.firstOrNull {
                it.name == "setDisplayPowerMode" && it.parameterCount == 3
            } ?: dm.javaClass.declaredMethods.firstOrNull {
                it.name == "setDisplayPowerMode"
            }?.apply { isAccessible = true } ?: return false
            if (m.parameterCount == 3) {
                m.invoke(dm, displayId, modeOn, "fall-shell-wake")
            } else if (m.parameterCount == 2) {
                m.invoke(dm, displayId, modeOn)
            } else {
                return false
            }
            true
        }.getOrElse { e ->
            System.err.println("[fall-shell] wakeDisplay failed: ${e.message}")
            System.err.flush()
            false
        }
    }

    /**
     * 强制把 VD 逻辑尺寸 + 捕获缓冲调整为指定尺寸（诊断/横屏适配用）。返回是否成功。
     * 与 [grabBitmap] 共用 [grabLock]：否则并发时会与 `resizeCapture` 的
     * `captureReader.close()` 交错，出现位图尺寸错乱/取帧异常。
     */
    fun forceResize(w: Int, h: Int): Boolean = synchronized(grabLock) {
        if (w <= 0 || h <= 0 || (w == capW && h == capH)) return (w == capW && h == capH)
        val ok = resizeCapture(w, h)
        if (ok) {
            width = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) h else w
            height = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) w else h
        }
        ok
    }

    /** VD 当前旋转（横屏 App 会请求 ROTATION_90/270）。 */
    val rotation: Int get() = display?.rotation ?: Surface.ROTATION_0

    /** 创建时初始捕获缓冲尺寸（竖版物理分辨率）。 */
    private val initialW: Int
    private val initialH: Int

    /** 当前捕获缓冲尺寸（随显示旋转 [resizeCapture] 重建）。 */
    @Volatile
    private var capW: Int
    @Volatile
    private var capH: Int

    /** 当前逻辑尺寸（display-local 坐标空间，随 [grabBitmap] 刷新）；dpi 固定。 */
    @Volatile
    var width: Int
        private set
    @Volatile
    var height: Int
        private set
    val dpi: Int

    /** resize 不被 ROM 支持时置位 → 只做按 rotation 转正兜底。 */
    @Volatile
    private var resizeFailed = false

    init {
        val metrics = android.util.DisplayMetrics()
        runCatching { display?.getRealMetrics(metrics) }
        val w = if (metrics.widthPixels > 0) metrics.widthPixels else 720
        val h = if (metrics.heightPixels > 0) metrics.heightPixels else 1280
        initialW = w
        initialH = h
        capW = w
        capH = h
        width = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) h else w
        height = if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) w else h
        dpi = if (metrics.densityDpi > 0) metrics.densityDpi else 320
    }

    companion object {
        /** 目标刷新率（Hz）：原生分辨率 + 高刷（面板支持时取 120）。 */
        const val DEFAULT_REFRESH_RATE = 120f

        /**
         * 显式尺寸为 0/负时解析实体屏真实值 （root 增强模式，阶段 1）：
         * 解析顺序 = `wm size` + `wm density`（已在测试机验证可用）→ DisplayManager
         * 默认屏 getRealMetrics → 兜底 720×1280@320。
         */
        fun create(
            context: Context,
            width: Int = 0,
            height: Int = 0,
            dpi: Int = 0,
            refreshRate: Float = 0f,
        ): VirtualDisplaySession {
            println("[fall-shell] VD.create v0.7 marker"); System.out.flush()
            val spec = if (width > 0 && height > 0 && dpi > 0) {
                DisplaySpec(width, height, dpi, refreshRate)
            } else {
                resolveRealSpec(context, refreshRate)
            }
            return createWithSpec(context, spec)
        }

        /** 实体屏规格（分辨率 + 密度 + 刷新率）。 */
        data class DisplaySpec(val width: Int, val height: Int, val dpi: Int, val refreshRate: Float = DEFAULT_REFRESH_RATE)

        /** 横屏自动适配检测节流（ms）。 */
        const val AUTO_RESIZE_CHECK_MS = 3000L

        /** 连续 N 次检测到同一目标布局才执行 resize（防抖动）。 */
        const val AUTO_RESIZE_CONFIRM = 2

        /** 连续 grab null 达该值一半时触发一次 wakeDisplay 尝试（≈0.5s@60fps）。 */
        const val WAKE_EVERY_NULL = 60

        /** 实体屏真实规格：`wm size/density` 输出 → 默认屏 getRealMetrics → 兜底。 */
        fun resolveRealSpec(context: Context, refreshRate: Float = 0f): DisplaySpec {
            val dm = displayManager(context)
            val rate = if (refreshRate > 0f) refreshRate else pickRefreshRate(dm)

            // 1) wm size; wm density（shell uid 可执行，测试机已验证可用）
            val fromWm = runCatching {
                val p = ProcessBuilder("sh", "-c", "wm size; wm density")
                    .redirectErrorStream(true)
                    .start()
                val out = p.inputStream.bufferedReader().use { it.readText() }
                val w = Regex("(?:Physical|Override) size: (\\d+)x(\\d+)")
                    .find(out)?.groupValues?.let { it[1].toInt() to it[2].toInt() }
                val d = Regex("(?:Physical|Override) density: (\\d+)")
                    .find(out)?.groupValues?.getOrNull(1)?.toInt()
                if (w != null && d != null && w.first > 0 && w.second > 0 && d > 0) {
                    DisplaySpec(w.first, w.second, d, rate)
                } else {
                    null
                }
            }.getOrNull()
            if (fromWm != null) return fromWm

            // 2) DisplayManager 默认屏 getRealMetrics（stubs 无 getDefaultDisplay，反射获取）
            val fromDm = runCatching {
                val defaultDisplay = DisplayManager::class.java
                    .getMethod("getDefaultDisplay")
                    .invoke(dm) as? android.view.Display
                val metrics = android.util.DisplayMetrics()
                defaultDisplay?.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0 && metrics.densityDpi > 0) {
                    DisplaySpec(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, rate)
                } else {
                    null
                }
            }.getOrNull()
            if (fromDm != null) return fromDm

            // 3) 兜底（与既有硬编码一致）
            return DisplaySpec(720, 1280, 320, rate)
        }

        /**
         * 从实体屏（默认显示）支持的模式里挑刷新率：优先恰好 120 → 不超过 120 的最高档 →
         * 最高档 → 120 兜底。避免把面板不支持的档位请求给 framework。
         */
        private fun pickRefreshRate(dm: DisplayManager): Float = runCatching {
            val modes = dm.displays.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }?.supportedModes
            val rates = modes?.map { it.refreshRate }?.distinct().orEmpty()
            if (rates.isEmpty()) {
                DEFAULT_REFRESH_RATE
            } else {
                rates.firstOrNull { kotlin.math.abs(it - DEFAULT_REFRESH_RATE) < 0.5f }
                    ?: rates.filter { it <= DEFAULT_REFRESH_RATE }.maxOrNull()
                    ?: rates.max()
            }
        }.getOrDefault(DEFAULT_REFRESH_RATE)

        /**
         * 用 `VirtualDisplayConfig.Builder`（Android 13+ 隐藏 API）建 VD：在原生分辨率/密度之外
         * 还能指定**请求刷新率**（高刷）与镜像源（[mirrorDisplayId]，0-copy 送显用）。
         * 旧 ROM / 缺该方法时返回 null，调用方回退公开 API（60Hz）。
         */
        fun createVirtualDisplay(
            dm: DisplayManager,
            uniqueId: String,
            width: Int,
            height: Int,
            dpi: Int,
            surface: Surface,
            flags: Int,
            refreshRate: Float,
            mirrorDisplayId: Int = -1,
        ): Any? = runCatching {
            val bCls = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
            val intType = Int::class.javaPrimitiveType
            val ctor = bCls.getDeclaredConstructor(String::class.java, intType, intType, intType)
                .apply { isAccessible = true }
            val b = ctor.newInstance(uniqueId, width, height, dpi)
            bCls.getDeclaredMethod("setSurface", Surface::class.java).apply { isAccessible = true }.invoke(b, surface)
            bCls.getDeclaredMethod("setFlags", intType).apply { isAccessible = true }.invoke(b, flags)
            if (mirrorDisplayId >= 0) {
                bCls.getDeclaredMethod("setDisplayIdToMirror", intType).apply { isAccessible = true }
                    .invoke(b, mirrorDisplayId)
            }
            if (refreshRate > 0f) {
                bCls.declaredMethods.firstOrNull { it.name == "setRequestedRefreshRate" && it.parameterCount == 1 }
                    ?.apply { isAccessible = true }
                    ?.invoke(b, refreshRate)
            }
            val config = bCls.getDeclaredMethod("build").apply { isAccessible = true }.invoke(b)
            val createM = dm.javaClass.declaredMethods
                .firstOrNull { it.name == "createVirtualDisplay" && it.parameterCount == 1 }
                ?.apply { isAccessible = true } ?: return null
            createM.invoke(dm, config)
        }.getOrElse { e ->
            System.err.println("[fall-shell] createVirtualDisplay(config) failed: ${e.message}")
            System.err.flush()
            null
        }

        private fun createWithSpec(context: Context, spec: DisplaySpec): VirtualDisplaySession {
            val dm = displayManager(context)
            val width = spec.width
            val height = spec.height
            val dpi = spec.dpi
            val captureReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val outputSurface = captureReader.surface

            val baseFlags = flagOf(DisplayManager::class.java, "VIRTUAL_DISPLAY_FLAG_PUBLIC") or
                flagOf(DisplayManager::class.java, "VIRTUAL_DISPLAY_FLAG_PRESENTATION") or
                flagOf(DisplayManager::class.java, "VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY")
            val touch = flagOf(DisplayManager::class.java, "VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH")
            val focus = flagOf(DisplayManager::class.java, "VIRTUAL_DISPLAY_FLAG_OWN_FOCUS")
            val trusted = flagOf(DisplayManager::class.java, "VIRTUAL_DISPLAY_FLAG_TRUSTED")
            val candidates = listOf(
                baseFlags or touch or focus or trusted,
                baseFlags or touch or focus,
                baseFlags or touch,
                baseFlags,
            )

            var lastError: Throwable? = null
            for (flags in candidates) {
                try {
                    // 优先 VirtualDisplayConfig.Builder（可带请求刷新率=高刷）；不可用时回退公开 API（60Hz）
                    val vd: Any? = createVirtualDisplay(dm, "FallShell", width, height, dpi, outputSurface, flags, spec.refreshRate)
                        ?: dm.createVirtualDisplay("FallShell", width, height, dpi, outputSurface, flags)
                    val vdDisplay = runCatching {
                        vd?.javaClass?.getMethod("getDisplay")?.invoke(vd) as? Display
                    }.getOrNull()
                    if (vd != null && vdDisplay != null) {
                        println(
                            "[fall-shell] VD created ${width}x$height@${spec.refreshRate}Hz " +
                                "flags=0x${Integer.toHexString(flags)} display=${vdDisplay.displayId}",
                        )
                        System.out.flush()
                        return VirtualDisplaySession(vd, dm, captureReader, spec.refreshRate)
                    }
                    lastError = IllegalStateException("display null, flags=0x${Integer.toHexString(flags)}")
                } catch (e: Throwable) {
                    lastError = e
                    System.err.println("[fall-shell] createVirtualDisplay candidate failed: ${e.message}")
                    e.printStackTrace(System.err)
                    System.err.flush()
                }
            }
            captureReader.close()
            throw IllegalStateException("createVirtualDisplay failed", lastError)
        }

        private fun displayManager(context: Context): DisplayManager {
            // 反射自建实例并显式注入包装 Context（固化 com.android.shell 身份）
            val custom = runCatching {
                val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
                ctor.newInstance(context) as DisplayManager
            }.getOrNull()
            return custom ?: (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
        }

        private fun flagOf(clazz: Class<*>, name: String): Int =
            Refl.fieldOf(clazz, name)?.getInt(null) ?: 0
    }

    /**
     * 将目标 App 启动到虚拟屏：显式组件（pkg/cls）优先（`am start -n`），
     * 否则按包名起 LAUNCHER 隐式 Intent（部分 ROM shell uid 解析不了第三方包）。
     */
    fun startApp(packageName: String, component: String? = null): String {
        val cmd = if (!component.isNullOrBlank()) {
            "am start --display $displayId -n $component"
        } else {
            "am start --display $displayId " +
                "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $packageName"
        }
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().use { it.readText() }
        val exit = p.waitFor()
        return output.trim().ifEmpty { "exit=$exit" }
    }

    /**
     * 截取虚拟屏当前帧（captureReader 的唯一消费方，本方法内部串行）。
     * RGBA_8888 → Bitmap；横屏 App 先做 [syncCaptureSize] 同步缓冲与 VD 尺寸（1:1 完整画面），
     * 再按 [rotation] 转正（resize 兜底 + SF 输出横置补偿）。返回尺寸即 [width]/[height]，
     * 与 touch/OCR 坐标一致（display-local）。
     */
    fun grabBitmap(): Bitmap? = synchronized(grabLock) {
        syncCaptureSize()
        val image = runCatching { captureReader.acquireLatestImage() }.getOrNull()
        if (image == null) {
            // 断帧保活：SF 停投帧（VD 空闲/静止/游戏加载期）时，
            // 交替尝试 ①唤醒显示状态 ②注入 KEYCODE_WAKEUP（无 UI 副作用）促 SF 重新合成
            lastGrabFresh = false
            nullStreak++
            if (nullStreak % WAKE_EVERY_NULL == WAKE_EVERY_NULL / 2) wakeDisplay()
            if (nullStreak % WAKE_EVERY_NULL == WAKE_EVERY_NULL - 1) {
                runCatching { inputBridge.key(displayId, KeyEvent.KEYCODE_WAKEUP) }
            }
            return null
        }
        nullStreak = 0
        lastFreshAt = SystemClock.elapsedRealtime()
        lastGrabFresh = true
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * capW
        val paddedW = capW + rowPadding / pixelStride
        // 按尺寸复用抓帧缓冲（双槽轮换），消除每帧 13MB 分配 + GC 抖动
        val scratch = obtainScratch(paddedW, capH)
        buffer.rewind()
        val copied = runCatching { scratch.copyPixelsFromBuffer(buffer) }.isSuccess
        image.close()
        if (!copied) return null // 缓冲与目标尺寸不匹配（resize 瞬间）→ 丢弃本次，下帧重建
        val frame = if (rowPadding == 0) scratch else Bitmap.createBitmap(scratch, 0, 0, capW, capH)
        val out = normalizeRotation(frame, rotation)
        if (out != null) lastFrame = out
        maybeAutoResizeLayout(out)
        return out
    }

    /**
     * 取一帧**独立副本**：调用方需长期持有（如 RPC 截帧要在锁外做 JPEG 压缩）时使用。
     * 代价是每帧一次分配，因此只用于低频路径——高频推流请用 [grabBitmapOrLast] 并及时消费。
     */
    fun grabBitmapCopy(): Bitmap? = synchronized(grabLock) {
        val src = grabBitmap() ?: lastFrame ?: return null
        if (src.isRecycled) return null
        runCatching { Bitmap.createBitmap(src) }.getOrNull()
    }

    /**
     * 抓帧缓冲（双槽轮换）：
     * 单槽会在下一次抓帧时覆盖 [lastFrame]（断帧回放依赖它），因此用两个槽交替写入，
     * 保证"最近一帧"在下一次抓帧后仍然完整；消费方在两次抓帧之内用完即可。
     */
    private fun obtainScratch(w: Int, h: Int): Bitmap {
        val slotIsA = scratchFlip
        scratchFlip = !scratchFlip
        val cur = if (slotIsA) scratchA else scratchB
        if (cur != null && !cur.isRecycled && cur.width == w && cur.height == h) return cur
        val fresh = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        if (slotIsA) scratchA = fresh else scratchB = fresh
        // 尺寸变化：旧缓冲不 recycle（可能仍被 lastFrame 引用），交给 GC 回收
        return fresh
    }

    /** 抓帧；断帧（null）时回退最近一帧重发，保证消费方（流/AI 感知）在静止画面下仍有图可出。 */
    fun grabBitmapOrLast(): Bitmap? = grabBitmap() ?: lastFrame

    // ---- 横屏自动适配（本 ROM 不旋转 VD：竖屏容器内 letterbox 横屏内容 → 自动 resize 交换宽高） ----

    private var lastLayoutCheckAt = 0L
    private var layoutTarget: Pair<Int, Int>? = null
    private var layoutConfirm = 0

    /**
     * 内容带检测：竖屏帧内出现横向内容带（游戏等横屏 App 被 letterbox 成顶部小条）→ 把 VD
     * resize 成横屏（App 随之铺满，截帧拿到完整分辨率）；横屏帧内出现竖向内容带（回竖屏 App）
     * → 反向 resize 还原。节流检测 + 连续确认，避免抖动/瞬时页面误触发。
     */
    private fun maybeAutoResizeLayout(bmp: Bitmap?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLayoutCheckAt < AUTO_RESIZE_CHECK_MS) return
        lastLayoutCheckAt = now
        if (resizeFailed || bmp == null || bmp.width <= 0 || bmp.height <= 0) return

        val band = contentBand(bmp) ?: return // 全黑/不可判定
        val (left, top, right, bottom) = band
        val fillW = right - left
        val fillH = bottom - top
        if (fillW <= 0f || fillH <= 0f) return
        val aspect = fillW / fillH
        val frameLandscape = bmp.width > bmp.height

        val landscapeTrigger = !frameLandscape && aspect > 1.35f && fillW > 0.55f && fillH < 0.65f
        val portraitTrigger = frameLandscape && aspect < 0.85f && fillH > 0.6f && fillW < 0.6f
        val target = when {
            landscapeTrigger -> if (capW > capH) null else (capH to capW)   // 横屏需求 → 交换为横屏
            portraitTrigger -> if (capW < capH) null else (capH to capW)    // 竖屏需求 → 交换为竖屏
            else -> null
        }

        if (target != null) {
            if (target == layoutTarget) layoutConfirm++ else {
                layoutTarget = target
                layoutConfirm = 1
            }
            if (layoutConfirm >= AUTO_RESIZE_CONFIRM) {
                layoutTarget = null
                layoutConfirm = 0
                println("[fall-shell] auto-layout: content=${(fillW * 100).toInt()}x${(fillH * 100).toInt()}% -> resize ${capW}x$capH -> ${target.first}x${target.second}")
                System.out.flush()
                if (resizeCapture(target.first, target.second)) {
                    width = target.first
                    height = target.second
                }
            }
        } else {
            layoutTarget = null
            layoutConfirm = 0
        }
    }

    /** 下采样扫描非黑内容包围盒（归一化 0..1）。全黑/占比过低返回 null。 */
    private fun contentBand(bmp: Bitmap): FloatArray? {
        val w = bmp.width
        val h = bmp.height
        val n = w * h
        if (n <= 0) return null
        val pixels = IntArray(n)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val rowStep = (h / 48).coerceAtLeast(1)
        val colStep = (w / 24).coerceAtLeast(1)
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        var bright = 0L
        var samples = 0L
        var y = 0
        while (y < h) {
            var x = 0
            var base = y * w
            while (x < w) {
                val p = pixels[base + x]
                val luma = (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
                if (luma > 24) { // 非黑（黑边阈值）
                    bright++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                samples++
                x += colStep
            }
            y += rowStep
        }
        if (samples <= 0) return null
        val fill = bright.toFloat() / samples
        if (fill < 0.02f || maxX < 0) return null // 全黑/加载期/信息极稀疏
        return floatArrayOf(
            minX.toFloat() / w, minY.toFloat() / h,
            (maxX + 1).toFloat() / w, (maxY + 1).toFloat() / h,
        )
    }

    /**
     * 同步缓冲与 VD 尺寸：显示旋转（横屏 App）后 VD 逻辑尺寸变为旋转后值，若仍用固定竖版
     * ImageReader 截帧，内容会被 SurfaceFlinger 缩小压进竖版缓冲。这里把 ImageReader 与 VD
     * 一并 resize 到旋转后逻辑尺寸 → 截帧为 1:1 完整画面。resize 失败回退纯旋转转正。
     */
    private fun syncCaptureSize() {
        if (resizeFailed) return
        val rot = rotation
        val swapped = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270
        val m = android.util.DisplayMetrics()
        runCatching { display?.getRealMetrics(m) }
        var tw = m.widthPixels
        var th = m.heightPixels
        if (tw <= 0 || th <= 0) return
        // 个别 ROM 的 metrics 不随旋转更新 → 按创建尺寸交换兜底
        if (tw == initialW && th == initialH && swapped) {
            tw = initialH
            th = initialW
        }
        if (tw == capW && th == capH) return
        resizeCapture(tw, th)
    }

    /** 重建 ImageReader 到新尺寸 + VD.setSurface/resize（公开 API，API 21+）。返回是否成功。 */
    private fun resizeCapture(w: Int, h: Int): Boolean {
        val newReader = runCatching { ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2) }.getOrNull() ?: return false
        val vd = virtualDisplay as? android.hardware.display.VirtualDisplay
        val ok = if (vd != null) {
            runCatching {
                vd.setSurface(newReader.surface)
                vd.resize(w, h, dpi)
                true
            }.getOrElse {
                println("[fall-shell] VD setSurface/resize failed: ${it.message}")
                false
            }
        } else false
        if (!ok) {
            runCatching { newReader.close() }
            resizeFailed = true
            System.err.println("[fall-shell] VD resize unsupported (no VirtualDisplay), fallback rotation-only")
            System.err.flush()
            return false
        }
        val old = captureReader
        captureReader = newReader
        capW = w
        capH = h
        println("[fall-shell] VD resize -> ${w}x$h"); System.out.flush()
        runCatching { old.close() }
        return true
    }

    /**
     * 按显示旋转把捕获帧旋回正向（resize 兜底 + SF 输出仍横置时补偿）。
     * 若某 ROM 方向相反（画面上下颠倒），交换 ROTATION_90/ROTATION_270 两个分支即可。
     */
    private fun normalizeRotation(bmp: Bitmap, rot: Int): Bitmap? {
        if (bmp == null) return null
        val deg = when (rot) {
            Surface.ROTATION_90 -> 270f   // 内容顺时针 90° → 逆时针旋回
            Surface.ROTATION_180 -> 180f
            Surface.ROTATION_270 -> 90f   // 内容逆时针 90° → 顺时针旋回
            else -> 0f
        }
        if (deg == 0f) {
            width = bmp.width
            height = bmp.height
            return bmp
        }
        val m = Matrix().apply { postRotate(deg, bmp.width / 2f, bmp.height / 2f) }
        val out = runCatching {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }.getOrNull() ?: run {
            width = bmp.width
            height = bmp.height
            return null
        }
        if (out !== bmp) bmp.recycle()
        width = out.width
        height = out.height
        return out
    }

    fun destroy() {
        synchronized(grabLock) {
            // 复用缓冲需随会话释放；lastFrame 若指向其中之一则只清引用（避免复用已 recycle 的位图）
            val last = lastFrame
            listOfNotNull(scratchA, scratchB).distinct().forEach { b ->
                if (b !== last) runCatching { b.recycle() }
            }
            scratchA = null
            scratchB = null
            lastFrame = null
        }
        runCatching {
            val m = DisplayManager::class.java.methods
                .firstOrNull { it.name == "releaseVirtualDisplay" && it.parameterCount == 1 }
            m?.invoke(dm, virtualDisplay)
        }
        runCatching { captureReader.close() }
    }
}
package com.fall.shell.display

import android.graphics.Bitmap
import com.fall.shell.capture.FlingerFrameCapture

/**
 * 统一取帧抽象（感知/送显双路径共用，豆包式纯虚拟屏方案 §4.3）：
 *
 * - [ImageReaderFrameProvider]：现状 ImageReader 路径（任何 ROM 可用，默认兜底，保持零回归）；
 * - [FlingerFrameProvider]：GPU 直读（screencap -d / su 1000 + SurfaceFlinger 反射），
 *   spike 未通过或取帧失败时返回 null，调用方回退 [ImageReaderFrameProvider]，不阻断任务。
 */
interface FrameProvider {
    val width: Int
    val height: Int

    /** 距最近一次「真实新帧」的毫秒数（从未抓到 → Long.MAX_VALUE）；
     *  送显用它判断内容是否动态（动态才值得走镜像 0-copy，静止直接走 EGL 秒出首帧）。 */
    val freshAgoMs: Long

    /** 最近一次 [grab] 是否拿到**真实新帧**（false = 取到的是断帧回放的同一张图，可节流不重发）。 */
    val grabWasFresh: Boolean

    /** 取当前帧；失败返回 null（调用方决定回退/降级）。实现需保证串行取帧。
     *  **返回的位图可能被下一次 [grab] 覆盖**（抓帧缓冲复用）→ 调用方必须在两次抓帧内消费完；
     *  需要长期持有请用 [grabCopy]。 */
    fun grab(): Bitmap?

    /** 取一帧独立副本（每帧一次分配）：适合低频、需要在锁外继续处理（压缩/上传）的调用方。 */
    fun grabCopy(): Bitmap? = grab()?.let { runCatching { Bitmap.createBitmap(it) }.getOrNull() }
}

/** ImageReader 路径（现状）：直接复用会话截帧逻辑，作为所有取帧的兜底。
 *  断帧（SF 停投）时回退最近一帧重发，保证静止画面下流/AI 感知持续有图。 */
class ImageReaderFrameProvider(
    private val session: VirtualDisplaySession,
) : FrameProvider {
    override val width: Int get() = session.width
    override val height: Int get() = session.height
    override val freshAgoMs: Long get() = session.freshAgoMs
    override val grabWasFresh: Boolean get() = session.grabWasFresh
    override fun grab(): Bitmap? = synchronized(this) { session.grabBitmapOrLast() }
    override fun grabCopy(): Bitmap? = session.grabBitmapCopy()
}

/**
 * GPU 直读路径（送显升级主体）：
 * 取帧顺序 = screencap -d（台阶0，shell uid 无特权可用，与 captureMainFrame 同族已验证）→
 * SurfaceControl.captureDisplay 反射（台阶1，FLAG_SECURE 受 CAPTURE_SECURE_VIDEO_OUTPUT
 * 签名权限约束，shell uid 下将 SecurityException → 返回 null；su 1000 特权上下文 spike 后启用）→ null。
 */
class FlingerFrameProvider(
    private val displayId: Int,
    private val displayWidth: Int,
    private val displayHeight: Int,
) : FrameProvider {
    override val width: Int get() = displayWidth
    override val height: Int get() = displayHeight
    override val freshAgoMs: Long get() = 0L // 每帧实时直读，恒视为「刚出的新帧」
    override val grabWasFresh: Boolean get() = true
    override fun grab(): Bitmap? = synchronized(this) {
        FlingerFrameCapture.grab(displayId).also { result ->
            if (result == null) {
                System.err.println("[fall-shell] FlingerFrameProvider grab failed, fallback to ImageReader")
                System.err.flush()
            }
        }
    }
}
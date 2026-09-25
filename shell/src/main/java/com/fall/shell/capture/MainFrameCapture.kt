package com.fall.shell.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 物理主屏截帧（阶段 2 spike：不依赖截图 API 权限，内存取帧语义 READ_FRAME_BUFFER）。
 *
 * 方案 A（已落地）：`screencap -p` 子进程捕获主屏 → PNG bytes 一次读入 → Bitmap → JPEG base64。
 * 绕截图 API 的权限模型（非录屏权限），shell uid 可直接执行；与现代合成管线无耦合（screencap 走 SurfaceFlinger）。
 *
 * 方案 B（CAPTURE_SECURE_VIDEO_OUTPUT，root 特权上下文 + SurfaceFlinger captureScreen）：
 * 目标是绕过 FLAG_SECURE 黑屏（银行/支付/微信零钱页）。该权限为 signature 级，普通 App 拿不到；
 * 且 DRM 视频内容预期仍黑。**需真机验证后决定是否落地**——本轮不在代码层实现。
 */
object MainFrameCapture {

    /**
     * 截取物理主屏当前帧 → JPEG(base64)。
     * @return Triple(width, height, data-b64)；失败返回 null（捕获失败/解码失败）。
     */
    fun grab(scale: Float = 1f, quality: Int = 70): Triple<Int, Int, String>? {
        val bmp = runCatching {
            val p = ProcessBuilder("screencap", "-p").redirectErrorStream(true).start()
            // 先读完 stdout（screencap 全屏 PNG 数 MB，管道缓冲有限，必须先消费再 waitFor 防死锁）
            val png = p.inputStream.use { it.readBytes() }
            p.waitFor()
            BitmapFactory.decodeByteArray(png, 0, png.size)
        }.getOrNull() ?: return null

        val effScale = scale.coerceIn(0.25f, 1f)
        val out = if (effScale >= 1f) {
            bmp
        } else {
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * effScale).toInt().coerceAtLeast(1),
                (bmp.height * effScale).toInt().coerceAtLeast(1),
                true,
            )
        }
        val stream = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 100), stream)
        val w = bmp.width
        val h = bmp.height
        if (out !== bmp) out.recycle()
        bmp.recycle()
        return Triple(w, h, Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP))
    }
}
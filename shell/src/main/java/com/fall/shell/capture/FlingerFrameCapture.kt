package com.fall.shell.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.lang.reflect.Method

/**
 * GPU 直读取帧（豆包式纯虚拟屏 §4.1/§4.2）：
 *
 * 台阶 0：`screencap -d <displayId>` 子进程按屏直取（Android 10+，
 * 与已落地的 `captureMainFrame`（screencap 同族、本 ROM 已验证）机制一致）。
 *
 * 台阶 1：`su 1000` 特权上下文 + `SurfaceFlinger.captureScreen/captureLayers` 反射。
 * 普通 shell uid 调 captureScreen（受 CAPTURE_SECURE_VIDEO_OUTPUT 签名级权限限制）会回退/被拒；
 * 通过 [grabViaSurfaceControl] 用 SurfaceControl 反射尝试（shell uid 下预期失败返回 null，
 * 由调用方回退）；`su 1000` 起 app_process 的完整 spike 见方案 §4.2，真机验证后回填。
 */
object FlingerFrameCapture {

    /**
     * 取某一 display 的当前帧：台阶 0（screencap -d）→ 台阶 1（SurfaceControl.captureDisplay）。
     * 全部失败返回 null（调用方回退 ImageReader，不阻断任务）。
     */
    fun grab(displayId: Int): Bitmap? {
        grabViaScreencap(displayId)?.let { return it }
        grabViaSurfaceControl(displayId)?.let { return it }
        return null
    }

    /** 台阶 0：`screencap -d <displayId>`（PNG 一次读入 → Bitmap）。 */
    fun grabViaScreencap(displayId: Int): Bitmap? = runCatching {
        val p = ProcessBuilder("screencap", "-d", displayId.toString())
            .redirectErrorStream(true)
            .start()
        // 先读完 stdout（全屏 PNG 数 MB，先消费再 waitFor 防管道死锁）
        val png = p.inputStream.use { it.readBytes() }
        p.waitFor()
        BitmapFactory.decodeByteArray(png, 0, png.size)
    }.getOrNull()

    /**
     * 台阶 1：反射 SurfaceControl.captureDisplay(DisplayCaptureArgs) → ScreenshotHardwareBuffer → Bitmap。
     * shell uid 无 CAPTURE_SECURE_VIDEO_OUTPUT 时抛 SecurityException → null（预期）；
     * `su 1000` 特权上下文下应可出图（含 FLAG_SECURE 页；DRM 预期仍黑，真机 spike 确认）。
     */
    fun grabViaSurfaceControl(displayId: Int): Bitmap? = runCatching {
        val surControl = Class.forName("android.view.SurfaceControl")
        val argsClass = Class.forName("android.view.SurfaceControl\$DisplayCaptureArgs")

        // DisplayCaptureArgs.Builder(displayToken) → build()
        val builderClazz = Class.forName("android.view.SurfaceControl\$DisplayCaptureArgs\$Builder")
        val getToken = surControl.methods.firstOrNull { m ->
            m.name == "getPhysicalDisplayToken" && m.parameterTypes.size == 1
                && m.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: throw NoSuchMethodException("getPhysicalDisplayToken")
        val token = getToken.invoke(null, displayId) ?: throw IllegalStateException("no display token")
        val builder = builderClazz.getConstructor(Any::class.java).newInstance(token)
        val build = builderClazz.methods.firstOrNull { it.name == "build" } ?: throw NoSuchMethodException("args.build")
        val args = build.invoke(builder)

        // captureDisplay(args) → ScreenshotHardwareBuffer；asBitmap() → Bitmap
        val capture: Method = surControl.methods.firstOrNull {
            it.name == "captureDisplay" && it.parameterTypes.size == 1
        } ?: surControl.declaredMethods.firstOrNull {
            it.name == "captureDisplay" && it.parameterTypes.size == 1
        }?.apply { isAccessible = true } ?: throw NoSuchMethodException("captureDisplay")
        val captured = capture.invoke(null, args) ?: throw IllegalStateException("captureDisplay returned null")
        val asBitmap = captured.javaClass.getMethod("asBitmap")
        asBitmap.invoke(captured) as? Bitmap
    }.getOrNull()
}
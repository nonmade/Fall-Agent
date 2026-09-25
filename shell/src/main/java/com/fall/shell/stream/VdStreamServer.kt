package com.fall.shell.stream

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
import com.fall.shell.display.FrameProvider
import com.fall.shell.display.VirtualDisplaySession
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * 送显链路（豆包式纯虚拟屏 台阶 2/3）：
 * 独立端口（默认 43111）推流，绕开 base64+JSON 的截帧轮询，直发字节流。
 *
 * 双模式（帧率跟随 VD 刷新率，最高 120fps）：
 * - [StreamFormat.JPEG]（台阶 2 过渡）：FrameProvider.grab() → JPEG 字节直发（60fps 目标，CPU 压缩上限）；
 * - [StreamFormat.H264]（台阶 3 主线）：H.264 硬编，帧率 = 虚拟屏刷新率（原生分辨率 + 120Hz）；
 *   镜像 0-copy（VirtualDisplayConfig 镜像源 VD → encoder input surface）优先，静止内容/镜像不可用时
 *   回退 EGL 上传逐帧入编。
 *
 * 帧协议（长度前缀直发，大端序）：
 * ```
 * [4B length][1B type][payload]
 *   type=0: config JSON {"format":"jpeg|h264","width":..,"height":..,"csd0":"b64","csd1":"b64"}
 *   type=1: 一帧数据（JPEG 字节 或 H.264 annex-b；len=1 的空帧为保活心跳，客户端跳过）
 * ```
 * 生命周期：`start()` → ServerSocket accept 客户端 → 推流；客户端断开或 `stop()` → 停流释放。
 */
class VdStreamServer(
    private val provider: FrameProvider,
    private val streamPort: Int = 43111,
    private val mirrorDisplayId: Int = -1,
    private val mirrorDpi: Int = 320,
    private val vdDm: android.hardware.display.DisplayManager? = null,
    /** 目标刷新率/帧率（Hz）：与源 VD 一致（原生分辨率 + 120Hz）。 */
    private val refreshRateHz: Float = 120f,
) : AutoCloseable {

    /** 推流帧率 = 刷新率（30~120 收敛，避免异常值打爆编码）。 */
    private val fps: Int = refreshRateHz.roundToInt().coerceIn(30, 120)

    enum class StreamFormat { JPEG, H264 }

    val port: Int get() = streamPort

    private val running = AtomicBoolean(false)
    private val sessions = CopyOnWriteArrayList<Thread>()
    private var serverSocket: ServerSocket? = null

    fun start(format: StreamFormat) {
        if (!running.compareAndSet(false, true)) return
        val server = ServerSocket(streamPort, 4, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        println("[fall-shell] stream listening 127.0.0.1:$streamPort format=$format")
        Thread({
            try {
                // 单推流会话（预览一次只开一路）：新客户端直接**接管**——旧会话在空屏/静止时可能
                // 长时间无帧可发、感知不到对端已断开（僵尸会话会占死唯一会话位），故主动中断。
                while (running.get()) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    if (sessions.isNotEmpty()) {
                        println("[fall-shell][stream] new client take over, drop ${sessions.size} stale session(s)")
                        sessions.forEach { it.interrupt() }
                        sessions.clear()
                    }
                    val t = Thread({ session(client, format) }, "fall-stream-" + format)
                    sessions.add(t)
                    t.start()
                }
            } catch (e: Throwable) {
                println("[fall-shell] stream accept stopped: ${e.message}")
            } finally {
                runCatching { server.close() }
            }
        }, "fall-stream-accept").apply { isDaemon = true; start() }
    }

    private fun session(client: Socket, format: StreamFormat) {
        val current = Thread.currentThread()
        try {
            val out = BufferedOutputStream(client.getOutputStream())
            when (format) {
                StreamFormat.JPEG -> jpegLoop(out, current)
                StreamFormat.H264 -> {
                    // 镜像直喂（VirtualDisplayConfig.setDisplayIdToMirror → encoder input surface，0-copy）
                    // 仅在有效时启用，否则沿用原 EGL/NV12 路径
                    if (vdDm != null && mirrorDisplayId >= 0) mirrorH264Loop(out, current)
                    else h264Loop(out, current)
                }
            }
        } catch (e: Throwable) {
            println("[fall-shell] stream session ended: ${e.message}")
        } finally {
            runCatching { client.close() }
            sessions.remove(current)
        }
    }

    /**
     * 镜像直喂 0-copy 推流（2026-09-24 验证可行）：用 Android 13+ VirtualDisplayConfig 建第二个 VD
     * `setDisplayIdToMirror(源VD)` + `setSurface(encoder input surface)`，SF 把源 VD 合成结果直接
     * 写进编码器（无 CPU 像素拷贝）；感知链（ImageReader）不受影响。层序：
     * 启动 → 等 encoder 产出 SPS/PPS → 发 config JSON + 带内参数集 → 主循环 drain 转发 + 周期
     * 关键帧 + 尺寸变化（横竖屏切换）自动重建。
     *
     * 首帧提速（2026-09-25）：启动只等 MIRROR_PRIME_MS（源 VD 有内容时创建镜像即触发一次合成，
   实测 +100~200ms 到参数集）；空屏（无任何合成）才回退 EGL。**不再按"源 VD 是否动态"预判**——
   预判会把"静止页面 + 稍后播视频"这类场景错误地压到 EGL（原生分辨率下仅 ~27fps）。
     */
    private fun mirrorH264Loop(out: BufferedOutputStream, me: Thread, upgradeTried: Boolean = false) {
        val dm = vdDm ?: return
        val t0 = System.currentTimeMillis()
        fun log(msg: String) {
            println("[fall-shell][stream] +${System.currentTimeMillis() - t0}ms $msg")
            System.out.flush()
        }
        val writer = SessionWriter(out)
        // 先建镜像（0-copy）——不再用「距上次抓帧多久」预判静止：该值会被观测间隔污染
        // （没人抓帧时它一直增长，动态内容也会被误判成静止而错过 0-copy）。
        // 静止与否交给 startup() 里 20ms 粒度的实时抓帧探测 + 首帧窗口快速回退。
        val bufInfo = MediaCodec.BufferInfo()
        val csd0 = AtomicReference<ByteArray?>(null)
        val csd1 = AtomicReference<ByteArray?>(null)
        var width = provider.width
        var height = provider.height
        val syncEvery = (fps / 2).coerceAtLeast(1)

        fun drainCodec(codec: MediaCodec, forward: Boolean): Boolean {
            while (true) {
                val idx = codec.dequeueOutputBuffer(bufInfo, 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return true
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx) ?: run { codec.releaseOutputBuffer(idx, false); continue }
                        val bytes = ByteArray(bufInfo.size)
                        buf.position(bufInfo.offset)
                        buf.get(bytes)
                        codec.releaseOutputBuffer(idx, false)
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            if (csd0.get() == null) csd0.set(bytes) else csd1.set(bytes)
                            if (forward && bytes.isNotEmpty() && !writer.sendBlock(bytes)) return false
                        } else {
                            if (forward && bytes.isNotEmpty() && !writer.sendBlock(bytes)) return false
                        }
                    }
                    else -> return true
                }
            }
        }

        fun requestSync(codec: MediaCodec) {
            runCatching { codec.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }

        // 镜像 VD + 编码器（尺寸跟随 provider 当前值；变化时重建）
        fun teardown(codec: MediaCodec?, vdb: Any?) {
            runCatching { vdb?.let { it.javaClass.getMethod("release").invoke(it) } }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }

        fun startCodec(): Pair<MediaCodec, android.view.Surface> {
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            // createInputSurface 必须在 start() 之前（Configured 状态）
            val input = c.createInputSurface()
            c.start()
            return c to input
        }

        // 镜像 VD：请求刷新率与源 VD 一致（原生分辨率 + 120Hz）
        fun createMirror(input: android.view.Surface): Any? =
            VirtualDisplaySession.createVirtualDisplay(
                dm, "fall-mirror", width, height, mirrorDpi, input, MIRROR_FLAGS, refreshRateHz, mirrorDisplayId,
            ) ?: run {
                log("镜像 VD 创建失败（VirtualDisplayConfig 不可用）")
                null
            }

        // ---- 启动（首帧交付 config） ----
        var codec: MediaCodec? = null
        var vdb: Any? = null
        fun startup(): Boolean {
            csd0.set(null); csd1.set(null)
            val tCreate = System.currentTimeMillis()
            val (c, input) = startCodec()
            codec = c
            vdb = createMirror(input)
            if (vdb == null) {
                teardown(c, null)
                return false
            }
            log("编码器+镜像 VD 就绪 ${System.currentTimeMillis() - tCreate}ms，等首帧参数集")
            // 等 encoder 首帧参数集（镜像投帧节奏=SF，最长 MIRROR_PRIME_MS）：
            // **不要**在这里因为「源 VD 暂时没新帧」而提前放弃——源 VD 有内容时，创建镜像 VD 本身
            // 就会触发一次合成（实测 +100~200ms 内到参数集），此时若判"静止"退回 EGL，等于把
            // 60~120fps 的 0-copy 路径换成原生分辨率下仅 ~27fps 的 EGL 上传路径（播放视频时尤其明显）。
            var waited = 0
            while (waited < MIRROR_PRIME_MS && csd0.get() == null) {
                drainCodec(c, forward = false)
                sleep(20)
                waited += 20
            }
            if (csd0.get() == null) {
                log("镜像 ${MIRROR_PRIME_MS}ms 未拿到参数集（源 VD 无合成，如空屏）→ 回退 EGL")
                teardown(c, vdb)
                return false
            }
            requestSync(c)
            val cfg = JSONObject()
                .put("format", "h264")
                .put("width", width)
                .put("height", height)
                .put("csd0", csd0.get()?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
                .put("csd1", csd1.get()?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
            writer.sendConfig(cfg.toString().toByteArray(Charsets.UTF_8))
            if (csd0.get() != null) writer.sendBlock(csd0.get()!!)
            if (csd1.get() != null) writer.sendBlock(csd1.get()!!)
            log("镜像首 config 已发出（${fps}fps 目标）")
            return true
        }

        if (!startup()) {
            log("镜像 h264 启动失败，回退 EGL/NV12")
            return h264Loop(out, me, upgradeTried)
        }

        var frameNo = 0
        try {
            while (running.get() && !me.isInterrupted) {
                val w = provider.width
                val h = provider.height
                if (w != width || h != height) {
                    // 尺寸变化（横竖屏切换导致 VD resize）→ **结束会话**让客户端重连拿新配置。
                    // 不能原地重建：客户端 FrameStreamClient 只在连接建立时读一次 config，中途重发的
                    // config 会被读循环丢弃（只处理 TYPE_FRAME），而 H264SurfaceDecoder 已按旧尺寸启动
                    // 且不校验尺寸 → 会把新尺寸帧喂给旧解码器（花屏/停帧）。EGL 与 JPEG 路径同样是 return。
                    log("镜像流尺寸变化 ${width}x$height -> ${w}x$h，结束会话等待客户端重连")
                    return
                }
                if (++frameNo % syncEvery == 0) codec?.let { requestSync(it) }
                val ok = codec?.let { drainCodec(it, forward = true) } ?: true
                if (!ok) return
                if (!writer.keepAliveIfIdle()) {
                    log("心跳失败（对端断开）→ 结束会话")
                    return
                }
                // 内容静止时不切走 EGL：镜像只是暂时没新帧（画面停在最后一帧，观感正确且零开销），
                // 一旦源 VD 再次合成即自动续流；切 EGL 反而要满帧率回放重复帧、白烧 CPU/GPU。
                sleep(1000L / fps)
            }
        } finally {
            teardown(codec, vdb)
        }
    }

    /** 台阶 3：H.264 硬编推流（EGL surface-input 主 / NV12 兜底，帧率=VD 刷新率）。 */
    private fun h264Loop(out: BufferedOutputStream, me: Thread, upgradeTried: Boolean = false) {
        // 1) 编码器需先有输入帧才会产出 CODEC_CONFIG（SPS/PPS）→ 启动即推首帧再收集参数集；
        // 2) 客户端 readConfig 断言流的第一个字节必须是 config JSON(type=0)，SPS/PPS 不能抢跑
        //    → 启动阶段 drain 只收不转，先发 config JSON 再补发 SPS/PPS，再进主循环推帧；
        // 3) 主路径 EGL surface-input（alpha 强制 1.0，PTS 帧序增量）；EGL 启动失败回退 NV12。
        var encW = provider.width
        var encH = provider.height
        val t0 = System.currentTimeMillis()
        fun log(msg: String) {
            println("[fall-shell][stream] +${System.currentTimeMillis() - t0}ms $msg")
            System.out.flush()
        }
        val writer = SessionWriter(out)
        val syncEvery = (fps / 2).coerceAtLeast(1)

        fun tryStart(encoder: H264Encoder): Boolean = runCatching {
            encoder.start()
            var firstPushed = false
            var tries = 0
            // 拿到参数集就立即出 config（不要把一个固定次数的循环跑完，白白多等 300ms）
            while (tries++ < H264ConfigWaitTries) {
                if (!firstPushed) {
                    // 空屏兜底：源 VD 尚无任何帧（lastFrame 回放也为空）→ 推黑帧，
                    // 保证 SPS/PPS/IDR 一定产出，避免"整流静默 → 客户端黑屏等超时"
                    val f = provider.grab() ?: blackFrame(encW, encH)
                    encoder.pushFrame(f)
                    firstPushed = true
                }
                // 启动阶段：只收集不转发（避免 SPS/PPS 抢在 config JSON 之前到客户端）
                val drainOk = encoder.drain { _ -> true }
                if (!drainOk) break
                // 本 ROM 编码器把 SPS+PPS 合并在一个 CODEC_CONFIG 缓冲里（csd1 恒为 null）→ 只等 csd0
                if (encoder.payloadCsd.first != null) break
                sleep(10)
            }
            val csd = encoder.payloadCsd
            // 启动即请求关键帧：确保首个可用接入点就是 IDR（部分 ROM 首帧不自动产 IDR）
            encoder.requestSyncFrame()
            val config = JSONObject()
                .put("format", "h264")
                .put("width", encW)
                .put("height", encH)
                .put("csd0", csd.first?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
                .put("csd1", csd.second?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
            writer.sendConfig(config.toString().toByteArray(Charsets.UTF_8))
            // 带内参数集：client 建好解码器后先喂 SPS/PPS，再喂首个 IDR（byte-stream 兼容）
            if (csd.first != null) writer.sendBlock(csd.first!!)
            if (csd.second != null) writer.sendBlock(csd.second!!)
            log("EGL 首 config 已发出 ${encW}x$encH（${fps}fps 目标）")
            true
        }.getOrElse { e ->
            log("h264 encoder start failed: ${e.message}")
            false
        }

        var encoder = H264Encoder(encW, encH, useEgl = true, fps = fps)
        var started = tryStart(encoder)
        if (!started) {
            runCatching { encoder.release() }
            log("h264 EGL 编码器不可用，回退 NV12")
            encoder = H264Encoder(encW, encH, useEgl = false, fps = fps)
            started = tryStart(encoder)
        }
        if (!started) {
            runCatching { encoder.release() }
            return
        }

        val frameInterval = 1000L / fps
        var frameNo = 0
        var freshStreak = 0
        try {
            while (running.get() && !me.isInterrupted) {
                // 显示旋转导致的尺寸变化 → 结束会话；客户端断线后重连，新会话按新尺寸重新配置
                val w = provider.width
                val h = provider.height
                if (w != encW || h != encH) {
                    log("h264 stream size changed ${encW}x$encH -> ${w}x$h, restart session")
                    return
                }
                val bmp = provider.grab()
                if (bmp != null) {
                    // 不节流：按目标帧率持续入编（静止内容也回放最后一帧，避免"帧率忽高忽低"）
                    encoder.pushFrame(bmp)
                    // 部分 ROM 忽略 I-frame-interval，显式周期请求关键帧保解码器能随机接入
                    if (++frameNo % syncEvery == 0) encoder.requestSyncFrame()
                    // 内容持续动态（连续 ~1s 真实新帧）→ 升级镜像 0-copy：
                    // EGL 在原生分辨率下只能 ~27fps，镜像是 60~120fps（每会话最多升级一次，避免抖动）
                    if (!upgradeTried && vdDm != null && mirrorDisplayId >= 0) {
                        freshStreak = if (provider.grabWasFresh) freshStreak + 1 else 0
                        if (freshStreak >= UPGRADE_FRESH_STREAK) {
                            log("内容持续动态（$freshStreak 帧新帧）→ 升级镜像 0-copy 推流")
                            runCatching { encoder.release() }
                            return mirrorH264Loop(out, me, upgradeTried = true)
                        }
                    }
                }
                val drainFailed = !encoder.drain { writer.sendBlock(it) }
                if (drainFailed) return
                if (!writer.keepAliveIfIdle()) {
                    log("心跳失败（对端断开）→ 结束会话")
                    return
                }
                sleep(frameInterval)
            }
        } finally {
            runCatching { encoder.release() }
        }
    }

    /** 台阶 2：JPEG 字节直发（60fps 目标；CPU 压缩上限，静止内容同样 1s 心跳节流）。 */
    private fun jpegLoop(out: BufferedOutputStream, me: Thread) {
        var w = provider.width
        var h = provider.height
        val writer = SessionWriter(out)
        val config = JSONObject()
            .put("format", "jpeg")
            .put("width", w)
            .put("height", h)
        writer.sendConfig(config.toString().toByteArray(Charsets.UTF_8))
        val gap = 1000L / 60
        while (running.get() && !me.isInterrupted) {
            // 尺寸变化（旋转）→ 结束会话让客户端重连拿新配置/新尺寸
            val cw = provider.width
            val ch = provider.height
            if (cw != w || ch != h) {
                println("[fall-shell] jpeg stream size changed ${w}x$h -> ${cw}x$ch, restart session")
                return
            }
            val bmp = provider.grab() ?: run { sleep(gap); continue }
            val jpeg = jpegBytes(bmp, 85) ?: run { sleep(gap); continue }
            if (!writer.sendBlock(jpeg)) return
            sleep(gap)
        }
    }

    /** 会话发送器：记录最近发送时刻，供空帧心跳（len=1，客户端跳过）保活并探测对端断开。 */
    private inner class SessionWriter(private val out: BufferedOutputStream) {
        @Volatile
        var lastSentAt = System.currentTimeMillis()

        fun sendBlock(payload: ByteArray): Boolean = runCatching {
            sendFrame(out, TYPE_FRAME, payload)
            true
        }.getOrDefault(false).also { if (it) lastSentAt = System.currentTimeMillis() }

        fun sendConfig(payload: ByteArray): Boolean = runCatching {
            sendFrame(out, TYPE_CONFIG, payload)
            true
        }.getOrDefault(false).also { if (it) lastSentAt = System.currentTimeMillis() }

        /** 空闲超过 [KEEPALIVE_IDLE_MS] 发一次空帧心跳；对端已断开时返回 false。 */
        fun keepAliveIfIdle(): Boolean {
            if (System.currentTimeMillis() - lastSentAt < KEEPALIVE_IDLE_MS) return true
            return sendBlock(ByteArray(0))
        }
    }

    /** 编码器输入用的黑帧（空屏兜底；尺寸与编码器一致）。 */
    private fun blackFrame(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w.coerceAtLeast(2), h.coerceAtLeast(2), Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLACK)
        }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun jpegBytes(bmp: Bitmap, quality: Int): ByteArray? = runCatching {
        val stream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        stream.toByteArray()
    }.getOrNull()

    private fun sendFrame(out: BufferedOutputStream, type: Int, payload: ByteArray) {
        val len = payload.size + 1
        val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(len).array()
        out.write(header)
        out.write(type)
        out.write(payload)
        out.flush()
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket?.close() }
        sessions.forEach { it.interrupt() }
        sessions.clear()
    }

    private companion object {
        const val TYPE_CONFIG = 0
        const val TYPE_FRAME = 1

        /** 等待编码器产出 SPS/PPS 的最大尝试次数（×10ms）。 */
        const val H264ConfigWaitTries = 30

        /** 会话空闲多久发一次空帧心跳（保活 + 探测对端断开）。 */
        const val KEEPALIVE_IDLE_MS = 1000L

        /** 镜像路径等首帧参数集的最长窗口（内容动态时通常远快于此）。 */
        const val MIRROR_PRIME_MS = 1200

        /** EGL 路径观察到连续这么多「真实新帧」即升级到镜像 0-copy（≈1s@60fps）。 */
        const val UPGRADE_FRESH_STREAK = 60

        /** 镜像 VD flags：VIRTUAL_DISPLAY_FLAG_PUBLIC(1) | PRESENTATION(2)。 */
        const val MIRROR_FLAGS = 3

        // EGL_RECORDABLE_ANDROID（EGL14 未导出该常量，取 AOSP 定义值 0x3142）
        const val EGL_RECORDABLE_ANDROID = 0x3142

        val VERTEX_SHADER_SRC = """
            attribute vec2 aPos;
            attribute vec2 aTex;
            varying vec2 vTex;
            void main() {
                vTex = aTex;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        /** 注意：输出 alpha 强制 1.0（该 ROM 将 alpha=0 帧当全透明黑投递，encProbe6 实测）。 */
        val FRAGMENT_SHADER_SRC = """
            precision mediump float;
            varying vec2 vTex;
            uniform sampler2D uTex;
            void main() {
                gl_FragColor = vec4(texture2D(uTex, vTex).rgb, 1.0);
            }
        """
    }

    /** H.264 编码器（双模式）：默认 EGL surface-input（0-copy），NV12 byte-buffer 兜底。
 * 2026-09-23 真机定案：daemon 里 GL 绘制/投递本无问题——分步探针（encProbe6）证明
 * shader/texture/draw/swap 全正常，之前"EGL 黑帧"是 **alpha=0 的帧被该 ROM 当作全透明黑投递**
 * （纹理 A=0→consumer 黑，A=255→红，glClear 因 alpha=1 正常）。因此：
 * ① fragment 输出强制 alpha=1.0；
 * ② PTS 按帧小增量（该 ROM 对绝对单调纳秒 PTS 不产关键帧）。
 * 仍保留 NV12 路径作回退（useEgl=false）。
 */
    internal class H264Encoder(
        private val width: Int,
        private val height: Int,
        private val useEgl: Boolean = true,
        /** 帧率（Hz）：KEY_FRAME_RATE 与每帧 PTS 增量（1e9/fps 纳秒，帧序小增量保 IDR 产出）。 */
        private val fps: Int = 60,
    ) {
        /** 每帧 PTS 增量（纳秒；NV12 路径再 /1000 转微秒）。 */
        private val frameDurationNs = 1_000_000_000L / fps.coerceAtLeast(1)

        private var codec: MediaCodec? = null
        private val bufferInfo = MediaCodec.BufferInfo()
        private val csd0 = AtomicReference<ByteArray?>(null)
        private val csd1 = AtomicReference<ByteArray?>(null)

        // NV12 兜底缓冲
        private val yuv = ByteArray(width * height * 3 / 2)
        private val pixels = IntArray(width * height)

        // EGL surface-input
        private var eglDisplay: EGLDisplay? = null
        private var eglContext: EGLContext? = null
        private var eglSurface: EGLSurface? = null
        private var textureId = 0
        private var program = 0
        private var uTexLoc = -1
        private val vertexBuffer: java.nio.FloatBuffer = java.nio.ByteBuffer
            .allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                val v = floatArrayOf(
                    -1f, -1f, 0f, 1f,
                    1f, -1f, 1f, 1f,
                    -1f, 1f, 0f, 0f,
                    1f, 1f, 1f, 0f,
                )
                put(v)
                position(0)
            }

        private var frameIndex = 0L

        /**
         * 纹理上传缓冲：按尺寸复用，避免每帧新建 13MB direct buffer。
         * 原来每帧 `allocateDirect(w*h*4)`，原生分辨率 27fps 下 ≈ 350MB/s 的分配 + 后续 GC 抖动。
         */
        private var uploadBuffer: ByteBuffer? = null

        val payloadCsd: Pair<ByteArray?, ByteArray?> get() = csd0.get() to csd1.get()

        fun start() {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    if (useEgl) MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            if (useEgl) {
                val inputSurface = c.createInputSurface()
                c.start()
                codec = c
                setupEgl(inputSurface)
            } else {
                c.start()
                codec = c
            }
        }

        // ---- EGL surface-input ----
        private fun setupEgl(inputSurface: android.view.Surface) {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfig, 0) || numConfig[0] <= 0) {
                throw IllegalStateException("eglChooseConfig failed")
            }
            val context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            val surface = EGL14.eglCreateWindowSurface(display, configs[0], inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, surface, surface, context)
            eglDisplay = display
            eglContext = context
            eglSurface = surface

            val vs = compileGles(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_SRC)
            val fs = compileGles(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SRC)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glBindAttribLocation(p, 0, "aPos")
            GLES20.glBindAttribLocation(p, 1, "aTex")
            GLES20.glLinkProgram(p)
            program = p
            uTexLoc = GLES20.glGetUniformLocation(program, "uTex")

            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            textureId = texIds[0]
        }

        private fun compileGles(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }

        /** 编码一帧：EGL 模式上传纹理/绘制/swap；NV12 模式转 YUV 入队。调用方保证串行。 */
        fun pushFrame(bmp: Bitmap) {
            val c = codec ?: return
            if (bmp.width != width || bmp.height != height) return
            if (useEgl) {
                pushEglFrame(bmp)
            } else {
                pushNv12Frame(bmp)
            }
        }

        private fun pushEglFrame(bmp: Bitmap) {
            val display = eglDisplay ?: return
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val w = bmp.width
            val h = bmp.height
            // 缓冲复用：尺寸变化时才重新分配
            val needed = w * h * 4
            var bb = uploadBuffer
            if (bb == null || bb.capacity() < needed) {
                bb = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder())
                uploadBuffer = bb
            }
            bb.clear()
            bmp.copyPixelsToBuffer(bb)
            bb.position(0)
            // 注意：不再逐字节置 A=255 —— fragment 输出已强制 vec4(rgb,1.0)，上传 A 通道无所谓；
            // 省掉每帧 13MB ByteArray 拷贝 + 循环（帧率优化）
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb,
            )
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glUniform1i(uTexLoc, 0)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glEnableVertexAttribArray(0)
            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glEnableVertexAttribArray(1)
            vertexBuffer.position(2)
            GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            // glFinish 保证每帧确定提交给编码器（去掉后 GPU 命令积压、静态页面 0 帧）。
            // 只做了缓冲复用；`glFinish → glFlush` 需真机复测（曾出现静态页 0 帧）后再改。
            GLES20.glFinish()
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, frameIndex * frameDurationNs)
            EGL14.eglSwapBuffers(display, eglSurface)
            frameIndex++
        }

        private fun pushNv12Frame(bmp: Bitmap) {
            val c = codec ?: return
            bmp.getPixels(pixels, 0, width, 0, 0, width, height)
            argbToNv12(pixels, yuv, width, height)
            val idx = try {
                c.dequeueInputBuffer(10_000)
            } catch (e: IllegalStateException) {
                return
            }
            if (idx < 0) return
            val buf = c.getInputBuffer(idx) ?: return
            buf.clear()
            buf.put(yuv)
            c.queueInputBuffer(idx, 0, yuv.size, frameIndex * (frameDurationNs / 1000), 0)
            frameIndex++
        }

        /** 显式请求关键帧（部分 ROM 的 I-frame-interval 不生效，周期兜底）。 */
        fun requestSyncFrame() {
            runCatching {
                codec?.setParameters(android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            }
        }

        /** ARGB(Bitmap.getPixels) → NV12（Rec.601 快速整数算法）。宽高需偶数。 */
        private fun argbToNv12(pix: IntArray, nv12: ByteArray, w: Int, h: Int) {
            val ySize = w * h
            var uv = ySize
            var y = 0
            while (y < h) {
                val y1 = y + 1
                var x = 0
                while (x < w) {
                    val p00 = pix[y * w + x]
                    val p01 = pix[y * w + x + 1]
                    val p10 = pix[y1 * w + x]
                    val p11 = pix[y1 * w + x + 1]
                    nv12[y * w + x] = rgbToY(p00)
                    nv12[y * w + x + 1] = rgbToY(p01)
                    nv12[y1 * w + x] = rgbToY(p10)
                    nv12[y1 * w + x + 1] = rgbToY(p11)
                    val r = (((p00 shr 16) and 0xFF) + ((p01 shr 16) and 0xFF) +
                        ((p10 shr 16) and 0xFF) + ((p11 shr 16) and 0xFF)) shr 2
                    val g = (((p00 shr 8) and 0xFF) + ((p01 shr 8) and 0xFF) +
                        ((p10 shr 8) and 0xFF) + ((p11 shr 8) and 0xFF)) shr 2
                    val b = ((p00 and 0xFF) + (p01 and 0xFF) + (p10 and 0xFF) + (p11 and 0xFF)) shr 2
                    nv12[uv++] = rgbToU(r, g, b)
                    nv12[uv++] = rgbToV(r, g, b)
                    x += 2
                }
                y += 2
            }
        }

        private fun rgbToY(p: Int): Byte {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            return ((66 * r + 129 * g + 25 * b + 128) shr 8).plus(16).toByte()
        }

        private fun rgbToU(r: Int, g: Int, b: Int): Byte =
            ((-38 * r - 74 * g + 112 * b + 128) shr 8).plus(128).toByte()

        private fun rgbToV(r: Int, g: Int, b: Int): Byte =
            ((112 * r - 94 * g - 18 * b + 128) shr 8).plus(128).toByte()

        /** 取编码器输出（annex-b + 带内参数集），逐帧回调；发送失败返回 false（对端断开）。 */
        fun drain(block: (ByteArray) -> Boolean): Boolean {
            val c = codec ?: return true
            while (true) {
                val idx = c.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return true
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    idx >= 0 -> {
                        val buf = c.getOutputBuffer(idx) ?: run { c.releaseOutputBuffer(idx, false); continue }
                        val bytes = ByteArray(bufferInfo.size)
                        buf.position(bufferInfo.offset)
                        buf.get(bytes)
                        c.releaseOutputBuffer(idx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            if (csd0.get() == null) csd0.set(bytes) else csd1.set(bytes)
                            // 带内转发参数集：必须先于 IDR 到达解码器（byte-stream 解码兼容）
                            if (bytes.isNotEmpty() && !block(bytes)) return false
                        } else {
                            if (bytes.isNotEmpty() && !block(bytes)) return false
                        }
                    }
                    else -> return true
                }
            }
        }

        fun release() {
            if (useEgl) {
                runCatching { EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, EGL14.EGL_NO_CONTEXT) }
                eglDisplay?.let { d ->
                    runCatching { eglSurface?.let { EGL14.eglDestroySurface(d, it) } }
                    runCatching { eglContext?.let { EGL14.eglDestroyContext(d, it) } }
                    runCatching { EGL14.eglTerminate(d) }
                }
            }
            codec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            codec = null
            uploadBuffer = null // 释放 13MB 复用缓冲
        }
    }
}
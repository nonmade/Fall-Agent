package com.fall.assistant.execution

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface

/**
 * H.264 硬解到 Surface（台阶 3 送显 app 侧）：config 后一帧帧 queueInputBuffer。
 *
 * 输入为 annex-b byte-stream（编码器输出含起始码 + 带内 SPS/PPS，shell 侧在 IDR 前先行补发），
 * 因此**不配置 csd-0/csd-1**（配置后多数解码器要求 avcc 长度前缀，反而破坏 byte-stream 兼容），
 * 由解码器自动解析带内参数集。
 *
 * **必须收割输出**（2026-09-24 两轮修复）：surface 输出模式不解输出会几帧后卡死；
 * 改用 `MediaCodec.Callback` 曾渲染出**全透明空帧**（真机 dump=棋盘格）。这里的可靠做法是
 * 在喂帧的同一线程顺带 `dequeueOutputBuffer` + `releaseOutputBuffer(render=true)` 手动收割，
 * 与 shell daemon 内验证可解的结构一致。
 */
class H264SurfaceDecoder(
    private val width: Int,
    private val height: Int,
) {
    private var codec: MediaCodec? = null
    private val outInfo = MediaCodec.BufferInfo()

    /** 首个解码输出被释放（= 首帧已提交渲染）回调：SurfaceView 没有 onSurfaceTextureUpdated，
     *  用它替代「首帧落面」判定（置 live / 解除看门狗）。可重复赋值，仅首次触发。 */
    @Volatile
    var onFirstFrame: (() -> Unit)? = null
    private var firstFrameNotified = false

    /** 绑定输出 Surface（SurfaceView 的 Surface 或 SurfaceTexture）并启动解码器。 */
    fun start(surface: Surface) {
        synchronized(this) {
            if (codec != null) return
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            // 警惕超小 buffer：给足上限，防编码器 B 帧突发
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height * 3 / 2)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
        }
    }

    /** 送一帧 annex-b 数据；阻塞取 input buffer（超时跳过丢帧，避免 DoS）。随后顺带收割输出。 */
    fun queue(data: ByteArray): Boolean {
        val c = codec ?: return false
        val index = try {
            c.dequeueInputBuffer(10_000)
        } catch (e: IllegalStateException) {
            return false
        }
        if (index < 0) {
            // 取不到 input buffer = 解码器在消化 + **输出未被收割**。surface 输出模式下未释放的
            // 输出缓冲会反过来占死 input，一旦命中就永久停帧 → 这里必须顺手排空输出。
            drainOutput(c)
            return false
        }
        val buf = c.getInputBuffer(index) ?: return false
        buf.clear()
        buf.put(data)
        c.queueInputBuffer(index, 0, data.size, System.nanoTime() / 1000, 0)
        drainOutput(c)
        return true
    }

    /** 手动收割解码输出并渲染（releaseOutputBuffer(render=true) → SurfaceTexture）。 */
    private fun drainOutput(c: MediaCodec) {
        while (true) {
            val idx = try {
                c.dequeueOutputBuffer(outInfo, 0)
            } catch (e: IllegalStateException) {
                return
            }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                idx >= 0 -> runCatching {
                    c.releaseOutputBuffer(idx, true)
                    if (!firstFrameNotified) {
                        firstFrameNotified = true
                        onFirstFrame?.invoke()
                    }
                }
                else -> return
            }
        }
    }

    fun release() {
        synchronized(this) {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            codec = null
        }
    }
}
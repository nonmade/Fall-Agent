package com.fall.automation.bridge

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 送显帧流客户端（豆包式纯虚拟屏 台阶 2/3）：
 * 连 shell 守护进程的独立推流端口，按帧协议读取：
 * ```
 * [4B length(BE)][1B type][payload]
 *   type=0: config JSON {"format":"jpeg|h264","width":..,"height":..,"csd0":"b64","csd1":"b64"}
 *   type=1: 一帧数据（JPEG 字节 或 H.264 annex-b）
 * ```
 * [openFrameStream] 通过 RPC 开流并连接；读帧循环在独立读线程，帧回调回主线程（调用方协程/Handler）。
 */
class FrameStreamClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 43111,
    private val onFrame: (ByteArray) -> Unit,
    private val onDisconnect: () -> Unit = {},
) : AutoCloseable {

    /** 流格式（config 首帧解析后置位）。 */
    enum class Format { JPEG, H264 }

    @Volatile
    var format: Format = Format.JPEG
        private set
    @Volatile
    var width: Int = 0
        private set
    @Volatile
    var height: Int = 0
        private set
    @Volatile
    var csd0: ByteArray? = null
        private set
    @Volatile
    var csd1: ByteArray? = null
        private set

    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var readerThread: Thread? = null

    /** 同步连接并启动读线程（阻塞至 config 首帧解析完成或失败）。 */
    suspend fun connect(timeoutMs: Long = 10_000): Boolean = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext true
        val s = Socket(host, port)
        s.soTimeout = timeoutMs.toInt()
        socket = s
        val input = s.getInputStream()
        try {
            readConfig(input)
            s.soTimeout = 0 // config 后进入流式阻塞读
            startReader(s, input)
            true
        } catch (e: Throwable) {
            println("[fall-stream] connect failed: ${e.message}")
            runCatching { s.close() }
            running.set(false)
            false
        }
    }

    private fun readConfig(input: InputStream) {
        val din = DataInputStream(input)
        val len = din.readInt() // BE
        val type = din.read()
        if (type != TYPE_CONFIG) throw IllegalStateException("expect config frame, got type=$type")
        val payload = ByteArray(len - 1)
        din.readFully(payload)
        val cfg = JsonParser.parseString(String(payload, Charsets.UTF_8)).asJsonObject
        format = if (cfg.get("format")?.asString == "h264") Format.H264 else Format.JPEG
        width = cfg.get("width")?.asInt ?: 0
        height = cfg.get("height")?.asInt ?: 0
        csd0 = cfg.get("csd0")?.takeIf { !it.isJsonNull }?.asString?.let {
            android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
        }
        csd1 = cfg.get("csd1")?.takeIf { !it.isJsonNull }?.asString?.let {
            android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
        }
    }

    private fun startReader(s: Socket, input: InputStream) {
        readerThread = Thread({
            try {
                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val len = readIntBe(input) ?: break
                    val type = input.read()
                    if (type < 0) break
                    if (len <= 1) continue
                    val payload = ByteArray(len - 1)
                    var off = 0
                    while (off < payload.size && running.get()) {
                        val n = input.read(payload, off, payload.size - off)
                        if (n < 0) break
                        off += n
                    }
                    if (off < payload.size) break
                    when (type) {
                        TYPE_FRAME -> onFrame(payload)
                    }
                }
            } catch (e: Throwable) {
                println("[fall-stream] reader ended: ${e.message}")
            } finally {
                running.set(false)
                runCatching { s.close() }
                runCatching { onDisconnect() }
            }
        }, "fall-frame-stream").apply { isDaemon = true; start() }
    }

    private fun readIntBe(input: InputStream): Int? {
        val b = ByteArray(4)
        var off = 0
        while (off < 4) {
            val n = input.read(b, off, 4 - off)
            if (n < 0) return null
            off += n
        }
        return ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).int
    }

    val isRunning: Boolean get() = running.get()

    override fun close() {
        running.set(false)
        runCatching { socket?.close() }
        readerThread?.interrupt()
        readerThread = null
    }

    private companion object {
        const val TYPE_CONFIG = 0
        const val TYPE_FRAME = 1
    }
}

/**
 * ShellBridge 扩展：送显流生命周期。
 * - [openFrameStream]：RPC captureStreamStart → 建 [FrameStreamClient] 连接读帧；
 * - [closeFrameStream]：停 RPC 流并关客户端。
 */
suspend fun ShellBridge.openFrameStream(
    format: String = "jpeg",
    port: Int = 43111,
    timeoutMs: Long = 10_000,
    onFrame: (ByteArray) -> Unit,
    onDisconnect: () -> Unit = {},
): FrameStreamClient? {
    val resp = call("captureStreamStart", JsonObject().apply {
        addProperty("format", format)
        addProperty("port", port)
    })
    val ok = resp.get("ok")?.asBoolean ?: false
    if (!ok) throw ShellException("captureStreamStart failed")
    val client = FrameStreamClient(port = port, onFrame = onFrame, onDisconnect = onDisconnect)
    return if (client.connect(timeoutMs = timeoutMs)) client else null
}

/** 关闭送显流（停 shell 推流 + 关本地连接）。 */
suspend fun ShellBridge.closeFrameStream(client: FrameStreamClient?) {
    client?.close()
    runCatching { call("captureStreamStop") }
}
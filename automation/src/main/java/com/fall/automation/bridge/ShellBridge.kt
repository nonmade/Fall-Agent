package com.fall.automation.bridge

import com.fall.core.agent.AutomationUnavailableException
import com.fall.core.bridge.DaemonProtocol
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * RPC 调用异常（服务端返回 error、鉴权失败、超时或断连）。
 *
 * [connectionLost]：失败来自连接层（写失败/reader 断流）而非服务端 error，调用方可据此提示"守护进程未连接"。
 */
class ShellException(
    message: String,
    val connectionLost: Boolean = false,
) : AutomationUnavailableException(message)

/**
 * Shell 守护进程（app_process，shell/root uid）的 NDJSON JSON-RPC 客户端。
 *
 * - 连接 127.0.0.1:[port]；请求-响应按 id 配对（CancellableFuture 挂起，**协程取消可中断等待**）；
 * - **鉴权握手**：token 可用时在连接建立后先发 `auth`，失败即视为通道不可用；
 * - 单连接 + Mutex 串行化；断线由 reader 线程按"连接代次"精确清理，不误伤新请求；
 * - 连接可用性由 [alive] 缓存，正常路径**不再每次调用都额外 ping**。
 *
 * 服务端方法见 `com.fall.shell.ShellServer`。result 形态分两类：
 * - **对象**（dump/截帧/status/createDisplay/captureStreamStart/inputText/inputTextMain）：用 [call] 取对象；
 * - **字符串**（`ping` 的 "pong"）或对象的 `{ok:true}`：用 [callRaw] 自行判读，或直接用 [callOk]。
 */
class ShellBridge(
    private val host: String = "127.0.0.1",
    private val port: Int = 43110,
    /** 握手 token 提供方；返回 null 表示不做握手（守护进程未启用鉴权）。 */
    private val tokenProvider: () -> String? = { DaemonAuth.token() },
) : AutoCloseable {

    private val mutex = Mutex()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var reader: BufferedReader? = null

    private var readerThread: Thread? = null

    /** id → 等待中的响应；由 reader 线程与调用线程并发访问 → 必须并发容器。 */
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()

    private var nextId = 1

    /** 连接代次：重连自增；旧 reader 线程只清理自己那一代，避免误清新请求。 */
    @Volatile
    private var epoch = 0

    /** 当前连接是否可用（最近一次调用成功 / 刚完成握手）。用于省掉"每次调用都 ping"。 */
    @Volatile
    private var alive = false

    /**
     * 最近一次**连接层**失败原因（连接被拒 / 握手失败）。
     *
     * 必须显式记录：`ping()` 为了"失败即重连一次"把异常 `runCatching` 掉了，若不留痕，
     * 上层只能笼统报"守护进程未连接"，把操作者引向"检查 Root 模式 / 重推 APK"，
     * 而真实原因往往是**设备上还跑着旧构建的守护进程**（未启用握手）→ 应引导去"重启守护进程"。
     */
    @Volatile
    private var lastFailure: String? = null

    /** 最近一次连接层失败原因；null 表示最近一次连接尝试是成功的。 */
    fun lastFailure(): String? = lastFailure

    @Volatile
    private var closed = false

    /** 是否已建立连接（不发起往返）。 */
    fun isConnected(): Boolean = alive && socket?.isConnected == true

    /**
     * 通道可用性：已连上直接返回 true（**零额外往返**）；否则重连并 ping 一次。
     * 调用方每次工具执行前调它即可，不再自己 ping。
     */
    suspend fun ensureConnected(timeoutMs: Long = 3000): Boolean {
        if (isConnected()) return true
        return ping(timeoutMs)
    }

    /**
     * 探测连通性：失败时丢弃旧连接重连一次（守护进程重启/断线自愈）。
     * 兼容两种 ping 形态：新版对象 `{pong:true}` 与旧版字符串 `"pong"`（设备上可能还跑着旧构建的守护进程）。
     */
    suspend fun ping(timeoutMs: Long = 3000): Boolean {
        val ok = runCatching { callRaw("ping", timeoutMs = timeoutMs).isPong() }.getOrDefault(false)
        if (ok) return true
        dropConnection()
        return runCatching { callRaw("ping", timeoutMs = timeoutMs).isPong() }.getOrDefault(false)
    }

    private fun JsonObject.isPong(): Boolean = when (val r = resultElement()) {
        null -> false
        else -> when {
            r.isJsonPrimitive -> r.asString == "pong"
            r.isJsonObject -> r.asJsonObject.get("pong")?.asBoolean == true
            else -> false
        }
    }

    /** `hello` 自报内容（协议版本协商，见 [DaemonProtocol]）。 */
    data class ServerInfo(
        val buildTag: String,
        val protocol: Int,
        /** 守护进程自身 APK 的 SHA-256；旧构建或读取失败时为 null。 */
        val apkHash: String?,
    )

    /**
     * 免鉴权版本自报（A3）：**独立短连接**直读 `hello`，不做握手 ——
     * 因此即使 token 不匹配、或设备上跑着旧构建，也能问到"对面到底是什么"，
     * 这是自愈判断（`RootManager.ensureDaemon`）能区分"未运行 / 版本过旧 / token 不匹配"的前提。
     *
     * 不可达或对面是没有 `hello` 的旧构建时返回 null（**不改动本实例的连接状态**）。
     */
    suspend fun hello(timeoutMs: Int = 2000): ServerInfo? = withContext(Dispatchers.IO) {
        runCatching {
            Socket(host, port).use { s ->
                s.soTimeout = timeoutMs
                val w = s.getOutputStream().bufferedWriter()
                val r = s.getInputStream().bufferedReader()
                val req = JsonObject().apply {
                    addProperty("jsonrpc", "2.0")
                    addProperty("id", 0)
                    addProperty("method", DaemonProtocol.METHOD_HELLO)
                    add("params", JsonObject())
                }
                w.write(req.toString())
                w.newLine()
                w.flush()
                val line = r.readLine() ?: return@use null
                val obj = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
                    ?: return@use null
                obj.resultElement()?.takeIf { it.isJsonObject }?.asJsonObject?.let {
                    ServerInfo(
                        buildTag = it.get("buildTag")?.takeIf { v -> !v.isJsonNull }?.asString ?: "",
                        protocol = it.get("protocol")?.asInt ?: 0,
                        apkHash = it.get("apkHash")?.takeIf { v -> !v.isJsonNull }?.asString,
                    )
                }
            }
        }.getOrNull()
    }

    /**
     * 同步调用一个方法，返回整个响应体（含 result/error 字段）。服务端 error 抛 [ShellException]。
     * 注：服务端 result 可能是 JSON 对象（dump/截帧等）也可能是字符串（`ping` 的 "pong"），
     * 裸调用返回完整响应交由调用方宽容解析（见 [resultElement] / [callOk]）。
     */
    suspend fun callRaw(method: String, params: JsonObject = JsonObject(), timeoutMs: Long = 30_000): JsonObject =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                ensureOpen()
                val id = nextId++
                val req = JsonObject().apply {
                    addProperty("jsonrpc", "2.0")
                    addProperty("id", id)
                    addProperty("method", method)
                    add("params", params)
                }
                val future = CompletableFuture<JsonObject>()
                pending[id] = future
                try {
                    val w = writer
                    if (w == null) throw ShellException("连接未就绪: $method", connectionLost = true)
                    try {
                        w.write(req.toString())
                        w.newLine()
                        w.flush()
                    } catch (e: Exception) {
                        // 写失败：请求未送达，标记断连让调用方走"守护进程未连接"提示
                        throw ShellException("写入失败: ${e.message}", connectionLost = true)
                    }
                    val resp = awaitResponse(future, method, timeoutMs)
                    val err = runCatching { resp.getAsJsonObject("error") }.getOrNull()
                    if (err != null) throw ShellException(err.get("message")?.asString ?: "未知错误")
                    alive = true
                    resp
                } finally {
                    pending.remove(id)
                }
            }
        }

    /** 取 result 的 JSON 对象形态（dump/截帧/status 等）。形态不符时抛 [ShellException] 而非静默失败。 */
    suspend fun call(method: String, params: JsonObject = JsonObject(), timeoutMs: Long = 30_000): JsonObject =
        callRaw(method, params, timeoutMs).resultElement()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: throw ShellException("响应 result 不是对象: $method")

    /**
     * 判定"执行成功"（统一入口）：
     * 兼容服务端两种成功形态 —— 旧版字符串 `result:"ok"` 与对象 `result:{ok:true}`。
     * 服务端 error 已由 [callRaw] 抛出，此处只判形态。
     */
    suspend fun callOk(method: String, params: JsonObject = JsonObject(), timeoutMs: Long = 30_000): Boolean {
        val element = callRaw(method, params, timeoutMs).resultElement() ?: return false
        return when {
            element.isJsonPrimitive -> element.asString == "ok"
            element.isJsonObject -> element.asJsonObject.get("ok")?.asBoolean == true
            else -> false
        }
    }

    /** captureFrame：截取虚拟屏当前帧 → JPEG base64。 */
    suspend fun captureFrame(scale: Float = 1f, quality: Int = 70): FrameData {
        val resp = call("captureFrame", JsonObject().apply {
            if (scale < 1f) addProperty("scale", scale)
            addProperty("quality", quality)
        })
        return FrameData(
            width = resp.get("width")?.asInt ?: 0,
            height = resp.get("height")?.asInt ?: 0,
            jpegBase64 = resp.get("data")?.asString ?: "",
        )
    }

    /** 向虚拟屏当前聚焦输入框输入文本（剪贴板 + KEYCODE_PASTE 定向 display）。 */
    suspend fun inputTextDisplay(text: String): String {
        val resp = call("inputText", JsonObject().apply { addProperty("text", text) })
        return resp.get("message")?.asString ?: "（无返回）"
    }

    /** captureFrame 返回体。 */
    data class FrameData(
        val width: Int,
        val height: Int,
        val jpegBase64: String,
    )

    /**
     * captureMainFrame：截取物理主屏当前帧（阶段 2 spike 方案 A：screencap 子进程）。
     * 与 captureFrame 同返回结构；失败抛 [ShellException]。
     */
    suspend fun captureMainFrame(scale: Float = 1f, quality: Int = 70): FrameData {
        val resp = call("captureMainFrame", JsonObject().apply {
            if (scale < 1f) addProperty("scale", scale)
            addProperty("quality", quality)
        })
        return FrameData(
            width = resp.get("width")?.asInt ?: 0,
            height = resp.get("height")?.asInt ?: 0,
            jpegBase64 = resp.get("data")?.asString ?: "",
        )
    }

    /** 丢弃当前连接（下次 ensureOpen 重建）。 */
    suspend fun dropConnection() = withContext(Dispatchers.IO) {
        markDead(epoch, "连接断开")
    }

    override fun close() {
        closed = true
        markDead(epoch, "bridge 已关闭")
        runCatching { readerThread?.interrupt() }
    }

    // ---------- 内部：连接与握手 ----------

    private fun ensureOpen() {
        if (closed) throw ShellException("bridge 已关闭")
        if (isConnected()) return
        // 先推进连接代次：仍在退出的旧 reader 线程会走 finally → markDead(旧代次)，
        // 若代次还没变，它会把下面刚建好的连接一并关掉（表现为偶发"写入失败"）。
        epoch++
        val myEpoch = epoch
        // 连接失败直接抛出（调用方转成"守护进程未连接"提示），不在此处重试
        val s = try {
            Socket(host, port)
        } catch (e: Exception) {
            lastFailure = "守护进程未连接（$host:$port）：${e.message}"
            throw ShellException(lastFailure!!, connectionLost = true)
        }
        s.soTimeout = 0 // 读由 readerThread 阻塞
        writer = s.getOutputStream().bufferedWriter()
        reader = s.getInputStream().bufferedReader()
        socket = s
        val t = tokenProvider()
        if (t != null && !handshake(t)) {
            runCatching { s.close() }
            socket = null
            writer = null
            reader = null
            lastFailure = AUTH_FAILED_MESSAGE
            throw ShellException(AUTH_FAILED_MESSAGE, connectionLost = true)
        }
        alive = true
        lastFailure = null
        startReader(myEpoch)
    }

    /** 连接建立后同步握手：写入 auth 请求并就地读取一行响应（此时尚无 reader 线程，不会竞争）。 */
    private fun handshake(token: String): Boolean {
        val w = writer ?: return false
        val r = reader ?: return false
        val id = nextId++
        val req = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", "auth")
            add("params", JsonObject().apply { addProperty("token", token) })
        }
        return runCatching {
            w.write(req.toString())
            w.newLine()
            w.flush()
            val line = r.readLine() ?: return false
            val obj = JsonParser.parseString(line).asJsonObject
            obj.getAsJsonObject("error") == null &&
                obj.resultElement()?.let { it.isJsonObject && it.asJsonObject.get("ok")?.asBoolean == true } == true
        }.getOrDefault(false)
    }

    /** 标记连接死亡：仅当调用方持有"当前代次"时才清理，避免旧 reader 误清新连接的 pending。 */
    private fun markDead(myEpoch: Int, reason: String) {
        if (myEpoch != epoch) return
        alive = false
        runCatching { socket?.close() }
        socket = null
        writer = null
        reader = null
        if (pending.isNotEmpty()) {
            pending.values.forEach { it.completeExceptionally(ShellException(reason, connectionLost = true)) }
            pending.clear()
        }
    }

    private fun startReader(myEpoch: Int) {
        val r = reader ?: return
        readerThread = Thread({
            try {
                while (!closed && myEpoch == epoch) {
                    val line = r.readLine() ?: break
                    val obj = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                    val id = obj.get("id")?.asInt ?: continue
                    pending.remove(id)?.complete(obj)
                }
            } catch (ignored: Throwable) {
            } finally {
                markDead(myEpoch, "连接断开")
            }
        }, "fall-bridge-reader").apply { isDaemon = true; start() }
    }

    /**
     * 等待响应：用协程挂起替代 `future.get(timeout)` —— **协程取消会立即中断等待**，
     * 用户点"停止"后不再被在途 RPC 拖住最长 30s。
     */
    private suspend fun awaitResponse(
        future: CompletableFuture<JsonObject>,
        method: String,
        timeoutMs: Long,
    ): JsonObject = try {
        withTimeout(timeoutMs) { future.awaitCancellable() }
    } catch (e: TimeoutCancellationException) {
        throw ShellException("RPC 超时: $method")
    }

    private suspend fun CompletableFuture<JsonObject>.awaitCancellable(): JsonObject =
        suspendCancellableCoroutine { cont: CancellableContinuation<JsonObject> ->
            whenComplete { value, error ->
                if (!cont.isActive) return@whenComplete
                if (error != null) {
                    cont.resumeWithException((error as? CompletionException)?.cause ?: error)
                } else {
                    cont.resume(value)
                }
            }
            cont.invokeOnCancellation { cancel(true) }
        }

    companion object {
        /** 握手失败提示（唯一来源：`ensureOpen` 抛错与 `lastFailure` 共用，避免两处文案漂移）。 */
        const val AUTH_FAILED_MESSAGE =
            "守护进程鉴权失败：token 不匹配或守护进程版本过旧 —— 请在 App 设置页点「重启守护进程」（或重启设备）后重试"
    }
}

/** 读响应体里的 `result` 元素（可能不存在）。 */
internal fun JsonObject.resultElement(): JsonElement? = get("result")

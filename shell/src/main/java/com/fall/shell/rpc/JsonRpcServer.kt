package com.fall.shell.rpc

import com.fall.core.bridge.DaemonProtocol
import org.json.JSONObject
import java.io.BufferedWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * NDJSON JSON-RPC 服务端：仅监听回环地址。
 * 请求-响应逐行收发；客户端保持连接期间注册为事件推送 Sink。
 *
 * **鉴权（2026-09-25）**：回环地址对其他 App 同样可达，而本进程由 `su` 拉起时是
 * root 身份并持有 INJECT_EVENTS —— 不校验则可被任意同机 App 静默读屏 + 伪造点击。
 * 因此 [token] 非 null 时，**每条连接的首个请求必须是 `auth`**，其余方法一律拒绝；
 * 握手连续失败 [MAX_AUTH_FAILS] 次直接断开。token 由 App 私有文件提供（见 `DaemonAuth`），
 * 其他 App 读不到。未传 token（开发期 adb 手动拉起）时保持旧行为并在日志中告警。
 *
 * **例外是 [DaemonProtocol.METHOD_HELLO]**（A3 版本自报）：必须在鉴权之前放行，否则
 * "token 不匹配"与"设备上是旧构建"两种情况在客户端看来完全一样，无法自愈（见 `DaemonProtocol`）。
 * 它只回报构建标记/协议版本/APK 哈希，不含任何设备信息。
 */
class JsonRpcServer(
    private val port: Int,
    /** 连接握手 token；null = 不启用鉴权（仅开发期 adb 手动拉起场景）。 */
    private val token: String? = null,
    /** `hello` 自报内容（免鉴权，见 [DaemonProtocol]）。 */
    private val helloInfo: JSONObject = JSONObject(),
    private val dispatch: (id: Any?, method: String, params: JSONObject) -> JSONObject,
) {
    private val clients = CopyOnWriteArrayList<Client>()

    fun run() {
        val server = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
        if (token == null) {
            println("[fall-shell] ⚠ 未启用 RPC 鉴权（未提供 --token-file）：任何同机 App 均可截屏/注入")
        } else {
            println("[fall-shell] rpc auth enabled (token len=${token.length})")
        }
        println("[fall-shell] rpc listening on 127.0.0.1:$port")
        while (true) {
            val socket = server.accept()
            Thread({ handle(socket) }, "fall-rpc-client").start()
        }
    }

    /** 服务端主动下推事件（日志/状态等）。 */
    fun broadcast(params: JSONObject) = clients.forEach { it.send(JsonRpc.event(params)) }

    private fun handle(socket: Socket) {
        val client = Client(socket)
        clients.add(client)
        var authed = token == null
        var authFails = 0
        try {
            socket.getInputStream().bufferedReader().forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val req = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                val id = JsonRpc.idOf(req)
                val method = JsonRpc.methodOf(req)
                val params = JsonRpc.paramsOf(req)

                // 免鉴权自报（A3）：必须在 auth 校验之前放行
                if (method == DaemonProtocol.METHOD_HELLO) {
                    client.send(JsonRpc.result(id, helloInfo))
                    return@forEachLine
                }
                if (method == METHOD_AUTH) {
                    val provided = params.optString("token")
                    val ok = token != null && constantTimeEquals(provided, token)
                    if (token == null) {
                        authed = true
                        client.send(JsonRpc.result(id, JSONObject().put("ok", true).put("auth", "disabled")))
                    } else if (ok) {
                        authed = true
                        client.send(JsonRpc.result(id, JSONObject().put("ok", true)))
                    } else {
                        authFails++
                        client.send(JsonRpc.error(id, ERR_UNAUTHORIZED, "auth failed"))
                        if (authFails >= MAX_AUTH_FAILS) throw AuthAbort()
                    }
                    return@forEachLine
                }
                if (!authed) {
                    client.send(
                        JsonRpc.error(id, ERR_UNAUTHORIZED, "unauthorized: send {\"method\":\"auth\"} first"),
                    )
                    return@forEachLine
                }
                val resp = runCatching { dispatch(id, method, params) }
                    .getOrElse { JsonRpc.error(id, -32000, it.message ?: "internal error") }
                client.send(resp)
            }
        } catch (e: AuthAbort) {
            println("[fall-shell] rpc client closed: auth failed x$authFails")
        } catch (e: Exception) {
            println("[fall-shell] rpc client closed: ${e.message}")
        } finally {
            clients.remove(client)
            runCatching { socket.close() }
        }
    }

    /** 定长比较，避免握手被按字节计时爆破（token 为 128bit 随机值，双保险）。 */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /** 握手失败次数超限：以异常结束该连接（`forEachLine` 无法直接 break）。 */
    private class AuthAbort : RuntimeException()

    private class Client(socket: Socket) {
        private val writer: BufferedWriter = socket.getOutputStream().bufferedWriter()
        @Synchronized
        fun send(obj: JSONObject) {
            runCatching {
                writer.write(obj.toString())
                writer.newLine()
                writer.flush()
            }
        }
    }

    companion object {
        const val METHOD_AUTH = "auth"
        const val ERR_UNAUTHORIZED = -32001
        private const val MAX_AUTH_FAILS = 3
    }
}

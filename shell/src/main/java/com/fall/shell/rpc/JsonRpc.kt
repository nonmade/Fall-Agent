package com.fall.shell.rpc

import org.json.JSONArray
import org.json.JSONObject

/** JSON-RPC 2.0 便利构造（NDJSON：每行一个 JSON）。 */
object JsonRpc {

    fun request(method: String, params: JSONObject?): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }

    fun result(id: Any?, result: Any?): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            if (result != null) put("result", result)
        }

    fun error(id: Any?, code: Int, message: String): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", JSONObject().put("code", code).put("message", message))
        }

    /** 服务端主动下推事件（日志/状态等）。 */
    fun event(params: JSONObject?): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "event")
            if (params != null) put("params", params)
        }

    fun idOf(req: JSONObject): Any? = if (req.has("id")) req.get("id") else null

    fun methodOf(req: JSONObject): String = req.optString("method", "")

    fun paramsOf(req: JSONObject): JSONObject = req.optJSONObject("params") ?: JSONObject()

    fun stringArrayOf(items: Collection<String>): JSONArray =
        JSONArray().apply { items.forEach(::put) }
}
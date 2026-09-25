package com.fall.core.data.remote

import com.fall.core.model.llm.ChatMessage
import com.fall.core.model.llm.ChatToolSpec
import com.fall.core.model.llm.LlmConfig
import com.fall.core.model.llm.ToolCall
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容 endpoint 的流式客户端实现（OkHttp + SSE）。
 * 本地 vLLM 与在线服务共用：仅 [LlmConfig.baseUrl]/[LlmConfig.apiKey] 不同。
 *
 * 实现要点：
 * - 请求 `stream:true` + `tool_choice:auto` + tools schema
 * - SSE 逐行解析 `data:`；`[DONE]` 结束
 * - 流式 tool_calls 按 index 聚合 name/arguments 碎片
 *
 * 超时适配离线 Thinking 模式模型（DeepSeek 等首 token 可达数十秒）：read 放宽到 90s。
 */
class OpenAiCompatibleLlmClient(
    private val okHttp: OkHttpClient = defaultClient(),
) : LlmClient {

    private val gson = Gson()

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun chat(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ChatToolSpec>,
        listener: LlmClient.Listener,
        trace: (String) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            try {
                val request = buildRequest(config, messages, tools)
                okHttp.newCall(request).execute().use { response ->
                    val body = response.body ?: run {
                        trace("HTTP ${response.code} empty body")
                        listener.onError(IllegalStateException("empty body, code=${response.code}"))
                        return@use
                    }
                    if (!response.isSuccessful) {
                        val detail = runCatching { body.string().take(500) }.getOrDefault("")
                        trace("HTTP ${response.code} $detail")
                        listener.onError(IllegalStateException("HTTP ${response.code}: $detail"))
                        return@use
                    }

                    val source = body.source()
                    val builders = mutableMapOf<Int, ToolCallBuilder>()
                    var lines = 0
                    var sawDone = false
                    var firstData: String? = null
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") {
                            sawDone = true
                            break
                        }
                        // 标准 SSE 事件以空行（\n\n）分隔；空 data 行必须跳过而不是终止流
                        if (data.isEmpty()) continue
                        if (lines == 0) firstData = data.take(120)
                        lines++

                        val delta = LlmProtocol.parseSseData(data, gson) ?: continue
                        delta.reasoning?.let { listener.onThinking(it) }
                        delta.content?.let { listener.onContent(it) }
                        delta.toolCallChunks.forEach { chunk ->
                            val builder = builders.getOrPut(chunk.index) { ToolCallBuilder() }
                            chunk.id?.let { builder.id = it }
                            chunk.name?.let { builder.name.append(it) }
                            chunk.arguments?.let { builder.arguments.append(it) }
                        }
                    }
                    builders.values.forEach { builder ->
                        builder.build()?.let { listener.onToolCall(it) }
                    }
                    // 连接在收到 [DONE] 之前关闭：已消费部分数据，说明流被截断，不能当作成功
                    if (!sawDone && lines > 0) {
                        trace("STREAM_TRUNCATED lines=$lines first=${firstData ?: ""}")
                        listener.onError(IllegalStateException("流式响应被截断（未收到 [DONE]）"))
                        return@use
                    }
                    trace("HTTP 200 lines=$lines done=$sawDone first=${firstData ?: ""} tools=${builders.size}")
                    listener.onDone()
                }
            } catch (e: Exception) {
                trace("EX ${e.javaClass.simpleName}: ${e.message}")
                listener.onError(e)
            }
        }
    }

    private fun buildRequest(config: LlmConfig, messages: List<ChatMessage>, tools: List<ChatToolSpec>): Request {
        val json = LlmProtocol.buildRequestJson(config, messages, tools, gson)
        return Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Accept", "text/event-stream")
            .apply { config.apiKey?.let { header("Authorization", "Bearer $it") } }
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
    }

    /** 按 index 聚合流式 tool_calls 碎片。 */
    private inner class ToolCallBuilder {
        var id: String = ""
        val name = StringBuilder()
        val arguments = StringBuilder()

        fun build(): ToolCall? {
            val toolName = name.toString()
            if (toolName.isBlank()) return null
            return ToolCall(
                id = id.ifEmpty { "call_$toolName" },
                name = toolName,
                arguments = LlmProtocol.parseArguments(arguments.toString(), gson),
            )
        }
    }
}
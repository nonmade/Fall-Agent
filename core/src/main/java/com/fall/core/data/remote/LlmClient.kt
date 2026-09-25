package com.fall.core.data.remote

import com.fall.core.model.llm.ChatMessage
import com.fall.core.model.llm.ChatToolSpec
import com.fall.core.model.llm.LlmConfig
import com.fall.core.model.llm.ToolCall

/**
 * 大模型客户端抽象：单次流式对话请求。
 *
 * 支持本地（vLLM）与在线（OpenAI 兼容）两种来源，协议一致：
 * `POST {baseUrl}/chat/completions`，stream + tool calls。
 *
 * 工具调用结果的回填（assistant -> tool -> ...）属于 Agent 循环（core.agent）职责，
 * 本接口只负责"发一次请求、收敛增量"。
 */
interface LlmClient {

    /**
     * 发起流式对话。
     *
     * 回调均在调用协程上下文中触发；正常结束时必定回调 [Listener.onDone]，
     * 异常路径回调 [Listener.onError]。
     *
     * @param trace 诊断回调（HTTP 状态码 / 首个流数据 / 异常），由宿主日志落盘，
     *              适应部分 ROM 清空 logcat 的调试场景。
     */
    suspend fun chat(
        config: LlmConfig,
        messages: List<ChatMessage>,
        tools: List<ChatToolSpec> = emptyList(),
        listener: Listener,
        trace: (String) -> Unit = {},
    )

    interface Listener {
        /** 流式文本增量（普通回答 / 思考过程展示）。 */
        fun onContent(delta: String)

        /**
         * 流式思考增量（`reasoning_content`，如 DeepSeek reasoner / Qwen3 thinking 模型）。
         * 增量语义：调用方自行累积。默认空实现——普通模型无该字段，实现可忽略。
         */
        fun onThinking(delta: String) {}

        /** 聚合完成的一个工具调用（参数已解析为 JSON 对象）。 */
        fun onToolCall(toolCall: ToolCall)

        /** 响应正常结束（含 [DONE] 或连接关闭）。 */
        fun onDone()

        /** 出现异常（网络 / 非 2xx / 解析失败）。 */
        fun onError(cause: Throwable)
    }
}
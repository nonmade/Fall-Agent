package com.fall.core.agent

import com.fall.core.data.remote.LlmClient
import com.fall.core.model.llm.ChatMessage
import com.fall.core.model.llm.ChatToolSpec
import com.fall.core.model.llm.LlmConfig
import com.fall.core.model.llm.ToolCall
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * 任务执行意图：决定走哪条降级链（双层路由第一层）。
 * - [FRONT]：前台协动——用户看着屏幕求助，走 无障碍→ADB→OCR 兜底。
 * - [SILENT]：后台静默——无人值守（定时签到 / "我在用手机你去后台干"），走 VirtualDisplay 链。
 */
enum class ExecutionChain { FRONT, SILENT }

/** 用户（预览页接管）暂停超过 [AgentLoop.pauseTimeoutMillis] 后抛出，终止整个任务。 */
class PauseTimeoutException(message: String) : RuntimeException(message)

/**
 * Agent 循环（ReAct 风格）：
 * 目标/历史 → 模型产出操作（文本或工具调用）→ 执行工具并回填结果 → 取新一轮 → 直到无工具调用或超出步数。
 *
 * 本轮为最小实现（P0.5 演示闭环）：单轮工具调用即可完成"打开 XX App"目标；
 * UI 感知（观察虚拟屏）与校正策略是接入 shell 通道后的进阶。
 *
 * 说明：工具执行必须发生在协程体内（非 LLM 回调线程），因此先收集 tool_calls，
 * 待一次流式响应结束后统一执行并回填。
 *
 * 双链（2026-09-17）：构造可选传 [silentRegistry]，`run()` 按 [ExecutionChain] 选择注册表。
 * 不传时行为与单链完全一致（兼容既有调用与测试）。
 */
class AgentLoop(
    private val llmClient: LlmClient,
    private val tools: List<ChatToolSpec>,
    private val registry: ToolRegistry,
    private val maxSteps: Int = 15,
    private val silentRegistry: ToolRegistry? = null,
    private val pausedProvider: () -> Boolean = { false },
    /** 用户暂停（预览页接管）的最长等待；>0 时超时抛 [PauseTimeoutException] 结束任务，0 表示无限等待。 */
    private val pauseTimeoutMillis: Long = 0,
) {
    /** 循环结果。 */
    data class Outcome(
        val text: String?,          // 最终文本回答（若无则为 null）
        val toolCalls: List<ToolCall>, // 本轮全部工具调用（执行过的）
        val error: Throwable? = null,
        /** 本任务全部回合消息（assistant(thinking+tool_calls) + tool(result) + 最终总结），供落库回放。 */
        val turnMessages: List<ChatMessage> = emptyList(),
    )

    /**
     * 执行一段带工具的目标。
     *
     * @param config      LLM 连接配置
     * @param systemHint  系统提示（工具使用约束）
     * @param goal        用户目标/问题（追加为最后一条 user 消息）
     * @param history     会话历史（不含本轮 goal）
     * @param onContentDelta 流式文本回调：**携带增量**（不是累积全文）。累积由调用方按自己的刷新
     *                    节奏做——逐 token 拼全文会让长回复产生 O(n²) 字符串拷贝。
     * @param onThinking  流式思考回调：**携带增量**（reasoning_content；无思考时不会被调用）
     */
    suspend fun run(
        config: LlmConfig,
        systemHint: String,
        goal: String,
        history: List<ChatMessage>,
        intent: ExecutionChain = ExecutionChain.FRONT,
        onContentDelta: (String) -> Unit = {},
        onThinking: (String) -> Unit = {},
        onToolResult: (name: String, result: String) -> Unit = { _, _ -> },
        onRound: (index: Int) -> Unit = {},
        trace: (String) -> Unit = {},
    ): Outcome {
        val activeRegistry = when (intent) {
            ExecutionChain.SILENT -> silentRegistry ?: registry
            ExecutionChain.FRONT -> registry
        }
        // [已停用] 布局指纹状态（连续相同布局检测，用于给模型附加"疑似循环"警告）（2026-09-19）
        // 原因：检测并警告"AI 原地打转"属于替 AI 判断执行效果；是否陷入循环应由 AI 通过
        // get_ui_layout / 截图自行感知（界面没变化它自然会换策略）。因此指纹追踪与警告一并移除。
        // var lastLayoutFingerprint = 0
        // var sameLayoutStreak = 0
        // 模型看到的工具 = 显式传入列表；未传时收敛为注册表实际可执行工具并集（去重，见 ToolRegistry.allSchemas）
        val chatTools = if (tools.isNotEmpty()) tools else activeRegistry.allSchemas
        var error: Throwable? = null
        var lastText: String? = null
        val allCalls = mutableListOf<ToolCall>()
        // 全部回合消息（assistant + tool + 最终总结），run 返回后由调用方落库回放
        val turnMessages = mutableListOf<ChatMessage>()
        // 工作区消息：system 提示 + 历史 + 本轮目标（执行工具后不断回填 tool 结果）
        val work = mutableListOf<ChatMessage>().apply {
            if (systemHint.isNotBlank()) {
                // 采集约束（阶段 3）：模型无跨步记忆，多步采集任务必须用 record_note 显式落笔记，
                // 避免依赖对话记忆导致跨帧信息丢失。
                add(
                    ChatMessage.system(
                        if (systemHint.contains(RECORD_NOTE_PROMPT)) systemHint
                        else systemHint + "\n" + RECORD_NOTE_PROMPT
                    )
                )
            }
            addAll(history)
            add(ChatMessage.user(goal))
        }

        // 步数预算耗尽标记：循环跑满（未提前 break）时保持 true，触发"仅总结"收尾轮
        var budgetExhausted = true

        for (step in 0 until maxSteps) {
            awaitNotPaused() // 用户从预览页接管虚拟屏时挂起，恢复后继续
            var anyToolCall = false
            val pendingCalls = mutableListOf<ToolCall>()
            // 本轮文本累积：StringBuilder 避免逐 token 的 O(n²) 全量重建
            val roundText = StringBuilder()
            // 本轮思考累积（reasoning_content）
            val thinkingSb = StringBuilder()

            llmClient.chat(
                config = config,
                // 预算注入：每轮动态告知剩余步数，仅进入单次请求（不污染 work / 不落库），
                // 让模型在步数耗尽前自行收敛（参考 Claude Code 的预算提示做法）
                messages = work + budgetHint(step, maxSteps),
                tools = chatTools,
                listener = object : LlmClient.Listener {
                    override fun onContent(delta: String) {
                        roundText.append(delta)
                        // 只回传增量：不再 `lastText = roundText.toString()`（每个 token 全量拷贝 → O(n²)）
                        onContentDelta(delta)
                    }

                    override fun onThinking(delta: String) {
                        thinkingSb.append(delta)
                        onThinking(delta)
                    }

                    override fun onToolCall(toolCall: ToolCall) {
                        anyToolCall = true
                        pendingCalls += toolCall
                    }

                    override fun onDone() = Unit

                    override fun onError(cause: Throwable) {
                        error = cause
                    }
                },
                trace = trace,
            )
            onRound(step)

            // 本轮文本在本轮结束时才取一次快照（供落库/收尾/异常兜底使用）
            roundText.toString().takeIf { it.isNotBlank() }?.let { lastText = it }

            if (error != null) {
                budgetExhausted = false
                break
            }
            if (!anyToolCall) {
                // 本轮模型直接文本作答（最终总结 / 普通回答）：作为一条 assistant 收进回合，保证落库完整
                lastText?.takeIf { it.isNotBlank() }?.let { summary ->
                    turnMessages += ChatMessage.assistant(summary)
                }
                budgetExhausted = false
                break
            }

            // 协程体内执行工具并回填：assistant(tool_calls) + tool(结果)
            // 同轮若模型同时输出文本/思考，一并写入 assistant（协议允许 content+tool_calls 并存；thinking 仅展示/落库，不进请求体）
            val turnThinking = thinkingSb.toString().takeIf { it.isNotBlank() }
            val turnText = lastText?.takeIf { it.isNotBlank() }
            pendingCalls.forEach { call ->
                awaitNotPaused()
                allCalls += call
                val result = activeRegistry.execute(call.name, call.arguments)
                onToolResult(call.name, result.take(80))
                val assistant = ChatMessage.assistant(
                    text = turnText,
                    toolCalls = listOf(call),
                    thinking = turnThinking,
                )
                turnMessages += assistant
                work += assistant

                // 观察加工：解码图片承载（多模态截图）+ 文本截断（保护上下文长度）。
                // [已停用] 布局指纹/循环警告（2026-09-19）：检测 AI 原地打转是替 AI 判断执行效果，决策归 AI。
                val observation = ToolObservations.decode(result)
                work += ChatMessage.toolResult(call.id, truncateObservation(observation.text))
                // 落库保留完整结果，便于回看
                turnMessages += ChatMessage.toolResult(call.id, observation.text)

                // 多模态截图：以 USER 消息注入模型上下文（图片仅允许出现在 USER 消息），模型下一轮直接看图。
                // 不写入 turnMessages（不落库）：截图是执行中自动的感知流程，若以 user 消息落库会被 UI 当作
                // "用户消息边界"，把同一任务的操作步骤拆成多张卡片；且 base64 也本就不随 Room 落库。
                if (observation.images.isNotEmpty()) {
                    val screenshot = ChatMessage.userWithImages(
                        text = SCREENSHOT_USER_HINT_FOR_MODEL,
                        images = observation.images,
                    )
                    work += screenshot
                }
            }
        }

        // 到顶收尾：预算耗尽（循环跑满且未正常结束）时追加一轮"仅总结"请求，
        // 让模型基于当前进展收尾，避免步数耗尽时无声中断（参考 Claude Code / dsh loop-guard 的优雅收尾）
        if (budgetExhausted) {
            summarizeAfterBudgetExhausted(work, config, trace)?.let { summary ->
                lastText = summary
                turnMessages += ChatMessage.assistant(summary)
            }
        }

        val finalText = lastText?.takeIf { it.isNotBlank() }
        // 容错：工具已实际执行成功，但后续总结环节失败——不把结果丢掉，仅放弃文本总结
        if (error != null && allCalls.isNotEmpty()) {
            return Outcome(text = finalText, toolCalls = allCalls, error = null, turnMessages = turnMessages)
        }
        return Outcome(
            text = finalText,
            toolCalls = allCalls,
            error = error,
            turnMessages = turnMessages,
        )
    }

    // ---------- 以下为辅助 ----------

    /**
     * 每轮预算提示：告知模型剩余执行步数，让其在耗尽前自行收敛。
     * 仅作为单次请求的末尾 user 消息注入（不写入 [work]、不落库、不回放给历史）。
     * 剩余步数 ≤ [BUDGET_WARN_AFTER] 时切换为强收敛警告。
     */
    private fun budgetHint(step: Int, maxSteps: Int): ChatMessage {
        val remaining = maxSteps - step
        val text = if (remaining <= BUDGET_WARN_AFTER) {
            "【执行预算】仅剩 $remaining 步（共 $maxSteps 步）。请优先完成当前动作并收敛：" +
                "若目标已达成或确定无法达成，直接输出最终总结，不要再发起新的工具调用。"
        } else {
            "【执行预算】剩余 $remaining/$maxSteps 步。若目标已达成请直接输出最终回答（不要调用工具）；" +
                "若确认无法完成，请说明原因。"
        }
        return ChatMessage.user(text)
    }

    /**
     * 预算耗尽后的"仅总结"轮（优雅收尾）：不再提供工具（tools 传空），
     * 模型只能基于当前进展输出文本总结，避免步数耗尽时无声中断。
     * 返回最终总结文本；失败或无输出返回 null（由调用方保留已有文本兜底）。
     */
    private suspend fun summarizeAfterBudgetExhausted(
        work: List<ChatMessage>,
        config: LlmConfig,
        trace: (String) -> Unit,
    ): String? {
        val sb = StringBuilder()
        var err: Throwable? = null
        llmClient.chat(
            config = config,
            messages = work + ChatMessage.user(BUDGET_EXHAUSTED_HINT),
            tools = emptyList(), // 不给工具：模型只能文本收尾，天然收敛
            listener = object : LlmClient.Listener {
                override fun onContent(delta: String) {
                    sb.append(delta)
                }

                override fun onThinking(delta: String) = Unit

                override fun onToolCall(toolCall: ToolCall) = Unit // 防御：不应发生，忽略

                override fun onDone() = Unit

                override fun onError(cause: Throwable) {
                    err = cause
                }
            },
            trace = trace,
        )
        if (err != null) return null
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /**
     * 暂停期间挂起（用户从预览页接管虚拟屏时由 ExecutionHub 置位）。
     * [pauseTimeoutMillis] > 0 时，单次等待超时就抛 [PauseTimeoutException]，
     * 避免预览页忘记关闭导致任务无限挂起。
     */
    private suspend fun awaitNotPaused() {
        if (pauseTimeoutMillis <= 0) {
            while (pausedProvider()) delay(200)
            return
        }
        try {
            withTimeout(pauseTimeoutMillis) {
                while (pausedProvider()) delay(200)
            }
        } catch (e: TimeoutCancellationException) {
            throw PauseTimeoutException("用户暂停超过 ${pauseTimeoutMillis / 1000}s，任务结束")
        }
    }

    /** 回填模型上下文前的观察加工：超长布局/OCR 文本仅保留头部，附截断说明。 */
    private fun truncateObservation(result: String): String {
        if (result.length <= MAX_OBSERVATION_CHARS) return result
        return result.take(MAX_OBSERVATION_CHARS) +
            "\n…(已截断，原 ${result.length} 字符，保留前 $MAX_OBSERVATION_CHARS)"
    }

    /**
     * [已停用] 观察回填加工：截断 + 「重复布局」循环警告（2026-09-19）
     * 原因：对连续相同布局附加"疑似陷入循环"警告，是系统替 AI 判断"它是否在原地打转"。
     * 按"只提供工具、决策归 AI"原则，执行效果是否异常由 AI 通过 get_ui_layout/截图自行感知，
     * 故警告逻辑移除；观察加工仅保留 [truncateObservation] 的截断（token 保护，非判断）。
     * 若后续换更强模型且仍需要防打转辅助，可在此恢复指纹检测，但仅作提示、不干预执行。
     */
    // private fun annotateObservation(
    //     name: String,
    //     result: String,
    //     fingerprint: Int,
    //     streak: Int,
    //     imageAware: Boolean = false,
    // ): Triple<String, Int, Int> {
    //     val truncated = truncateObservation(result)
    //     if (name != AgentTools.NAME_GET_UI_LAYOUT || imageAware) return Triple(truncated, fingerprint, streak)
    //     val fp = result.trim().hashCode()
    //     val nextStreak = if (fp == fingerprint) streak + 1 else 1
    //     val annotated = if (nextStreak >= REPEAT_LAYOUT_WARN_AFTER) {
    //         "⚠ 当前界面已连续 $nextStreak 次与上一次观察完全相同（疑似陷入循环）：" +
    //             "请优先 press_back 返回上一步或更换策略，不要重复点击/滚动同一区域。\n$truncated"
    //     } else {
    //         truncated
    //     }
    //     return Triple(annotated, fp, nextStreak)
    // }

    private companion object {
        /** 采集类任务约束（追加到 system 提示，阶段 3）。 */
        const val RECORD_NOTE_PROMPT =
            "采集类任务（记录价格、标题、链接、时间等数据）必须用 record_note 工具保存已获取的数据，不要依赖对话记忆。"

        /** 单条工具观察回填上下文的最大字符数。 */
        const val MAX_OBSERVATION_CHARS = 2048

        /** 剩余步数 ≤ 该值时，预算提示切换为"尽快收敛"的强警告。 */
        const val BUDGET_WARN_AFTER = 3

        /** 预算耗尽后"仅总结"轮注入的提示（不依赖具体步数）。 */
        const val BUDGET_EXHAUSTED_HINT =
            "【执行预算已耗尽】本次任务已用尽全部工具调用步数。请立即停止工具调用，" +
                "基于当前进展输出最终总结：任务是否完成、已完成哪些步骤、剩余未完成事项或失败原因。"

        /** [已停用] get_ui_layout 连续相同布局达到该次数时附加循环警告（2026-09-19）：随 annotateObservation 一并停用。 */
        // const val REPEAT_LAYOUT_WARN_AFTER = 3

        /** 多模态截图注入 USER 消息时的提示文本（模型可见）。 */
        const val SCREENSHOT_USER_HINT_FOR_MODEL =
            "（系统捕获的当前屏幕截图。请依据截图判断当前界面并自主选择操作：" +
            "点击某个位置用 tap(x,y)，需要翻页/滚动列表用 scroll(direction)，需要返回用 press_back，" +
            "需要输入用 input_text，输入后可 press_enter 提交。需要指定位置的参数（tap 的 x/y）以截图左上角为原点、" +
            "按截图注明的实际像素尺寸取值）"
    }
}
package com.fall.assistant.ui.screen

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fall.assistant.core.AgentRuntime
import com.fall.assistant.core.AppContainer
import com.fall.assistant.core.DebugLog
import com.fall.assistant.core.RootManager
import com.fall.assistant.execution.ExecutionHub
import com.fall.assistant.execution.OverlayService
import com.fall.automation.service.AccessibilityExecutorService
import com.fall.core.agent.ExecutionChain
import com.fall.core.agent.PHONE_AGENT_SYSTEM_HINT
import com.fall.core.agent.TaskContext
import com.fall.core.data.local.ChatMessageEntity
import com.fall.core.data.local.ChatSessionEntity
import com.fall.core.data.repository.ChatRepository
import com.fall.core.data.repository.SettingsRepository
import com.fall.core.data.repository.toLlmConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(
    private val appContext: Context,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    /** 每任务装配执行链（含虚拟屏会话等任务级资源），由调用方负责 finally 回收。 */
    private val newRuntime: (taskId: String) -> AgentRuntime,
    private val rootManager: RootManager,
) : ViewModel() {

    private companion object {
        const val TAG = "FallChat"

        /** 流式渲染节流间隔：模型 token 可能极快（数百行/秒），逐 token 刷新会打爆主线程。 */
        const val STREAM_FLUSH_MS = 33L

        /** 系统提示：约束模型执行手机操作时按"感知→行动→确认"循环调用工具。 */
        const val SYSTEM_HINT = PHONE_AGENT_SYSTEM_HINT
    }

    data class UiState(
        val sessions: List<ChatSessionEntity> = emptyList(),
        val currentSessionId: Long? = null,
        val messages: List<ChatMessageEntity> = emptyList(),
        /** 执行中的任务实时状态（未落库），null 表示无进行中任务。 */
        val pendingTask: PendingTask? = null,
        val input: String = "",
        val sending: Boolean = false,
        val error: String? = null,
    )

    /** 流式增量：区分内容/思考，由渲染协程按 33ms 合并消费。 */
    private data class StreamChunk(val thinking: Boolean, val delta: String)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var messagesJob: Job? = null
    private var sendJob: Job? = null

    /**
     * 执行中的任务实时状态：流式回答 + 已产生的工具步骤（未落库）。
     * run() 返回后随 [ChatRepository.appendAgentTurns] 落库并清空。
     */
    private val _pendingTask = MutableStateFlow<PendingTask?>(null)

    init {
        viewModelScope.launch {
            chatRepository.sessions.collect { sessions ->
                _uiState.update { st ->
                    val valid = st.currentSessionId?.takeIf { id -> sessions.any { it.id == id } }
                    when {
                        valid != null -> st.copy(sessions = sessions)
                        sessions.isNotEmpty() -> st.copy(sessions = sessions, currentSessionId = null)
                        else -> st.copy(sessions = sessions, currentSessionId = null)
                    }
                }
                // 首次或有会话但未订阅消息时，选中最近活跃会话
                val state = _uiState.value
                if (state.currentSessionId == null && state.sessions.isNotEmpty()) {
                    selectSession(state.sessions.first().id)
                }
            }
        }
    }

    fun selectSession(sessionId: Long) {
        messagesJob?.cancel()
        _pendingTask.value = null // 切换会话：丢弃旧会话未提交的临时任务
        _uiState.update { it.copy(currentSessionId = sessionId, error = null) }
        messagesJob = viewModelScope.launch {
            combine(
                chatRepository.messages(sessionId),
                _pendingTask,
            ) { db, pending -> db to pending }
                .collect { (db, pending) ->
                    _uiState.update { it.copy(messages = db, pendingTask = pending) }
                }
        }
    }

    fun createSession() {
        viewModelScope.launch {
            val id = chatRepository.createSession("新会话")
            selectSession(id)
        }
    }

    fun deleteSession(id: Long) {
        viewModelScope.launch {
            chatRepository.deleteSession(id)
            if (_uiState.value.currentSessionId == id) {
                messagesJob?.cancel()
                _uiState.update { it.copy(currentSessionId = null, error = null) }
            }
        }
    }

    fun updateInput(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    fun send() {
        val sessionId = _uiState.value.currentSessionId ?: return
        val text = _uiState.value.input.trim()
        if (text.isEmpty() || _uiState.value.sending) return
        if (text.length > 4000) {
            _uiState.update { it.copy(error = "消息过长（上限 4000 字）") }
            return
        }
        sendJob = viewModelScope.launch {
            // 任务 id：笔记落盘分文件、执行状态隔离、停止信号定向都按它区分
            val taskId = TaskContext.newTaskId()
            val runtime = newRuntime(taskId)
            _uiState.update { it.copy(sending = true, input = "", error = null) }
            // 链门禁（2026-09-18）：后台静默链仅 root 模式可用；否则降级前台链（无障碍为主）
            val sett = settingsRepository.settings.first()
            val chain =
                if (sett.rootModeEnabled && rootManager.isRootAvailable()) ExecutionChain.SILENT
                else ExecutionChain.FRONT
            // 前台链预检：无障碍未启用时手机操作必然失败，提前提示用户去设置页开启（不阻断普通文本问答）
            if (chain == ExecutionChain.FRONT && !AccessibilityExecutorService.isEnabled(appContext)) {
                _uiState.update {
                    it.copy(error = "无障碍服务未开启，手机操作将不可用：请到「设置 → 执行可视化权限 → 无障碍」开启『Fall 自动化』（或开启 Root 模式走静默链）")
                }
            }
            ExecutionHub.begin(taskId, text, chain)
            // 监控页/胶囊的"停止" → 只取消本任务（原先仅 ChatViewModel 订阅，外部任务停不掉）
            val stopWatcher = launch { ExecutionHub.stopRequests(taskId).collect { sendJob?.cancel() } }
            DebugLog.log(
                "CHAT",
                "send task=$taskId goal=${text.take(80)} chain=$chain " +
                    "a11y=${AccessibilityExecutorService.isEnabled(appContext)} rootOn=${sett.rootModeEnabled}",
            )
            // 悬浮窗仅后台静默（root 模式）展示：前台操作用户看着屏幕，不需要状态胶囊遮挡
            if (chain == ExecutionChain.SILENT) {
                // 守护进程保障（A1）：版本自检（hello 免鉴权自报 + 握手），设备上跑着旧构建/
                // token 不匹配时自动重启（限流一次），无需用户先手工重启手机或重推 APK。
                // 失败不阻断（执行器内部还有明确报错）
                val daemonOk = runCatching { rootManager.ensureDaemon() }.getOrDefault(false)
                DebugLog.log(
                    "CHAT",
                    "ensureDaemon(启动前自检/自愈)=$daemonOk reason=${rootManager.lastEnsureFailure() ?: "-"}",
                )
                runCatching { OverlayService.start(appContext) }
            }

            // 流式渲染节流：AgentLoop 现在回传**增量**，这里按 33ms 合并成批后
            // 才做一次字符串快照 + UI 更新，避免"每 token 全量拷贝 + 每 token 触发 Compose 重组"。
            val streamChannel = Channel<StreamChunk>(Channel.UNLIMITED)
            val renderer = launch {
                val content = StringBuilder()
                val thinking = StringBuilder()
                for (chunk in streamChannel) {
                    if (chunk.thinking) thinking.append(chunk.delta) else content.append(chunk.delta)
                    delay(STREAM_FLUSH_MS)
                    // 合并节流窗口内到达的后续增量
                    while (true) {
                        val next = streamChannel.tryReceive().getOrNull() ?: break
                        if (next.thinking) thinking.append(next.delta) else content.append(next.delta)
                    }
                    if (content.isNotEmpty()) {
                        val full = content.toString()
                        _uiState.update { st -> st.copy(pendingTask = st.pendingTask?.copy(answerText = full)) }
                        ExecutionHub.appendText(taskId, full)
                    }
                    if (thinking.isNotEmpty()) updatePendingThinking(thinking.toString())
                }
            }

            try {
                Log.i(TAG, "send: goal=${text.take(50)}")
                chatRepository.appendMessage(
                    ChatMessageEntity(sessionId = sessionId, role = "user", content = text)
                )
                // 先落用户消息再挂实时任务，保证分组能合并到刚发送的那条任务组
                _pendingTask.value = PendingTask()
                val config = sett.toLlmConfig()
                Log.i(TAG, "llm config: source=${config.source} model=${config.model}")
                val history = chatRepository.toLlmMessages(sessionId, limit = 20)

                val outcome = runtime.agentLoop.run(
                    config = config,
                    systemHint = SYSTEM_HINT,
                    goal = text,
                    history = history,
                    intent = chain, // 链门禁解析结果：root 就绪才静默，否则前台
                    onContentDelta = { delta -> streamChannel.trySend(StreamChunk(thinking = false, delta = delta)) },
                    onThinking = { delta -> streamChannel.trySend(StreamChunk(thinking = true, delta = delta)) },
                    onToolResult = { name, result ->
                        ExecutionHub.appendStep(taskId, name, result)
                        DebugLog.log("TOOL", "$name -> ${result.take(200)}")
                        runCatching { commitPendingTurn(name, result) }
                    },
                    trace = { DebugLog.log("LLMTRACE", it) },
                )
                outcome.error?.let { throw it }
                DebugLog.log(
                    "CHAT",
                    "outcome error=${outcome.error?.message} calls=${outcome.toolCalls.size} " +
                        "text=${outcome.text?.take(80)} turns=${outcome.turnMessages.size}",
                )

                // 完成：把完整回合（含最终总结）落库，由库 Flow 刷新出真实轨迹，再清除实时任务
                if (outcome.turnMessages.isNotEmpty()) {
                    chatRepository.appendAgentTurns(sessionId, outcome.turnMessages)
                } else {
                    // 无轨迹（如首轮即报错/无回答）：回落旧逻辑只落一条总结，避免空会话
                    val fallback = outcome.text
                        ?: if (outcome.toolCalls.isEmpty()) "（无回答）"
                        else outcome.toolCalls.joinToString("；") { call ->
                            val pkg = call.arguments.get("packageName")?.asString
                            if (pkg != null) "${call.name}($pkg)" else call.name
                        }
                    chatRepository.appendMessage(
                        ChatMessageEntity(sessionId = sessionId, role = "assistant", content = fallback)
                    )
                }
                _pendingTask.value = null // 实时任务结束：由库 Flow 呈现持久化轨迹
            } catch (e: Throwable) {
                val stopped = e is CancellationException
                val msg = if (stopped) "已由用户停止" else (e.message ?: e.javaClass.simpleName)
                DebugLog.log("CHAT", "send ${if (stopped) "cancelled" else "failed"}: $msg (${e.javaClass.simpleName})")
                if (stopped) {
                    Log.i(TAG, "send cancelled by user")
                } else {
                    Log.w(TAG, "send failed: $msg", e)
                }
                ExecutionHub.setError(taskId, if (stopped) "已由用户停止" else msg)
                _pendingTask.value = null
                _uiState.update { st ->
                    st.copy(error = if (stopped) "执行已停止" else "请求失败：$msg")
                }
            } finally {
                stopWatcher.cancel()
                // 收尾统一走 NonCancellable：用户中途停止时也要把流排空、把虚拟屏会话回收掉
                withContext(NonCancellable) {
                    streamChannel.close()
                    runCatching { renderer.join() }
                    // 释放本任务持有的设备侧资源（虚拟屏会话等）——异常/取消路径同样回收
                    runCatching { runtime.release() }
                }
                _pendingTask.value = null // 结束/取消兜底：丢弃未落库的临时任务
                ExecutionHub.end(taskId)
                runCatching { OverlayService.stop(appContext) }
                _uiState.update { it.copy(sending = false) }
            }
        }
    }

    /**
     * 流式思考增量 → 更新执行中任务的"进行中"步骤（running=true，尚无工具名/结果）。
     * 无进行中步骤则新建一个，使思考实时出现在任务卡片。
     */
    private fun updatePendingThinking(full: String) {
        _pendingTask.update { pt ->
            if (pt == null) return@update null
            val steps = pt.steps
            val last = steps.lastOrNull()
            if (last != null && last.running) {
                pt.copy(steps = steps.dropLast(1) + last.copy(thinking = full))
            } else {
                pt.copy(steps = steps + ToolStep(thinking = full, running = true))
            }
        }
    }

    /**
     * 工具结果到达：把"进行中"步骤提交为已完成步骤（工具名 + 结果），
     * 或直接追加一个新步骤（无思考直接调用工具的防御路径）。
     */
    private fun commitPendingTurn(name: String, shortResult: String) {
        _pendingTask.update { pt ->
            if (pt == null) return@update null
            val steps = pt.steps
            val last = steps.lastOrNull()
            if (last != null && last.running) {
                pt.copy(steps = steps.dropLast(1) + last.copy(toolName = name, result = shortResult, running = false))
            } else {
                pt.copy(steps = steps + ToolStep(toolName = name, result = shortResult))
            }
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(
                appContext = container.appContext,
                chatRepository = container.chatRepository,
                settingsRepository = container.settingsRepository,
                newRuntime = container::newRuntime,
                rootManager = container.rootManager,
            ) as T
    }
}

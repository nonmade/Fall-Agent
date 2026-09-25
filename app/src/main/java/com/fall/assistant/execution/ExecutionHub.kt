package com.fall.assistant.execution

import com.fall.core.agent.ExecutionChain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/** 一次 Agent 执行的可视化状态（悬浮胶囊 / 全屏监控页共享）。 */
data class ExecutionUiState(
    /** 所属任务 id（多任务并存时用于区分；见 [ExecutionHub] 的隔离说明）。 */
    val taskId: String = "",
    val active: Boolean = false,
    val goal: String = "",
    val chain: ExecutionChain = ExecutionChain.SILENT, // 当前执行的降级链（胶囊点击分流用）
    val steps: List<String> = emptyList(), // 工具步骤日志
    val text: String = "",                 // 模型实时文本（思考/总结）
    val error: String? = null,
) {
    val running: Boolean get() = active && error == null
}

/**
 * 跨组件执行状态中枢（ChatViewModel / AgentTaskActivity 写入，胶囊/监控页/预览页订阅）。
 *
 * **按任务隔离（2026-09-25 架构调整）**：原实现是"单一全局状态"，聊天页与 adb 下发任务并发时
 * 会互相覆盖 —— 任一方 `end()` 会清掉另一方的胶囊与暂停态，`requestStop()` 也无法区分目标。
 * 现在每个任务用 [ExecutionUiState.taskId] 标识，状态按 id 存放；对外暴露的 [state] 是
 * **当前任务**（最近 [begin] 的那个）的快照，单任务场景与旧行为完全一致。
 *
 * [paused]：用户从预览页接管虚拟屏时挂起**当前任务**（防抢注入），关闭预览自动恢复。
 */
object ExecutionHub {

    private val _state = MutableStateFlow(ExecutionUiState())
    val state: StateFlow<ExecutionUiState> = _state.asStateFlow()

    /** 各任务状态（仅多任务并发时才有多条；单任务场景只含一条）。 */
    private val taskStates = ConcurrentHashMap<String, ExecutionUiState>()

    @Volatile
    private var currentTaskId: String = ""

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    @Volatile
    private var pausedTaskId: String? = null

    private val _stopRequest = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** 当前正在执行的任务 id（无任务时为空串）。 */
    fun currentTask(): String = currentTaskId

    fun begin(taskId: String, goal: String, chain: ExecutionChain = ExecutionChain.SILENT) {
        val initial = ExecutionUiState(taskId = taskId, active = true, goal = goal, chain = chain)
        taskStates[taskId] = initial
        currentTaskId = taskId
        _state.value = initial
        pausedTaskId = null
        _paused.value = false
    }

    /** 用户接管虚拟屏：挂起**当前任务**（不结束任务）。 */
    fun requestPause() = requestPause(currentTaskId)

    /** 挂起指定任务（执行器按 taskId 判断，避免"暂停 A 结果把 B 也停了"）。 */
    fun requestPause(taskId: String) {
        val target = taskStates[taskId] ?: return
        if (!target.active) return
        pausedTaskId = taskId
        _paused.value = true
    }

    /** 恢复被挂起的任务（null = 恢复任意暂停中的任务；预览页关闭即调用）。 */
    fun requestResume() {
        pausedTaskId = null
        _paused.value = false
    }

    /** 指定任务当前是否处于"用户接管"暂停态（AgentLoop 的 pausedProvider 用）。 */
    fun isPaused(taskId: String): Boolean = pausedTaskId == taskId

    fun appendStep(taskId: String, name: String, result: String) =
        mutate(taskId) { it.copy(steps = it.steps + "▶ $name → $result") }

    fun appendText(taskId: String, text: String) =
        mutate(taskId) { it.copy(text = text) }

    fun setError(taskId: String, msg: String) =
        mutate(taskId) { it.copy(active = false, error = if (msg.length > 200) msg.take(200) else msg) }

    fun end(taskId: String) {
        mutate(taskId) { it.copy(active = false, error = null) }
        if (pausedTaskId == taskId) requestResume()
        taskStates.remove(taskId) // 结束的任务不再保留可变状态；_state 仍保留最后一次快照供 UI 展示
    }

    /** 请求停止**当前任务**（监控页/胶囊的停止按钮）。 */
    fun requestStop() {
        val id = currentTaskId
        if (id.isNotEmpty()) _stopRequest.tryEmit(id)
    }

    fun requestStop(taskId: String) {
        if (taskId.isNotEmpty()) _stopRequest.tryEmit(taskId)
    }

    /** 订阅"停止本任务"请求（每个任务只收到属于自己的停止信号）。 */
    fun stopRequests(taskId: String): Flow<Unit> =
        _stopRequest.asSharedFlow().filter { it == taskId }.map { }

    private inline fun mutate(taskId: String, block: (ExecutionUiState) -> ExecutionUiState) {
        val base = taskStates[taskId] ?: ExecutionUiState(taskId = taskId)
        val next = block(base)
        taskStates[taskId] = next
        if (taskId == currentTaskId) _state.value = next
    }
}

package com.fall.assistant.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.fall.assistant.FallApplication
import com.fall.assistant.core.AppContainer
import com.fall.assistant.core.DebugLog
import com.fall.assistant.execution.ExecutionHub
import com.fall.assistant.execution.OverlayService
import com.fall.automation.bridge.DaemonAuth
import com.fall.core.agent.AgentLoop
import com.fall.core.agent.ExecutionChain
import com.fall.core.agent.PHONE_AGENT_SYSTEM_HINT
import com.fall.core.agent.TaskContext
import com.fall.core.data.local.ChatMessageEntity
import com.fall.core.data.repository.toLlmConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * ADB / 任务下发入口（本 ROM 的 `am broadcast` 会吞掉 extras，故用 Activity 承载）：
 *
 * ```bash
 * # token 只落在 App 私有目录（其他 App 受 SELinux 保护读不到），adb 侧先取出来：
 * adb shell "run-as com.fall.assistant cat files/.task_token"
 * adb shell am start -n com.fall.assistant/.agent.AgentTaskActivity \
 *   -e token "<上一步取到的 token>" \
 *   -e goal "帮我打开b站然后找到up主空山猎人然后找到他主页的第一个视频,然后给这个视频点个赞"
 * ```
 *
 * 鉴权：本 Activity 需 `exported=true` 才能被 adb 拉起（非导出时 shell 也起不来），
 * 因此改为**要求 intent 携带 App 私有 token**——否则任意第三方 App 一条 startActivity 即可用
 * 用户身份执行任意自动化、消耗 LLM 额度。应用内代码走 [runAgent] 静态方法，不经此校验。
 *
 * 与聊天页共享同一 [AppContainer] 的任务装配；执行过程实时写入 [ExecutionHub]，
 * 并落库到新建会话。
 */
class AgentTaskActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val goal = intent?.getStringExtra(EXTRA_GOAL)?.trim().orEmpty()
        val mode = intent?.getStringExtra(EXTRA_MODE)?.trim().orEmpty()
        if (!authorized(intent)) {
            DebugLog.log("AGENT", "rejected external start: bad/missing token (goal=${goal.take(40)})")
            finish()
            return
        }
        if (goal.isEmpty()) {
            DebugLog.log("AGENT", "empty goal, exit")
            finish()
            return
        }
        DebugLog.log("AGENT", "goal=$goal mode=$mode")
        runAgent(this, goal, mode)
        finish()
    }

    /**
     * 外部入口凭据校验：token 由 [DaemonAuth] 在 App 启动时落到私有目录，
     * 应用内调用不经此处（[runAgent] 是纯静态方法）。
     */
    private fun authorized(intent: Intent?): Boolean {
        val expected = DaemonAuth.taskToken() ?: return false
        val provided = intent?.getStringExtra(EXTRA_TOKEN)?.trim() ?: return false
        return MessageDigest.isEqual(provided.toByteArray(), expected.toByteArray())
    }

    companion object {
        const val EXTRA_GOAL = "goal"
        const val EXTRA_MODE = "mode" // silent(默认,后台静默) | front(前台协动)
        const val EXTRA_TOKEN = "token" // 外部入口凭据

        /** 供应用内代码以纯逻辑方式触发（不依赖 Activity 展示）。 */
        fun runAgent(context: Context, goal: String, mode: String = "") {
            val container = (context.applicationContext as FallApplication).container
            // 任务 id：执行状态隔离 + 笔记落盘分文件 + 停止信号定向
            val taskId = TaskContext.newTaskId()
            val runtime = container.newRuntime(taskId)
            var stopWatcher: Job? = null
            val taskJob = GlobalScope.launch(Dispatchers.Main) {
                var sessionId: Long = 0L
                // 链门禁（2026-09-18）：显式 front 走前台；否则仅 root 模式且 su 可用时静默
                val sett = container.settingsRepository.settings.first()
                val chain =
                    if (mode == "front" ||
                        !(sett.rootModeEnabled && container.rootManager.isRootAvailable())
                    ) {
                        ExecutionChain.FRONT
                    } else {
                        ExecutionChain.SILENT
                    }
                // 阶段 5（P3+ 实验）：多 VD 并行开关已提供设置项；daemon 多会话（同进程多个
                // VirtualDisplay）是其硬前提，尚未落地——开启时仍按串行执行，仅打日志避免静默失效
                if (sett.parallelVdEnabled) {
                    DebugLog.log("AGENT", "parallelVdEnabled=true but daemon multi-session not ready: run serial (P3+)")
                }
                try {
                    ExecutionHub.begin(taskId, goal, chain)
                    // 阶段 1：SILENT 链（root 静默）任务期间保持屏幕唤醒（svc power stayon），
                    // 结束/异常时在 finally 恢复原策略（非 root 静默链本就不会走到这里）
                    val stayAwakeHeld = chain == ExecutionChain.SILENT
                    if (stayAwakeHeld) {
                        runCatching { container.rootManager.setStayAwake(true) }.getOrDefault(false)
                    }
                    // 悬浮窗仅后台静默（root 模式）展示；前台（front）任务用户可见执行，无需胶囊
                    if (chain == ExecutionChain.SILENT) {
                        // 守护进程保障（A1）：版本自检（hello 免鉴权自报 + 握手），设备上跑着旧构建/
                        // token 不匹配时自动重启（限流一次），无需用户先手工重启手机或重推 APK。
                        // 失败不阻断（执行器内部还有明确报错）
                        val daemonOk = runCatching { container.rootManager.ensureDaemon() }.getOrDefault(false)
                        DebugLog.log(
                            "AGENT",
                            "ensureDaemon(启动前自检/自愈)=$daemonOk " +
                                "reason=${container.rootManager.lastEnsureFailure() ?: "-"} rootOn=${sett.rootModeEnabled}",
                        )
                        runCatching { OverlayService.start(context) }
                    }
                    sessionId = container.chatRepository.createSession("任务")
                    container.chatRepository.appendMessage(
                        ChatMessageEntity(sessionId = sessionId, role = "user", content = goal)
                    )
                    DebugLog.log("AGENT", "session=$sessionId task=$taskId")
                    val config = sett.toLlmConfig()
                    DebugLog.log("AGENT", "llm ${config.source} ${config.model} @ ${config.baseUrl}")
                    val outcome = runtime.agentLoop.run(
                        config = config,
                        systemHint = PHONE_AGENT_SYSTEM_HINT,
                        goal = goal,
                        history = emptyList(),
                        intent = chain,
                        onContentDelta = {
                            ExecutionHub.appendText(taskId, it)
                            DebugLog.log("LLM", "text(${it.takeLast(60)})")
                        },
                        onToolResult = { name, result ->
                            ExecutionHub.appendStep(taskId, name, result)
                            DebugLog.log("TOOL", "$name -> ${result.take(200)}")
                        },
                        onRound = { i ->
                            DebugLog.log("ROUND", "round#$i done")
                        },
                        trace = { DebugLog.log("LLMTRACE", it) },
                    )
                    DebugLog.log(
                        "AGENT",
                        "outcome error=${outcome.error?.message} calls=${outcome.toolCalls.size} text=${outcome.text?.take(60)}"
                    )
                    outcome.error?.let { throw it }
                    container.chatRepository.appendMessage(
                        ChatMessageEntity(
                            sessionId = sessionId,
                            role = "assistant",
                            content = outcome.text?.takeIf { it.isNotBlank() } ?: "已执行完成",
                        )
                    )
                    ExecutionHub.appendStep(taskId, "finish", "任务完成")
                } catch (e: CancellationException) {
                    DebugLog.log("AGENT", "cancelled")
                    ExecutionHub.setError(taskId, "已由用户停止")
                } catch (e: Throwable) {
                    DebugLog.log("AGENT", "FAIL ${e.javaClass.simpleName}: ${e.message}")
                    ExecutionHub.setError(taskId, e.message ?: e.javaClass.simpleName)
                    container.chatRepository.appendMessage(
                        ChatMessageEntity(sessionId = sessionId, role = "assistant", content = "执行失败：${e.message}")
                    )
                } finally {
                    stopWatcher?.cancel()
                    // 收尾统一走 NonCancellable：用户停止/异常时也要回收虚拟屏会话
                    withContext(NonCancellable) {
                        runCatching { runtime.release() }
                    }
                    if (chain == ExecutionChain.SILENT) {
                        runCatching { container.rootManager.setStayAwake(false) }.getOrDefault(false)
                    }
                    ExecutionHub.end(taskId)
                    runCatching { OverlayService.stop(context) }
                }
            }
            // 停止订阅：原先只有聊天入口订阅 stopRequest，监控页/胶囊的"停止"对 adb 任务无效
            stopWatcher = GlobalScope.launch(Dispatchers.Main) {
                ExecutionHub.stopRequests(taskId).collect { taskJob.cancel() }
            }
        }
    }
}
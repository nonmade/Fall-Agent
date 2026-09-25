package com.fall.assistant.core

import android.content.Context
import com.fall.assistant.agent.AppLauncherExecutor
import com.fall.assistant.execution.ExecutionHub
import com.fall.automation.agent.AccessibilityAutomationExecutor
import com.fall.automation.agent.ShellChannelExecutor
import com.fall.automation.agent.VirtualDisplayAutomationExecutor
import com.fall.automation.bridge.DaemonAuth
import com.fall.core.agent.AgentLoop
import com.fall.core.agent.AgentTools
import com.fall.core.agent.ToolRegistry
import com.fall.core.data.local.createFallDatabase
import com.fall.core.data.remote.LlmClient
import com.fall.core.data.remote.OpenAiCompatibleLlmClient
import com.fall.core.data.repository.ChatRepository
import com.fall.core.data.repository.DataStoreSettingsRepository
import com.fall.core.data.repository.RoomChatRepository
import com.fall.core.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * 轻量服务容器（无 DI 框架阶段）：持有应用级单例。
 * 后续如需 Hilt/Koin 再整体替换，接口不变。
 *
 * 注意：**执行链不再由容器长期持有**（见 [newRuntime]）—— 执行器持有任务级可变状态
 * （虚拟屏会话、节点缓存、笔记），必须与任务同生命周期。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    init {
        // 守护进程 RPC 鉴权 token 与外部任务入口凭据：
        // 生成/读取一次落盘（App 私有目录，0600），供 RootManager 传 --token-file 与
        // ShellBridge 握手使用。幂等，非首次为纯内存读。
        DaemonAuth.ensure(appContext)
    }

    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(appContext)

    val rootManager: RootManager = RootManager(appContext)

    val llmClient: LlmClient = OpenAiCompatibleLlmClient()

    private val database = createFallDatabase(appContext)

    val chatRepository: ChatRepository = RoomChatRepository(database)

    /**
     * 为**一次任务**装配执行链（2026-09-25 架构调整）。
     *
     * 双链路由（2026-09-17 定稿，链路选择仍在 [AgentLoop.run] 的 `intent`）：
     * - 前台协动链：AppLauncher(open_app) → 无障碍 → ADB shell（依次降级）
     * - 后台静默链：VirtualDisplay（VD 是后台静默唯一可用通道，失败即提示）
     *
     * 每个任务新建执行器实例的原因：执行器含任务级可变状态。旧的"容器持有单例 registry"会让
     * 聊天页与 adb 下发任务共享同一个 VD / 节点缓存 / 笔记，且上一个任务未调 `finish` 时
     * 虚拟屏与其内的 App 会残留给下一个任务。调用方必须 `try { run } finally { runtime.release() }`。
     */
    fun newRuntime(taskId: String): AgentRuntime {
        val frontendRegistry = ToolRegistry(
            listOf(
                AppLauncherExecutor(appContext),
                AccessibilityAutomationExecutor(appContext, taskId),
                ShellChannelExecutor(),
            )
        )
        val silentRegistry = ToolRegistry(
            listOf(
                VirtualDisplayAutomationExecutor(
                    appContext,
                    taskId,
                    // 实时读取感知模式：设置页切换后下次 get_ui_layout 立即生效
                    perceptionModeProvider = { settingsRepository.settings.first().perceptionMode },
                ),
            )
        )
        val loop = AgentLoop(
            llmClient = llmClient,
            tools = AgentTools.defaults(),
            registry = frontendRegistry,
            maxSteps = 100,
            silentRegistry = silentRegistry,
            // 用户从预览页接管虚拟屏时挂起**本任务**（按 taskId 隔离，避免误停其他任务）
            pausedProvider = { ExecutionHub.isPaused(taskId) },
        )
        return AgentRuntime(loop, listOf(frontendRegistry, silentRegistry))
    }
}

/**
 * 一次任务的执行装配（AgentLoop + 其执行链）。**每任务一个实例**，由调用方在 `finally` 中
 * 调用 [release] 释放设备侧资源（虚拟屏会话），保证资源生命周期与任务一致。
 */
class AgentRuntime internal constructor(
    val agentLoop: AgentLoop,
    private val registries: List<ToolRegistry>,
) {
    /** 释放全部通道持有的设备侧资源（幂等；单通道异常不影响其他通道与整体收尾）。 */
    suspend fun release() {
        registries.forEach { runCatching { it.releaseAll() } }
    }
}

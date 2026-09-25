package com.fall.core.agent

import com.google.gson.JsonObject
import com.fall.core.model.llm.ChatToolSpec
import kotlinx.coroutines.CancellationException

/**
 * 工具执行器：把模型的工具调用落到真实动作。
 *
 * 多通道实现：
 * - `AppLauncherExecutor`（P0.5 演示通道）：应用内 PackageManager 直接启动
 * - `AccessibilityAutomationExecutor`（前台主通道）：无障碍服务
 * - `ShellChannelExecutor`（前台次通道）：守护进程主屏注入
 * - `VirtualDisplayAutomationExecutor`（后台静默通道）：虚拟屏
 *
 * `core.agent` 只依赖本接口，不感知具体通道。
 *
 * **生命周期约定（2026-09-25）**：执行器可能持有设备侧资源（虚拟屏会话）。这类执行器必须
 * **按任务新建**，并由调用方在 `finally` 中通过 [ToolRegistry.releaseAll] 释放 —— 否则资源会
 * 跨任务残留（下一个任务复用上一个任务的虚拟屏与界面）。
 */
interface ToolExecutor {

    /** 本执行器支持的工具名集合（与 [schema] 一一对应）。 */
    val supportedTools: Set<String>

    /** 工具声明（喂给模型的 JSON Schema）。 */
    val schema: List<ChatToolSpec>

    /** 执行一个工具，返回给模型的文本结果（observation）。 */
    suspend fun execute(name: String, args: JsonObject): String

    /** 任务收尾：释放本执行器持有的设备侧资源。默认无资源可释放（无状态通道）。 */
    suspend fun release() = Unit
}

/**
 * 执行通道不可用（如无障碍未开启 / ADB 守护进程未拉起）。
 * 由 [ToolRegistry] 捕获并降级到下一个可用通道。
 */
open class AutomationUnavailableException(message: String) : RuntimeException(message)

/** 简单注册表：按名派发到对应执行器（同名单注册多个时按注册顺序链式降级）。 */
class ToolRegistry(
    private val executors: List<ToolExecutor>,
) {
    /** name → 按注册顺序（优先级从高到低）的执行器列表。 */
    private val byName: Map<String, List<ToolExecutor>> =
        executors.flatMap { e -> e.supportedTools.map { it to e } }
            .groupBy({ it.first }, { it.second })

    /** 全部执行器的工具声明，按工具名去重（多通道共享同一套 schema 时避免重复喂给模型）。 */
    val allSchemas: List<ChatToolSpec> = executors.flatMap { it.schema }.distinctBy { it.name }

    /**
     * 依次尝试各通道执行工具，返回给模型的观察文本：
     * - 通道抛 [AutomationUnavailableException]（通道不可用）→ 降级下一个候选；
     * - **协程取消必须原样抛出**（用户已停止任务，不得继续尝试下一个候选通道）；
     * - 执行器内部意外异常 → 作为一条失败观察返回（不中断整个 Agent 循环）；
     * - 全部候选不可用 → 返回降级提示观察。
     */
    suspend fun execute(name: String, args: JsonObject): String {
        val candidates = byName[name] ?: return "未知工具: $name"
        var lastUnavailable: String? = null
        for (executor in candidates) {
            try {
                return executor.execute(name, args)
            } catch (e: CancellationException) {
                // CancellationException 是 Exception 子类，若不先重抛会被下面的分支吞掉：
                // 表现为"用户已停止，Agent 仍继续尝试其他通道"（对有副作用的工具很危险）
                throw e
            } catch (e: AutomationUnavailableException) {
                lastUnavailable = e.message
            } catch (e: Exception) {
                return "工具 $name 执行失败: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return "工具不可用：$name（${lastUnavailable ?: "未知原因"}）"
    }

    /** 任务收尾：释放全部通道的设备侧资源（幂等；单体失败不影响其他通道与整体收尾）。 */
    suspend fun releaseAll() {
        executors.forEach { runCatching { it.release() } }
    }
}

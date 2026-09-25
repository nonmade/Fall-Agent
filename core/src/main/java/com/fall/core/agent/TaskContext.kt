package com.fall.core.agent

/**
 * 任务 id 工具。
 *
 * 2026-09-25 架构调整：原实现用 `@Volatile var currentTaskId` 表达"当前任务"，是全局可变状态
 * （且聊天入口从未赋值，导致每条 record_note 都新建文件）。现在 taskId 由入口
 * **每任务生成后显式传入执行器构造参数**，这里只保留生成函数。
 */
object TaskContext {

    /** 生成新任务 id（时间戳 + 随机数，天然近似唯一）。 */
    fun newTaskId(): String = "t${System.currentTimeMillis()}-${(Math.random() * 1000).toInt()}"
}

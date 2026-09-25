package com.fall.core.bridge

/**
 * 守护进程（`app_process` + shell APK）与 App 之间的版本契约（A3）。
 *
 * 背景：守护进程的生命周期**独立于 App 安装包** —— 设备上可能是上一次构建留下的旧进程
 * （换设备装新 App、或用旧 APK 反复拉起时最明显），而旧进程缺少新 RPC 方法/鉴权，
 * 表现为"静默链不可用"或"鉴权失败"。客户端单看"端口可连"无法分辨，因此协议层加一条
 * **免鉴权**自报方法 [METHOD_HELLO]：守护进程回报 [BUILD_TAG]、[VERSION] 与自身 APK 的
 * SHA-256，客户端据此判断"设备上跑的是不是本次构建"，不一致时自动重启
 * （见 `RootManager.ensureDaemon`）。
 */
object DaemonProtocol {

    /** RPC 方法名：免鉴权自报（服务端必须在鉴权校验**之前**放行）。 */
    const val METHOD_HELLO = "hello"

    /**
     * 协议版本：RPC 方法签名/语义发生**不兼容**变更时 +1。
     * 客户端读到的 `protocol` 不等于本值即判定守护进程过旧。
     */
    const val VERSION = 1

    /** 构建标记（人类可读，日志与设置页展示；判定"是否同构建"以 APK 哈希为准）。 */
    const val BUILD_TAG = "2026-09-25f-selfheal"
}

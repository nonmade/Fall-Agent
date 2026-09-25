/**
 * 模块总览：独立 APK 的静默自动化进程（由 adb + app_process 启动，无需 launcher Activity）。
 *
 * 子包规划：
 * - display  VirtualDisplay 生命周期与目标 App 启动
 * - ui       UiAutomation 节点树读取 → 紧凑文本（Agent 感知）
 * - input    InputManager 触摸/按键/剪贴板/IME 注入（定向到 target display）
 * - ocr      离线 OCR 兜底（Paddle Lite，P2）
 * - stream   H.264 虚拟屏推流到 app（骨架占位）
 * - rpc      JSON-RPC 服务端（127.0.0.1:43110）
 *
 * 入口：[Main]。
 */
package com.fall.shell
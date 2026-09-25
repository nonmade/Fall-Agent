/**
 * Android 自动化执行模块：把"动作"落到真实设备。
 *
 * - service   AccessibilityExecutorService：免 adb 的普通自动化通道（前台可感知，需用户开启无障碍）
 * - bridge    ShellBridge：与 shell 进程（127.0.0.1:43110，JSON-RPC）通信，驱动 VirtualDisplay 后台静默通道
 *
 * 通道选择策略（双层路由）：后台静默走 shell，普通任务走 Accessibility。
 */
package com.fall.automation
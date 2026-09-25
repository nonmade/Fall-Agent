/**
 * ShellBridge：连接 shell 进程的 JSON-RPC 客户端。
 *
 * shell 仅监听回环 127.0.0.1:43110，RPC 面：startTask/stopTask/status/logs/ping。
 * TODO(P0.5)：基于 OkHttp/Ktor 实现长连接 + 事件订阅（H.264 推流另行定义）。
 */
package com.fall.automation.bridge
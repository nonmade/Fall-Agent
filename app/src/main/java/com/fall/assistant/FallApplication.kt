package com.fall.assistant

import android.app.Application
import android.util.Log
import com.fall.assistant.core.AppContainer
import com.fall.assistant.core.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FallApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 部分国产 ROM 会清空 logcat（实测机型复现），崩溃栈无处可查；
        // 全局捕获写入 files/crash/ 供诊断（run-as com.fall.assistant cat files/crash/*.log）。
        installCrashLog()
        container = AppContainer(this)
        // 初始化文件日志（files/logs/fall.log，轮转有界），并按持久化设置恢复「日志模式」开关。
        DebugLog.init(this)
        GlobalScope.launch(Dispatchers.IO) {
            val s = container.settingsRepository.settings.first()
            DebugLog.setEnabled(s.debugLoggingEnabled)
        }
    }

    private fun installCrashLog() {
        val dir = File(filesDir, "crash").apply { mkdirs() }
        // 必须先捕获前一个（系统的 KillApplicationHandler），再设置自己的处理器。
        // 若在回调里再调 getDefaultUncaughtExceptionHandler()，拿到的是本处理器自身，
        // 会无限递归 -> StackOverflowError 死循环（表现为 App 卡死无响应 + 每秒刷一个 crash 文件）。
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stamp = SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date())
                val f = File(dir, "crash_$stamp.log")
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                f.writeText(
                    "thread=${thread.name}\n" +
                        "${throwable.javaClass.name}: ${throwable.message}\n$sw"
                )
                Log.e("FallCrash", "uncaught on ${thread.name}", throwable)
            } catch (_: Throwable) {
                // 日志写出失败不得影响后续终止流程
            } finally {
                // 交还给先前处理器（系统默认会结束进程）；没有先前处理器则直接自杀
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        }
    }
}
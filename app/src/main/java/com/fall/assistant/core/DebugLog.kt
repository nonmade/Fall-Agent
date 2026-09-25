package com.fall.assistant.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件级日志系统（本 ROM 清空 logcat，改写文件，`run-as` 读取调试）。
 *
 * 设计要点：
 * - **开关**：设置页「日志模式」控制（[setEnabled]）。关闭后所有日志调用立即短路，
 *   不产生任何文件 IO 与存储；崩溃日志（files/crash/）作为安全网始终保留、不受开关影响。
 * - **分级**：VERBOSE < DEBUG < INFO < WARN < ERROR，阈值默认 DEBUG（开发期全量记录）。
 * - **存储与轮转**：常规日志写 files/logs/fall.log，单文件上限 1MB，超限滚动为
 *   fall.log.1/.2/...，共保留 5 份（≤5MB）；启动时清理超过 7 天的旧文件。
 * - **读取**：`adb shell run-as com.fall.assistant cat files/logs/fall.log`
 */
object DebugLog {

    enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

    private const val MAX_FILE_BYTES = 1 * 1024 * 1024
    private const val MAX_FILES = 5             // fall.log + fall.log.1..3
    private const val RETENTION_DAYS = 7L        // 超过 7 天的旧日志启动时清理
    private const val MAX_CRASH_FILES = 10       // 崩溃日志保留上限（安全网）

    @Volatile
    private var enabled = true // 开发期默认开；正式发布前随设置默认值一起改为 false

    @Volatile
    private var file: File? = null

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var crashDir: File? = null

    private val threshold = Level.DEBUG

    /** 幂等；由 Application 统一调用（AgentTaskActivity 等入口不再单独 init）。 */
    fun init(context: Context) {
        logDir = File(context.filesDir, "logs").apply { mkdirs() }
        crashDir = File(context.filesDir, "crash").apply { mkdirs() }
        file = File(logDir, "fall.log")
        cleanExpired()
    }

    /** 日志模式开关（设置页控制）。关闭后所有日志调用立即返回，不产生任何 IO。 */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    /** 兼容旧调用点：默认 INFO 级写一行。 */
    fun log(tag: String, msg: String) = log(Level.INFO, tag, msg)

    fun v(tag: String, msg: String) = log(Level.VERBOSE, tag, msg)
    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)
    fun e(tag: String, msg: String) = log(Level.ERROR, tag, msg)

    private fun log(level: Level, tag: String, msg: String) {
        if (!enabled) return // 开关关闭：零成本短路，不触碰文件系统
        if (level.ordinal < threshold.ordinal) return
        val f = file ?: return
        kotlin.runCatching {
            synchronized(this) {
                if (f.length() > MAX_FILE_BYTES) rotate()
                val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
                f.appendText("[$stamp][${level.name[0]}][$tag] $msg\n")
            }
        }
    }

    /** fall.log → fall.log.1 → .2 → ... 滚动，删除最旧一份。 */
    private fun rotate() {
        val dir = logDir ?: return
        for (i in MAX_FILES - 2 downTo 1) {
            val from = File(dir, "fall.log.$i")
            val to = File(dir, "fall.log.${i + 1}")
            if (from.exists()) from.renameTo(to)
        }
        File(dir, "fall.log").renameTo(File(dir, "fall.log.1"))
    }

    /** 启动清理：删除超过保留期的常规日志；崩溃日志只保留最近 [MAX_CRASH_FILES] 份。 */
    private fun cleanExpired() {
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24 * 3600 * 1000L
        logDir?.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
        crashDir?.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CRASH_FILES)
            ?.forEach { it.delete() }
    }
}

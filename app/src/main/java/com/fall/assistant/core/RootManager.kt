package com.fall.assistant.core

import android.content.Context
import android.os.SystemClock
import com.fall.automation.bridge.DaemonAuth
import com.fall.automation.bridge.ShellBridge
import com.fall.core.bridge.DaemonProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/**
 * Root 能力探测与守护进程自举/自愈（best-effort）。
 *
 * 背景：后台静默链（VirtualDisplay）依赖 `app_process` 守护进程（shell 特权）。它的生命周期
 * **独立于 App 安装包**：设备上可能是上次构建留下的旧进程（换设备、换 APK 后最明显），旧进程
 * 缺少新 RPC 方法或鉴权，表现为"静默链不可用 / 鉴权失败"。因此这里不只"探活"，而是做
 * **版本自检 + 自动重启**（A1/A3）：
 *
 * - [probeDaemon]：免鉴权 `hello`（拿到对面的构建标记/协议版本/APK 哈希）+ 握手 `ping`
 *   （验 token），得出"未运行 / 版本过旧 / token 不匹配 / 可用"；
 * - [ensureDaemon]：任务开始前调用。自检不通过且有 root 时**重启一次**（内置 APK 见
 *   [ShellApkBundle]），限流防抖动；重启失败不阻断任务（执行器内部还有明确报错）；
 * - [restartDaemon]：设置页"重启"按钮（用户显式操作，不受限流约束）。
 *
 * **只在任务开始前自愈**：重启会清掉守护进程侧的虚拟屏会话，任务中途重启 = 当前任务报废。
 */
class RootManager(private val context: Context) {

    /** 守护进程探测结果（设置页展示 + 自愈判断共用）。 */
    data class DaemonStatus(
        /** 端口可连（有响应）。 */
        val running: Boolean,
        /** `hello` 自报的构建标记；对面为没有 `hello` 的旧构建时 null。 */
        val buildTag: String?,
        /** `hello` 自报的协议版本。 */
        val protocol: Int?,
        /** `hello` 自报的自身 APK 哈希。 */
        val apkHash: String?,
        /** App 内置 shell APK 的哈希；无内置资源时 null（此时不做哈希比对）。 */
        val expectedHash: String?,
        /** 握手是否通过（token 匹配）。 */
        val authOk: Boolean,
        /** 可用 = 在跑 + 握手通过 + 与内置构建一致。 */
        val usable: Boolean,
        /** 面向用户/日志的状态说明。 */
        val message: String,
    )

    /** 上一次 [ensureDaemon] 失败的原因（供日志与提示；成功时为 null）。 */
    @Volatile
    private var lastEnsureFailure: String? = null

    /** 上次重启时刻（`elapsedRealtime`），用于限流。 */
    private var lastRestartAt = 0L

    /** 探针专用连接（只用于 [probeDaemon]；与执行器各自的 bridge 互不影响）。 */
    private val probeBridge by lazy { ShellBridge() }

    fun lastEnsureFailure(): String? = lastEnsureFailure

    /** `su -c id` 探测 root 可用性；内部已切 IO，超时（Magisk 首弹窗偏慢）兜底返回 false。 */
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            withTimeout(RootProbeTimeoutMs) {
                runSuId().contains("uid=0")
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 探测守护进程状态与版本（无副作用：不拉起、不重启）。设置页与自愈判断共用。
     * 不需要 root：`hello`/`ping` 都是回环直连。
     */
    suspend fun probeDaemon(): DaemonStatus = withContext(Dispatchers.IO) {
        val expected = ShellApkBundle.expectedHash(context)
        val info = probeBridge.hello()
        val authOk = probeBridge.ping(ProbeTimeoutMs)
        val running = info != null || authOk
        // 版本判定：协议版本必须一致；内置 APK 与对面都报得出哈希时还要哈希一致
        // （人肉维护的构建标记会漏；哈希不会）
        val protocolOk = info?.protocol == DaemonProtocol.VERSION
        val hashOk = expected == null || info?.apkHash == null || info.apkHash == expected
        val versionOk = info != null && protocolOk && hashOk
        val usable = running && authOk && versionOk
        DaemonStatus(
            running = running,
            buildTag = info?.buildTag,
            protocol = info?.protocol,
            apkHash = info?.apkHash,
            expectedHash = expected,
            authOk = authOk,
            usable = usable,
            message = when {
                usable -> "运行中 · 构建 ${info?.buildTag} · 协议 ${info?.protocol}"
                !running -> "未运行"
                !versionOk -> "版本不匹配（设备上是 ${info?.buildTag ?: "无自报的旧构建"}，" +
                    "App 内置 ${DaemonProtocol.BUILD_TAG}）"
                !authOk -> "握手失败（token 不匹配）"
                else -> "不可用"
            },
        )
    }

    /**
     * 任务开始前确保守护进程可用（A1 自愈）：
     * 探活 + 版本自检 → 不匹配/不可用则**限流**重启一次（用 App 内置 APK）→ 等到就绪再复查。
     *
     * 重启失败不抛错，只返回 false 并记录原因（执行器内部还有明确报错，避免与任务错误重复打扰）。
     */
    suspend fun ensureDaemon(): Boolean = withContext(Dispatchers.IO) {
        val before = probeDaemon()
        if (before.usable) {
            lastEnsureFailure = null
            return@withContext true
        }
        if (!isRootAvailable()) {
            lastEnsureFailure = "守护进程${before.message}，且未检测到 root 权限，无法自动拉起/重启"
            return@withContext false
        }
        if (SystemClock.elapsedRealtime() - lastRestartAt < AUTO_RESTART_COOLDOWN_MS) {
            lastEnsureFailure = "守护进程${before.message}；${AUTO_RESTART_COOLDOWN_MS / 1000}s 内已自动重启过，跳过重复重启"
            return@withContext false
        }
        DebugLog.log(TAG, "守护进程自检不通过（${before.message}）→ 自动重启")
        val after = restartAndWait()
        if (!after.usable) DebugLog.log(TAG, lastEnsureFailure ?: "守护进程仍不可用")
        after.usable
    }

    /**
     * 强制重启守护进程（设置页按钮；用户显式操作，不受限流约束）：
     * 先 pkill 旧进程（root 身份可杀），等端口释放后用**最新可用 APK** 重新拉起并等它就绪。
     */
    suspend fun restartDaemon(): Boolean = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) {
            lastEnsureFailure = "未检测到 root 权限，无法重启守护进程"
            return@withContext false
        }
        restartAndWait().usable
    }

    /**
     * root 下自动启用无障碍服务（免用户去系统设置手动开启）：
     * `settings put secure enabled_accessibility_services <component> && settings put secure accessibility_enabled 1`。
     * 组件名由调用方给出（如 AccessibilityExecutorService.componentName）。
     */
    suspend fun enableAccessibility(component: String): Boolean = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext false
        runCatching {
            val cmd = "settings put secure enabled_accessibility_services $component" +
                " && settings put secure accessibility_enabled 1"
            val process = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val finished = process.waitFor(CmdTimeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroy()
            finished && process.exitValue() == 0
        }.getOrDefault(false)
    }

    /**
     * root 下切换屏幕保活（`svc power stayon`），配合 VD 后台静默（阶段 1）：
     * SILENT 链任务期间保持休眠不超时，任务结束恢复原策略（YAGNI：不新增设置项）。
     * 非 root / 执行失败直接返回 false，不打扰用户。
     * 安全红线：仅停留唤醒，不做 Keyguard 绕过（真锁屏下静默操作第三方 App 仍需 L3 授权）。
     */
    suspend fun setStayAwake(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext false
        runCatching {
            val cmd = "svc power stayon ${if (enabled) "true" else "false"}"
            val process = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val finished = process.waitFor(CmdTimeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroy()
            finished && process.exitValue() == 0
        }.getOrDefault(false)
    }

    // ---------- 内部：拉起 / 重启 ----------

    /** 重启并等待就绪；同时维护 [lastEnsureFailure]。 */
    private suspend fun restartAndWait(): DaemonStatus {
        lastRestartAt = SystemClock.elapsedRealtime()
        if (!restartInternal()) {
            lastEnsureFailure = "守护进程重启失败：${lastEnsureFailure ?: "su 拉起未成功"}"
            return probeDaemon()
        }
        val after = waitUntilUsable()
        lastEnsureFailure = if (after.usable) null else "守护进程重启后仍不可用（${after.message}）"
        return after
    }

    /** pkill 旧进程 → 重建探针连接 → 用最新 APK 拉起。返回是否成功下发。 */
    private suspend fun restartInternal(): Boolean {
        killDaemon()
        // 旧连接指向已死进程：显式丢弃，避免本次探针/后续调用先撞一次写失败
        probeBridge.dropConnection()
        return launchDaemon()
    }

    /** 拉起守护进程：优先 App 内置 APK（[ShellApkBundle]），回落设备上手工 push 的路径。 */
    private fun launchDaemon(): Boolean {
        val apk = resolveShellApk()
        if (apk == null) {
            lastEnsureFailure = "没有可用的守护进程 APK（内置资源不可用，且 $LegacyShellApkPath 不存在）"
            DebugLog.log(TAG, lastEnsureFailure!!)
            return false
        }
        val launched = runCatching {
            val process = ProcessBuilder("su", "-c", launchCommand(apk))
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(CmdTimeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroy()
            // 拉起命令是 nohup 后台任务，其自身输出为空即视为 su 已接管成功
            process.inputStream.bufferedReader().use { it.readText().isBlank() }
        }.getOrDefault(false)
        DebugLog.log(TAG, "launchDaemon(apk=$apk) su=$launched")
        return launched
    }

    /**
     * 拉起命令：与 PC 端 adb 等价，但**额外传 `--token-file`**——
     * 守护进程据此启用 RPC 握手鉴权；token 文件在 App 私有目录（root 可读、其他 App 受 SELinux 挡）。
     */
    private fun launchCommand(apkPath: String): String {
        DaemonAuth.ensure(context)
        val tokenArg = DaemonAuth.tokenFilePath()?.let { " --token-file=$it" }.orEmpty()
        return "CLASSPATH=$apkPath " +
            "nohup app_process /system/bin com.fall.shell.Main --port=$ShellPort$tokenArg " +
            ">$DaemonLogPath 2>&1 &"
    }

    /** 内置 APK 优先；不可用时回落设备上手工 push 的 `/data/local/tmp/fall-shell.apk`。 */
    private fun resolveShellApk(): String? {
        ShellApkBundle.materialize(context)?.let { return it }
        val legacy = LegacyShellApkPath.takeIf { suFileExists(it) }
        DebugLog.log(
            TAG,
            if (legacy != null) "内置 shell APK 不可用 → 回落 $legacy" else "内置 shell APK 与 $LegacyShellApkPath 均不可用",
        )
        return legacy
    }

    /** 杀掉旧守护进程（root 身份可杀），并给端口一个释放窗口。 */
    private fun killDaemon() {
        runCatching {
            val process = ProcessBuilder("su", "-c", "pkill -f com.fall.shell.Main")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(CmdTimeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroy()
        }
        Thread.sleep(KillSettleMs)
    }

    /** 轮询等待守护进程就绪（拉起/重启后 ShellContext 初始化 + 端口绑定需要时间）。 */
    private suspend fun waitUntilUsable(): DaemonStatus {
        val deadline = SystemClock.elapsedRealtime() + ReadyTimeoutMs
        var last = probeDaemon()
        while (!last.usable && SystemClock.elapsedRealtime() < deadline) {
            delay(ReadyPollMs)
            last = probeDaemon()
        }
        return last
    }

    /** root 下经 su 检查文件是否存在。 */
    private fun suFileExists(path: String): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "test -f $path && echo exists")
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(CmdTimeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            false
        } else {
            process.inputStream.bufferedReader().use { it.readText().contains("exists") }
        }
    }.getOrDefault(false)

    /** 执行 `su -c id` 并返回输出（含 stderr；超时销毁进程防泄漏）。 */
    private fun runSuId(): String = runCatching {
        val process = ProcessBuilder("su", "-c", "id")
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(CmdTimeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            ""
        } else {
            process.inputStream.bufferedReader().use { it.readText() }
        }
    }.getOrDefault("")

    private companion object {
        const val TAG = "FallRoot"

        /** 守护进程默认端口（与 ShellBridge / Main.kt --port 一致）。 */
        const val ShellPort = 43110

        /** 旧流程手工 push 的位置（内置 APK 不可用时的回落）。 */
        const val LegacyShellApkPath = "/data/local/tmp/fall-shell.apk"

        /** 守护进程日志（root 可写，便于 adb 排查）。 */
        const val DaemonLogPath = "/data/local/tmp/fall-shell.log"

        /** 探测超时：Magisk 首次授权弹窗可能较慢，给足 5s。 */
        const val RootProbeTimeoutMs = 5_000L

        /** 单次 waitFor 上限。 */
        const val CmdTimeoutMs = 4_000L

        /** 探针（hello / ping）单次往返超时。 */
        const val ProbeTimeoutMs = 2_000L

        /** 自动重启限流窗口：窗口内不再重复重启（防"起来了又挂"造成抖动）。 */
        const val AUTO_RESTART_COOLDOWN_MS = 120_000L

        /** pkill 后等端口释放。 */
        const val KillSettleMs = 1_200L

        /** 拉起后就绪等待上限与轮询间隔。 */
        const val ReadyTimeoutMs = 10_000L
        const val ReadyPollMs = 500L
    }
}

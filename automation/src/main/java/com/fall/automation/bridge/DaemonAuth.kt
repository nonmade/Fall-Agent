package com.fall.automation.bridge

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * 守护进程鉴权 token 与应用私有 token 文件。
 *
 * 背景：`:shell` 守护进程只监听回环，但**回环对其他 App 完全可达**；由 `su` 拉起时还是
 * root 身份并持有 INJECT_EVENTS，因此"无鉴权"等于任意同机 App 可静默读屏 + 伪造点击。
 * 这里用"随机 token 落在仅本 App 可读的文件里"做握手校验（token 由 mutex 保护的进程内缓存持有，
 * 不落任何跨 App 可读位置）：
 *
 * - [ensure]：读取/创建 `filesDir/.daemon_token` 与 `filesDir/.task_token`（权限收为 0600）；
 * - [token] / [tokenFilePath]：供 [ShellBridge] 握手、[com.fall.assistant.core.RootManager] 传
 *   `--token-file=` 启动参数（守护进程为 root，可读 App 私有目录）；
 * - [taskToken]：`AgentTaskActivity` 外部下发任务的一次性凭据。
 *
 * App 私有目录受 SELinux `app_data_file` 标签保护，其他 App 无法读取；配合 0600 是双保险。
 */
object DaemonAuth {

    /** 守护进程 RPC 握手 token 文件（App 私有）。 */
    private const val DAEMON_TOKEN_FILE = ".daemon_token"

    /** 外部下发任务凭据文件（App 私有；adb 侧用 run-as 读取）。 */
    private const val TASK_TOKEN_FILE = ".task_token"

    private val random = SecureRandom()

    @Volatile
    private var token: String? = null

    @Volatile
    private var tokenPath: String? = null

    @Volatile
    private var taskToken: String? = null

    /**
     * 读取（必要时生成）两个 token；幂等，可在 App 启动时调用一次。
     * 含极小文件 IO，首次调用建议放在 IO 线程（非首次为纯内存读）。
     */
    fun ensure(context: Context): String = synchronized(this) {
        token?.let { return it!! }
        val daemonFile = File(context.filesDir, DAEMON_TOKEN_FILE)
        val daemon = readOrCreate(daemonFile)
        token = daemon
        tokenPath = daemonFile.absolutePath
        taskToken = readOrCreate(File(context.filesDir, TASK_TOKEN_FILE))
        daemon
    }

    /** 当前握手 token；[ensure] 之前为 null（null 表示不启用握手，仅用于未带 token 的开发期守护进程）。 */
    fun token(): String? = token

    /** token 文件绝对路径（`--token-file=` 参数值）。 */
    fun tokenFilePath(): String? = tokenPath

    /** 外部任务下发凭据。 */
    fun taskToken(): String? = taskToken

    /** 读文件；不存在/为空则生成新 token 写入并把权限收为仅属主可读写。 */
    private fun readOrCreate(file: File): String {
        runCatching {
            file.readText().trim().takeIf { it.isNotBlank() }?.let { return it }
        }
        val fresh = newToken()
        runCatching {
            file.writeText(fresh)
            restrictToOwner(file)
        }
        return fresh
    }

    /** 32 hex 字符（128 bit）随机 token。 */
    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 尽力收权限为 0600（App 私有目录 + SELinux 已是主保护，这里是纵深防御）。 */
    @Suppress("DEPRECATION")
    private fun restrictToOwner(file: File) {
        runCatching { file.setReadable(false, false) }
        runCatching { file.setReadable(true, true) }
        runCatching { file.setWritable(false, false) }
        runCatching { file.setWritable(true, true) }
        runCatching { file.setExecutable(false, false) }
    }
}

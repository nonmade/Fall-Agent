package com.fall.shell

import com.fall.core.bridge.DaemonProtocol
import java.io.File
import java.security.MessageDigest

/**
 * app_process 入口（后台静默自动化进程核心，无 Activity）。
 *
 * 启动方式（生产路径由 App 经 `su` 自动拉起，见 `RootManager`：优先用 App 内置 assets 里的
 * shell APK 解压到私有目录，回落到手工 push 的 /data/local/tmp/fall-shell.apk）：
 * ```
 * adb push shell-debug.apk /data/local/tmp/fall-shell.apk
 * adb shell "CLASSPATH=/data/local/tmp/fall-shell.apk nohup app_process /system/bin \
 *   com.fall.shell.Main --port=43110 --token-file=/data/local/tmp/fall-shell.token &"
 * ```
 *
 * `--token-file`（可选但强烈建议）：RPC 握手 token 文件路径。App 侧传的是 App 私有目录下的
 * `.daemon_token`（root 身份可读，其他 App 受 SELinux 保护读不到）；未提供时**不启用鉴权**并打印告警，
 * 仅用于开发期 adb 手动拉起（见 `JsonRpcServer` 的鉴权说明）。
 *
 * 可用方法见 [ShellServer]。
 */
object Main {

    /** 构建标记：日志首行打印 + `hello` 自报，便于确认设备上跑的是不是最新构建。 */
    const val BUILD_TAG = DaemonProtocol.BUILD_TAG

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args
            .mapNotNull { it.removePrefix("--port=").toIntOrNull() }
            .firstOrNull() ?: 43110
        val tokenFile = args.firstNotNullOfOrNull { arg ->
            val p = arg.removePrefix("--token-file=")
            p.takeIf { p != arg && p.isNotBlank() }
        }
        val token = tokenFile?.let { path ->
            runCatching { File(path).readText().trim() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: run {
                    println("[fall-shell] ⚠ 读取 token 文件失败（$path）：本次禁用 RPC 鉴权")
                    null
                }
        }
        val apkHash = selfApkHash()
        println(
            "[fall-shell] starting build=$BUILD_TAG, protocol=${DaemonProtocol.VERSION}, " +
                "apkHash=${apkHash?.take(12) ?: "(unknown)"}, port=$port, " +
                "tokenFile=${tokenFile ?: "(none)"}",
        )
        try {
            ShellServer(port, token, helloInfo(apkHash)).run()
        } catch (t: Throwable) {
            println("[fall-shell] fatal: $t")
            t.printStackTrace()
            runCatching { System.exit(1) }
        }
    }

    /** `hello` 自报内容（免鉴权，供客户端做版本/构建比对，见 [DaemonProtocol]）。 */
    private fun helloInfo(apkHash: String?): org.json.JSONObject = org.json.JSONObject()
        .put("buildTag", BUILD_TAG)
        .put("protocol", DaemonProtocol.VERSION)
        .put("apkHash", apkHash ?: org.json.JSONObject.NULL)

    /**
     * 自身 APK 的 SHA-256：`CLASSPATH`（本进程的 `java.class.path`）指向的 shell APK。
     * 客户端把本值与 App 内置 APK 的哈希对比，即可判定"设备上跑的是不是本次构建"——
     * 比人肉维护的构建标记可靠（改了代码忘改标记不会被漏掉）。
     */
    private fun selfApkHash(): String? = runCatching {
        val path = System.getProperty("java.class.path")
            ?.substringBefore(File.pathSeparatorChar)
            ?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val digest = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}

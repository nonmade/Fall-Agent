package com.fall.assistant.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * 内置守护进程 APK（A2）：把与 App **同构建**的 shell APK 打进 assets，运行期解压到 App 私有目录，
 * 作为 `app_process` 的 CLASSPATH 使用。
 *
 * 为什么需要：守护进程的生命周期独立于 App 安装包，旧实现依赖手工
 * `adb push shell-debug.apk /data/local/tmp/fall-shell.apk` —— 换设备或更新代码后必须重新
 * push + 重启守护进程，否则会撞"鉴权失败 / 缺新方法"。内置后 App 自带可用构建，配合
 * `RootManager.ensureDaemon` 的版本自检即可自动切换到本次构建（免 adb、免重启手机）。
 *
 * 安全：解压到 `filesDir/shell/`（SELinux `app_data_file` 保护 + 其他 App 不可写），
 * 并用 `PackageManager` 解析归档签名，要求与 App 自身签名一致，避免被替换成第三方 APK。
 * 设备上 `root` 身份可读该路径（与 `.daemon_token` 同理），守护进程因此能加载它。
 */
object ShellApkBundle {

    /** assets 里的文件名（构建期由 Gradle 复制，见 `app/build.gradle.kts`）。 */
    private const val ASSET_NAME = "fall-shell.apk"

    private const val DIR_NAME = "shell"

    private const val FILE_NAME = "fall-shell.apk"

    @Volatile
    private var expectedHash: String? = null

    @Volatile
    private var hashProbed = false

    /** 解压目标路径（未解压时不存在）。 */
    fun extractedFile(context: Context): File = File(File(context.filesDir, DIR_NAME), FILE_NAME)

    /**
     * 内置 APK 的 SHA-256（进程内缓存；assets 在一个进程内不可变）。
     * 与守护进程 `hello` 回报的自身哈希比对即可判定"设备上跑的是不是本次构建"。
     * 无内置资源（assets 未打进包）时返回 null。
     */
    fun expectedHash(context: Context): String? {
        if (hashProbed) return expectedHash
        expectedHash = runCatching { sha256 { context.assets.open(ASSET_NAME) } }.getOrNull()
        hashProbed = true
        return expectedHash
    }

    /**
     * 确保内置 APK 可用：内容哈希一致时直接复用，否则解压 → 校验哈希与签名 → 原子替换。
     * 返回可用路径；无内置资源或校验失败返回 null（调用方回落设备上的 `/data/local/tmp`）。
     */
    fun materialize(context: Context): String? {
        val expected = expectedHash(context) ?: return null
        val appSigner = appSigner(context) ?: return null
        val out = extractedFile(context)
        if (out.isFile && sha256Of(out) == expected && signerOf(context, out)?.contentEquals(appSigner) == true) {
            return out.absolutePath
        }
        val tmp = File(out.parentFile, "$FILE_NAME.tmp")
        return runCatching {
            out.parentFile?.mkdirs()
            context.assets.open(ASSET_NAME).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (sha256Of(tmp) != expected) return@runCatching null
            if (signerOf(context, tmp)?.contentEquals(appSigner) != true) return@runCatching null
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            out.absolutePath
        }.getOrNull()
    }

    /** 文件 SHA-256（十六进制小写）。 */
    private fun sha256Of(file: File): String? = runCatching { sha256 { file.inputStream() } }.getOrNull()

    private inline fun sha256(open: () -> java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        open().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** APK 归档里的首个签名证书字节。 */
    private fun signerOf(context: Context, apk: File): ByteArray? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
                ?.signatures?.firstOrNull()?.toByteArray()
        }
    }.getOrNull()

    /** 已安装 App 自身的首个签名证书字节。 */
    private fun appSigner(context: Context): ByteArray? = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        }
    }.getOrNull()
}

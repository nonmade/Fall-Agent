package com.fall.assistant.agent

import android.content.Context
import android.content.Intent
import com.fall.core.agent.AgentTools
import com.fall.core.agent.ToolExecutor
import com.fall.core.model.llm.ChatToolSpec
import com.google.gson.JsonObject

/**
 * 打开应用通道：优先按 packageName；缺失或无效时按 appLabel 模糊匹配已装应用。
 * 便于模型只凭应用中文名（如"哔哩哔哩"）也能打开目标。
 */
class AppLauncherExecutor(private val context: Context) : ToolExecutor {

    override val supportedTools: Set<String> = setOf(AgentTools.NAME_OPEN_APP)

    override val schema: List<ChatToolSpec> = listOf(AgentTools.openApp())

    override suspend fun execute(name: String, args: JsonObject): String {
        if (name != AgentTools.NAME_OPEN_APP) return "不支持的工具: $name"
        val packageName = string(args, "packageName").ifBlank { null }
        val label = string(args, "appLabel").ifBlank { null }

        val resolved = resolve(packageName, label)
        val target = resolved ?: return "未找到应用${packageName?.let { "（$it）" } ?: ""}${label?.let { "（$it）" } ?: ""}，请检查名称"

        val launchIntent = context.packageManager.getLaunchIntentForPackage(target)
            ?: return "应用已找到但没有启动入口: $target"

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(launchIntent)
        return "已打开应用 ${label ?: target}（$target）"
    }

    private fun string(obj: JsonObject, key: String): String =
        if (obj.has(key) && !obj.get(key).isJsonNull) obj.get(key).asString.trim() else ""

    /** 包名优先（仅英文包名有效）；无效时回退按 label 精确/包含匹配已装应用。
     * 模型常把中文应用名错放进 packageName，因此无效包名也会按名称模糊匹配。 */
    private fun resolve(packageName: String?, label: String?): String? {
        if (!packageName.isNullOrBlank()) {
            if (context.packageManager.getLaunchIntentForPackage(packageName) != null) return packageName
        }
        if (label.isNullOrBlank() && packageName != null) {
            return matchByLabel(packageName)
        }
        if (!label.isNullOrBlank()) {
            return matchByLabel(label)
        }
        return null
    }

    private fun matchByLabel(targetRaw: String): String? {
        val target = targetRaw.trim()
        if (target.isBlank()) return null
        val pm = context.packageManager
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrNull() ?: return null
        // 先精确匹配，再包含匹配
        apps.forEach { app ->
            val l = runCatching { app.loadLabel(pm).toString() }.getOrNull() ?: return@forEach
            if (l == target) return app.packageName
        }
        apps.forEach { app ->
            val l = runCatching { app.loadLabel(pm).toString() }.getOrNull() ?: return@forEach
            if (l.contains(target) || target.contains(l)) return app.packageName
        }
        return null
    }
}
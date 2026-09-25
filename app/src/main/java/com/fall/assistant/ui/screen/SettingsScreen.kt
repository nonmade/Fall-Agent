package com.fall.assistant.ui.screen

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fall.assistant.FallApplication
import com.fall.assistant.core.AppContainer
import com.fall.assistant.core.RootManager
import com.fall.core.agent.PerceptionMode
import com.fall.core.bridge.DaemonProtocol
import com.fall.core.data.repository.PreviewQuality
import com.fall.core.model.llm.LlmSource

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(localAppContainer()),
    ),
) {
    val state by viewModel.uiState.collectAsState()
    val draft = state.draft

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExecutionPermissionsSection()
        AccessibilitySection(
            enabled = state.accessibilityEnabled,
            error = state.accessibilityError,
            onRefresh = viewModel::refreshAccessibility,
            onAutoEnable = viewModel::autoEnableAccessibility,
        )

        SectionTitle("调试")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("日志模式", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "开启：写入 files/logs/fall.log（含 LLM 调用与工具执行轨迹，轮转上限约 5MB），" +
                        "可用 run-as 读取调试；关闭：不产生任何日志。崩溃日志始终保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = draft.debugLoggingEnabled,
                onCheckedChange = viewModel::setLoggingEnabled,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Root 模式", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "开启后后台静默任务走 VirtualDisplay（需设备 root：首次开启会请求授权）；" +
                        "守护进程用 App 内置的 shell APK 自动拉起，无需手工 adb push；" +
                        "未开启时任务自动走前台（无障碍）执行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = draft.rootModeEnabled,
                onCheckedChange = viewModel::setRootModeEnabled,
            )
        }
        state.rootModeError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        DaemonSection(
            status = state.daemonStatus,
            probing = state.daemonProbing,
            rootModeEnabled = draft.rootModeEnabled,
            message = state.daemonRestartMessage,
            onRefresh = viewModel::refreshDaemonStatus,
            onRestart = viewModel::restartDaemon,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("多虚拟屏并行执行（实验）", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "多个任务走独立 VirtualDisplay 并行执行（P3+，默认关）：占用更多 CPU/GPU/显存，建议最多 2 个；" +
                        "关闭时任务逐个串行执行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = draft.parallelVdEnabled,
                onCheckedChange = viewModel::setParallelVdEnabled,
            )
        }

        SectionTitle("预览画质（虚拟屏围观页）")
        Text(
            "选择点开胶囊后虚拟屏画面呈现方式（预览统一全屏 + 60fps）：" +
                "低/高=JPEG 推流 60fps（流失败自动回退轮询，默认）；实时=H.264 硬编推流 + GPU 直读（实验，最流畅，需 Root）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviewQuality.entries.forEach { quality ->
                FilterChip(
                    selected = draft.previewQuality == quality,
                    onClick = { viewModel.setPreviewQuality(quality) },
                    label = {
                        Text(
                            when (quality) {
                                PreviewQuality.LOW -> "低（现状）"
                                PreviewQuality.HIGH -> "高（过渡）"
                                PreviewQuality.REALTIME -> "实时（实验）"
                            }
                        )
                    },
                )
            }
        }

        SectionTitle("感知方式（静默任务）")
        Text(
            "选择 VirtualDisplay 静默任务识别屏幕的方式，切换后下次任务立即生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PerceptionMode.entries.forEach { mode ->
                FilterChip(
                    selected = draft.perceptionMode == mode,
                    onClick = { viewModel.setPerceptionMode(mode) },
                    label = {
                        Text(if (mode == PerceptionMode.MULTIMODAL) "多模态（推荐）" else "启用 OCR 模型")
                    },
                )
            }
        }
        Text(
            when (draft.perceptionMode) {
                PerceptionMode.MULTIMODAL ->
                    "截图直发模型视觉直读：能「看见」图标/布局/层级，无 OCR 几何启发式误判；" +
                        "需模型支持图片输入（deepseek-flash / 视觉 VL 模型）。"
                PerceptionMode.OCR ->
                    "端侧 PP-OCRv4 识别文字与坐标，识别结果替代截图发给模型（不上传图片、省流量/省钱，纯文本模型可用）；" +
                        "仅识别文字，图标/tab 等纯图形元素不可见。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionTitle("模型连接")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LlmSource.entries.forEach { source ->
                FilterChip(
                    selected = draft.llmSource == source,
                    onClick = { viewModel.updateDraft { it.copy(llmSource = source) } },
                    label = { Text(if (source == LlmSource.LOCAL) "本地 vLLM" else "在线 API") },
                )
            }
        }
        OutlinedTextField(
            value = draft.llmBaseUrl,
            onValueChange = { value -> viewModel.updateDraft { it.copy(llmBaseUrl = value) } },
            label = { Text("LLM 服务地址") },
            supportingText = {
                Text(
                    if (draft.llmSource == LlmSource.LOCAL) {
                        "本地推理服务地址（vLLM / Ollama 等，OpenAI 兼容端点）"
                    } else {
                        "在线端点地址（OpenAI 兼容，需填 API Key）"
                    }
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.llmModel,
            onValueChange = { value -> viewModel.updateDraft { it.copy(llmModel = value) } },
            label = { Text("模型名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ApiKeyField(
            key = draft.llmApiKey,
            onKeyChange = { value -> viewModel.updateDraft { it.copy(llmApiKey = value) } },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = viewModel::save,
                enabled = !state.loading && !state.saving,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.saving) "保存中…" else "保存")
            }
            OutlinedButton(
                onClick = viewModel::testConnection,
                enabled = !state.testing,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.testing) "测试中…" else "测试连接")
            }
        }
        if (state.testing) {
            CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
        }

        if (state.saved) {
            Text("已保存", color = MaterialTheme.colorScheme.primary)
        }
        when (val r = state.testResult) {
            is SettingsViewModel.TestResult.Ok ->
                Text("连接成功：${r.reply}", color = MaterialTheme.colorScheme.primary)
            is SettingsViewModel.TestResult.Failed ->
                Text("连接失败：${r.message}", color = MaterialTheme.colorScheme.error)
            null -> Unit
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

/**
 * 执行可视化权限引导：悬浮窗（灵动岛胶囊） + 通知（前台服务可见性兜底）。
 * 返回页面时（onResume）刷新状态。
 */
@Composable
private fun ExecutionPermissionsSection() {
    val context = LocalContext.current
    val hasOverlay = remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val hasNotification = remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlay.value = Settings.canDrawOverlays(context)
                hasNotification.value = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SectionTitle("执行可视化权限")
    PermissionRow(
        label = "悬浮窗（灵动岛胶囊）",
        granted = hasOverlay.value,
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                )
            }
        },
    )
    PermissionRow(
        label = "通知（执行状态/通知栏提示）",
        granted = hasNotification.value,
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
        },
    )
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = if (granted) "✅ $label" else "🚫 $label",
            style = MaterialTheme.typography.bodyMedium,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onClick) {
            Text(if (granted) "查看" else "去开启")
        }
    }
}

/**
 * 无障碍执行通道状态与启用入口（前台操作的核心通道，非 Root 用户必需）：
 * - 已启用：显示状态；
 * - 未启用：可跳转系统无障碍设置手动开启，或（root 设备）一键自动写 secure settings。
 * 页面回到前台时刷新状态。
 */
@Composable
private fun AccessibilitySection(
    enabled: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onAutoEnable: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { onRefresh() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onRefresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column {
        Text(
            text = if (enabled) "✅ 无障碍（Fall 自动化）已启用" else "🚫 无障碍（Fall 自动化）未启用",
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text(
            text = "前台操作（非 Root 模式）依赖无障碍服务。未启用时手机操作会失败：" +
                "可在系统设置中手动开启『Fall 自动化』，或使用下方按钮一键启用（需 Root）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (!enabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onAutoEnable() },
                    modifier = Modifier.weight(1f),
                ) { Text("一键启用（需 Root）") }
                Button(
                    onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("去系统设置开启") }
            }
        }
    }
}
/**
 * 后台守护进程（静默执行）状态与版本。
 *
 * 它是**独立于 App 安装包**的常驻进程（root/`app_process`），可能与 App 的构建不一致
 * （换设备、更新 App 后旧进程仍在跑）→ 这里显示探测结果并提供一键重启；任务开始前
 * `RootManager.ensureDaemon()` 还会自动自检并自愈，无需用户先手工重启手机。
 */
@Composable
private fun DaemonSection(
    status: RootManager.DaemonStatus?,
    probing: Boolean,
    rootModeEnabled: Boolean,
    message: String?,
    onRefresh: () -> Unit,
    onRestart: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { onRefresh() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onRefresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("后台守护进程（静默执行）", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = when {
                status == null -> if (probing) "探测中…" else "未探测"
                status.usable -> "✅ ${status.message}"
                status.running -> "⚠️ ${status.message}（点「重启」自动换成 App 内置构建）"
                else -> "🚫 ${status.message}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = when {
                status == null -> MaterialTheme.colorScheme.onSurfaceVariant
                status.usable -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.error
            },
        )
        status?.let {
            Text(
                "设备上：构建 ${it.buildTag ?: "未知（旧构建无版本自报）"} · 协议 ${it.protocol ?: "?"}" +
                    "　App 内置：${DaemonProtocol.BUILD_TAG} · 协议 ${DaemonProtocol.VERSION}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onRefresh, enabled = !probing) { Text("刷新") }
            Button(onClick = onRestart, enabled = rootModeEnabled && !probing) { Text("重启") }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ApiKeyField(
    key: String,
    onKeyChange: (String) -> Unit,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = key,
        onValueChange = onKeyChange,
        label = { Text("API Key（在线端点必填）") },
        singleLine = true,
        supportingText = { Text("本地部署可留空") },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "隐藏 Key" else "显示 Key",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun localAppContainer(): AppContainer {
    val app = LocalContext.current.applicationContext as FallApplication
    return app.container
}
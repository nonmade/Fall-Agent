package com.fall.assistant.ui.screen

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fall.assistant.core.AppContainer
import com.fall.assistant.core.DebugLog
import com.fall.assistant.core.RootManager
import com.fall.automation.service.AccessibilityExecutorService
import com.fall.core.agent.PerceptionMode
import com.fall.core.data.remote.LlmClient
import com.fall.core.data.repository.AppSettings
import com.fall.core.data.repository.PreviewQuality
import com.fall.core.data.repository.SettingsRepository
import com.fall.core.data.repository.toLlmConfig
import com.fall.core.model.llm.ChatMessage
import com.fall.core.model.llm.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val appContext: Context,
    private val repository: SettingsRepository,
    private val llmClient: LlmClient,
    private val rootManager: RootManager,
) : ViewModel() {

    data class UiState(
        val draft: AppSettings = AppSettings(),
        val loading: Boolean = true,
        val saving: Boolean = false,
        val saved: Boolean = false,
        val testing: Boolean = false,
        val testResult: TestResult? = null,
        val rootModeError: String? = null,
        /** 重启守护进程的结果提示。 */
        val daemonRestartMessage: String? = null,
        /** 守护进程状态与版本（探测所得；null = 尚未探测）。 */
        val daemonStatus: RootManager.DaemonStatus? = null,
        /** 正在探测守护进程。 */
        val daemonProbing: Boolean = false,
        /** 无障碍服务是否启用（前台链可用性）。 */
        val accessibilityEnabled: Boolean = false,
        /** 无障碍自动启用失败的提示。 */
        val accessibilityError: String? = null,
    )

    sealed interface TestResult {
        data class Ok(val reply: String) : TestResult
        data class Failed(val message: String) : TestResult
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val current = repository.settings.first()
            DebugLog.setEnabled(current.debugLoggingEnabled)
            _uiState.update { it.copy(draft = current, loading = false) }
            refreshAccessibility()
            refreshDaemonStatus()
        }
    }

    fun updateDraft(transform: (AppSettings) -> AppSettings) {
        _uiState.update { it.copy(draft = transform(it.draft), saved = false) }
    }

    /**
     * 日志模式开关：即时生效（不等「保存」按钮）。
     * 持久化到设置，并同步 [DebugLog] 运行态；关闭后日志系统零 IO。
     */
    fun setLoggingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val updated = _uiState.value.draft.copy(debugLoggingEnabled = enabled)
            repository.save(updated)
            DebugLog.setEnabled(enabled)
            _uiState.update { it.copy(draft = updated) }
        }
    }

    /**
     * 感知方式开关：即时生效（不等「保存」按钮），下次静默任务生效。
     * 多模态 = 截图直发模型（需模型支持视觉）；OCR = 端侧离线识别。
     */
    fun setPerceptionMode(mode: PerceptionMode) {
        viewModelScope.launch {
            val updated = _uiState.value.draft.copy(perceptionMode = mode)
            repository.save(updated)
            _uiState.update { it.copy(draft = updated) }
        }
    }

    /**
     * 多虚拟屏并行开关（实验，P3+，阶段 5）：即时生效，默认关。
     * 开启后多个任务走独立 VirtualDisplay 并行执行（资源敏感，建议最多 2 个）。
     */
    fun setParallelVdEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val updated = _uiState.value.draft.copy(parallelVdEnabled = enabled)
            repository.save(updated)
            _uiState.update { it.copy(draft = updated) }
        }
    }

    /**
     * 预览画质档位（豆包式纯虚拟屏 台阶 4）：即时生效，下次打开预览页生效。
     * 低=现状 JPEG 轮询（默认，零回归）；高=socket 原始帧（过渡）；实时=H.264 硬编（实验，需 Root）。
     */
    fun setPreviewQuality(quality: PreviewQuality) {
        viewModelScope.launch {
            val updated = _uiState.value.draft.copy(previewQuality = quality)
            repository.save(updated)
            _uiState.update { it.copy(draft = updated) }
        }
    }

    /**
     * root 模式开关：开启时先请求/校验 root 权限（Magisk 会弹授权框，给 3 次重试
     * 容忍首次弹窗超时），成功后 best-effort 拉起自动化守护进程；失败回滚开关并提示。
     */
    fun setRootModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(rootModeError = null) }
            if (!enabled) {
                val updated = _uiState.value.draft.copy(rootModeEnabled = false)
                repository.save(updated)
                _uiState.update { it.copy(draft = updated) }
                return@launch
            }
            // 探测 root：首次 Magisk 授权弹窗可能拖慢，重试 3 次 × 3s 兜底
            var granted = false
            repeat(ROOT_PROBE_RETRIES) {
                granted = withContext(Dispatchers.IO) { rootManager.isRootAvailable() }
                if (granted) return@repeat
                delay(ROOT_PROBE_RETRY_GAP_MS)
            }
            if (!granted) {
                _uiState.update {
                    it.copy(rootModeError = "未检测到 root 权限（如弹出授权请允许后重试）")
                }
                return@launch
            }
            val updated = _uiState.value.draft.copy(rootModeEnabled = true)
            repository.save(updated)
            _uiState.update { it.copy(draft = updated) }
            // best-effort 拉起守护进程（静默链可用的前提；apk 缺失会在执行时引导）
            withContext(Dispatchers.IO) { rootManager.ensureDaemon() }
            refreshDaemonStatus()
        }
    }

    /**
     * 强制重启守护进程：先 pkill，再用**最新可用 APK**（App 内置 shell APK 优先，
     * 见 `ShellApkBundle`）重新拉起 —— 用于"设备上还跑着旧构建 / token 不匹配"的场景，
     * 无需重启手机或手工 adb push。用户显式操作，不受自愈限流约束。
     */
    fun restartDaemon() {
        viewModelScope.launch {
            _uiState.update { it.copy(daemonRestartMessage = null, daemonProbing = true) }
            val status = withContext(Dispatchers.IO) {
                val ok = rootManager.restartDaemon()
                // 重启已等到就绪，这里再探一次拿最终状态与版本
                if (ok) rootManager.probeDaemon() else null
            }
            _uiState.update {
                it.copy(
                    daemonProbing = false,
                    daemonStatus = status ?: it.daemonStatus,
                    daemonRestartMessage = status?.let { s -> "守护进程已重启（${s.message}）" }
                        ?: rootManager.lastEnsureFailure()
                        ?: "重启失败：需要 root 权限（Magisk 授权后重试）",
                )
            }
        }
    }

    /** 探测守护进程状态与版本（设置页展示）：只读回环直连，不需要 root，不拉起/不重启。 */
    fun refreshDaemonStatus() {
        viewModelScope.launch {
            _uiState.update { it.copy(daemonProbing = true) }
            val status = withContext(Dispatchers.IO) { rootManager.probeDaemon() }
            _uiState.update { it.copy(daemonProbing = false, daemonStatus = status) }
        }
    }

    /** 检测无障碍服务是否启用（返回设置页/执行后调用）。 */
    fun refreshAccessibility() {
        _uiState.update {
            it.copy(accessibilityEnabled = AccessibilityExecutorService.isEnabled(appContext))
        }
    }

    /**
     * 自动启用无障碍：root 可用时经 su 写 secure settings（免去系统设置手动开）；
     * 失败给出提示并引导手动开启。
     */
    fun autoEnableAccessibility() {
        viewModelScope.launch {
            _uiState.update { it.copy(accessibilityError = null) }
            val ok = withContext(Dispatchers.IO) {
                rootManager.enableAccessibility(AccessibilityExecutorService.componentName(appContext))
            }
            if (ok) {
                delay(800) // 等待系统注册服务
                refreshAccessibility()
                if (!_uiState.value.accessibilityEnabled) {
                    _uiState.update { it.copy(accessibilityError = "已写入设置但系统尚未生效，请到系统设置→无障碍确认『Fall 自动化』已开启") }
                }
            } else {
                _uiState.update {
                    it.copy(accessibilityError = "自动启用失败（需要 root 权限）：请到系统设置→无障碍手动开启『Fall 自动化』")
                }
            }
        }
    }

    fun save() {
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, saved = false) }
            repository.save(_uiState.value.draft)
            _uiState.update { it.copy(saving = false, saved = true) }
        }
    }

    /** 用当前草稿发起一次最小流式请求验证连通性。 */
    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(testing = true, testResult = null) }
            val result = try {
                val reply = StringBuilder()
                var failure: Throwable? = null
                llmClient.chat(
                    config = _uiState.value.draft.toLlmConfig(),
                    messages = listOf(ChatMessage.user("请只回复两个字符：OK")),
                    listener = object : LlmClient.Listener {
                        override fun onContent(delta: String) {
                            reply.append(delta)
                        }

                        override fun onToolCall(toolCall: ToolCall) = Unit

                        override fun onDone() = Unit

                        override fun onError(cause: Throwable) {
                            failure = cause
                        }
                    },
                )
                failure?.let { throw it }
                val replyText = reply.toString().trim().take(120)
                if (replyText.isEmpty()) {
                    Log.w(TAG, "test ok but empty content（模型可能处于 Thinking 模式）")
                    TestResult.Ok("连接成功（模型无文本输出，可能处于思考模式；网络与鉴权正常）")
                } else {
                    Log.i(TAG, "test ok: $replyText")
                    TestResult.Ok(replyText)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "test failed: ${e.message ?: e.javaClass.simpleName}", e)
                TestResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            _uiState.update { it.copy(testing = false, testResult = result) }
        }
    }

    private companion object {
        const val TAG = "FallSettings"

        /** root 探测重试次数与间隔（容忍 Magisk 首次授权弹窗慢）。 */
        const val ROOT_PROBE_RETRIES = 3
        const val ROOT_PROBE_RETRY_GAP_MS = 3_000L
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(
                appContext = container.appContext,
                repository = container.settingsRepository,
                llmClient = container.llmClient,
                rootManager = container.rootManager,
            ) as T
    }
}
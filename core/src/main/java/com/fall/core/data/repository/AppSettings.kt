package com.fall.core.data.repository

import com.fall.core.agent.PerceptionMode
import com.fall.core.model.llm.LlmConfig
import com.fall.core.model.llm.LlmSource

/** 预览画质档位（豆包式纯虚拟屏 台阶 4）：默认 [LOW] 保持现状轮询零回归。 */
enum class PreviewQuality {
    /** 现状：captureFrame JPEG base64 轮询（3–8fps）。 */
    LOW,

    /** 台阶 2：socket 原始帧通道（JPEG 直发，~10–15fps）。 */
    HIGH,

    /** 台阶 3：H.264 硬编推流 + TextureView 解码（30–60fps）。 */
    REALTIME,
}

/**
 * 应用设置（持久化于 Preferences DataStore）。
 *
 * - [llmSource / llmBaseUrl / llmApiKey / llmModel]：大模型连接（本地 vLLM 或在线 OpenAI 兼容端点）
 * - [debugLoggingEnabled]：日志模式开关。开发期默认开启（方便调试，日志轮转有界）；
 *   正式发布前应把 [DEFAULT_DEBUG_LOGGING_ENABLED] 改为 false（关闭后日志系统零 IO、零落盘）。
 *
 * TODO(P1)：[llmApiKey] 迁移到 Keystore 加密存储（安全基线 4.8）。
 */
data class AppSettings(
    val llmSource: LlmSource = LlmSource.LOCAL,
    val llmBaseUrl: String = DEFAULT_LLM_BASE_URL,
    val llmApiKey: String = "",
    val llmModel: String = DEFAULT_LLM_MODEL,
    val debugLoggingEnabled: Boolean = DEFAULT_DEBUG_LOGGING_ENABLED,
    /** root 模式开关：开启（且设备实际授予 root）后后台静默链（VirtualDisplay）可用。 */
    val rootModeEnabled: Boolean = DEFAULT_ROOT_MODE_ENABLED,
    /** 静默链 UI 感知方式：多模态截图直读（默认）/ 端侧 OCR。 */
    val perceptionMode: PerceptionMode = DEFAULT_PERCEPTION_MODE,
    /** 多虚拟屏并行执行（实验，P3+，默认关）：多 VD 多 Agent 隔离并行，吃 CPU/GPU/显存。 */
    val parallelVdEnabled: Boolean = DEFAULT_PARALLEL_VD_ENABLED,
    /** 预览画质：低（现状轮询）/ 高（socket JPEG）/ 实时（H.264 硬编，实验）。默认低零回归。 */
    val previewQuality: PreviewQuality = DEFAULT_PREVIEW_QUALITY,
) {
    companion object {
        /** 端点地址与模型名**不预填**（本地/在线都由用户自己填写），避免把示例值当真实配置发请求。 */
        const val DEFAULT_LLM_MODEL = ""
        const val DEFAULT_LLM_BASE_URL = ""

        /** 开发期默认开；正式发布前改为 false（关闭即零 IO/零存储）。 */
        const val DEFAULT_DEBUG_LOGGING_ENABLED = true

        /** root 模式默认关闭（需用户在设置页开启并授权）。 */
        const val DEFAULT_ROOT_MODE_ENABLED = false

        /** 多 VD 并行默认关闭（P3+ 实验，资源敏感）。 */
        const val DEFAULT_PARALLEL_VD_ENABLED = false

        /** 预览画质默认低（现状 JPEG 轮询，零回归）。 */
        val DEFAULT_PREVIEW_QUALITY = PreviewQuality.LOW

        /** 默认多模态（截图直读模型，视觉能力由模型提供）。 */
        val DEFAULT_PERCEPTION_MODE = PerceptionMode.MULTIMODAL
    }
}

/** 规整：trim + 去尾斜杠（**不做任何"空值回落默认"** —— 端点/模型没填就保持为空，由用户补齐）。 */
fun AppSettings.normalized(): AppSettings = copy(
    llmBaseUrl = llmBaseUrl.trim().trimEnd('/'),
    llmApiKey = llmApiKey.trim(),
    llmModel = llmModel.trim(),
)

/** 组装为可直接发起请求的 [LlmConfig]。 */
fun AppSettings.toLlmConfig(): LlmConfig {
    val n = normalized()
    return LlmConfig(
        source = n.llmSource,
        baseUrl = n.llmBaseUrl,
        apiKey = n.llmApiKey.ifBlank { null },
        model = n.llmModel,
    )
}
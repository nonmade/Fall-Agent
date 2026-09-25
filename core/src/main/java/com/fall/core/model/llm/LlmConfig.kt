package com.fall.core.model.llm

/**
 * 模型来源。
 * - LOCAL：本地部署推理服务（vLLM / Ollama / llama.cpp），走局域网，通常无需 apiKey。
 * - ONLINE：任意 OpenAI 兼容在线端点（Qwen 官方 / DeepSeek / OpenRouter 等），需要 apiKey。
 *
 * 两者协议完全相同（chat/completions + SSE + tools），区别仅在于端点与鉴权。
 */
enum class LlmSource { LOCAL, ONLINE }

/**
 * LLM 连接配置。
 *
 * @param source  模型来源（LOCAL / ONLINE）
 * @param baseUrl 服务根地址，不含路径，如 `http://192.168.1.100:8000` 或 `https://dashscope.aliyuncs.com/compatible-mode/v1`
 * @param apiKey  Bearer Token；LOCAL 场景可空
 * @param model   模型名，如 `Qwen3.8-27B` / 在线模型 ID
 */
data class LlmConfig(
    val source: LlmSource,
    val baseUrl: String,
    val apiKey: String? = null,
    val model: String,
) {
    companion object {
        /** 本地 vLLM 默认端点与模型（按实际部署调整）。 */
        fun local(baseUrl: String = "http://192.168.1.100:8000", model: String = "Qwen3.8-27B") =
            LlmConfig(LlmSource.LOCAL, baseUrl, apiKey = null, model = model)

        /** 在线端点示例：阿里云百炼（DashScope）OpenAI 兼容模式。 */
        fun online(baseUrl: String, apiKey: String, model: String) =
            LlmConfig(LlmSource.ONLINE, baseUrl, apiKey = apiKey, model = model)
    }
}
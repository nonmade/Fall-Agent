package com.fall.core.agent

/**
 * 工具观察结果中的图片承载协议。
 *
 * 工具执行结果仍是 [String]（[ToolExecutor.execute] 契约不变），但静默链在多模态感知时
 * 需要在观察里携带截图。约定：执行器把图片以 `data:` URL 包进标记对里，Agent 循环解包后：
 * - 文本部分（剔除标记与 base64）作为 tool 结果回填模型上下文；
 * - 图片部分以单独 USER 消息（[com.fall.core.model.llm.ChatMessage.userWithImages]）注入，
 *   使模型下一轮能直接"看见"屏幕（DeepSeek 规范：图片仅允许出现在 USER 消息）。
 */
object ToolObservations {

    const val IMAGE_BEGIN = "[[OBS-IMAGE:BEGIN]]"
    const val IMAGE_END = "[[OBS-IMAGE:END]]"

    /** 工具观察（解码后）。[images] 为 data URL / http(s) URL 列表，可能与 [text] 并存。 */
    data class Observation(val text: String, val images: List<String>) {
        /** 纯文本观察（无图片），与历史行为完全一致。 */
        val isImageAware: Boolean get() = images.isNotEmpty()
    }

    /** 编码：文本 + 单张图片 data URL → 带标记的原始字符串。 */
    fun encode(text: String, imageDataUrl: String): String =
        IMAGE_BEGIN + imageDataUrl + IMAGE_END + text

    /**
     * 解码：提取标记对中的图片，其余内容（含标记外文本）拼接为提示文本。
     * 非法标记/无标记时原样返回（兼容既有纯文本观察）。
     */
    fun decode(raw: String): Observation {
        if (raw.isEmpty()) return Observation(raw, emptyList())
        val images = mutableListOf<String>()
        val text = StringBuilder()
        var cursor = 0
        while (true) {
            val start = raw.indexOf(IMAGE_BEGIN, cursor)
            if (start < 0) {
                text.append(raw.substring(cursor))
                break
            }
            text.append(raw.substring(cursor, start))
            val end = raw.indexOf(IMAGE_END, start + IMAGE_BEGIN.length)
            if (end < 0) {
                // 有开始无结束：按脏数据整体并入文本（不丢信息）
                text.append(raw.substring(start))
                break
            }
            val url = raw.substring(start + IMAGE_BEGIN.length, end).trim()
            if (url.isNotEmpty()) images += url
            cursor = end + IMAGE_END.length
        }
        return Observation(text.toString(), images)
    }
}
package com.fall.core.model.llm

import com.google.gson.JsonObject

/**
 * 暴露给模型的工具声明（OpenAI tools 数组项）。
 *
 * [parameters] 为 JSON Schema 对象，例如：
 * ```json
 * {"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"}},"required":["x","y"]}
 * ```
 */
data class ChatToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
) {
    companion object {
        /** 便捷构造：parameterSchema 直接传原始 schema JSON 字符串。 */
        fun of(name: String, description: String, parameterSchemaJson: String): ChatToolSpec {
            val schema = com.google.gson.JsonParser.parseString(parameterSchemaJson)
            return ChatToolSpec(name, description, if (schema.isJsonObject) schema.asJsonObject else JsonObject())
        }
    }
}
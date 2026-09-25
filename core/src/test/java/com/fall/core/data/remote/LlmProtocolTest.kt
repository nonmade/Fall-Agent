package com.fall.core.data.remote

import com.fall.core.model.llm.ChatMessage
import com.fall.core.model.llm.ChatToolSpec
import com.fall.core.model.llm.LlmConfig
import com.fall.core.model.llm.LlmSource
import com.fall.core.model.llm.ToolCall
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmProtocolTest {

    private val gson = Gson()

    @Test
    fun `解析流式正文增量`() {
        val delta = LlmProtocol.parseSseData("""{"choices":[{"delta":{"content":"你好"}}]}""", gson)!!
        assertEquals("你好", delta.content)
        assertTrue(delta.toolCallChunks.isEmpty())
    }

    @Test
    fun `解析流式 tool_calls 分片并保持 index`() {
        val chunk1 = LlmProtocol.parseSseData(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"tap_target","arguments":"{\"targetIndex\":3}"}}]}}]}""",
            gson,
        )!!.toolCallChunks
        assertEquals(1, chunk1.size)
        assertEquals(0, chunk1[0].index)
        assertEquals("call_1", chunk1[0].id)
        assertEquals("tap_target", chunk1[0].name)
        assertEquals("{\"targetIndex\":3}", chunk1[0].arguments)
    }

    @Test
    fun `聚合多段 arguments 碎片为一个 ToolCall`() {
        val parts = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"tap_target"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"targetIndex\":"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"3}"}}]}}]}""",
        )
        val builder = StringBuilder()
        var name: String? = null
        parts.forEach { line ->
            val d = LlmProtocol.parseSseData(line, gson)!!
            d.toolCallChunks.forEach { c ->
                name = c.name ?: name
                c.arguments?.let { builder.append(it) }
            }
        }
        assertEquals("tap_target", name)
        val args = LlmProtocol.parseArguments(builder.toString(), gson)
        assertEquals(3, args.get("targetIndex").asInt)
    }

    @Test
    fun `解析流式 reasoning_content 思考字段`() {
        val delta = LlmProtocol.parseSseData(
            """{"choices":[{"delta":{"reasoning_content":"我先分析一下界面结构"}}]}""",
            gson,
        )!!
        assertEquals("我先分析一下界面结构", delta.reasoning)
        assertNull(delta.content)
        assertTrue(delta.toolCallChunks.isEmpty())
    }

    @Test
    fun `思考字段作为 reasoning_content 原样回传`() {
        val messages = listOf(
            ChatMessage.assistant(
                text = "调用工具",
                toolCalls = listOf(ToolCall("call_1", "tap", gson.toJsonTree(mapOf("x" to 1, "y" to 2)).asJsonObject)),
                thinking = "秘密思考过程",
            ),
        )
        val json = gson.fromJson(
            LlmProtocol.buildRequestJson(LlmConfig.online("http://x/v1", "sk", "qwen-max"), messages, emptyList(), gson),
            com.google.gson.JsonObject::class.java,
        )
        val assistant = json.getAsJsonArray("messages")[0].asJsonObject
        // DeepSeek V4 思考模式：assistant 的 reasoning_content 必须原样回传，否则 400
        assertEquals("秘密思考过程", assistant.get("reasoning_content")?.asString)
    }

    @Test
    fun `忽略无意义的 SSE data`() {
        assertNull(LlmProtocol.parseSseData("", gson))
        assertNull(LlmProtocol.parseSseData("not-json", gson))
        LlmProtocol.parseSseData("""{"choices":[]}""", gson)?.let { d ->
            assertNull(d.content)
            assertTrue(d.toolCallChunks.isEmpty())
        }
    }

    @Test
    fun `请求体包含本地端点默认流式与 tools`() {
        val config = LlmConfig.local(baseUrl = "http://127.0.0.1:8000", model = "Qwen3.8-27B")
        val messages = listOf(
            ChatMessage.system("你是一个手机助手"),
            ChatMessage.user("打开设置"),
        )
        val tools = listOf(
            ChatToolSpec.of("tap_target", "点击界面元素", """{"type":"object","properties":{"targetIndex":{"type":"integer"}},"required":["targetIndex"]}"""),
        )
        val json = gson.fromJson(LlmProtocol.buildRequestJson(config, messages, tools, gson), com.google.gson.JsonObject::class.java)
        assertEquals("Qwen3.8-27B", json.get("model").asString)
        assertEquals(true, json.get("stream").asBoolean)
        assertEquals("auto", json.get("tool_choice").asString)
        assertEquals(2, json.getAsJsonArray("messages").size())
        assertEquals("system", json.getAsJsonArray("messages")[0].asJsonObject.get("role").asString)
        assertEquals(1, json.getAsJsonArray("tools").size())
        assertEquals("function", json.getAsJsonArray("tools")[0].asJsonObject.get("type").asString)
    }

    @Test
    fun `assistant tool_calls 与 TOOL 结果消息序列化`() {
        val messages = listOf(
            ChatMessage.assistant(
                text = null,
                toolCalls = listOf(ToolCall("call_1", "tap_target", gson.toJsonTree(mapOf("targetIndex" to 3)).asJsonObject)),
            ),
            ChatMessage.toolResult("call_1", "ok"),
        )
        val json = gson.fromJson(
            LlmProtocol.buildRequestJson(LlmConfig.online("http://x/v1", "sk-test", "qwen-max"), messages, emptyList(), gson),
            com.google.gson.JsonObject::class.java,
        )
        val msgs = json.getAsJsonArray("messages")
        assertEquals(2, msgs.size())
        val assistant = msgs[0].asJsonObject
        assertEquals("assistant", assistant.get("role").asString)
        assertTrue(assistant.has("tool_calls"))
        val fn = assistant.getAsJsonArray("tool_calls")[0].asJsonObject.getAsJsonObject("function")
        assertEquals("call_1", assistant.getAsJsonArray("tool_calls")[0].asJsonObject.get("id").asString)
        // 规范：arguments 必须是 JSON 字符串（DeepSeek 对"对象"直接 400）
        assertTrue(fn.get("arguments").isJsonPrimitive && fn.get("arguments").asJsonPrimitive.isString)
        assertEquals("""{"targetIndex":3}""", fn.get("arguments").asString)
        val result = msgs[1].asJsonObject
        assertEquals("tool", result.get("role").asString)
        assertEquals("call_1", result.get("tool_call_id").asString)
        // 无 tools 时不得发送裸 tool_choice（部分服务端会 400）
        assertTrue(!json.has("tool_choice"))
    }

    @Test
    fun `含图片的 USER 消息序列化为 content 块数组多模态格式`() {
        val messages = listOf(
            ChatMessage.system("你是手机助手"),
            ChatMessage.userWithImages(
                text = "（截图）",
                images = listOf("data:image/jpeg;base64,AAAA"),
            ),
            ChatMessage.user("无图消息"),
        )
        val json = gson.fromJson(
            LlmProtocol.buildRequestJson(LlmConfig.online("http://x/v1", "sk-test", "deepseek-flash"), messages, emptyList(), gson),
            com.google.gson.JsonObject::class.java,
        )
        val msgs = json.getAsJsonArray("messages")
        // 带图消息：content 必须是块数组（text + image_url + detail=original 保留原图坐标）
        val multimodal = msgs[1].asJsonObject
        assertTrue(multimodal.get("content").isJsonArray)
        val blocks = multimodal.getAsJsonArray("content")
        assertEquals(2, blocks.size())
        assertEquals("text", blocks[0].asJsonObject.get("type").asString)
        assertEquals("（截图）", blocks[0].asJsonObject.get("text").asString)
        assertEquals("image_url", blocks[1].asJsonObject.get("type").asString)
        val imageUrl = blocks[1].asJsonObject.getAsJsonObject("image_url")
        assertEquals("data:image/jpeg;base64,AAAA", imageUrl.get("url").asString)
        assertEquals("original", imageUrl.get("detail").asString)
        // 无图消息：保持字符串 content（兼容既有纯文本端点）
        assertTrue(msgs[2].asJsonObject.get("content").isJsonPrimitive)
        assertEquals("无图消息", msgs[2].asJsonObject.get("content").asString)
    }
}
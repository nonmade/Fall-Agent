package com.fall.core.agent

import com.fall.core.data.remote.LlmClient
import com.fall.core.model.llm.ChatMessage
import com.fall.core.model.llm.ChatRole
import com.fall.core.model.llm.ChatToolSpec
import com.fall.core.model.llm.LlmConfig
import com.fall.core.model.llm.LlmSource
import com.fall.core.model.llm.ToolCall
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {

    private fun config() = LlmConfig(LlmSource.LOCAL, "http://127.0.0.1:8000", null, "model-x")

    private fun openAppSpec() = ChatToolSpec.of(
        name = "open_app",
        description = "打开应用",
        parameterSchemaJson = """{"type":"object","properties":{"packageName":{"type":"string"}},"required":["packageName"]}""",
    )

    private fun jsonObj(raw: String): JsonObject = JsonParser.parseString(raw).asJsonObject

    /** 每次 chat 调用时触发 responder（顺序可跨轮次断言）。 */
    private class FakeLlmClient(
        private val onMessages: (List<ChatMessage>) -> Unit = {},
        private val responder: (LlmClient.Listener) -> Unit,
    ) : LlmClient {
        override suspend fun chat(
            config: LlmConfig,
            messages: List<ChatMessage>,
            tools: List<ChatToolSpec>,
            listener: LlmClient.Listener,
            trace: (String) -> Unit,
        ) {
            onMessages(messages)
            responder(listener)
            listener.onDone()
        }
    }

    private fun executor(): ToolExecutor = object : ToolExecutor {
        override val supportedTools: Set<String> = setOf("open_app")
        override val schema: List<ChatToolSpec> = listOf(openAppSpec())
        override suspend fun execute(name: String, args: JsonObject): String = "已打开 ${args.get("packageName")?.asString}"
    }

    @Test
    fun `工具调用后第二轮文本总结`() = runTest {
        var round = 0
        val client = FakeLlmClient { listener ->
            round++
            when (round) {
                1 -> listener.onToolCall(ToolCall("c1", "open_app", jsonObj("""{"packageName":"com.android.settings"}""")))
                else -> listener.onContent("已打开 设置")
            }
        }
        val loop = AgentLoop(client, listOf(openAppSpec()), ToolRegistry(listOf(executor())), maxSteps = 3)
        val out = loop.run(config(), "hint", "帮我打开设置", emptyList())

        assertNull(out.error)
        assertEquals("已打开 设置", out.text)
        assertEquals(1, out.toolCalls.size)
        assertEquals(2, round)
    }

    @Test
    fun `工具已执行但总结环节失败时不丢结果`() = runTest {
        var round = 0
        val client = FakeLlmClient { listener ->
            round++
            if (round == 1) {
                listener.onToolCall(ToolCall("c1", "open_app", jsonObj("""{"packageName":"com.android.settings"}""")))
            } else {
                listener.onError(RuntimeException("second round boom"))
            }
        }
        val loop = AgentLoop(client, listOf(openAppSpec()), ToolRegistry(listOf(executor())), maxSteps = 3)
        val out = loop.run(config(), "hint", "goal", emptyList())

        assertNull(out.error) // 工具已成功，屏蔽总结环节异常
        assertEquals(1, out.toolCalls.size)
    }

    @Test
    fun `模型直接文本作答不循环`() = runTest {
        var calls = 0
        val client = FakeLlmClient { listener ->
            calls++
            listener.onContent("你好")
        }
        val loop = AgentLoop(client, listOf(openAppSpec()), ToolRegistry(listOf(executor())), maxSteps = 3)
        val out = loop.run(config(), "hint", "你好", emptyList())

        assertEquals(1, calls)
        assertEquals("你好", out.text)
        assertTrue(out.toolCalls.isEmpty())
        assertNull(out.error)
    }

    @Test
    fun `无工具时首轮异常直接透传`() = runTest {
        val client = FakeLlmClient { listener ->
            listener.onError(RuntimeException("network down"))
        }
        val loop = AgentLoop(client, listOf(openAppSpec()), ToolRegistry(listOf(executor())), maxSteps = 3)
        val out = loop.run(config(), "hint", "goal", emptyList())

        assertNotNull(out.error)
        assertTrue(out.toolCalls.isEmpty())
    }

    @Test
    fun `回合消息收集思考与工具结果`() = runTest {
        val client = FakeLlmClient { listener ->
            listener.onThinking("先想想怎么打开")
            listener.onToolCall(ToolCall("c1", "open_app", jsonObj("""{"packageName":"com.android.settings"}""")))
        }
        val loop = AgentLoop(client, listOf(openAppSpec()), ToolRegistry(listOf(executor())), maxSteps = 3)
        val out = loop.run(config(), "hint", "goal", emptyList())

        assertTrue(out.turnMessages.isNotEmpty())
        val assistant = out.turnMessages.first { it.role == ChatRole.ASSISTANT }
        assertEquals("先想想怎么打开", assistant.thinking)
        assertEquals(1, assistant.toolCalls?.size)
        val toolResult = out.turnMessages.first { it.role == ChatRole.TOOL }
        assertEquals("c1", toolResult.toolCallId)
        assertTrue(toolResult.content?.contains("已打开") == true)
    }

    @Test
    fun `onThinking 回调透传增量思考`() = runTest {
        val collected = mutableListOf<String>()
        val client = FakeLlmClient { listener ->
            listener.onThinking("第1段")
            listener.onThinking("第2段")
        }
        val loop = AgentLoop(client, listOf(openAppSpec()), ToolRegistry(listOf(executor())), maxSteps = 3)
        loop.run(config(), "hint", "goal", emptyList(), onThinking = { collected += it })

        // 回调语义为增量（避免每 token 全量拷贝成 O(n²)），累积由调用方按刷新节奏做
        assertEquals(listOf("第1段", "第2段"), collected)
    }

    @Test
    fun `纯文本总结作为回合消息返回`() = runTest {
        val client = FakeLlmClient { listener ->
            listener.onContent("好的，已完成")
        }
        val loop = AgentLoop(client, listOf(openAppSpec()), ToolRegistry(listOf(executor())), maxSteps = 3)
        val out = loop.run(config(), "hint", "goal", emptyList())

        assertEquals(1, out.turnMessages.size)
        assertEquals("好的，已完成", out.turnMessages.first().content)
    }

    /**
     * [已停用] 连续相同布局观察时附加循环警告（2026-09-19）
     * 原因：与 AgentLoop 同步停用——布局指纹/循环警告已移除（不替 AI 判断是否陷入循环，决策归 AI）。
     * 若恢复 annotateObservation，可一并恢复本用例。
     */
    // @Test
    // fun `连续相同布局观察时附加循环警告`() = runTest {
    //     var round = 0
    //     val layout = "[0] 首页 (10,20 100x50) [可点击]"
    //     val seenRequests = mutableListOf<List<ChatMessage>>()
    //     val client = FakeLlmClient(
    //         responder = { listener ->
    //             round++
    //             when {
    //                 round <= 3 -> listener.onToolCall(ToolCall("c$round", "get_ui_layout"))
    //                 else -> listener.onContent("结束")
    //             }
    //         },
    //         onMessages = { seenRequests += it },
    //     )
    //     val layoutSpec = ChatToolSpec.of(
    //         name = "get_ui_layout",
    //         description = "布局",
    //         parameterSchemaJson = """{"type":"object","properties":{}}""",
    //     )
    //     val layoutExecutor = object : ToolExecutor {
    //         override val supportedTools: Set<String> = setOf("get_ui_layout")
    //         override val schema: List<ChatToolSpec> = listOf(layoutSpec)
    //         override suspend fun execute(name: String, args: JsonObject): String = layout
    //     }
    //     val loop = AgentLoop(
    //         client,
    //         listOf(layoutSpec),
    //         ToolRegistry(listOf(layoutExecutor)),
    //         maxSteps = 5,
    //     )
    //     loop.run(config(), "hint", "goal", emptyList())
    //
    //     // 第 4 轮请求体的历史中，最后一条 get_ui_layout 工具结果应已附加循环警告
    //     val lastTool = seenRequests.last().last { it.role == ChatRole.TOOL }
    //     assertTrue(lastTool.content?.contains("⚠") == true)
    //     assertTrue(lastTool.content?.contains("疑似陷入循环") == true)
    // }

    @Test
    fun `多模态截图观察解码为 USER 图片消息且不进 tool 文本`() = runTest {
        val fakeJpeg = "data:image/jpeg;base64,QWJjZA==" // 模拟 VD 截帧 data URL
        val seenRequests = mutableListOf<List<ChatMessage>>()
        var round = 0
        val client = FakeLlmClient(
            responder = { listener ->
                round++
                if (round <= 2) listener.onToolCall(ToolCall("c$round", "get_ui_layout"))
                else listener.onContent("结束")
            },
            onMessages = { seenRequests += it },
        )
        val layoutSpec = ChatToolSpec.of(
            name = "get_ui_layout",
            description = "布局",
            parameterSchemaJson = """{"type":"object","properties":{}}""",
        )
        val multimodalExecutor = object : ToolExecutor {
            override val supportedTools: Set<String> = setOf("get_ui_layout")
            override val schema: List<ChatToolSpec> = listOf(layoutSpec)
            override suspend fun execute(name: String, args: JsonObject): String =
                ToolObservations.encode("（截图已提供） 截图尺寸 720x1280", fakeJpeg)
        }
        val loop = AgentLoop(
            client,
            listOf(layoutSpec),
            ToolRegistry(listOf(multimodalExecutor)),
            maxSteps = 3,
        )
        val out = loop.run(config(), "hint", "goal", emptyList())

        // 下一轮请求体中：tool 结果不含 base64，且其后追加了带图 USER 消息
        val secondTurn = seenRequests.last()
        val tool = secondTurn.last { it.role == ChatRole.TOOL }
        assertTrue(tool.content?.contains("QWJjZA==") == false)
        assertTrue(tool.content?.contains("截图尺寸 720x1280") == true)
        val imageMsg = secondTurn.last { it.role == ChatRole.USER && it.images != null }
        assertEquals(listOf(fakeJpeg), imageMsg.images)

        // 落库回合：不再含截图占位 user 消息（截图仅进模型上下文，不落库展示，
        // 避免 UI 把同一任务的操作步骤拆成多张卡片）
        assertTrue(out.turnMessages.none { it.role == ChatRole.USER && it.content?.contains("截图") == true })
    }

    @Test
    fun `每轮请求末尾注入剩余步数预算提示且不污染落库回合`() = runTest {
        var round = 0
        val seenRequests = mutableListOf<List<ChatMessage>>()
        val client = FakeLlmClient(
            onMessages = { seenRequests += it },
            responder = { listener ->
                round++
                listener.onToolCall(ToolCall("c$round", "open_app", jsonObj("""{"packageName":"a.b"}""")))
            },
        )
        val loop = AgentLoop(client, listOf(openAppSpec()), ToolRegistry(listOf(executor())), maxSteps = 2)
        val out = loop.run(config(), "hint", "goal", emptyList())

        assertTrue(seenRequests.isNotEmpty())
        // 每一轮请求体末尾都是预算 USER 消息（含"执行预算"）
        seenRequests.forEach { msgs ->
            val last = msgs.last()
            assertEquals(ChatRole.USER, last.role)
            assertTrue(last.content?.contains("执行预算") == true)
        }
        // 预算提示仅注入单次请求：不落库（turnMessages 无预算内容）
        assertTrue(out.turnMessages.none { it.content?.contains("执行预算") == true })
    }

    @Test
    fun `步数耗尽后追加仅总结轮并落库收尾总结`() = runTest {
        var round = 0
        val toolsSeen = mutableListOf<List<ChatToolSpec>>()
        val client = object : LlmClient {
            override suspend fun chat(
                config: LlmConfig,
                messages: List<ChatMessage>,
                tools: List<ChatToolSpec>,
                listener: LlmClient.Listener,
                trace: (String) -> Unit,
            ) {
                round++
                toolsSeen += tools
                if (round <= 2) {
                    listener.onToolCall(ToolCall("c$round", "open_app", jsonObj("""{"packageName":"a.b"}""")))
                } else {
                    listener.onContent("步数已用尽：应用已打开，但点赞操作未执行")
                }
                listener.onDone()
            }
        }
        val loop = AgentLoop(client, listOf(openAppSpec()), ToolRegistry(listOf(executor())), maxSteps = 2)
        val out = loop.run(config(), "hint", "goal", emptyList())

        assertEquals(3, round) // 2 轮工具 + 1 轮"仅总结"收尾
        assertTrue(toolsSeen.last().isEmpty()) // 收尾轮不再提供工具，模型只能文本收尾
        assertEquals("步数已用尽：应用已打开，但点赞操作未执行", out.text)
        assertEquals(2, out.toolCalls.size)
        assertTrue(out.turnMessages.any { it.role == ChatRole.ASSISTANT && it.content?.contains("步数已用尽") == true })
    }
}
package com.fall.core.agent

import com.fall.core.model.llm.ChatToolSpec

/**
 * 手机自动化工具集（P0.5 完整版）。
 *
 * 命名约定：target 索引一律来自 `get_ui_layout` 输出的 index（0 起），坐标均为屏幕坐标。
 * 通道无关：Accessibility（前台）/ Shell+VirtualDisplay（后台静默）共享同一套 schema。
 */
object AgentTools {

    const val NAME_OPEN_APP = "open_app"
    const val NAME_GET_UI_LAYOUT = "get_ui_layout"
    const val NAME_TAP_TARGET = "tap_target"
    const val NAME_TAP = "tap"
    const val NAME_INPUT_TEXT = "input_text"
    const val NAME_SCROLL = "scroll"
    const val NAME_PRESS_BACK = "press_back"
    const val NAME_PRESS_HOME = "press_home"
    const val NAME_PRESS_ENTER = "press_enter"
    const val NAME_RECORD_NOTE = "record_note"
    const val NAME_WAIT = "wait"
    const val NAME_FINISH = "finish"

    // ------ 工具声明（预构建常量，避免每次调用重复解析 schema JSON） ------

    /** 打开指定应用（按包名；包名不可解析时按名称模糊匹配已装应用）。 */
    private val OPEN_APP = ChatToolSpec.of(
        name = NAME_OPEN_APP,
        description = "打开手机上的一个应用（Android App）。packageName 必须是英文包名（如 tv.danmaku.bili）；中文应用名请填到 appLabel（如\"哔哩哔哩\"）。注意：本工具只支持应用名/包名，不支持 URL、网页链接或深链接（如 bilibili://search...）——它们一定会查找失败。",
        parameterSchemaJson = """
            {
              "type": "object",
              "properties": {
                "packageName": {"type": "string", "description": "英文包名（可选，如 tv.danmaku.bili）"},
                "appLabel": {"type": "string", "description": "中文应用名（可选，如 哔哩哔哩）"}
              },
              "required": []
            }
        """.trimIndent(),
    )

    /** 获取当前 UI 布局（可操作目标列表，含 index/文本/坐标/可点击/可输入）。 */
    private val GET_UI_LAYOUT = ChatToolSpec.of(
        name = NAME_GET_UI_LAYOUT,
        description = "获取当前屏幕的 UI 元素列表（index、文本、坐标、是否可点击/可输入）。开始操作前与每次点击/滚动后都应调用，以掌握当前界面。",
        parameterSchemaJson = """{"type":"object","properties":{}}""",
    )

    /** 点击布局列表中指定 index 的元素（优先使用）。 */
    private val TAP_TARGET = ChatToolSpec.of(
        name = NAME_TAP_TARGET,
        description = "点击 get_ui_layout 返回的某个元素（按 index）。这是最常用的点击方式，优先于 tap 坐标。",
        parameterSchemaJson = """
            {
              "type": "object",
              "properties": {
                "index": {"type": "integer", "description": "get_ui_layout 返回的 target/input 索引"},
                "reason": {"type": "string", "description": "为什么点它（可选）"}
              },
              "required": ["index"]
            }
        """.trimIndent(),
    )

    /** 按屏幕坐标点击（布局无法覆盖时的兜底）。 */
    private val TAP = ChatToolSpec.of(
        name = NAME_TAP,
        description = "按屏幕坐标点击（仅当 get_ui_layout 未能给出目标元素时使用）。",
        parameterSchemaJson = """
            {
              "type": "object",
              "properties": {
                "x": {"type": "integer"}, "y": {"type": "integer"}
              },
              "required": ["x", "y"]
            }
        """.trimIndent(),
    )

    /** 向输入框输入文本（需先聚焦输入框）。 */
    private val INPUT_TEXT = ChatToolSpec.of(
        name = NAME_INPUT_TEXT,
        description = "向当前聚焦的输入框输入文本（输入前先用 tap_target 点击输入框使其聚焦）。",
        parameterSchemaJson = """
            {
              "type": "object",
              "properties": {
                "text": {"type": "string", "description": "要输入的完整文本"}
              },
              "required": ["text"]
            }
        """.trimIndent(),
    )

    /** 滚动（列表/页面翻页）。 */
    private val SCROLL = ChatToolSpec.of(
        name = NAME_SCROLL,
        description = "滚动当前列表或页面。direction 为 forward（向下/下一页）或 backward（向上/上一页）。目标元素不在当前视野时用它。",
        parameterSchemaJson = """
            {
              "type": "object",
              "properties": {
                "direction": {"type": "string", "enum": ["forward", "backward"]}
              },
              "required": ["direction"]
            }
        """.trimIndent(),
    )

    private val PRESS_BACK = ChatToolSpec.of(
        name = NAME_PRESS_BACK,
        description = "按下系统返回键（退出当前页面/弹层）。",
        parameterSchemaJson = """{"type":"object","properties":{}}""",
    )

    private val PRESS_HOME = ChatToolSpec.of(
        name = NAME_PRESS_HOME,
        description = "回到 Android 桌面（Home）。",
        parameterSchemaJson = """{"type":"object","properties":{}}""",
    )

    /** 按下回车/搜索确认（提交搜索、发送消息等）。 */
    private val PRESS_ENTER = ChatToolSpec.of(
        name = NAME_PRESS_ENTER,
        description = "按下回车键（提交搜索 / 发送消息）。输入搜索词后若界面没有可见的搜索按钮，用它提交。",
        parameterSchemaJson = """{"type":"object","properties":{}}""",
    )

    /**
     * 记录笔记（采集任务落盘）：模型无跨步记忆，多步采集任务（"帮我记录 X 的数据"）必须
     * 显式用本工具保存已获取的数据，供后续检索与总结。
     */
    private val RECORD_NOTE = ChatToolSpec.of(
        name = NAME_RECORD_NOTE,
        description = "记录一条数据到任务笔记（采集到的数据 / 页面要点必须用本工具记下，供后续检索与总结）。" +
            "例如抓取的价格、标题、链接、时间等。",
        parameterSchemaJson = """
            {
              "type": "object",
              "properties": {
                "content": {"type": "string", "description": "要记录的内容（必填）"},
                "tag": {"type": "string", "description": "分类标签（可选），如 价格 / 链接 / 标题"}
              },
              "required": ["content"]
            }
        """.trimIndent(),
    )

    /** 等待页面加载（网络/动画）。 */
    private val WAIT = ChatToolSpec.of(
        name = NAME_WAIT,
        description = "等待指定毫秒（页面加载、动画完成）。在点击/输入/返回后可调用它等待界面稳定，然后再 get_ui_layout。",
        parameterSchemaJson = """
            {
              "type": "object",
              "properties": {
                "ms": {"type": "integer", "description": "等待毫秒数，500~3000 为宜"}
              },
              "required": ["ms"]
            }
        """.trimIndent(),
    )

    private val FINISH = ChatToolSpec.of(
        name = NAME_FINISH,
        description = "任务完成，结束全部操作后总结。",
        parameterSchemaJson = """{"type":"object","properties":{}}""",
    )

    fun openApp(): ChatToolSpec = OPEN_APP

    fun getUiLayout(): ChatToolSpec = GET_UI_LAYOUT

    fun tapTarget(): ChatToolSpec = TAP_TARGET

    fun tap(): ChatToolSpec = TAP

    fun inputText(): ChatToolSpec = INPUT_TEXT

    fun scroll(): ChatToolSpec = SCROLL

    fun pressBack(): ChatToolSpec = PRESS_BACK

    fun pressHome(): ChatToolSpec = PRESS_HOME

    fun pressEnter(): ChatToolSpec = PRESS_ENTER

    fun recordNote(): ChatToolSpec = RECORD_NOTE

    fun wait(): ChatToolSpec = WAIT

    fun finish(): ChatToolSpec = FINISH

    /** 当前暴露给模型的全部工具（按推荐顺序）。 */
    fun defaults(): List<ChatToolSpec> = listOf(
        OPEN_APP,
        GET_UI_LAYOUT,
        TAP_TARGET,
        TAP,
        INPUT_TEXT,
        SCROLL,
        PRESS_BACK,
        PRESS_HOME,
        PRESS_ENTER,
        RECORD_NOTE,
        WAIT,
        FINISH,
    )
}
package com.fall.assistant.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fall.assistant.FallApplication
import com.fall.assistant.core.AppContainer
import com.fall.assistant.execution.ExecutionHub
import com.fall.core.data.local.ChatMessageEntity

/** 首页建议（点击填入输入框并聚焦）。 */
private val SUGGESTIONS = listOf(
    "打开哔哩哔哩",
    "帮我写一段工作总结",
    "介绍一下你自己",
    "今天有什么安排？",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory(localAppContainer()),
    ),
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var inputFocused by remember { mutableStateOf(false) }
    // 消息分组：用户消息为任务边界，其后的思考/工具调用/结果合并为一条 AI 回复卡片
    val groups = remember(state.messages, state.pendingTask) { groupTasks(state.messages, state.pendingTask) }
    val itemCount = groups.size + (if (state.error != null) 1 else 0)
    // 实时进度版本号：执行中步骤/回答变化时驱动跟随滚动
    val liveVersion = state.pendingTask?.let { it.steps.size * 1_000_000 + it.answerText.length } ?: -1

    // 新消息/错误 → 滚动到底部（执行中先定位到任务卡片，随后由实时跟随对齐底部）
    LaunchedEffect(itemCount) {
        if (itemCount > 0) {
            if (state.sending) listState.scrollToItem(itemCount - 1)
            else listState.animateScrollToItem(itemCount - 1)
        }
    }

    // 流式/步骤更新时：用户贴底才跟随，避免打断向上翻阅
    LaunchedEffect(state.sending, liveVersion) {
        if (state.sending) alignLiveBottom(listState)
    }

    // 点击输入框（弹出输入法）→ 列表滚到底，消息不被键盘遮挡
    LaunchedEffect(inputFocused) {
        if (inputFocused && itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 输入框跟随弹出的输入法上移（配合 Manifest adjustResize + edge-to-edge）
            .imePadding(),
    ) {
        SessionBar(
            title = state.sessions.firstOrNull { it.id == state.currentSessionId }?.title ?: "新对话",
            sessions = state.sessions,
            onSelect = viewModel::selectSession,
            onNew = viewModel::createSession,
            onDelete = viewModel::deleteSession,
        )

        if (state.messages.isEmpty() && state.error == null && !state.sending) {
            WelcomeContent(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onSuggestion = { text ->
                    viewModel.updateInput(text)
                    focusRequester.requestFocus()
                },
            )
        } else {
            MessageList(
                groups = groups,
                listState = listState,
                error = state.error,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }

        InputBar(
            input = state.input,
            sending = state.sending,
            onInputChange = viewModel::updateInput,
            onSend = viewModel::send,
            onStop = ExecutionHub::requestStop,
            focusRequester = focusRequester,
            onFocusChange = { inputFocused = it },
        )
    }
}

@Composable
private fun MessageList(
    groups: List<MessageGroup>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    error: String?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(
            groups,
            key = { _, g -> g.key },
        ) { _, group ->
            MessageItem(group)
        }
        error?.let { err ->
            item(key = "error") { ErrorCard(err) }
        }
    }
}

@Composable
private fun MessageItem(group: MessageGroup) {
    when (group) {
        is MessageGroup.Standard -> RegularBubble(group.message)
        is MessageGroup.Task -> TaskCard(group)
    }
}

@Composable
private fun RegularBubble(message: ChatMessageEntity) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            // 用户消息：右侧气泡 + 头像
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.fillMaxWidth(0.82f),
            ) {
                Text(
                    text = message.content.ifBlank { "…" },
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            AvatarCircle(
                icon = Icons.Outlined.Person,
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 助手消息：头像 + 全宽卡片（主流 AI 对话样式）
            AvatarCircle(
                icon = Icons.Outlined.AutoAwesome,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = message.content.ifBlank { "…" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 工具执行状态色（与主题无关的语义色，浅/深色下均清晰可读）。 */
private val StatusOk = Color(0xFF34A853)
private val StatusRunning = Color(0xFFFBBC04)
private val StatusError = Color(0xFFEA4335)

/**
 * 任务回复卡片（PC Agent 风格单卡片）：
 * 用户气泡 + AI 回复（最终回答 + 可折叠"执行过程"时间线）。
 * 执行中（[MessageGroup.Task.live] != null）自动展开并实时更新；完成后默认收起。
 */
@Composable
private fun TaskCard(group: MessageGroup.Task) {
    val live = group.live
    val answer = live?.answerText?.takeIf { it.isNotBlank() } ?: group.answerText
    val steps = live?.steps ?: group.steps
    // 执行中自动展开；完成后收起
    var processExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(live != null) { processExpanded = live != null }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 用户气泡
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.fillMaxWidth(0.82f),
            ) {
                Text(
                    text = group.userMessage.content.ifBlank { "…" },
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            AvatarCircle(
                icon = Icons.Outlined.Person,
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        // AI 回复卡片
        Row(modifier = Modifier.fillMaxWidth()) {
            AvatarCircle(
                icon = Icons.Outlined.AutoAwesome,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
                modifier = Modifier.weight(1f),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = answer.ifBlank { if (live != null) "…" else "" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (live != null) BlinkingCursor()
                    }
                    if (steps.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        ProcessBar(
                            steps = steps,
                            running = live != null,
                            expanded = processExpanded,
                            onToggle = { processExpanded = !processExpanded },
                        )
                        AnimatedVisibility(visible = processExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                steps.forEach { step -> StepRow(step) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 执行过程总览条：状态点 + 折叠标题 + 进度/结果摘要。 */
@Composable
private fun ProcessBar(
    steps: List<ToolStep>,
    running: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val toolCount = steps.count { it.toolName.isNotBlank() }
    val done = steps.none { it.running }
    val summary = when {
        running -> "执行中 · 已完成 ${steps.size} 步"
        done -> "已执行 ${steps.size} 步 · $toolCount 个工具 · 全部成功"
        else -> "已执行 ${steps.size} 步 · $toolCount 个工具"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
    ) {
        StatusDot(running = running, ok = done)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "执行过程",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = summary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            imageVector = Icons.Outlined.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .size(18.dp)
                .rotate(if (expanded) 0f else -90f),
        )
    }
}

/** 单个工具步骤：思考折叠 + 工具名/状态 + 参数与结果折叠。 */
@Composable
private fun StepRow(step: ToolStep) {
    var thinkingOpen by remember { mutableStateOf(false) }
    var detailOpen by remember { mutableStateOf(false) }
    val hasDetail = step.arguments != null || step.result != null
    Column(modifier = Modifier.fillMaxWidth()) {
        if (step.thinking != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { thinkingOpen = !thinkingOpen }
                    .padding(vertical = 2.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(if (thinkingOpen) 0f else -90f),
                )
                Text(
                    text = "思考",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = thinkingOpen) {
                Text(
                    text = step.thinking,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    modifier = Modifier.padding(start = 16.dp, bottom = 2.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
            StatusDot(running = step.running, ok = true)
            Spacer(Modifier.width(6.dp))
            if (step.running) {
                Text(
                    text = "执行中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = step.toolName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (hasDetail) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { detailOpen = !detailOpen }
                    .padding(vertical = 2.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(if (detailOpen) 0f else -90f),
                )
                Text(
                    text = "查看参数 / 结果",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = detailOpen) {
                Text(
                    text = buildString {
                        step.arguments?.let { append("参数: ").append(it).append('\n') }
                        step.result?.let { append("结果: ").append(it) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 16.dp, bottom = 2.dp),
                )
            }
        }
    }
}

/**
 * 实时跟随滚动：仅当最后一项仍可见（用户未向上翻阅较远）时，把它的底部对齐视口底边，
 * 让新增步骤/回答实时可见；scrollToItem 的 scrollOffset 会把该项顶部放到
 * viewportStart + scrollOffset 处，故传 viewportEnd - 项高即底部对齐。
 */
private suspend fun alignLiveBottom(listState: androidx.compose.foundation.lazy.LazyListState) {
    val info = listState.layoutInfo
    val target = info.totalItemsCount - 1
    if (target < 0) return
    val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return
    if (lastVisible.index < target - 1) return // 用户已向上翻阅，不打断
    val last = info.visibleItemsInfo.lastOrNull { it.index == target } ?: return
    val desiredOffset = info.viewportEndOffset - last.size
    if (last.offset != desiredOffset) {
        listState.scrollToItem(target, desiredOffset)
    }
}

/** 状态点：执行中 = 脉冲动画，成功 = 绿色，失败 = 红色。 */
@Composable
private fun StatusDot(running: Boolean, ok: Boolean) {
    val color = when {
        running -> StatusRunning
        ok -> StatusOk
        else -> StatusError
    }
    val alpha = if (running) {
        val transition = rememberInfiniteTransition(label = "statusPulse")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "statusPulseA",
        )
        a
    } else 1f
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}

/** 流式输出时末尾闪烁的 ▍ 光标。 */
@Composable
private fun BlinkingCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorAlpha",
    )
    Text(
        text = "▍",
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.alpha(alpha),
    )
}

@Composable
private fun AvatarCircle(
    icon: ImageVector,
    container: Color,
    content: Color,
    size: Dp = 30.dp,
    iconSize: Dp = 18.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun WelcomeContent(
    modifier: Modifier = Modifier,
    onSuggestion: (String) -> Unit,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AvatarCircle(
            icon = Icons.Outlined.AutoAwesome,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.primary,
            size = 56.dp,
            iconSize = 30.dp,
        )
        Spacer(Modifier.height(16.dp))
        Text("Fall 助手", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "随时随地，让 AI 帮你打开应用、完成任务、解答问题",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SUGGESTIONS.forEach { s ->
                SuggestionChip(onClick = { onSuggestion(s) }, label = { Text(s) })
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun SessionBar(
    title: String,
    sessions: List<com.fall.core.data.local.ChatSessionEntity>,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(onClick = { menuOpen = true }) {
                Text(title, maxLines = 1)
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = "切换会话")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                sessions.forEach { session ->
                    DropdownMenuItem(
                        text = { Text(session.title, maxLines = 1) },
                        onClick = {
                            menuOpen = false
                            onSelect(session.id)
                        },
                        trailingIcon = {
                            IconButton(onClick = { onDelete(session.id) }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "删除会话",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("＋ 新对话") },
                    onClick = {
                        menuOpen = false
                        onNew()
                    },
                )
            }
        }
    }
}

@Composable
private fun InputBar(
    input: String,
    sending: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // Composer 药丸输入框：无边框、圆角、placeholder 内嵌
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.weight(1f),
        ) {
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { onFocusChange(it.isFocused) }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!sending) onSend() }),
                decorationBox = { innerTextField ->
                    Box {
                        if (input.isEmpty()) {
                            Text(
                                text = "给 AI 说点什么…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        if (sending) {
            // 生成中：停止按钮（主流 AI 工具标准交互）
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer),
            ) {
                Icon(
                    Icons.Outlined.Stop,
                    contentDescription = "停止",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        } else {
            val canSend = input.isNotBlank()
            IconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (canSend) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "发送",
                    tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun localAppContainer(): AppContainer {
    val app = LocalContext.current.applicationContext as FallApplication
    return app.container
}

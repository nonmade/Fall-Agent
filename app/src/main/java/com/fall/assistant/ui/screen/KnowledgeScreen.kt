package com.fall.assistant.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun KnowledgeScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = "知识库",
        description = "本地知识库 / RAG 管理页（P2 实现）",
        modifier = modifier,
    )
}
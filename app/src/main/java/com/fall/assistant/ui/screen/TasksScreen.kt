package com.fall.assistant.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun TasksScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = "任务",
        description = "定时任务 / 自动化任务管理页（P1 实现）",
        modifier = modifier,
    )
}
package com.fall.assistant.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** 应用内四个一级目的地（底部导航 Tab）。 */
enum class FallDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Chat("chat", "对话", Icons.Outlined.ChatBubbleOutline),
    Tasks("tasks", "任务", Icons.Outlined.CheckCircleOutline),
    Knowledge("knowledge", "知识库", Icons.Outlined.FolderOpen),
    Settings("settings", "设置", Icons.Outlined.Settings),
}
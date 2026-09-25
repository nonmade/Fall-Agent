package com.fall.assistant.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.fall.assistant.ui.screen.ChatScreen
import com.fall.assistant.ui.screen.KnowledgeScreen
import com.fall.assistant.ui.screen.SettingsScreen
import com.fall.assistant.ui.screen.TasksScreen

/** 一级导航图：四个 Tab 页面。 */
@Composable
fun FallNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = FallDestination.Chat.route,
        modifier = modifier,
    ) {
        composable(FallDestination.Chat.route) { ChatScreen() }
        composable(FallDestination.Tasks.route) { TasksScreen() }
        composable(FallDestination.Knowledge.route) { KnowledgeScreen() }
        composable(FallDestination.Settings.route) { SettingsScreen() }
    }
}
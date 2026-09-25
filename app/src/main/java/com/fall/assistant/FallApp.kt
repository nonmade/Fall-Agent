package com.fall.assistant

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.fall.assistant.ui.navigation.FallNavHost
import com.fall.assistant.ui.navigation.FallBottomBar

/**
 * Fall 应用根 Composable。
 *
 * 骨架阶段仅提供底部导航容器与四个占位页（对话 / 任务 / 知识库 / 设置）；
 * 页面与 ViewModel 的接线在 P0 循环中逐步填充。
 */
@Composable
fun FallApp() {
    val navController = rememberNavController()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = { FallBottomBar(navController = navController) },
    ) { innerPadding ->
        // 输入法弹出时底部导航已被键盘遮挡，不再占用布局空间——
        // 否则其高度会与页面内的 imePadding 叠加，导致输入栏与键盘之间出现空隙。
        val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
        FallNavHost(
            navController = navController,
            modifier = Modifier.padding(
                PaddingValues(
                    start = innerPadding.calculateStartPadding(LayoutDirection.Ltr),
                    top = innerPadding.calculateTopPadding(),
                    end = innerPadding.calculateEndPadding(LayoutDirection.Ltr),
                    bottom = if (imeBottom > 0) 0.dp else innerPadding.calculateBottomPadding(),
                )
            ),
        )
    }
}
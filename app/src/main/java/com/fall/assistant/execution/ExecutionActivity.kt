package com.fall.assistant.execution

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fall.assistant.ui.theme.FallTheme

/** 全屏执行监控页：实时步骤流 + 模型文本 + 错误 + 停止控制。 */
class ExecutionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FallTheme { ExecutionScreen() }
        }
    }
}

@Composable
private fun ExecutionScreen() {
    val state by ExecutionHub.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("AI 正在执行", style = MaterialTheme.typography.titleMedium)
        Text(
            text = state.goal,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        Spacer(Modifier.padding(4.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "text") {
                if (state.text.isNotBlank()) {
                    Text(
                        text = state.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // 步骤 key 用索引而非内容文本：连续相同输出（如 get_ui_layout 结果一致）会触发 LazyColumn key 冲突崩溃
            itemsIndexed(state.steps) { _, step ->
                Surface(shape = MaterialTheme.shapes.small, tonalElevation = 1.dp) {
                    Text(
                        text = step,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            state.error?.let { err ->
                item(key = "error") {
                    Text(
                        text = "错误：$err",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { ExecutionHub.requestStop() },
                enabled = state.running,
                modifier = Modifier.weight(1f),
            ) { Text(if (state.running) "停止执行" else "已结束") }
        }
    }
}
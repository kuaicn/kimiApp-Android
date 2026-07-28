package com.kimi.app.ui.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kimi.app.data.store.AppTask
import com.kimi.app.data.wire.WireTask
import com.kimi.app.ui.AppViewModel

/** 后台任务面板：会话任务列表（运行/完成/失败）+ 详情（输出）+ 取消 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksSheet(
    viewModel: AppViewModel,
    sessionId: String,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val tasks = state.tasksBySession[sessionId] ?: emptyList()
    var detail by remember { mutableStateOf<WireTask?>(null) }
    var loadingDetail by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            val currentDetail = detail
            if (currentDetail == null) {
                Text("后台任务（${tasks.size}）", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                if (tasks.isEmpty()) {
                    Text(
                        "暂无任务",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                LazyColumn {
                    items(tasks, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            onClick = {
                                loadingDetail = task.id
                                viewModel.loadTaskDetail(sessionId, task.id) {
                                    detail = it
                                    loadingDetail = null
                                }
                            },
                            onCancel = { viewModel.cancelTask(sessionId, task.id) },
                            loading = loadingDetail == task.id,
                        )
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            } else {
                TaskDetail(
                    task = currentDetail,
                    onBack = { detail = null },
                )
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: AppTask,
    onClick: () -> Unit,
    onCancel: () -> Unit,
    loading: Boolean,
) {
    val (icon, tint) = when (task.status) {
        "running" -> Icons.Default.HourglassTop to MaterialTheme.colorScheme.primary
        "completed" -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        else -> Icons.Default.Error to MaterialTheme.colorScheme.error
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                task.description.ifBlank { task.command ?: task.kind },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${task.kind} · ${statusLabel(task.status)}" +
                    (task.subagentType?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else if (task.status == "running") {
            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Cancel,
                    contentDescription = "取消任务",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun TaskDetail(task: WireTask, onBack: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            Text(
                task.description.ifBlank { "任务详情" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "${task.kind} · ${statusLabel(task.status)}" + (task.command?.let { "\n命令：$it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                task.output ?: task.output_preview ?: "（无输出）",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

private fun statusLabel(status: String): String = when (status) {
    "running" -> "运行中"
    "completed" -> "已完成"
    "failed" -> "失败"
    "cancelled" -> "已取消"
    else -> status
}

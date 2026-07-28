package com.kimi.app.ui.drawer

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kimi.app.data.wire.WireFsBrowseResult
import com.kimi.app.ui.AppViewModel

/** 添加工作区对话框：通过 GET /fs:browse 浏览服务器目录，选定后注册 */
@Composable
fun AddWorkspaceDialog(
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
) {
    var browse by remember { mutableStateOf<WireFsBrowseResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var manualPath by remember { mutableStateOf("") }

    fun load(path: String?) {
        loading = true
        error = null
        viewModel.browseFs(
            path = path,
            onResult = { browse = it; loading = false },
            onError = { error = it; loading = false },
        )
    }

    LaunchedEffect(Unit) { load(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加工作区") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 当前路径 + 上级
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { browse?.parent?.let { load(it) } },
                        enabled = browse?.parent != null,
                    ) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "上级目录")
                    }
                    Text(
                        browse?.path ?: "…",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = browse != null) { },
                    )
                }
                if (loading) {
                    Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                } else {
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(browse?.entries?.filter { it.is_dir } ?: emptyList(), key = { it.path }) { entry ->
                            Row(
                                Modifier.fillMaxWidth().clickable { load(entry.path) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = manualPath,
                    onValueChange = { manualPath = it },
                    label = { Text("或直接输入路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val path = manualPath.trim().ifBlank { browse?.path ?: return@TextButton }
                    viewModel.createWorkspace(path, null)
                    onDismiss()
                },
            ) { Text("选择此目录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

package com.kimi.app.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kimi.app.data.wire.WireFsEntry
import com.kimi.app.data.wire.WireFsReadResult
import com.kimi.app.data.wire.WireGitStatus
import com.kimi.app.ui.AppViewModel
import com.kimi.app.ui.chat.DiffLinesView
import com.kimi.app.data.store.DiffLine

/** 文件浏览器（全屏对话框）：文件树 / Git 变更 + 文件预览 + Diff 视图 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileTreeDialog(
    viewModel: AppViewModel,
    sessionId: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var tab by remember { mutableStateOf(0) } // 0=文件 1=变更
            var preview by remember { mutableStateOf<WireFsReadResult?>(null) }
            var diffPath by remember { mutableStateOf<Pair<String, String>?>(null) } // path to diff text

            when {
                diffPath != null -> DiffView(viewModel, sessionId, diffPath!!) { diffPath = null }
                preview != null -> FilePreview(preview!!) { preview = null }
                else -> Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(if (tab == 0) "工作区文件" else "Git 变更") },
                            navigationIcon = {
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Default.Close, contentDescription = "关闭")
                                }
                            },
                        )
                    },
                ) { padding ->
                    Column(Modifier.padding(padding)) {
                        TabRow(selectedTabIndex = tab) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("文件") })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Git 变更") })
                        }
                        if (tab == 0) {
                            FileTree(viewModel, sessionId) { preview = it }
                        } else {
                            GitChanges(viewModel, sessionId) { path -> diffPath = path to "" }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 文件树
// ---------------------------------------------------------------------------

@Composable
private fun FileTree(viewModel: AppViewModel, sessionId: String, onPreview: (WireFsReadResult) -> Unit) {
    var path by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<WireFsEntry>?>(null) }
    var grep by remember { mutableStateOf("") }
    var grepResult by remember { mutableStateOf<List<String>?>(null) }
    var loading by remember { mutableStateOf(true) }

    fun load(p: String?) {
        loading = true
        viewModel.fsList(sessionId, p, depth = 1) {
            items = it?.items ?: emptyList()
            loading = false
        }
    }

    LaunchedEffect(path) { load(path) }

    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                val parent = path?.substringBeforeLast('/', missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
                path = parent
            }, enabled = path != null) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "上级")
            }
            Text(
                path ?: ".",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        // grep 搜索
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = grep,
                onValueChange = { grep = it },
                placeholder = { Text("grep 内容搜索…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                if (grep.isNotBlank()) {
                    viewModel.fsGrep(sessionId, grep) { result ->
                        grepResult = result?.files?.map { f -> f.path } ?: emptyList()
                    }
                }
            }) {
                Icon(Icons.Default.Search, contentDescription = "搜索")
            }
        }
        if (grepResult != null) {
            TextButton(onClick = { grepResult = null; grep = "" }) { Text("清除搜索结果") }
        }
        if (loading) {
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        } else {
            LazyColumn {
                val shown = items.orEmpty().let { list ->
                    if (grepResult != null) list.filter { it.path in grepResult!! } else list
                }
                items(shown.sortedWith(compareByDescending<WireFsEntry> { it.kind == "directory" }.thenBy { it.name }), key = { it.path }) { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (entry.kind == "directory") {
                                    path = entry.path
                                } else {
                                    viewModel.fsRead(sessionId, entry.path) { it?.let(onPreview) }
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (entry.kind == "directory") Icons.Default.Folder else Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(entry.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        entry.git_status?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Git 变更
// ---------------------------------------------------------------------------

@Composable
private fun GitChanges(viewModel: AppViewModel, sessionId: String, onDiff: (String) -> Unit) {
    var status by remember { mutableStateOf<WireGitStatus?>(null) }
    LaunchedEffect(Unit) {
        viewModel.fsGitStatus(sessionId) { status = it }
    }
    val st = status
    if (st == null) {
        Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(24.dp))
        }
        return
    }
    Column(Modifier.padding(12.dp)) {
        Text(
            "分支 ${st.branch.ifBlank { "?" }} · +${st.additions} -${st.deletions}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (st.entries.isEmpty()) {
            Text("工作区干净，没有变更", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn {
            items(st.entries.toList().sortedBy { it.first }, key = { it.first }) { (path, changeKind) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onDiff(path) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Difference, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(8.dp))
                    Text(path, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(changeKind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 预览 / Diff
// ---------------------------------------------------------------------------

@Composable
private fun FilePreview(file: WireFsReadResult, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            Text(
                file.path,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "${file.mime}${file.line_count?.let { " · $it 行" } ?: ""}${if (file.truncated) " · 已截断" else ""}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                if (file.is_binary) "（二进制文件，无法预览）" else file.content,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun DiffView(viewModel: AppViewModel, sessionId: String, target: Pair<String, String>, onBack: () -> Unit) {
    var diff by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(target.first) {
        viewModel.fsDiff(sessionId, target.first) { diff = it ?: "（无法获取 diff）" }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            Text(target.first, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val text = diff
        if (text == null) {
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        } else {
            val lines = text.lines().map { line ->
                DiffLine(
                    kind = when {
                        line.startsWith("+") && !line.startsWith("+++") -> "add"
                        line.startsWith("-") && !line.startsWith("---") -> "rem"
                        else -> "ctx"
                    },
                    gutter = "",
                    text = line,
                )
            }
            DiffLinesView(lines = lines, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

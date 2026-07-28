package com.kimi.app.ui.drawer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kimi.app.core.util.formatTimeShort
import com.kimi.app.data.store.ConnectionState
import com.kimi.app.data.wire.WireSession
import com.kimi.app.ui.AppViewModel
import com.kimi.app.ui.settings.SettingsDialog

/** 左侧抽屉：品牌区、新建、搜索、工作区分组会话列表、底部设置 */
@Composable
fun SessionDrawer(viewModel: AppViewModel, onNavigate: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val groups by viewModel.workspaceGroups.collectAsState()
    val servers by viewModel.servers.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showAddWorkspace by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }

    ModalDrawerSheet {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // 品牌 + 连接状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Kimi",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                ConnectionDot(state.connection)
            }
            Text(
                servers.firstOrNull { it.id == viewModel.activeServerId.collectAsState().value }?.name ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider()

        // 新建 + 搜索
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f).clickable {
                    viewModel.openDraft(state.activeWorkspaceId ?: state.workspaces.firstOrNull()?.id)
                    onNavigate()
                },
            ) {
                Row(
                    Modifier.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    Text("新对话", color = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.clickable { searching = !searching },
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "搜索会话",
                    modifier = Modifier.padding(10.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (searching) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索会话标题…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(4.dp))
        }

        // 会话列表（工作区折叠状态按组绑定：折叠时子项不渲染）
        val collapsedGroups = remember { mutableStateOf(setOf<String>()) }
        LazyColumn(Modifier.weight(1f)) {
            val query = searchQuery.trim()
            for (group in groups) {
                val groupKey = group.workspace?.id ?: group.subtitle
                val expanded = groupKey !in collapsedGroups.value
                val filtered = if (query.isEmpty()) {
                    group.sessions
                } else {
                    group.sessions.filter {
                        it.title.contains(query, true) || (it.last_prompt?.contains(query, true) == true)
                    }
                }
                if (filtered.isEmpty() && query.isNotEmpty()) continue
                item(key = "ws_$groupKey") {
                    WorkspaceHeader(
                        group = group,
                        expanded = expanded,
                        onToggle = {
                            collapsedGroups.value =
                                if (expanded) collapsedGroups.value + groupKey else collapsedGroups.value - groupKey
                        },
                        onNewSession = {
                            viewModel.openDraft(group.workspace?.id)
                            onNavigate()
                        },
                        onRename = { name ->
                            group.workspace?.let { viewModel.renameWorkspace(it.id, name) }
                        },
                        onDelete = {
                            group.workspace?.let { viewModel.deleteWorkspace(it.id) }
                        },
                    )
                }
                if (expanded) {
                    items(filtered, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            active = session.id == state.activeSessionId,
                            turnActive = state.turnActiveBySession[session.id] == true,
                            onClick = {
                                viewModel.selectSession(session.id)
                                onNavigate()
                            },
                            onRename = { viewModel.renameSession(session.id, it) },
                            onArchive = { viewModel.archiveSession(session.id) },
                            onFork = { viewModel.forkSession(session.id) },
                        )
                    }
                }
            }
            // 已归档区
            val archived = state.sessions.filter { it.archived }
            if (archived.isNotEmpty()) {
                item {
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Archive,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "已归档（${archived.size}）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (expanded) {
                        for (session in archived) {
                            SessionRow(
                                session = session,
                                active = false,
                                turnActive = false,
                                onClick = {
                                    viewModel.restoreSession(session.id)
                                    viewModel.selectSession(session.id)
                                    onNavigate()
                                },
                                onRename = {},
                                onArchive = {},
                                onFork = {},
                            )
                        }
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { showAddWorkspace = true }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("添加工作区", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { viewModel.loadMoreSessions() }, modifier = Modifier.padding(12.dp)) {
                    Text("加载更多…")
                }
            }
        }

        HorizontalDivider()
        // 底部设置
        Row(
            Modifier.fillMaxWidth().clickable { showSettings = true }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Settings, contentDescription = "设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text("设置", style = MaterialTheme.typography.bodyLarge)
        }
    }

    if (showSettings) {
        SettingsDialog(viewModel = viewModel, onDismiss = { showSettings = false })
    }
    if (showAddWorkspace) {
        AddWorkspaceDialog(viewModel = viewModel, onDismiss = { showAddWorkspace = false })
    }
}

@Composable
private fun ConnectionDot(connection: ConnectionState) {
    val color = when (connection) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
    }
    val label = when (connection) {
        ConnectionState.CONNECTED -> "已连接"
        ConnectionState.CONNECTING -> "连接中"
        ConnectionState.DISCONNECTED -> "未连接"
    }
    Badge(containerColor = color.copy(alpha = 0.15f), contentColor = color) {
        Text(label, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun WorkspaceHeader(
    group: com.kimi.app.ui.WorkspaceGroupUi,
    expanded: Boolean,
    onToggle: () -> Unit,
    onNewSession: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column {
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    group.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    group.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onNewSession, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Add, contentDescription = "在此工作区新建", modifier = Modifier.size(18.dp))
            }
            if (group.workspace != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "工作区菜单", modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("复制路径") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = {
                                menuOpen = false
                                // 简化：复制到剪贴板由调用处处理 —— 这里直接省略，仅关闭
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                            onClick = { menuOpen = false; renaming = true },
                        )
                        DropdownMenuItem(
                            text = { Text("移除工作区") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                }
            }
        }
        if (!expanded) {
            // 折叠时子项由 LazyColumn 控制 —— 简单起见用 padding 提示
        }
    }

    if (renaming) {
        TextInputDialog(
            title = "重命名工作区",
            initial = group.title,
            onConfirm = { renaming = false; if (it.isNotBlank()) onRename(it.trim()) },
            onDismiss = { renaming = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("移除工作区") },
            text = { Text("仅从注册表移除，不会删除其中的会话。确定移除「${group.title}」吗？") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SessionRow(
    session: WireSession,
    active: Boolean,
    turnActive: Boolean,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onArchive: () -> Unit,
    onFork: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }

    Surface(
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(
            Modifier.clickable(onClick = onClick).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    session.title.ifBlank { "未命名会话" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatTimeShort(session.updated_at),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SessionBadges(session, turnActive)
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "会话菜单",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = { menuOpen = false; renaming = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Fork 副本") },
                        onClick = { menuOpen = false; onFork() },
                    )
                    if (!session.archived) {
                        DropdownMenuItem(
                            text = { Text("归档") },
                            onClick = { menuOpen = false; confirmArchive = true },
                        )
                    }
                }
            }
        }
    }

    if (renaming) {
        TextInputDialog(
            title = "重命名会话",
            initial = session.title,
            onConfirm = { renaming = false; if (it.isNotBlank()) onRename(it.trim()) },
            onDismiss = { renaming = false },
        )
    }
    if (confirmArchive) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("归档会话") },
            text = { Text("归档后将从列表隐藏，可随时恢复。确定归档「${session.title}」吗？") },
            confirmButton = {
                TextButton(onClick = { confirmArchive = false; onArchive() }) { Text("归档") }
            },
            dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SessionBadges(session: WireSession, turnActive: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 6.dp)) {
        if (turnActive || session.main_turn_active == true) {
            Badge(containerColor = MaterialTheme.colorScheme.primary) {
                Text("运行中", modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
        when (session.pending_interaction) {
            "approval" -> Badge(containerColor = MaterialTheme.colorScheme.error) {
                Text("待审批", modifier = Modifier.padding(horizontal = 4.dp))
            }

            "question" -> Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
                Text("待回答", modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

package com.kimi.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kimi.app.data.store.AppRole
import com.kimi.app.data.store.ChatTurn
import com.kimi.app.data.store.TurnBlock
import com.kimi.app.data.store.TurnRole
import com.kimi.app.data.store.buildApprovalBlock
import com.kimi.app.ui.AppViewModel
import kotlinx.coroutines.launch

/** 聊天主屏：顶栏 + 回合列表 + 底部输入区 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: AppViewModel, onOpenDrawer: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val turns by viewModel.turns.collectAsState()
    val questions by viewModel.pendingQuestions.collectAsState()
    val approvals by viewModel.pendingApprovals.collectAsState()

    val session = state.activeSession
    val sessionId = state.activeSessionId
    val context = androidx.compose.ui.platform.LocalContext.current
    val busy = session?.busy == true || (sessionId != null && state.turnActiveBySession[sessionId] == true)
    val live = sessionId?.let { state.liveStatusBySession[it] }
    var menuOpen by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showModes by remember { mutableStateOf(false) }
    var showTasks by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    val currentModel = live?.model?.takeIf { it.isNotBlank() }
        ?: session?.agent_config?.model?.takeIf { it.isNotBlank() }
        ?: state.config?.default_model

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            session?.title?.ifBlank { "未命名会话" } ?: "新对话",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            buildString {
                                append(currentModel ?: "")
                                val ctx = live?.contextTokens ?: session?.usage?.context_tokens ?: 0
                                val limit = live?.maxContextTokens ?: session?.usage?.context_limit ?: 0
                                if (limit > 0) append(" · 上下文 ${ctx / 1000}k/${limit / 1000}k")
                                if (state.connection != com.kimi.app.data.store.ConnectionState.CONNECTED) {
                                    append(" · 离线")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { showModelPicker = true },
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "打开会话列表")
                    }
                },
                actions = {
                    if (sessionId != null || session != null) {
                        IconButton(onClick = { showModes = true }) {
                            Icon(Icons.Default.Tune, contentDescription = "模式设置")
                        }
                    }
                    if (sessionId != null) {
                        IconButton(onClick = { showTasks = true }) {
                            Icon(Icons.Default.Engineering, contentDescription = "后台任务")
                        }
                        IconButton(onClick = { showFiles = true }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "工作区文件")
                        }
                    }
                    if (session != null) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "会话操作")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("压缩历史") },
                                leadingIcon = { Icon(Icons.Default.Compress, null) },
                                onClick = { menuOpen = false; viewModel.compactSession(session.id) },
                            )
                            DropdownMenuItem(
                                text = { Text("Fork 副本") },
                                leadingIcon = { Icon(Icons.Default.CallSplit, null) },
                                onClick = { menuOpen = false; viewModel.forkSession(session.id) },
                            )
                            DropdownMenuItem(
                                text = { Text("撤销上一回合") },
                                leadingIcon = { Icon(Icons.Default.Undo, null) },
                                onClick = { menuOpen = false; viewModel.undoSession(session.id) },
                            )
                            DropdownMenuItem(
                                text = { Text("导出会话") },
                                leadingIcon = { Icon(Icons.Default.Download, null) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.exportSession(context, session.id, session.title) { name ->
                                        // 结果以通知呈现（在 ViewModel 内补一条 notice）
                                        if (name != null) viewModel.notice("已导出到下载目录：$name")
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("归档会话") },
                                leadingIcon = { Icon(Icons.Default.Archive, null) },
                                onClick = { menuOpen = false; viewModel.archiveSession(session.id) },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            val listState = rememberLazyListState()
            val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

            // ---- 滚动行为（reverseLayout：索引 0 = 视觉底部）----
            // 1) 列表天然吸附底部，进入会话即在最新位置
            // 2) 贴底时新内容（流式增量/审批/问题）自动跟随（scrollToItem(0)）
            // 3) 离开底部后出现"回到底部"按钮，期间有新消息显示"新消息"提示
            val isAtBottom by remember {
                androidx.compose.runtime.derivedStateOf {
                    listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 0
                }
            }
            // 变化前一刻是否贴底（避免新条目布局完成前后的状态抖动）
            val stickToBottom = remember { androidx.compose.runtime.mutableStateOf(true) }
            var hasNewWhileAway by remember { androidx.compose.runtime.mutableStateOf(false) }
            LaunchedEffect(listState) {
                snapshotFlow { isAtBottom }.collect { atBottom ->
                    stickToBottom.value = atBottom
                    if (atBottom) hasNewWhileAway = false
                }
            }

            var didInitialScroll by remember(sessionId) { androidx.compose.runtime.mutableStateOf(false) }
            LaunchedEffect(turns.size) {
                // reverseLayout 天然停留在底部，无需主动滚动，仅记录已加载
                if (turns.isNotEmpty()) didInitialScroll = true
            }

            // 新内容自动滚底（用户翻历史时不打扰）
            // reverseLayout：索引 0 即视觉底部，scrollToItem(0) 永远精确到底
            val lastKey = turns.lastOrNull()?.let { "${it.id}:${it.text.length}:${it.blocks?.size}" }
            LaunchedEffect(lastKey, questions.size, approvals.size) {
                if (turns.isEmpty()) return@LaunchedEffect
                if (stickToBottom.value) {
                    listState.scrollToItem(0)
                } else if (didInitialScroll) {
                    hasNewWhileAway = true
                }
            }

            // 底部优先的显示列表（索引 0 = 视觉最底部）
            val attachedApprovalIds = turns.mapNotNull { it.approvalId }.toSet()
            val looseApprovals = approvals.filter { it.approval_id !in attachedApprovalIds }
            val showWorking = busy && (turns.isEmpty() || turns.last().role == TurnRole.USER)
            val displayItems = remember(turns, looseApprovals, questions, showWorking, state.hasMoreMessagesBySession[sessionId]) {
                buildList<Any> {
                    if (showWorking) add("working")
                    if (questions.isNotEmpty()) add("questions")
                    if (looseApprovals.isNotEmpty()) add("loose_approvals")
                    addAll(turns.asReversed())
                    if (sessionId != null && state.hasMoreMessagesBySession[sessionId] == true) add("load_older")
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(
                    count = displayItems.size,
                    key = { index ->
                        when (val it = displayItems[index]) {
                            is ChatTurn -> it.id
                            else -> it as String
                        }
                    },
                    contentType = { index ->
                        when (val it = displayItems[index]) {
                            is ChatTurn -> it.role
                            else -> "meta"
                        }
                    },
                ) { index ->
                    when (val item = displayItems[index]) {
                        "load_older" -> {
                            var loading by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) {
                                if (!loading && sessionId != null) {
                                    loading = true
                                    viewModel.loadOlderMessages(sessionId)
                                }
                            }
                            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }

                        "working" -> {
                            Row(
                                Modifier.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Kimi 正在工作…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        "questions" -> {
                            for (q in questions) {
                                QuestionCard(
                                    request = q,
                                    onAnswer = { answers ->
                                        viewModel.respondQuestion(sessionId ?: return@QuestionCard, q.question_id, answers)
                                    },
                                    onDismiss = { viewModel.dismissQuestion(sessionId ?: return@QuestionCard, q.question_id) },
                                )
                            }
                        }

                        "loose_approvals" -> {
                            for (approval in looseApprovals) {
                                ApprovalCard(
                                    approval = buildApprovalBlock(approval),
                                    request = approval,
                                    onRespond = { decision, scope, feedback ->
                                        viewModel.respondApproval(sessionId ?: return@ApprovalCard, approval.approval_id, decision, scope, feedback)
                                    },
                                )
                            }
                        }

                        is ChatTurn -> TurnView(
                            turn = item,
                            viewModel = viewModel,
                            streaming = busy && item.id == turns.lastOrNull()?.id,
                        )
                    }
                }
            }

            // 回到底部快捷按钮（离开底部时出现；期间有新消息则变为"新消息"提示）
            androidx.compose.animation.AnimatedVisibility(
                visible = !isAtBottom && turns.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 8.dp),
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
                androidx.compose.material3.Surface(
                    onClick = {
                        hasNewWhileAway = false
                        coroutineScope.launch { listState.scrollToItem(0) }
                    },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    color = if (hasNewWhileAway) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (hasNewWhileAway) {
                            Text(
                                "新消息",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(2.dp))
                        }
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "回到底部",
                            tint = if (hasNewWhileAway) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            }

            val goal = sessionId?.let { state.goalBySession[it] }
            if (goal != null) {
                GoalStrip(goal = goal)
            }

            ChatDock(
                viewModel = viewModel,
                sessionId = sessionId,
                busy = busy,
                queued = sessionId?.let { state.queuedBySession[it] } ?: emptyList(),
            )
        }
    }

    if (showModelPicker) {
        com.kimi.app.ui.settings.ModelPickerSheet(
            viewModel = viewModel,
            currentModel = currentModel,
            onSelect = { model ->
                if (sessionId != null) {
                    viewModel.setSessionModel(sessionId, model)
                }
            },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showModes && sessionId != null) {
        ModesSheet(
            viewModel = viewModel,
            sessionId = sessionId,
            onDismiss = { showModes = false },
        )
    }

    if (showTasks && sessionId != null) {
        com.kimi.app.ui.panels.TasksSheet(
            viewModel = viewModel,
            sessionId = sessionId,
            onDismiss = { showTasks = false },
        )
    }

    if (showFiles && sessionId != null) {
        com.kimi.app.ui.files.FileTreeDialog(
            viewModel = viewModel,
            sessionId = sessionId,
            onDismiss = { showFiles = false },
        )
    }
}

/** 模式弹层：思考级别 / 计划模式 / 权限模式 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ModesSheet(viewModel: AppViewModel, sessionId: String, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val live = state.liveStatusBySession[sessionId]
    val model = live?.model?.takeIf { it.isNotBlank() }
        ?: state.activeSession?.agent_config?.model
        ?: state.config?.default_model
    val wireModel = state.models.firstOrNull { "${it.provider}/${it.model}" == model || it.model == model }
    val efforts = wireModel?.support_efforts ?: listOf("off", "low", "medium", "high")
    val thinkingLevels = (listOf("off") + efforts).distinct()
    val currentThinking = live?.thinkingLevel?.takeIf { it.isNotBlank() } ?: wireModel?.default_effort ?: "off"
    val currentPermission = live?.permission?.takeIf { it.isNotBlank() } ?: "manual"

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("模式", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            Text("思考级别", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.padding(vertical = 8.dp)) {
                for (level in thinkingLevels) {
                    val label = when (level) {
                        "off" -> "关闭"
                        "low" -> "低"
                        "medium" -> "中"
                        "high" -> "高"
                        "max" -> "最高"
                        else -> level
                    }
                    androidx.compose.material3.FilterChip(
                        selected = currentThinking == level,
                        onClick = { viewModel.setSessionProfile(sessionId, thinking = level) },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("计划模式", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "先出计划，经你批准后再执行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = live?.planMode == true,
                    onCheckedChange = { viewModel.setSessionProfile(sessionId, planMode = it) },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("权限模式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            for ((mode, label, desc) in listOf(
                Triple("manual", "手动审批", "每个敏感操作都需要你确认"),
                Triple("auto", "自动批准", "自动批准常见操作，风险操作仍询问"),
                Triple("yolo", "完全放行", "不再询问任何操作（谨慎使用）"),
            )) {
                Row(
                    Modifier.fillMaxWidth().clickable { viewModel.setSessionProfile(sessionId, permissionMode = mode) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(selected = currentPermission == mode, onClick = null)
                    Column {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun TurnView(turn: ChatTurn, viewModel: AppViewModel, streaming: Boolean) {
    when (turn.role) {
        TurnRole.USER -> UserTurnView(turn)
        TurnRole.COMPACTION -> {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    "—— 上下文已压缩 ——",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        TurnRole.CRON -> {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    "定时任务触发",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                MarkdownBlock(turn.text)
            }
        }

        TurnRole.ASSISTANT -> AssistantTurnView(turn, viewModel, streaming)
    }
}

@Composable
private fun UserTurnView(turn: ChatTurn) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        turn.skillActivation?.let {
            Text(
                "技能：${it.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        turn.attachments?.forEach { attachment ->
            when (attachment.kind) {
                "image" -> com.kimi.app.ui.common.AuthImage(
                    url = attachment.url,
                    contentDescription = attachment.name ?: "图片",
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                "video" -> Text(
                    "视频：${attachment.name ?: attachment.url}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                else -> Text(
                    "附件：${attachment.name ?: "文件"}" +
                        (attachment.size?.let { "（${com.kimi.app.core.util.formatBytes(it)}）" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (turn.text.isNotBlank()) {
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.padding(start = 48.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Text(
                    turn.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun AssistantTurnView(turn: ChatTurn, viewModel: AppViewModel, streaming: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        val blocks = turn.blocks
        if (blocks == null) {
            // 无顺序块（老数据）：先思考后正文
            turn.thinking?.let { ThinkingBlock(it, streaming) }
            if (turn.text.isNotBlank()) MarkdownBlock(turn.text)
            turn.tools?.forEach { ToolCallCard(it) }
        } else {
            for (block in blocks) {
                when (block) {
                    is TurnBlock.ThinkingBlock -> ThinkingBlock(block.thinking, streaming)
                    is TurnBlock.TextBlock -> if (block.text.isNotBlank()) MarkdownBlock(block.text)
                    is TurnBlock.ToolBlock -> ToolCallCard(block.tool)
                }
            }
        }
        turn.approval?.let { approvalBlock ->
            val request = viewModel.pendingApprovals.collectAsState().value
                .firstOrNull { it.approval_id == turn.approvalId }
            ApprovalCard(
                approval = approvalBlock,
                request = request,
                onRespond = { decision, scope, feedback ->
                    val sid = viewModel.state.value.activeSessionId ?: return@ApprovalCard
                    viewModel.respondApproval(sid, turn.approvalId ?: return@ApprovalCard, decision, scope, feedback)
                },
            )
        }
    }
}

package com.kimi.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kimi.app.AppContainer
import com.kimi.app.core.SettingsStore
import com.kimi.app.core.StoredServer
import com.kimi.app.core.ThemeMode
import com.kimi.app.data.store.AppState
import com.kimi.app.data.store.AppTask
import com.kimi.app.data.store.ChatTurn
import com.kimi.app.data.store.KimiClient
import com.kimi.app.data.store.TurnGrouper
import com.kimi.app.data.wire.WireApprovalRequest
import com.kimi.app.data.wire.WireQuestionAnswer
import com.kimi.app.data.wire.WireQuestionRequest
import com.kimi.app.data.wire.WireSession
import com.kimi.app.data.wire.WireWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 工作区分组视图模型（web 端 sidebar 的 workspaceGroups 对齐） */
data class WorkspaceGroupUi(
    val workspace: WireWorkspace?,
    val title: String,
    val subtitle: String,
    val sessions: List<WireSession>,
)

class AppViewModel(
    val client: KimiClient,
    private val settings: SettingsStore,
) : ViewModel() {

    // ---- 设置侧 ----
    val servers = settings.serversFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeServerId = settings.activeServerIdFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val themeMode = settings.themeModeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val onboarded = settings.onboardedFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val favoriteModels = settings.favoriteModelsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val state: StateFlow<AppState> = client.state

    // ---- 聊天派生 ----

    val activeSession = state.map { it.activeSession }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 活跃会话的回合列表（消息 → ChatTurn，重计算放 Default 调度器） */
    @OptIn(ExperimentalCoroutinesApi::class)
    val turns: StateFlow<List<ChatTurn>> = state
        .map { it.activeSessionId }
        .distinctUntilChanged()
        .flatMapLatest { sid ->
            if (sid == null) {
                flowOf(emptyList())
            } else {
                combine(
                    state.map { it.messagesBySession[sid] ?: emptyList() }.distinctUntilChanged(),
                    state.map { it.approvalsBySession[sid] ?: emptyList() }.distinctUntilChanged(),
                    state.map {
                        (it.turnActiveBySession[sid] == true) || (it.sessions.firstOrNull { s -> s.id == sid }?.busy == true)
                    }.distinctUntilChanged(),
                    state.map { it.planReviewByToolCallId }.distinctUntilChanged(),
                ) { messages, approvals, active, plans ->
                    TurnGrouper.messagesToTurns(
                        messages = messages,
                        approvals = approvals,
                        sessionActive = active,
                        planReviewByToolCallId = plans,
                        fileUrlOf = { fid -> client.fileUrl(fid) },
                    )
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingApprovals: StateFlow<List<WireApprovalRequest>> = state
        .map { it.approvalsBySession[it.activeSessionId] ?: emptyList() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingQuestions: StateFlow<List<WireQuestionRequest>> = state
        .map { it.questionsBySession[it.activeSessionId] ?: emptyList() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeTasks: StateFlow<List<AppTask>> = state
        .map { it.tasksBySession[it.activeSessionId] ?: emptyList() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 工作区分组（含按 cwd 推导的无注册工作区会话） */
    val workspaceGroups: StateFlow<List<WorkspaceGroupUi>> = state
        .map { s -> buildWorkspaceGroups(s.sessions, s.workspaces) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun buildWorkspaceGroups(
        sessions: List<WireSession>,
        workspaces: List<WireWorkspace>,
    ): List<WorkspaceGroupUi> {
        val visible = sessions.filter { !it.archived }
        val groups = mutableListOf<WorkspaceGroupUi>()
        val assigned = mutableSetOf<String>()
        for (ws in workspaces) {
            val inWs = visible.filter {
                it.workspace_id == ws.id || (it.workspace_id == null && cwdOf(it) == ws.root)
            }
            assigned.addAll(inWs.map { it.id })
            if (inWs.isNotEmpty() || true) {
                groups.add(
                    WorkspaceGroupUi(
                        workspace = ws,
                        title = ws.name.ifBlank { ws.root.substringAfterLast('/') },
                        subtitle = ws.root,
                        sessions = inWs.sortedByDescending { it.updated_at },
                    ),
                )
            }
        }
        val rest = visible.filter { it.id !in assigned }
        if (rest.isNotEmpty()) {
            // 按 cwd 推导未注册工作区
            val byCwd = rest.groupBy { cwdOf(it).ifBlank { "其他" } }
            for ((cwd, list) in byCwd) {
                groups.add(
                    WorkspaceGroupUi(
                        workspace = null,
                        title = cwd.substringAfterLast('/').ifBlank { cwd },
                        subtitle = cwd,
                        sessions = list.sortedByDescending { it.updated_at },
                    ),
                )
            }
        }
        return groups
    }

    private fun cwdOf(s: WireSession): String =
        s.metadata["cwd"]?.toString()?.removeSurrounding("\"") ?: ""

    // ---- 动作（代理到 client/settings） ----

    fun selectSession(id: String?) = launch { client.selectSession(id) }
    fun openDraft(workspaceId: String?) = client.openDraft(workspaceId)
    fun sendPrompt(text: String, attachments: List<com.kimi.app.data.wire.WireMessageContent> = emptyList()) =
        launch { client.sendPrompt(text, attachments) }

    /** 读取附件 uri 的字节/名称/MIME（IO 线程） */
    fun readAttachment(
        context: android.content.Context,
        uri: android.net.Uri,
        onResult: (bytes: ByteArray?, name: String, mime: String) -> Unit,
    ) = launch {
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
                } ?: uri.lastPathSegment ?: "attachment"
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                Triple(bytes, name, mime)
            }.getOrNull() ?: Triple(null, "attachment", "application/octet-stream")
        }
        onResult(result.first, result.second, result.third)
    }
    fun abort(sessionId: String) = launch { client.abortSession(sessionId) }
    fun loadOlderMessages(sessionId: String) = launch { client.loadOlderMessages(sessionId) }
    fun loadMoreSessions() = launch { client.loadMoreSessions() }
    fun reload() = launch { client.loadDomainData() }

    fun connectNewServer(url: String, token: String?) = launch {
        settings.addServer(url, token)
        // addServer 已设为活跃；让 client 连接
        settings.activeServerFlow.firstOrNull()?.let { client.connectTo(it.toProfile()) }
    }

    fun switchServer(server: StoredServer) = launch {
        settings.setActiveServer(server.id)
        client.connectTo(server.toProfile())
    }

    fun removeServer(server: StoredServer) = launch { settings.removeServer(server.id) }

    fun submitServerToken(token: String) = launch { client.submitServerToken(token) }

    fun respondApproval(sessionId: String, approvalId: String, decision: String, scope: String? = null, feedback: String? = null, selectedLabel: String? = null) =
        launch { client.respondApproval(sessionId, approvalId, decision, scope, feedback, selectedLabel) }

    fun respondQuestion(sessionId: String, questionId: String, answers: Map<String, WireQuestionAnswer>) =
        launch { client.respondQuestion(sessionId, questionId, answers) }

    fun dismissQuestion(sessionId: String, questionId: String) =
        launch { client.dismissQuestion(sessionId, questionId) }

    fun renameSession(id: String, title: String) = launch { client.renameSession(id, title) }
    fun archiveSession(id: String) = launch { client.archiveSession(id) }
    fun restoreSession(id: String) = launch { client.restoreSession(id) }
    fun forkSession(id: String) = launch { client.forkSession(id) }
    fun compactSession(id: String) = launch { client.compactSession(id) }
    fun undoSession(id: String) = launch { client.undoSession(id) }

    fun createWorkspace(root: String, name: String?) = launch { client.createWorkspace(root, name) }
    fun renameWorkspace(id: String, name: String) = launch { client.renameWorkspace(id, name) }
    fun deleteWorkspace(id: String) = launch { client.deleteWorkspace(id) }

    fun browseFs(path: String?, onResult: (com.kimi.app.data.wire.WireFsBrowseResult) -> Unit, onError: (String) -> Unit) =
        launch {
            val result = client.fsBrowse(path)
            if (result != null) onResult(result) else onError("无法读取目录")
        }

    fun exportSession(context: android.content.Context, id: String, title: String, onDone: (String?) -> Unit) =
        launch { onDone(client.exportSession(context, id, title)) }

    // ---- 任务 / 文件系统 ----

    fun loadTaskDetail(sessionId: String, taskId: String, onResult: (com.kimi.app.data.wire.WireTask?) -> Unit) =
        launch { onResult(client.getTaskDetail(sessionId, taskId)) }

    fun cancelTask(sessionId: String, taskId: String) = launch { client.cancelTask(sessionId, taskId) }

    fun fsList(sessionId: String, path: String?, depth: Int, onResult: (com.kimi.app.data.wire.WireFsListResult?) -> Unit) =
        launch { onResult(client.fsList(sessionId, path, depth)) }

    fun fsRead(sessionId: String, path: String, onResult: (com.kimi.app.data.wire.WireFsReadResult?) -> Unit) =
        launch { onResult(client.fsRead(sessionId, path)) }

    fun fsSearch(sessionId: String, query: String, onResult: (com.kimi.app.data.wire.WireFsSearchResult?) -> Unit) =
        launch { onResult(client.fsSearch(sessionId, query)) }

    fun fsGrep(sessionId: String, pattern: String, onResult: (com.kimi.app.data.wire.WireFsGrepResult?) -> Unit) =
        launch { onResult(client.fsGrep(sessionId, pattern)) }

    fun fsGitStatus(sessionId: String, onResult: (com.kimi.app.data.wire.WireGitStatus?) -> Unit) =
        launch { onResult(client.fsGitStatus(sessionId)) }

    fun fsDiff(sessionId: String, path: String, onResult: (String?) -> Unit) =
        launch { onResult(client.fsDiff(sessionId, path)?.diff) }

    fun listSkills(sessionId: String, onResult: (List<com.kimi.app.data.wire.WireSkill>) -> Unit) =
        launch { onResult(client.listSkills(sessionId)) }

    fun activateSkill(sessionId: String, skillName: String, args: String?) =
        launch { client.activateSkill(sessionId, skillName, args) }

    fun uploadFile(bytes: ByteArray, name: String, mediaType: String, onResult: (com.kimi.app.data.wire.WireFileMeta?) -> Unit) =
        launch { onResult(client.uploadFile(bytes, name, mediaType)) }

    fun setSessionModel(sessionId: String, model: String) = launch { client.setSessionModel(sessionId, model) }
    fun setSessionProfile(sessionId: String, thinking: String? = null, permissionMode: String? = null, planMode: Boolean? = null) =
        launch { client.setSessionProfile(sessionId, thinking, permissionMode, planMode) }

    /** 切换计划模式（读取当前 liveStatus 后取反） */
    fun togglePlanMode(sessionId: String) {
        val current = state.value.liveStatusBySession[sessionId]?.planMode == true
        setSessionProfile(sessionId, planMode = !current)
        notice(if (current) "已关闭计划模式" else "已开启计划模式")
    }

    /** 循环切换思考级别：off → low → medium → high →（模型支持 max 时继续）→ off */
    fun cycleThinking(sessionId: String) {
        val st = state.value
        val live = st.liveStatusBySession[sessionId]
        val model = live?.model?.takeIf { it.isNotBlank() }
            ?: st.activeSession?.agent_config?.model
            ?: st.config?.default_model
        val wireModel = st.models.firstOrNull { it.model == model }
        val levels = (listOf("off") + (wireModel?.support_efforts ?: listOf("low", "medium", "high"))).distinct()
        val current = live?.thinkingLevel?.takeIf { it.isNotBlank() } ?: wireModel?.default_effort ?: "off"
        val next = levels[(levels.indexOf(current) + 1).mod(levels.size)]
        setSessionProfile(sessionId, thinking = next)
        notice("思考级别：$next")
    }

    fun setTheme(mode: ThemeMode) = launch { settings.setThemeMode(mode) }
    fun dismissNotice(id: Long) = client.dismissNotice(id)
    fun logout() = launch { client.oauthLogout() }
    fun toggleFavoriteModel(key: String) = launch { settings.toggleFavoriteModel(key) }

    /** 操作反馈提示（导出成功等） */
    fun notice(message: String) {
        client.postNotice(message)
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AppViewModel(container.client, container.settings) as T
        }
    }
}

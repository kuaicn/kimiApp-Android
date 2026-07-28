package com.kimi.app.data.store

import com.kimi.app.core.SettingsStore
import com.kimi.app.core.util.long
import com.kimi.app.core.util.str
import com.kimi.app.data.api.ApiException
import com.kimi.app.data.api.KimiApi
import com.kimi.app.data.api.SERVER_AUTH_UNAUTHORIZED_CODE
import com.kimi.app.data.api.ServerProfile
import com.kimi.app.data.api.buildOkHttpClient
import com.kimi.app.data.api.unwrap
import com.kimi.app.data.api.unwrapOrNull
import com.kimi.app.data.api.wireJson
import com.kimi.app.data.wire.EvApprovalExpired
import com.kimi.app.data.wire.EvApprovalResolved
import com.kimi.app.data.wire.EvAssistantDelta
import com.kimi.app.data.wire.EvGoalUpdated
import com.kimi.app.data.wire.EvMessageCreated
import com.kimi.app.data.wire.EvMessageUpdated
import com.kimi.app.data.wire.EvQuestionAnswered
import com.kimi.app.data.wire.EvQuestionDismissed
import com.kimi.app.data.wire.EvSessionCreated
import com.kimi.app.data.wire.EvSessionDeleted
import com.kimi.app.data.wire.EvSessionUpdated
import com.kimi.app.data.wire.EvSessionUsageUpdated
import com.kimi.app.data.wire.EvSessionWorkChanged
import com.kimi.app.data.wire.EvTaskCompleted
import com.kimi.app.data.wire.EvTaskCreated
import com.kimi.app.data.wire.EvTaskProgress
import com.kimi.app.data.wire.EvToolOutput
import com.kimi.app.data.wire.EvToolProgress
import com.kimi.app.data.wire.EvWorkspaceChanged
import com.kimi.app.data.wire.EvWorkspaceDeleted
import com.kimi.app.data.wire.WireAgentConfig
import com.kimi.app.data.wire.WireApprovalRequest
import com.kimi.app.data.wire.WireApprovalResponse
import com.kimi.app.data.wire.WireCompactRequest
import com.kimi.app.data.wire.WireConfig
import com.kimi.app.data.wire.WireCreateSessionRequest
import com.kimi.app.data.wire.WireCreateWorkspaceRequest
import com.kimi.app.data.wire.WireForkRequest
import com.kimi.app.data.wire.WireMessageContent
import com.kimi.app.data.wire.WireOAuthLoginPollResult
import com.kimi.app.data.wire.WireOAuthLoginStartResult
import com.kimi.app.data.wire.WirePromptSubmission
import com.kimi.app.data.wire.WireQuestionAnswer
import com.kimi.app.data.wire.WireQuestionRequest
import com.kimi.app.data.wire.WireQuestionResponse
import com.kimi.app.data.wire.WireRenameWorkspaceRequest
import com.kimi.app.data.wire.WireSessionCursor
import com.kimi.app.data.wire.WireSessionSnapshot
import com.kimi.app.data.wire.WireUndoRequest
import com.kimi.app.data.wire.WireUpdateSessionProfileRequest
import com.kimi.app.data.ws.KimiSocket
import com.github.f4b6a3.ulid.UlidCreator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.atomic.AtomicLong

/**
 * 总协调器（≈ kimi-web 的 useKimiWebClient + daemon/client.ts）：
 * REST 加载流水线、快照同步、WS 事件分派、全部写操作。
 * 单例持有于 AppContainer，与 Activity 生命周期无关。
 */
class KimiClient(
    private val settings: SettingsStore,
    private val appScope: CoroutineScope,
) : KimiSocket.Handler {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state

    private val noticeSeq = AtomicLong(1)

    var profile: ServerProfile? = null
        private set
    private var clientId: String = ""
    private var okHttp: OkHttpClient? = null
    private var api: KimiApi? = null
    private var socket: KimiSocket? = null
    private val projector = AgentProjector()

    /** 会话订阅 LRU（上限 4，活跃会话不淘汰） */
    private val subscriptionLru = ArrayDeque<String>()

    /** 首发会话创建守卫（防止创建+首发窗口内重复点击产生多个空会话） */
    private val startingFirstPrompt = mutableSetOf<String>()

    private fun update(f: (AppState) -> AppState) = _state.update(f)

    private fun notice(message: String, isError: Boolean = false) {
        applyEvent(AppEvent.NoticeAdded(AppNotice(noticeSeq.getAndIncrement(), message, isError)))
    }

    /** UI 层的操作反馈（导出成功等） */
    fun postNotice(message: String) = notice(message)

    fun dismissNotice(id: Long) {
        update { it.copy(notices = it.notices.filterNot { n -> n.id == id }) }
    }

    // -----------------------------------------------------------------------
    // 启动 / 连接
    // -----------------------------------------------------------------------

    /** 应用入口：读取持久化服务器配置；有则连接加载，无则停在连接页 */
    suspend fun start() {
        clientId = settings.clientId()
        val active = settings.activeServerFlow.firstOrNull()
        if (active != null) {
            connectTo(active.toProfile())
        }
    }

    /** 切换/连接服务器：重建 HTTP/WS 客户端并全量加载 */
    suspend fun connectTo(profile: ServerProfile) {
        this.profile = profile
        socket?.disconnect()
        socket = null
        update {
            // 换服务器即清空全部领域状态，回到加载页
            AppState(
                connection = ConnectionState.CONNECTING,
                serverAuthRequired = false,
            )
        }
        rebuildHttp(profile)
        load()
    }

    private fun rebuildHttp(profile: ServerProfile) {
        val client = buildOkHttpClient({ this.profile }, { clientId })
        okHttp = client
        api = Retrofit.Builder()
            .baseUrl(profile.apiBaseUrl)
            .client(client)
            .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KimiApi::class.java)
    }

    fun okHttpClient(): OkHttpClient? = okHttp

    /** 目录浏览（添加工作区用）。注意："fs:browse" 首段含冒号，必须传完整 URL（Retrofit 会把首段冒号误判为 scheme） */
    suspend fun fsBrowse(path: String?): com.kimi.app.data.wire.WireFsBrowseResult? = try {
        val base = profile?.apiBaseUrl ?: return null
        api?.fsBrowse("${base}fs:browse", path)?.unwrap()
    } catch (e: Exception) {
        android.util.Log.w("KimiClient", "fsBrowse($path) failed", e)
        null
    }

    suspend fun fsHome(): com.kimi.app.data.wire.WireFsHomeResult? = try {
        val base = profile?.apiBaseUrl ?: return null
        api?.fsHome("${base}fs:home")?.unwrap()
    } catch (e: Exception) {
        null
    }

    /** 导出会话 zip 到下载目录，返回文件名 */
    suspend fun exportSession(context: android.content.Context, sessionId: String, title: String): String? {
        val http = okHttp ?: return null
        val prof = profile ?: return null
        return try {
            com.kimi.app.data.api.FileTransfer(http, prof).exportSessionToDownloads(context, sessionId, title)
        } catch (e: Exception) {
            handleError(e, "导出失败")
            null
        }
    }

    /** 上传附件（图片/文件），返回文件元信息 */
    suspend fun uploadFile(bytes: ByteArray, name: String, mediaType: String): com.kimi.app.data.wire.WireFileMeta? {
        val http = okHttp ?: return null
        val prof = profile ?: return null
        return try {
            com.kimi.app.data.api.FileTransfer(http, prof).uploadFile(bytes, name, mediaType)
        } catch (e: Exception) {
            handleError(e, "上传失败")
            null
        }
    }

    // -----------------------------------------------------------------------
    // 任务 / 文件系统（面板数据源，读操作失败静默返回 null）
    // -----------------------------------------------------------------------

    suspend fun getTaskDetail(sessionId: String, taskId: String): com.kimi.app.data.wire.WireTask? = try {
        api?.getTask(sessionId, taskId, withOutput = true, outputBytes = 64 * 1024)?.unwrap()
    } catch (e: Exception) {
        null
    }

    suspend fun cancelTask(sessionId: String, taskId: String) {
        try {
            api?.cancelTask(sessionId, taskId)?.unwrap()
        } catch (e: Exception) {
            handleError(e, "取消任务失败")
        }
    }

    suspend fun fsList(sessionId: String, path: String?, depth: Int): com.kimi.app.data.wire.WireFsListResult? = try {
        api?.fsList(sessionId, com.kimi.app.data.wire.WireFsListRequest(path, depth, include_git_status = true))?.unwrap()
    } catch (e: Exception) {
        null
    }

    suspend fun fsRead(sessionId: String, path: String, length: Int? = 256 * 1024): com.kimi.app.data.wire.WireFsReadResult? = try {
        api?.fsRead(sessionId, com.kimi.app.data.wire.WireFsReadRequest(path, length = length))?.unwrap()
    } catch (e: Exception) {
        null
    }

    suspend fun fsSearch(sessionId: String, query: String): com.kimi.app.data.wire.WireFsSearchResult? = try {
        api?.fsSearch(sessionId, com.kimi.app.data.wire.WireFsSearchRequest(query, limit = 20))?.unwrap()
    } catch (e: Exception) {
        null
    }

    suspend fun fsGrep(sessionId: String, pattern: String): com.kimi.app.data.wire.WireFsGrepResult? = try {
        api?.fsGrep(sessionId, com.kimi.app.data.wire.WireFsGrepRequest(pattern))?.unwrap()
    } catch (e: Exception) {
        null
    }

    suspend fun fsGitStatus(sessionId: String): com.kimi.app.data.wire.WireGitStatus? = try {
        api?.fsGitStatus(sessionId, com.kimi.app.data.wire.WireFsGitStatusRequest())?.unwrap()
    } catch (e: Exception) {
        null
    }

    suspend fun fsDiff(sessionId: String, path: String): com.kimi.app.data.wire.WireFsDiffResult? = try {
        api?.fsDiff(sessionId, com.kimi.app.data.wire.WireFsDiffRequest(path))?.unwrap()
    } catch (e: Exception) {
        null
    }

    suspend fun listSkills(sessionId: String): List<com.kimi.app.data.wire.WireSkill> = try {
        api?.listSessionSkills(sessionId)?.unwrap()?.skills ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    suspend fun activateSkill(sessionId: String, skillName: String, args: String?) {
        try {
            api?.activateSkill(sessionId, skillName, com.kimi.app.data.wire.WireSkillActivateRequest(args))?.unwrap()
        } catch (e: Exception) {
            handleError(e, "技能激活失败")
        }
    }

    suspend fun steerPrompts(sessionId: String, promptIds: List<String>) {
        try {
            api?.steerPrompts(sessionId, com.kimi.app.data.wire.WirePromptSteerRequest(promptIds))?.unwrap()
        } catch (e: Exception) {
            handleError(e, "插队失败")
        }
    }

    /** 按会话键读取/保存输入草稿（持久化到 DataStore） */
    suspend fun settingsDraft(key: String): String =
        settings.draftsFlow.firstOrNull()?.get(key) ?: ""

    suspend fun saveDraft(key: String, text: String) = settings.saveDraft(key, text)

    /** 图片/附件 URL（Coil 请求走同一 OkHttp，自带 Bearer 头） */
    fun fileUrl(fileId: String): String = "${profile?.apiBaseUrl}files/$fileId"

    fun fsDownloadUrl(sessionId: String, path: String): String {
        val encoded = java.net.URLEncoder.encode(path, "UTF-8").replace("%2F", "/")
        return "${profile?.apiBaseUrl}sessions/$sessionId/fs/$encoded:download"
    }

    // -----------------------------------------------------------------------
    // 加载流水线（client.load 移植）
    // -----------------------------------------------------------------------

    suspend fun load() {
        val api = this.api ?: return
        update { it.copy(connection = ConnectionState.CONNECTING) }
        try {
            // healthz：最多 10 次，1s 退避
            var lastError: Exception? = null
            var healthy = false
            repeat(10) { attempt ->
                if (healthy) return@repeat
                try {
                    api.healthz().unwrap()
                    healthy = true
                } catch (e: Exception) {
                    lastError = e
                    delay(1000L + attempt * 200L)
                }
            }
            if (!healthy) throw lastError ?: ApiException(-1, "无法连接服务器")

            // auth 就绪检查：未就绪 → 轮询直至就绪（用户可能在 OAuth 登录）
            val auth = api.auth().unwrap()
            if (!auth.ready) {
                update { it.copy(authReady = false, initialized = true, connection = ConnectionState.CONNECTED) }
                ensureSocket()
                pollAuthUntilReady()
                return
            }
            loadDomainData()
        } catch (e: Exception) {
            handleError(e, "连接服务器失败")
            update { it.copy(connection = ConnectionState.DISCONNECTED, initialized = true) }
        }
    }

    private suspend fun pollAuthUntilReady() {
        val api = this.api ?: return
        appScope.launch {
            while (true) {
                delay(2000)
                try {
                    if (api.auth().unwrap().ready) {
                        update { it.copy(authReady = true) }
                        loadDomainData()
                        return@launch
                    }
                } catch (e: Exception) {
                    if (e is ApiException && e.isAuthFailure) return@launch // 等用户重新输入 token
                    // 其余错误继续轮询
                }
            }
        }
    }

    /** auth 就绪后调用（OAuth 登录完成也走这里） */
    suspend fun loadDomainData() {
        val api = this.api ?: return
        try {
            val meta = runCatching { api.meta().unwrap() }.getOrNull()
            val sessions = api.listSessions(pageSize = 50).unwrap()
            val workspaces = runCatching { api.listWorkspaces().unwrap().items }.getOrDefault(emptyList())
            val models = runCatching { api.listModels().unwrap().items }.getOrDefault(emptyList())
            val providers = runCatching { api.listProviders().unwrap().items }.getOrDefault(emptyList())
            val config = runCatching { api.config().unwrap() }.getOrNull()
            ensureSocket()
            update {
                it.copy(
                    initialized = true,
                    authReady = true,
                    connection = ConnectionState.CONNECTED,
                    serverMeta = meta,
                    sessions = sessions.items,
                    workspaces = workspaces,
                    models = models,
                    providers = providers,
                    config = config,
                )
            }
            // 恢复上次打开的会话
            val lastId = settings.lastSessionId()
            if (lastId != null && sessions.items.any { s -> s.id == lastId }) {
                selectSession(lastId)
            }
        } catch (e: Exception) {
            handleError(e, "加载数据失败")
            update { it.copy(initialized = true) }
        }
    }

    private fun ensureSocket() {
        val profile = this.profile ?: return
        if (socket != null) return
        val s = KimiSocket(okHttp ?: return, appScope)
        s.handler = this
        socket = s
        s.connect(profile, clientId)
    }

    // -----------------------------------------------------------------------
    // 会话选择与快照同步
    // -----------------------------------------------------------------------

    suspend fun selectSession(id: String?) {
        if (id == null) {
            update { it.copy(activeSessionId = null) }
            settings.setLastSession(null)
            return
        }
        update { it.copy(activeSessionId = id) }
        settings.setLastSession(id)
        if (state.value.messagesBySession[id] == null) {
            try {
                val snapshot = api?.getSessionSnapshot(id)?.unwrap() ?: return
                applySnapshot(id, snapshot)
            } catch (e: Exception) {
                handleError(e, "加载会话失败")
                return
            }
        }
        subscribeSession(id)
        refreshSessionRuntime(id)
    }

    /** 会话进入草稿模式：有工作区上下文但无后端会话，首发消息时才创建 */
    fun openDraft(workspaceId: String?) {
        update { it.copy(activeSessionId = null, activeWorkspaceId = workspaceId) }
        appScope.launch { settings.setLastSession(null) }
    }

    private fun applySnapshot(id: String, snapshot: WireSessionSnapshot) {
        val messages = snapshot.messages.items.map { it.toApp() }
        update {
            it.copy(
                messagesBySession = it.messagesBySession + (id to messages),
                hasMoreMessagesBySession = it.hasMoreMessagesBySession + (id to snapshot.messages.has_more),
                approvalsBySession = it.approvalsBySession + (id to snapshot.pending_approvals),
                questionsBySession = it.questionsBySession + (id to snapshot.pending_questions),
                tasksBySession = it.tasksBySession + (id to (snapshot.subagents ?: emptyList()).map { t -> t.toApp() }),
                lastSeqBySession = it.lastSeqBySession + (id to snapshot.as_of_seq),
                epochBySession = it.epochBySession + (id to snapshot.epoch),
                sessions = if (it.sessions.any { s -> s.id == id }) {
                    it.sessions.map { s -> if (s.id == id) snapshot.session else s }
                } else {
                    listOf(snapshot.session) + it.sessions
                },
            )
        }
        // in-flight 播种：流式中间态立即渲染
        snapshot.in_flight_turn?.let { turn ->
            projector.seedInFlight(id, turn).forEach { applyEvent(it) }
        } ?: projector.reset(id)
    }

    private fun subscribeSession(id: String) {
        // LRU：先移到队尾
        subscriptionLru.remove(id)
        subscriptionLru.addLast(id)
        val cursor = WireSessionCursor(
            seq = state.value.lastSeqBySession[id] ?: 0,
            epoch = state.value.epochBySession[id],
        )
        socket?.subscribe(id, cursor)
        // 超上限淘汰最旧（活跃会话除外）
        while (subscriptionLru.size > 4) {
            val victim = subscriptionLru.firstOrNull { it != state.value.activeSessionId } ?: break
            subscriptionLru.remove(victim)
            socket?.unsubscribe(victim)
        }
    }

    /** 选中会话后拉取运行时状态/任务/目标（镜像 web 的 selectSession 副作用） */
    private fun refreshSessionRuntime(id: String) {
        val api = this.api ?: return
        appScope.launch {
            runCatching { api.getSessionStatus(id).unwrap() }.onSuccess { st ->
                update {
                    it.copy(
                        liveStatusBySession = it.liveStatusBySession + (id to SessionLiveStatus(
                            model = st.model,
                            thinkingLevel = st.thinking_level,
                            permission = st.permission,
                            planMode = st.plan_mode,
                            swarmMode = st.swarm_mode,
                            contextTokens = st.context_tokens,
                            maxContextTokens = st.max_context_tokens,
                        )),
                    )
                }
            }
            runCatching { api.listTasks(id).unwrap() }.onSuccess { res ->
                update {
                    it.copy(tasksBySession = it.tasksBySession + (id to res.items.map { t -> t.toApp() }))
                }
            }
            runCatching { api.getSessionGoal(id).unwrapOrNull() }.onSuccess { goal ->
                update { it.copy(goalBySession = if (goal == null) it.goalBySession - id else it.goalBySession + (id to goal)) }
            }
        }
    }

    suspend fun loadOlderMessages(sessionId: String) {
        val api = this.api ?: return
        val oldest = state.value.messagesBySession[sessionId]
            ?.firstOrNull { !it.id.startsWith(OPTIMISTIC_ID_PREFIX) && !it.id.startsWith("stream_") }
            ?: return
        try {
            val page = api.listMessages(sessionId, beforeId = oldest.id, pageSize = 50).unwrap()
            val older = page.items.map { it.toApp() }
            update {
                val current = it.messagesBySession[sessionId] ?: emptyList()
                it.copy(
                    messagesBySession = it.messagesBySession + (sessionId to (older + current)),
                    hasMoreMessagesBySession = it.hasMoreMessagesBySession + (sessionId to page.has_more),
                )
            }
        } catch (e: Exception) {
            handleError(e, "加载历史消息失败")
        }
    }

    suspend fun loadMoreSessions() {
        val api = this.api ?: return
        val oldest = state.value.sessions.lastOrNull() ?: return
        try {
            val page = api.listSessions(beforeId = oldest.id, pageSize = 50).unwrap()
            update {
                val known = it.sessions.map { s -> s.id }.toSet()
                it.copy(sessions = it.sessions + page.items.filterNot { s -> s.id in known })
            }
        } catch (e: Exception) {
            handleError(e, "加载更多会话失败")
        }
    }

    // -----------------------------------------------------------------------
    // 提示词
    // -----------------------------------------------------------------------

    suspend fun sendPrompt(text: String, attachments: List<WireMessageContent> = emptyList()) {
        val api = this.api ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return

        var sid = state.value.activeSessionId
        if (sid == null) {
            // 草稿首发：先建会话（daemon 要求 metadata 始终为对象；模型显式携带，否则回合报 model.not_configured）
            val wsId = state.value.activeWorkspaceId
            val guardKey = wsId ?: "__default__"
            if (!startingFirstPrompt.add(guardKey)) return // 创建+首发窗口内的重复提交直接丢弃
            try {
                // 复用该工作区最新的空会话（服务端已有空会话时不新建，避免空会话堆积）
                val cwd = state.value.workspaces.firstOrNull { it.id == wsId }?.root
                val reusable = state.value.sessions
                    .filter { !it.archived && it.message_count == 0 }
                    .filter { s ->
                        wsId == null ||
                            s.workspace_id == wsId ||
                            (s.workspace_id == null && cwd != null &&
                                (s.metadata["cwd"]?.jsonPrimitive?.contentOrNull == cwd))
                    }
                    .maxByOrNull { it.updated_at }
                if (reusable != null) {
                    selectSession(reusable.id)
                    sid = reusable.id
                } else {
                    val session = api.createSession(
                        WireCreateSessionRequest(
                            metadata = JsonObject(
                                if (cwd != null) {
                                    mapOf("cwd" to kotlinx.serialization.json.JsonPrimitive(cwd))
                                } else {
                                    emptyMap()
                                },
                            ),
                            workspace_id = wsId,
                            agent_config = state.value.config?.default_model
                                ?.takeIf { it.isNotBlank() }
                                ?.let { WireAgentConfig(model = it) },
                        ),
                    ).unwrap()
                    applyEvent(AppEvent.SessionCreated(session))
                    sid = session.id
                    update { it.copy(activeSessionId = sid) }
                    settings.setLastSession(sid)
                    subscribeSession(sid)
                }
            } catch (e: Exception) {
                handleError(e, "创建会话失败")
                return
            } finally {
                startingFirstPrompt.remove(guardKey)
            }
        }
        val sessionId = sid

        // daemon 要求每个 prompt 显式携带 model/thinking（kimi-web 同）：会话模型 → 实时模型 → 默认模型
        val session = state.value.sessions.firstOrNull { it.id == sessionId }
        val live = state.value.liveStatusBySession[sessionId]
        val model = session?.agent_config?.model?.takeIf { it.isNotBlank() }
            ?: live?.model?.takeIf { it.isNotBlank() }
            ?: state.value.config?.default_model?.takeIf { it.isNotBlank() }
        val thinking = live?.thinkingLevel?.takeIf { it.isNotBlank() }
        val permissionMode = live?.permission?.takeIf { it.isNotBlank() }

        val content = attachments + WireMessageContent.Text(trimmed)
        val optimisticId = OPTIMISTIC_ID_PREFIX + UlidCreator.getUlid().toString().lowercase()
        val optimistic = AppMessage(
            id = optimisticId,
            sessionId = sessionId,
            role = AppRole.USER,
            content = content.map { it.toApp() },
            createdAt = java.time.Instant.now().toString(),
        )
        applyEvent(AppEvent.MessageCreated(optimistic))

        try {
            val result = api.submitPrompt(
                sessionId,
                WirePromptSubmission(
                    content = content,
                    model = model,
                    thinking = thinking,
                    permission_mode = permissionMode,
                    plan_mode = live?.planMode?.takeIf { it },
                ),
            ).unwrap()
            // 给乐观消息打上 promptId（回声对账依赖）
            update {
                val msgs = it.messagesBySession[sessionId] ?: return@update it
                it.copy(
                    messagesBySession = it.messagesBySession + (sessionId to msgs.map { m ->
                        if (m.id == optimisticId) m.copy(promptId = result.prompt_id) else m
                    }),
                )
            }
            if (result.status == "queued") {
                update {
                    val q = it.queuedBySession[sessionId] ?: emptyList()
                    it.copy(
                        queuedBySession = it.queuedBySession +
                            (sessionId to (q + QueuedPrompt(result.prompt_id, trimmed))),
                    )
                }
            }
        } catch (e: Exception) {
            // 提交失败：撤下乐观消息
            update {
                val msgs = it.messagesBySession[sessionId] ?: emptyList()
                it.copy(
                    messagesBySession = it.messagesBySession +
                        (sessionId to msgs.filterNot { m -> m.id == optimisticId }),
                )
            }
            handleError(e, "发送失败")
        }
    }

    suspend fun abortSession(sessionId: String) {
        try {
            api?.abortSession(sessionId)?.unwrap()
        } catch (e: Exception) {
            handleError(e, "中断失败")
        }
    }

    // -----------------------------------------------------------------------
    // 审批 / 问题
    // -----------------------------------------------------------------------

    suspend fun respondApproval(
        sessionId: String,
        approvalId: String,
        decision: String,
        scope: String? = null,
        feedback: String? = null,
        selectedLabel: String? = null,
    ) {
        try {
            api?.respondApproval(
                sessionId,
                approvalId,
                WireApprovalResponse(decision, scope, feedback, selectedLabel),
            )?.unwrap()
            applyEvent(AppEvent.ApprovalFinished(sessionId, approvalId))
        } catch (e: Exception) {
            handleError(e, "审批提交失败")
        }
    }

    suspend fun respondQuestion(
        sessionId: String,
        questionId: String,
        answers: Map<String, WireQuestionAnswer>,
    ) {
        try {
            api?.respondQuestion(sessionId, questionId, WireQuestionResponse(answers, method = "click"))?.unwrap()
            applyEvent(AppEvent.QuestionFinished(sessionId, questionId))
        } catch (e: Exception) {
            handleError(e, "回答提交失败")
        }
    }

    suspend fun dismissQuestion(sessionId: String, questionId: String) {
        try {
            api?.dismissQuestion(sessionId, questionId)?.unwrap()
            applyEvent(AppEvent.QuestionFinished(sessionId, questionId))
        } catch (e: ApiException) {
            // 40909：已关闭，直接按关闭处理
            if (e.code == 40909) applyEvent(AppEvent.QuestionFinished(sessionId, questionId))
            else handleError(e, "关闭问题失败")
        } catch (e: Exception) {
            handleError(e, "关闭问题失败")
        }
    }

    // -----------------------------------------------------------------------
    // 会话管理
    // -----------------------------------------------------------------------

    suspend fun renameSession(id: String, title: String) {
        try {
            val session = api?.updateSessionProfile(id, WireUpdateSessionProfileRequest(title = title))?.unwrap()
            if (session != null) applyEvent(AppEvent.SessionUpdated(session))
        } catch (e: Exception) {
            handleError(e, "重命名失败")
        }
    }

    suspend fun archiveSession(id: String) {
        try {
            api?.archiveSession(id)?.unwrap()
            update { st ->
                st.copy(
                    sessions = st.sessions.map { if (it.id == id) it.copy(archived = true) else it },
                    activeSessionId = if (st.activeSessionId == id) null else st.activeSessionId,
                )
            }
        } catch (e: Exception) {
            handleError(e, "归档失败")
        }
    }

    suspend fun restoreSession(id: String) {
        try {
            val session = api?.restoreSession(id)?.unwrap()
            if (session != null) applyEvent(AppEvent.SessionUpdated(session))
        } catch (e: Exception) {
            handleError(e, "恢复失败")
        }
    }

    suspend fun forkSession(id: String) {
        try {
            val forked = api?.forkSession(id, WireForkRequest())?.unwrap() ?: return
            applyEvent(AppEvent.SessionCreated(forked))
            selectSession(forked.id)
        } catch (e: Exception) {
            handleError(e, "Fork 失败")
        }
    }

    suspend fun compactSession(id: String, instruction: String? = null) {
        try {
            api?.compactSession(id, WireCompactRequest(instruction))?.unwrap()
            notice("已请求压缩历史")
        } catch (e: Exception) {
            handleError(e, "压缩失败")
        }
    }

    suspend fun undoSession(id: String, count: Int = 1) {
        try {
            api?.undoSession(id, WireUndoRequest(count))?.unwrap()
            // 服务端重写历史后客户端重新快照
            resyncSession(id)
        } catch (e: Exception) {
            handleError(e, "撤销失败")
        }
    }

    suspend fun deleteSessionNotice() = Unit // 协议无删除会话端点（归档即删除路径）

    // -----------------------------------------------------------------------
    // 工作区
    // -----------------------------------------------------------------------

    suspend fun createWorkspace(root: String, name: String? = null) {
        try {
            val ws = api?.createWorkspace(WireCreateWorkspaceRequest(root, name))?.unwrap() ?: return
            applyEvent(AppEvent.WorkspaceUpserted(ws))
            update { it.copy(activeWorkspaceId = ws.id) }
        } catch (e: Exception) {
            handleError(e, "添加工作区失败")
        }
    }

    suspend fun renameWorkspace(id: String, name: String) {
        try {
            val ws = api?.renameWorkspace(id, WireRenameWorkspaceRequest(name))?.unwrap() ?: return
            applyEvent(AppEvent.WorkspaceUpserted(ws))
        } catch (e: Exception) {
            handleError(e, "重命名工作区失败")
        }
    }

    suspend fun deleteWorkspace(id: String) {
        try {
            api?.deleteWorkspace(id)?.unwrap()
            applyEvent(AppEvent.WorkspaceDeleted(id))
        } catch (e: Exception) {
            handleError(e, "移除工作区失败")
        }
    }

    // -----------------------------------------------------------------------
    // 模型 / 模式
    // -----------------------------------------------------------------------

    suspend fun setSessionModel(sessionId: String, model: String) {
        try {
            val session = api?.updateSessionProfile(
                sessionId,
                WireUpdateSessionProfileRequest(agent_config = WireAgentConfig(model = model)),
            )?.unwrap()
            if (session != null) applyEvent(AppEvent.SessionUpdated(session))
            update {
                val live = it.liveStatusBySession[sessionId] ?: SessionLiveStatus()
                it.copy(liveStatusBySession = it.liveStatusBySession + (sessionId to live.copy(model = model)))
            }
        } catch (e: Exception) {
            handleError(e, "切换模型失败")
        }
    }

    suspend fun setSessionProfile(
        sessionId: String,
        thinking: String? = null,
        permissionMode: String? = null,
        planMode: Boolean? = null,
    ) {
        try {
            val session = api?.updateSessionProfile(
                sessionId,
                WireUpdateSessionProfileRequest(
                    agent_config = WireAgentConfig(
                        thinking = thinking,
                        permission_mode = permissionMode,
                        plan_mode = planMode,
                    ),
                ),
            )?.unwrap()
            if (session != null) applyEvent(AppEvent.SessionUpdated(session))
            update {
                val live = it.liveStatusBySession[sessionId] ?: SessionLiveStatus()
                it.copy(
                    liveStatusBySession = it.liveStatusBySession + (sessionId to live.copy(
                        thinkingLevel = thinking ?: live.thinkingLevel,
                        permission = permissionMode ?: live.permission,
                        planMode = planMode ?: live.planMode,
                    )),
                )
            }
        } catch (e: Exception) {
            handleError(e, "更新会话设置失败")
        }
    }

    // -----------------------------------------------------------------------
    // OAuth / 服务器认证
    // -----------------------------------------------------------------------

    suspend fun oauthStart(): WireOAuthLoginStartResult? = try {
        api?.oauthLoginStart()?.unwrap()
    } catch (e: Exception) {
        handleError(e, "发起登录失败")
        null
    }

    suspend fun oauthPoll(): WireOAuthLoginPollResult? = try {
        api?.oauthLoginPoll()?.unwrapOrNull()
    } catch (e: Exception) {
        null
    }

    suspend fun oauthCancel() {
        runCatching { api?.oauthLoginCancel()?.unwrap() }
    }

    suspend fun oauthLogout() {
        try {
            api?.oauthLogout()?.unwrap()
            notice("已登出")
        } catch (e: Exception) {
            handleError(e, "登出失败")
        }
    }

    /** 服务器凭据失效后用户重新提交 token */
    suspend fun submitServerToken(token: String) {
        val profile = this.profile ?: return
        settings.updateToken(profile.id, token)
        update { it.copy(serverAuthRequired = false) }
        connectTo(profile.copy(token = token))
    }

    // -----------------------------------------------------------------------
    // WS Handler
    // -----------------------------------------------------------------------

    private fun applyEvent(event: AppEvent) {
        if (event is AppEvent.ResyncRequested) {
            appScope.launch { resyncSession(event.sessionId) }
            return
        }
        update { EventReducer.reduce(it, event) }
    }

    private suspend fun resyncSession(sessionId: String) {
        try {
            val snapshot = api?.getSessionSnapshot(sessionId)?.unwrap() ?: return
            applySnapshot(sessionId, snapshot)
            subscribeSession(sessionId)
        } catch (e: Exception) {
            handleError(e, "重新同步失败")
        }
    }

    override fun onProtocolEvent(type: String, sessionId: String?, seq: Long, payload: JsonObject) {
        val event = mapProtocolEvent(type, sessionId, payload) ?: return
        if (sessionId != null && sessionId.isNotEmpty()) {
            advanceCursor(sessionId, seq)
        }
        applyEvent(event)
    }

    private fun advanceCursor(sessionId: String, seq: Long) {
        if (seq <= 0) return
        val current = state.value.lastSeqBySession[sessionId] ?: 0
        if (seq > current) {
            update { it.copy(lastSeqBySession = it.lastSeqBySession + (sessionId to seq)) }
        }
        socket?.trackCursor(sessionId, seq, state.value.epochBySession[sessionId])
    }

    private inline fun <reified T> decode(payload: JsonObject): T? =
        runCatching { wireJson.decodeFromJsonElement<T>(payload) }.getOrNull()

    private fun mapProtocolEvent(type: String, sessionId: String?, payload: JsonObject): AppEvent? {
        val sid = sessionId ?: return mapGlobalEvent(type, payload)
        return when (type) {
            "event.session.created" -> decode<EvSessionCreated>(payload)?.let { AppEvent.SessionCreated(it.session) }
            "event.session.updated" -> decode<EvSessionUpdated>(payload)?.let { AppEvent.SessionUpdated(it.session) }
            "event.session.deleted" -> decode<EvSessionDeleted>(payload)?.let { AppEvent.SessionDeleted(it.session_id) }
            "event.session.work_changed" -> decode<EvSessionWorkChanged>(payload)?.let {
                AppEvent.SessionWorkChanged(sid, it.busy, it.main_turn_active, it.pending_interaction, it.last_turn_reason)
            }

            "event.session.status_changed" -> {
                // 已弃用：映射为 busy 事实
                val status = payload.str("status") ?: return null
                AppEvent.SessionWorkChanged(sid, status == "running", null, null, null)
            }

            "event.session.usage_updated" -> decode<EvSessionUsageUpdated>(payload)?.let {
                AppEvent.SessionUsageUpdated(sid, it.usage)
            }

            "event.session.history_compacted" -> null // seq 推进即可；UI 由 compaction.* 驱动
            "event.message.created" -> decode<EvMessageCreated>(payload)?.let {
                AppEvent.MessageCreated(it.message.toApp())
            }

            "event.message.updated" -> decode<EvMessageUpdated>(payload)?.let {
                AppEvent.MessageUpdated(sid, it.message_id, it.content.map { c -> c.toApp() })
            }

            "event.assistant.delta" -> decode<EvAssistantDelta>(payload)?.let {
                AppEvent.AssistantDelta(sid, it.message_id, it.content_index, it.delta.text, it.delta.thinking)
            }

            "event.tool.output" -> decode<EvToolOutput>(payload)?.let {
                AppEvent.ToolOutput(sid, it.tool_call_id, it.chunk)
            }

            "event.tool.progress" -> decode<EvToolProgress>(payload)?.let {
                it.message?.takeIf { m -> m.isNotBlank() }?.let { m -> AppEvent.ToolOutput(sid, it.tool_call_id, m) }
            }

            "event.approval.requested" -> decode<WireApprovalRequest>(payload)?.let {
                AppEvent.ApprovalRequested(it)
            }

            "event.approval.resolved" -> decode<EvApprovalResolved>(payload)?.let {
                AppEvent.ApprovalFinished(sid, it.approval_id)
            }

            "event.approval.expired" -> decode<EvApprovalExpired>(payload)?.let {
                AppEvent.ApprovalFinished(sid, it.approval_id)
            }

            "event.question.requested" -> decode<WireQuestionRequest>(payload)?.let {
                AppEvent.QuestionRequested(it)
            }

            "event.question.answered" -> decode<EvQuestionAnswered>(payload)?.let {
                AppEvent.QuestionFinished(sid, it.question_id)
            }

            "event.question.dismissed" -> decode<EvQuestionDismissed>(payload)?.let {
                AppEvent.QuestionFinished(sid, it.question_id)
            }

            "event.task.created" -> decode<EvTaskCreated>(payload)?.let { AppEvent.TaskUpserted(it.task.toApp()) }
            "event.task.progress" -> decode<EvTaskProgress>(payload)?.let {
                AppEvent.TaskProgressed(sid, it.task_id, it.output_chunk)
            }

            "event.task.completed" -> decode<EvTaskCompleted>(payload)?.let {
                AppEvent.TaskFinished(sid, it.task_id, it.status, it.output_preview, it.output_bytes)
            }

            "event.goal.updated" -> decode<EvGoalUpdated>(payload)?.let {
                AppEvent.GoalUpdated(sid, it.snapshot?.takeIf { g -> g.status != "complete" })
            }

            // 已知但无需处理的流式/工具事件（推进 seq）
            "event.assistant.tool_use_started", "event.assistant.tool_use_delta",
            "event.assistant.tool_use_completed", "event.assistant.completed",
            "event.tool.started", "event.tool.completed",
            -> null

            else -> mapGlobalEvent(type, payload)
        }
    }

    /** 全局（非会话作用域）事件 */
    private fun mapGlobalEvent(type: String, payload: JsonObject): AppEvent? = when (type) {
        "event.workspace.created", "event.workspace.updated" ->
            decode<EvWorkspaceChanged>(payload)?.let { AppEvent.WorkspaceUpserted(it.workspace) }

        "event.workspace.deleted" ->
            decode<EvWorkspaceDeleted>(payload)?.let { AppEvent.WorkspaceDeleted(it.workspace_id) }

        "event.config.changed" -> {
            val config = payload["config"]?.let {
                runCatching { wireJson.decodeFromJsonElement(WireConfig.serializer(), it) }.getOrNull()
            }
            if (config != null) {
                update { it.copy(config = config) }
            }
            null
        }

        "event.model_catalog.changed" -> {
            appScope.launch {
                val api = this@KimiClient.api ?: return@launch
                val models = runCatching { api.listModels().unwrap().items }.getOrNull()
                val providers = runCatching { api.listProviders().unwrap().items }.getOrNull()
                update {
                    it.copy(
                        models = models ?: it.models,
                        providers = providers ?: it.providers,
                    )
                }
            }
            null
        }

        else -> null
    }

    override fun onRawEvent(type: String, sessionId: String?, seq: Long, volatile: Boolean, offset: Long?, payload: JsonObject) {
        val sid = sessionId ?: return
        val events = projector.project(type, sid, offset, payload)
        if (!volatile) {
            // volatile 帧不推进水位；其余原始帧是持久帧
            advanceCursor(sid, seq)
        }
        for (event in events) {
            if (event is AppEvent.CompactionCompleted) {
                applyEvent(event.copy(seq = seq))
            } else {
                applyEvent(event)
            }
        }
    }

    override fun onResync(sessionId: String, currentSeq: Long, epoch: String?) {
        update {
            it.copy(
                lastSeqBySession = it.lastSeqBySession + (sessionId to currentSeq),
                epochBySession = if (epoch != null) it.epochBySession + (sessionId to epoch) else it.epochBySession,
            )
        }
        appScope.launch { resyncSession(sessionId) }
    }

    override fun onConnectionState(connected: Boolean) {
        update {
            it.copy(
                connection = if (connected) ConnectionState.CONNECTED else ConnectionState.CONNECTING,
            )
        }
    }

    override fun onError(code: Int, msg: String, fatal: Boolean) {
        if (code == SERVER_AUTH_UNAUTHORIZED_CODE || code == 401) {
            update { it.copy(serverAuthRequired = true) }
            return
        }
        if (msg.isNotBlank()) notice(msg, isError = fatal)
    }

    // -----------------------------------------------------------------------

    private fun handleError(e: Exception, prefix: String) {
        if (e is ApiException && e.isAuthFailure) {
            update { it.copy(serverAuthRequired = true) }
            return
        }
        notice("$prefix：${e.message ?: e.javaClass.simpleName}", isError = true)
    }
}

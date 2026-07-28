package com.kimi.app.data.store

import com.kimi.app.data.wire.WireApprovalRequest
import com.kimi.app.data.wire.WireConfig
import com.kimi.app.data.wire.WireGoalSnapshot
import com.kimi.app.data.wire.WireMeta
import com.kimi.app.data.wire.WireModel
import com.kimi.app.data.wire.WireProvider
import com.kimi.app.data.wire.WireQuestionRequest
import com.kimi.app.data.wire.WireSession
import com.kimi.app.data.wire.WireWorkspace

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * 应用状态树（≈ kimi-web rawState）。不可变，所有变更经 EventReducer / KimiClient setter。
 */
data class AppState(
    val initialized: Boolean = false,
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    /** GET /auth 的就绪状态；false 时展示认证闸门 */
    val authReady: Boolean = true,
    /** 服务器凭据失效（401/40101）→ 弹出服务器认证 */
    val serverAuthRequired: Boolean = false,
    val serverMeta: WireMeta? = null,

    val sessions: List<WireSession> = emptyList(),
    val workspaces: List<WireWorkspace> = emptyList(),
    val activeSessionId: String? = null,
    val activeWorkspaceId: String? = null,

    val messagesBySession: Map<String, List<AppMessage>> = emptyMap(),
    val hasMoreMessagesBySession: Map<String, Boolean> = emptyMap(),
    val approvalsBySession: Map<String, List<WireApprovalRequest>> = emptyMap(),
    val questionsBySession: Map<String, List<WireQuestionRequest>> = emptyMap(),
    val tasksBySession: Map<String, List<AppTask>> = emptyMap(),
    val goalBySession: Map<String, WireGoalSnapshot?> = emptyMap(),
    val lastSeqBySession: Map<String, Long> = emptyMap(),
    val epochBySession: Map<String, String> = emptyMap(),
    val turnActiveBySession: Map<String, Boolean> = emptyMap(),
    val queuedBySession: Map<String, List<QueuedPrompt>> = emptyMap(),
    val liveStatusBySession: Map<String, SessionLiveStatus> = emptyMap(),
    /** ExitPlanMode 的 plan_review 展示留存（toolCallId → plan/path），审批消失后计划仍可见 */
    val planReviewByToolCallId: Map<String, PlanReview> = emptyMap(),
    /** 进行中的历史压缩（sessionId → trigger） */
    val compactionBySession: Map<String, String> = emptyMap(),

    val models: List<WireModel> = emptyList(),
    val providers: List<WireProvider> = emptyList(),
    val config: WireConfig? = null,

    val notices: List<AppNotice> = emptyList(),
) {
    val activeSession: WireSession? get() = sessions.firstOrNull { it.id == activeSessionId }

    data class PlanReview(val plan: String, val path: String? = null)
}

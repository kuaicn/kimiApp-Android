@file:Suppress("PropertyName")
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.kimi.app.data.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// 本文件 DTO 与 apps/kimi-web/src/api/daemon/wire.ts 一一对应，字段保持 wire 上的 snake_case。

// ---------------------------------------------------------------------------
// Envelope & Page
// ---------------------------------------------------------------------------

@Serializable
data class WireEnvelope<T>(
    val code: Int,
    val msg: String = "",
    val data: T? = null,
    val request_id: String = "",
    val details: JsonElement? = null,
)

@Serializable
data class WirePage<T>(
    val items: List<T> = emptyList(),
    val has_more: Boolean = false,
)

// ---------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------

@Serializable
data class WireSessionUsage(
    val input_tokens: Long = 0,
    val output_tokens: Long = 0,
    val cache_read_tokens: Long = 0,
    val cache_creation_tokens: Long = 0,
    val total_cost_usd: Double = 0.0,
    val context_tokens: Long = 0,
    val context_limit: Long = 0,
    val turn_count: Int = 0,
)

@Serializable
data class WireSessionUsageDelta(
    val input_tokens: Long = 0,
    val output_tokens: Long = 0,
    val cache_read_tokens: Long = 0,
    val cache_creation_tokens: Long = 0,
    val cost_usd: Double = 0.0,
)

@Serializable
data class WirePermissionRule(
    val id: String,
    val tool_name: String = "",
    val matcher: Matcher? = null,
    val decision: String = "approved",
    val created_at: String = "",
    val created_by: String = "user",
) {
    @Serializable
    data class Matcher(
        val kind: String = "always",
        val value: String? = null,
    )
}

@Serializable
data class WireAgentConfig(
    val model: String? = null,
    val system_prompt: String? = null,
    val tools: List<String>? = null,
    val mcp_servers: List<String>? = null,
    val thinking: String? = null,
    val permission_mode: String? = null,
    val plan_mode: Boolean? = null,
    val swarm_mode: Boolean? = null,
    val goal_objective: String? = null,
    val goal_control: String? = null,
)

@Serializable
data class WireSession(
    val id: String,
    val title: String = "",
    val created_at: String = "",
    val updated_at: String = "",
    val busy: Boolean = false,
    val main_turn_active: Boolean? = null,
    val pending_interaction: String? = null,
    val last_turn_reason: String? = null,
    val archived: Boolean = false,
    val current_prompt_id: String? = null,
    val last_prompt: String? = null,
    val workspace_id: String? = null,
    val metadata: JsonObject = JsonObject(emptyMap()),
    val agent_config: WireAgentConfig = WireAgentConfig(),
    val usage: WireSessionUsage = WireSessionUsage(),
    val permission_rules: List<WirePermissionRule> = emptyList(),
    val message_count: Int = 0,
    val last_seq: Long = 0,
)

/** GET /sessions/{id}/status — 运行时状态（与 TUI /status 对齐） */
@Serializable
data class WireSessionRuntimeStatus(
    val model: String? = null,
    val thinking_level: String = "",
    val permission: String = "",
    val plan_mode: Boolean = false,
    val swarm_mode: Boolean = false,
    val context_tokens: Long = 0,
    val max_context_tokens: Long = 0,
    val context_usage: Double = 0.0,
)

/** GET /sessions/{id}/goal — camelCase，与 goal.updated 事件载荷同形 */
@Serializable
data class WireGoalSnapshot(
    val goalId: String,
    val objective: String = "",
    val completionCriterion: String? = null,
    val status: String = "active",
    val turnsUsed: Int = 0,
    val tokensUsed: Long = 0,
    val wallClockMs: Long = 0,
    val terminalReason: String? = null,
    val budget: Budget = Budget(),
) {
    @Serializable
    data class Budget(
        val tokenBudget: Long? = null,
        val turnBudget: Int? = null,
        val wallClockBudgetMs: Long? = null,
        val remainingTokens: Long? = null,
        val remainingTurns: Int? = null,
        val remainingWallClockMs: Long? = null,
        val tokenBudgetReached: Boolean = false,
        val turnBudgetReached: Boolean = false,
        val wallClockBudgetReached: Boolean = false,
        val overBudget: Boolean = false,
    )
}

@Serializable
data class WireSessionWarning(
    val code: String = "",
    val message: String = "",
    val severity: String = "info",
)

@Serializable
data class WireSessionWarningsResponse(
    val warnings: List<WireSessionWarning> = emptyList(),
)

// ---------------------------------------------------------------------------
// Workspace + 目录浏览
// ---------------------------------------------------------------------------

@Serializable
data class WireWorkspace(
    val id: String,
    val root: String = "",
    val name: String = "",
    val last_opened_at: String? = null,
    val session_count: Int = 0,
)

@Serializable
data class WireFsBrowseEntry(
    val name: String,
    val path: String,
    val is_dir: Boolean,
)

@Serializable
data class WireFsBrowseResult(
    val path: String = "",
    val parent: String? = null,
    val entries: List<WireFsBrowseEntry> = emptyList(),
)

@Serializable
data class WireFsHomeResult(
    val home: String = "",
    val recent_roots: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// Message
// ---------------------------------------------------------------------------

@Serializable
@JsonClassDiscriminator("kind")
sealed interface WireImageSource {
    @Serializable
    @SerialName("url")
    data class Url(val url: String, val id: String? = null) : WireImageSource

    @Serializable
    @SerialName("base64")
    data class Base64(val media_type: String, val data: String) : WireImageSource

    @Serializable
    @SerialName("file")
    data class FileRef(val file_id: String) : WireImageSource
}

@Serializable
@JsonClassDiscriminator("type")
sealed interface WireMessageContent {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : WireMessageContent

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val tool_call_id: String,
        val tool_name: String,
        val input: JsonElement? = null,
    ) : WireMessageContent

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val tool_call_id: String,
        val output: JsonElement? = null,
        val is_error: Boolean? = null,
    ) : WireMessageContent

    @Serializable
    @SerialName("image")
    data class Image(val source: WireImageSource) : WireMessageContent

    @Serializable
    @SerialName("video")
    data class Video(val source: WireImageSource) : WireMessageContent

    @Serializable
    @SerialName("file")
    data class FilePart(
        val file_id: String,
        val name: String = "",
        val media_type: String = "",
        val size: Long = 0,
    ) : WireMessageContent

    @Serializable
    @SerialName("thinking")
    data class Thinking(val thinking: String, val signature: String? = null) : WireMessageContent
}

@Serializable
data class WireMessage(
    val id: String,
    val session_id: String,
    val role: String,
    val content: List<WireMessageContent> = emptyList(),
    val created_at: String = "",
    val prompt_id: String? = null,
    val parent_message_id: String? = null,
    val metadata: JsonObject? = null,
)

// ---------------------------------------------------------------------------
// Prompt
// ---------------------------------------------------------------------------

@Serializable
data class WirePromptSubmission(
    val content: List<WireMessageContent>,
    val metadata: JsonObject? = null,
    val agent_id: String? = null,
    val model: String? = null,
    val thinking: String? = null,
    val permission_mode: String? = null,
    val plan_mode: Boolean? = null,
    val swarm_mode: Boolean? = null,
    val goal_objective: String? = null,
    val goal_control: String? = null,
)

@Serializable
data class WirePromptSubmitResult(
    val prompt_id: String,
    val user_message_id: String = "",
    val status: String? = null,
)

@Serializable
data class WirePromptSteerRequest(
    val prompt_ids: List<String>,
)

@Serializable
data class WirePromptSteerResult(
    val steered: Boolean = false,
    val prompt_ids: List<String> = emptyList(),
)

@Serializable
data class WirePromptAbortResult(
    val aborted: Boolean = false,
    val at_seq: Long? = null,
)

// ---------------------------------------------------------------------------
// Approval
// ---------------------------------------------------------------------------

@Serializable
data class WireApprovalRequest(
    val approval_id: String,
    val session_id: String,
    val turn_id: Long? = null,
    val tool_call_id: String = "",
    val tool_name: String = "",
    val action: String = "",
    val tool_input_display: JsonElement? = null,
    val display: JsonElement? = null,
    val expires_at: String = "",
    val created_at: String = "",
)

@Serializable
data class WireApprovalResponse(
    val decision: String,
    val scope: String? = null,
    val feedback: String? = null,
    val selected_label: String? = null,
)

@Serializable
data class WireApprovalResolveResult(
    val resolved: Boolean = true,
    val resolved_at: String = "",
)

// ---------------------------------------------------------------------------
// Question
// ---------------------------------------------------------------------------

@Serializable
data class WireQuestionOption(
    val id: String = "",
    val label: String = "",
    val description: String? = null,
    val recommended: Boolean? = null,
    val is_recommended: Boolean? = null,
)

@Serializable
data class WireQuestionItem(
    val id: String = "",
    val question: String = "",
    val header: String? = null,
    val body: String? = null,
    val options: List<WireQuestionOption> = emptyList(),
    val multi_select: Boolean? = null,
    val allow_other: Boolean? = null,
    val other_label: String? = null,
    val other_description: String? = null,
)

@Serializable
data class WireQuestionRequest(
    val question_id: String,
    val session_id: String,
    val turn_id: Long? = null,
    val tool_call_id: String? = null,
    val questions: List<WireQuestionItem> = emptyList(),
    val created_at: String = "",
)

@Serializable
@JsonClassDiscriminator("kind")
sealed interface WireQuestionAnswer {
    @Serializable
    @SerialName("single")
    data class Single(val option_id: String) : WireQuestionAnswer

    @Serializable
    @SerialName("multi")
    data class Multi(val option_ids: List<String>) : WireQuestionAnswer

    @Serializable
    @SerialName("other")
    data class Other(val text: String) : WireQuestionAnswer

    @Serializable
    @SerialName("multi_with_other")
    data class MultiWithOther(val option_ids: List<String>, val other_text: String) : WireQuestionAnswer

    @Serializable
    @SerialName("skipped")
    data object Skipped : WireQuestionAnswer
}

@Serializable
data class WireQuestionResponse(
    val answers: Map<String, WireQuestionAnswer>,
    val method: String? = null,
    val note: String? = null,
)

@Serializable
data class WireQuestionResolveResult(
    val resolved: Boolean = true,
    val resolved_at: String = "",
)

@Serializable
data class WireQuestionDismissResult(
    val dismissed: Boolean = true,
    val dismissed_at: String = "",
)

// ---------------------------------------------------------------------------
// Task
// ---------------------------------------------------------------------------

@Serializable
data class WireTask(
    val id: String,
    val session_id: String,
    val kind: String = "tool",
    val description: String = "",
    val status: String = "running",
    val command: String? = null,
    val created_at: String = "",
    val started_at: String? = null,
    val completed_at: String? = null,
    val output_preview: String? = null,
    val output_bytes: Long? = null,
    val output: String? = null,
    val subagent_phase: String? = null,
    val subagent_type: String? = null,
    val parent_tool_call_id: String? = null,
    val suspended_reason: String? = null,
    val swarm_index: Int? = null,
    val run_in_background: Boolean? = null,
)

@Serializable
data class WireTaskListResponse(
    val items: List<WireTask> = emptyList(),
)

// ---------------------------------------------------------------------------
// File System
// ---------------------------------------------------------------------------

@Serializable
data class WireFsEntry(
    val path: String,
    val name: String = "",
    val kind: String = "file",
    val size: Long? = null,
    val modified_at: String = "",
    val etag: String? = null,
    val mime: String? = null,
    val language_id: String? = null,
    val is_binary: Boolean? = null,
    val is_symlink_to: String? = null,
    val git_status: String? = null,
    val child_count: Int? = null,
)

@Serializable
data class WireFsListRequest(
    val path: String? = null,
    val depth: Int? = null,
    val include_git_status: Boolean? = null,
)

@Serializable
data class WireFsListResult(
    val items: List<WireFsEntry> = emptyList(),
    val children_by_path: Map<String, List<WireFsEntry>>? = null,
    val truncated: Boolean = false,
)

@Serializable
data class WireFsReadRequest(
    val path: String,
    val offset: Int? = null,
    val length: Int? = null,
)

@Serializable
data class WireFsReadResult(
    val path: String = "",
    val content: String = "",
    val encoding: String = "utf-8",
    val size: Long = 0,
    val truncated: Boolean = false,
    val etag: String = "",
    val mime: String = "",
    val language_id: String? = null,
    val line_count: Int? = null,
    val is_binary: Boolean = false,
)

@Serializable
data class WireFsSearchRequest(
    val query: String,
    val limit: Int? = null,
)

@Serializable
data class WireFsSearchResult(
    val items: List<Item> = emptyList(),
    val truncated: Boolean = false,
) {
    @Serializable
    data class Item(
        val path: String,
        val name: String = "",
        val kind: String = "file",
        val score: Double = 0.0,
        val match_positions: List<Int> = emptyList(),
    )
}

@Serializable
data class WireFsGrepRequest(
    val pattern: String,
    val regex: Boolean? = null,
    val case_sensitive: Boolean? = null,
)

@Serializable
data class WireFsGrepResult(
    val files: List<FileMatches> = emptyList(),
    val files_scanned: Int = 0,
    val truncated: Boolean = false,
    val elapsed_ms: Long = 0,
) {
    @Serializable
    data class FileMatches(
        val path: String,
        val matches: List<Match> = emptyList(),
    ) {
        @Serializable
        data class Match(
            val line: Int = 0,
            val col: Int = 0,
            val text: String = "",
            val before: String = "",
            val after: String = "",
        )
    }
}

@Serializable
data class WireFsGitStatusRequest(
    val paths: List<String>? = null,
)

@Serializable
data class WireGitStatus(
    val branch: String = "",
    val ahead: Int = 0,
    val behind: Int = 0,
    val entries: Map<String, String> = emptyMap(),
    val additions: Int = 0,
    val deletions: Int = 0,
    val pullRequest: PullRequest? = null,
) {
    @Serializable
    data class PullRequest(
        val number: Int = 0,
        val state: String = "",
        val url: String = "",
    )
}

@Serializable
data class WireFsDiffRequest(
    val path: String,
)

@Serializable
data class WireFsDiffResult(
    val path: String = "",
    val diff: String = "",
)

// ---------------------------------------------------------------------------
// Model + Provider
// ---------------------------------------------------------------------------

@Serializable
data class WireModel(
    val provider: String,
    val model: String,
    val display_name: String? = null,
    val max_context_size: Long = 0,
    val capabilities: List<String>? = null,
    val support_efforts: List<String>? = null,
    val default_effort: String? = null,
)

@Serializable
data class WireModelListResponse(
    val items: List<WireModel> = emptyList(),
)

@Serializable
data class WireProvider(
    val id: String,
    val type: String = "",
    val base_url: String? = null,
    val default_model: String? = null,
    val has_api_key: Boolean = false,
    val status: String = "unconfigured",
    val models: List<String>? = null,
)

@Serializable
data class WireProviderListResponse(
    val items: List<WireProvider> = emptyList(),
)

@Serializable
data class WireProviderRefreshResult(
    val changed: List<Changed> = emptyList(),
    val unchanged: List<String> = emptyList(),
    val failed: List<Failed> = emptyList(),
) {
    @Serializable
    data class Changed(
        val provider_id: String = "",
        val provider_name: String = "",
        val added: List<String> = emptyList(),
        val removed: List<String> = emptyList(),
    )

    @Serializable
    data class Failed(
        val provider: String = "",
        val reason: String = "",
    )
}

@Serializable
data class WireProviderCreateRequest(
    val type: String,
    val api_key: String? = null,
    val base_url: String? = null,
    val default_model: String? = null,
)

/** GET /config — 字段庞杂且客户端只读取少数字段，保留 raw 透传 */
@Serializable
data class WireConfig(
    val default_provider: String? = null,
    val default_model: String? = null,
    val default_permission_mode: String? = null,
    val raw: JsonObject? = null,
)

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

@Serializable
data class WireAuthResult(
    val ready: Boolean = false,
    val providers_count: Int = 0,
    val default_model: String? = null,
    val managed_provider: JsonObject? = null,
)

@Serializable
data class WireOAuthLoginStartResult(
    val flow_id: String = "",
    val provider: String = "",
    val status: String = "",
    val verification_uri: String? = null,
    val verification_uri_complete: String? = null,
    val user_code: String? = null,
    val expires_in: Int? = null,
    val interval: Int? = null,
    val expires_at: String? = null,
)

@Serializable
data class WireOAuthLoginPollResult(
    val flow_id: String = "",
    val status: String = "",
    val resolved_at: String? = null,
)

@Serializable
data class WireOAuthCancelResult(
    val cancelled: Boolean = false,
    val status: String = "",
)

@Serializable
data class WireLogoutResult(
    val logged_out: Boolean = false,
)

// ---------------------------------------------------------------------------
// 文件上传 / 健康 / 元数据
// ---------------------------------------------------------------------------

@Serializable
data class WireFileMeta(
    val id: String,
    val name: String = "",
    val media_type: String = "",
    val size: Long = 0,
    val created_at: String = "",
    val expires_at: String? = null,
)

@Serializable
data class WireHealthz(
    val status: String = "ok",
    val uptime_sec: Double = 0.0,
)

@Serializable
data class WireMeta(
    val server_version: String = "",
    val server_id: String = "",
    val started_at: String = "",
    val capabilities: Map<String, Boolean> = emptyMap(),
    val open_in_apps: List<String>? = null,
    val dangerous_bypass_auth: Boolean? = null,
    val backend: String? = null,
)

// ---------------------------------------------------------------------------
// 会话杂项操作结果
// ---------------------------------------------------------------------------

@Serializable
data class WireArchiveResult(
    val archived: Boolean = true,
)

@Serializable
data class WireAbortResult(
    val aborted: Boolean = false,
)

@Serializable
data class WireUndoRequest(
    val count: Int,
)

@Serializable
data class WireCompactRequest(
    val instruction: String? = null,
)

@Serializable
data class WireForkRequest(
    val title: String? = null,
)

@Serializable
data class WireCreateSessionRequest(
    val title: String? = null,
    val metadata: JsonObject? = null,
    val workspace_id: String? = null,
    val agent_config: WireAgentConfig? = null,
)

@Serializable
data class WireUpdateSessionProfileRequest(
    val title: String? = null,
    val metadata: JsonObject? = null,
    val agent_config: WireAgentConfig? = null,
)

@Serializable
data class WireCreateWorkspaceRequest(
    val root: String,
    val name: String? = null,
)

@Serializable
data class WireRenameWorkspaceRequest(
    val name: String,
)

@Serializable
data class WireSkill(
    val name: String,
    val description: String = "",
    val path: String = "",
    val source: String = "",
    val type: String? = null,
    val disable_model_invocation: Boolean? = null,
)

@Serializable
data class WireSkillListResponse(
    val skills: List<WireSkill> = emptyList(),
)

@Serializable
data class WireSkillActivateRequest(
    val args: String? = null,
)

@Serializable
data class WireSkillActivateResult(
    val activated: Boolean = true,
    val skill_name: String = "",
)

@Serializable
data class WireTaskCancelResult(
    val cancelled: Boolean = true,
)

// ---------------------------------------------------------------------------
// v2 同步协议：游标 + 会话快照
// ---------------------------------------------------------------------------

@Serializable
data class WireSessionCursor(
    val seq: Long,
    val epoch: String? = null,
)

@Serializable
data class WireInFlightToolCall(
    val tool_call_id: String,
    val name: String,
    val args: JsonElement? = null,
    val description: String? = null,
    val display: JsonElement? = null,
    val last_progress: LastProgress? = null,
) {
    @Serializable
    data class LastProgress(
        val kind: String = "progress",
        val text: String? = null,
        val percent: Double? = null,
    )
}

@Serializable
data class WireInFlightTurn(
    val turn_id: Long,
    val assistant_text: String = "",
    val thinking_text: String = "",
    val running_tools: List<WireInFlightToolCall> = emptyList(),
    val current_prompt_id: String? = null,
)

/** `GET /sessions/{sid}/snapshot` — 在水位处原子重建状态 */
@Serializable
data class WireSessionSnapshot(
    val as_of_seq: Long,
    val epoch: String = "",
    val session: WireSession,
    val messages: WirePage<WireMessage> = WirePage(),
    val in_flight_turn: WireInFlightTurn? = null,
    val subagents: List<WireTask>? = null,
    val pending_approvals: List<WireApprovalRequest> = emptyList(),
    val pending_questions: List<WireQuestionRequest> = emptyList(),
)

package com.kimi.app.data.api

import com.kimi.app.data.wire.WireAbortResult
import com.kimi.app.data.wire.WireArchiveResult
import com.kimi.app.data.wire.WireAuthResult
import com.kimi.app.data.wire.WireCompactRequest
import com.kimi.app.data.wire.WireConfig
import com.kimi.app.data.wire.WireCreateSessionRequest
import com.kimi.app.data.wire.WireCreateWorkspaceRequest
import com.kimi.app.data.wire.WireEnvelope
import com.kimi.app.data.wire.WireFileMeta
import com.kimi.app.data.wire.WireForkRequest
import com.kimi.app.data.wire.WireFsBrowseResult
import com.kimi.app.data.wire.WireFsDiffRequest
import com.kimi.app.data.wire.WireFsDiffResult
import com.kimi.app.data.wire.WireFsGrepRequest
import com.kimi.app.data.wire.WireFsGrepResult
import com.kimi.app.data.wire.WireFsGitStatusRequest
import com.kimi.app.data.wire.WireFsHomeResult
import com.kimi.app.data.wire.WireFsListRequest
import com.kimi.app.data.wire.WireFsListResult
import com.kimi.app.data.wire.WireFsReadRequest
import com.kimi.app.data.wire.WireFsReadResult
import com.kimi.app.data.wire.WireFsSearchRequest
import com.kimi.app.data.wire.WireFsSearchResult
import com.kimi.app.data.wire.WireGitStatus
import com.kimi.app.data.wire.WireGoalSnapshot
import com.kimi.app.data.wire.WireHealthz
import com.kimi.app.data.wire.WireLogoutResult
import com.kimi.app.data.wire.WireMessage
import com.kimi.app.data.wire.WireMeta
import com.kimi.app.data.wire.WireModel
import com.kimi.app.data.wire.WireModelListResponse
import com.kimi.app.data.wire.WireOAuthCancelResult
import com.kimi.app.data.wire.WireOAuthLoginPollResult
import com.kimi.app.data.wire.WireOAuthLoginStartResult
import com.kimi.app.data.wire.WirePage
import com.kimi.app.data.wire.WireApprovalResolveResult
import com.kimi.app.data.wire.WireApprovalResponse
import com.kimi.app.data.wire.WirePromptAbortResult
import com.kimi.app.data.wire.WirePromptSteerRequest
import com.kimi.app.data.wire.WirePromptSteerResult
import com.kimi.app.data.wire.WirePromptSubmission
import com.kimi.app.data.wire.WirePromptSubmitResult
import com.kimi.app.data.wire.WireProvider
import com.kimi.app.data.wire.WireProviderCreateRequest
import com.kimi.app.data.wire.WireProviderListResponse
import com.kimi.app.data.wire.WireProviderRefreshResult
import com.kimi.app.data.wire.WireQuestionDismissResult
import com.kimi.app.data.wire.WireQuestionResolveResult
import com.kimi.app.data.wire.WireQuestionResponse
import com.kimi.app.data.wire.WireRenameWorkspaceRequest
import com.kimi.app.data.wire.WireSession
import com.kimi.app.data.wire.WireSessionRuntimeStatus
import com.kimi.app.data.wire.WireSessionSnapshot
import com.kimi.app.data.wire.WireSessionWarningsResponse
import com.kimi.app.data.wire.WireSkillActivateRequest
import com.kimi.app.data.wire.WireSkillActivateResult
import com.kimi.app.data.wire.WireSkillListResponse
import com.kimi.app.data.wire.WireTask
import com.kimi.app.data.wire.WireTaskCancelResult
import com.kimi.app.data.wire.WireTaskListResponse
import com.kimi.app.data.wire.WireUndoRequest
import com.kimi.app.data.wire.WireUpdateSessionProfileRequest
import com.kimi.app.data.wire.WireWorkspace
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * /api/v1 REST 接口。所有方法返回统一信封，由调用方 [unwrap]。
 * 文件上传/下载/会话导出等二进制端点在 FileTransfer 中用裸 OkHttp 实现。
 */
interface KimiApi {

    // ---- 健康 / 元数据 / 认证 ----

    @GET("healthz")
    suspend fun healthz(): WireEnvelope<WireHealthz>

    @GET("meta")
    suspend fun meta(): WireEnvelope<WireMeta>

    @GET("auth")
    suspend fun auth(): WireEnvelope<WireAuthResult>

    @POST("oauth/login")
    suspend fun oauthLoginStart(@Body body: JsonObject = emptyJsonBody): WireEnvelope<WireOAuthLoginStartResult>

    @GET("oauth/login")
    suspend fun oauthLoginPoll(): WireEnvelope<WireOAuthLoginPollResult?>

    @HTTP(method = "DELETE", path = "oauth/login", hasBody = false)
    suspend fun oauthLoginCancel(): WireEnvelope<WireOAuthCancelResult>

    @POST("oauth/logout")
    suspend fun oauthLogout(@Body body: JsonObject = emptyJsonBody): WireEnvelope<WireLogoutResult>

    // ---- 配置 ----

    @GET("config")
    suspend fun config(): WireEnvelope<WireConfig>

    // ---- 会话 ----

    @GET("sessions")
    suspend fun listSessions(
        @Query("before_id") beforeId: String? = null,
        @Query("page_size") pageSize: Int? = null,
        @Query("include_archive") includeArchive: Boolean? = null,
        @Query("archived_only") archivedOnly: Boolean? = null,
        @Query("exclude_empty") excludeEmpty: Boolean? = null,
        @Query("workspace_id") workspaceId: String? = null,
    ): WireEnvelope<WirePage<WireSession>>

    @POST("sessions")
    suspend fun createSession(@Body body: WireCreateSessionRequest): WireEnvelope<WireSession>

    @GET("sessions/{id}")
    suspend fun getSession(@Path("id") id: String): WireEnvelope<WireSession>

    @POST("sessions/{id}/profile")
    suspend fun updateSessionProfile(
        @Path("id") id: String,
        @Body body: WireUpdateSessionProfileRequest,
    ): WireEnvelope<WireSession>

    @GET("sessions/{id}/status")
    suspend fun getSessionStatus(@Path("id") id: String): WireEnvelope<WireSessionRuntimeStatus>

    @GET("sessions/{id}/goal")
    suspend fun getSessionGoal(@Path("id") id: String): WireEnvelope<WireGoalSnapshot?>

    @GET("sessions/{id}/warnings")
    suspend fun getSessionWarnings(@Path("id") id: String): WireEnvelope<WireSessionWarningsResponse>

    @POST("sessions/{id}:archive")
    suspend fun archiveSession(@Path("id") id: String): WireEnvelope<WireArchiveResult>

    @POST("sessions/{id}:restore")
    suspend fun restoreSession(@Path("id") id: String): WireEnvelope<WireSession>

    @POST("sessions/{id}:abort")
    suspend fun abortSession(@Path("id") id: String): WireEnvelope<WireAbortResult>

    @POST("sessions/{id}:compact")
    suspend fun compactSession(@Path("id") id: String, @Body body: WireCompactRequest): WireEnvelope<JsonObject?>

    @POST("sessions/{id}:undo")
    suspend fun undoSession(@Path("id") id: String, @Body body: WireUndoRequest): WireEnvelope<JsonObject?>

    @POST("sessions/{id}:fork")
    suspend fun forkSession(@Path("id") id: String, @Body body: WireForkRequest): WireEnvelope<WireSession>

    @GET("sessions/{id}/messages")
    suspend fun listMessages(
        @Path("id") id: String,
        @Query("before_id") beforeId: String? = null,
        @Query("page_size") pageSize: Int? = null,
        @Query("role") role: String? = null,
    ): WireEnvelope<WirePage<WireMessage>>

    @GET("sessions/{id}/snapshot")
    suspend fun getSessionSnapshot(@Path("id") id: String): WireEnvelope<WireSessionSnapshot>

    // ---- 提示词 ----

    @POST("sessions/{id}/prompts")
    suspend fun submitPrompt(
        @Path("id") id: String,
        @Body body: WirePromptSubmission,
    ): WireEnvelope<WirePromptSubmitResult>

    @POST("sessions/{id}/prompts:steer")
    suspend fun steerPrompts(
        @Path("id") id: String,
        @Body body: WirePromptSteerRequest,
    ): WireEnvelope<WirePromptSteerResult>

    @POST("sessions/{id}/prompts/{promptId}:abort")
    suspend fun abortPrompt(
        @Path("id") id: String,
        @Path("promptId") promptId: String,
    ): WireEnvelope<WirePromptAbortResult>

    // ---- 审批 / 问题 ----

    @POST("sessions/{id}/approvals/{approvalId}")
    suspend fun respondApproval(
        @Path("id") id: String,
        @Path("approvalId") approvalId: String,
        @Body body: WireApprovalResponse,
    ): WireEnvelope<WireApprovalResolveResult>

    @POST("sessions/{id}/questions/{questionId}")
    suspend fun respondQuestion(
        @Path("id") id: String,
        @Path("questionId") questionId: String,
        @Body body: WireQuestionResponse,
    ): WireEnvelope<WireQuestionResolveResult>

    @POST("sessions/{id}/questions/{questionId}:dismiss")
    suspend fun dismissQuestion(
        @Path("id") id: String,
        @Path("questionId") questionId: String,
    ): WireEnvelope<WireQuestionDismissResult>

    // ---- 技能 ----

    @GET("sessions/{id}/skills")
    suspend fun listSessionSkills(@Path("id") id: String): WireEnvelope<WireSkillListResponse>

    @GET("workspaces/{id}/skills")
    suspend fun listWorkspaceSkills(@Path("id") id: String): WireEnvelope<WireSkillListResponse>

    @POST("sessions/{id}/skills/{skillName}:activate")
    suspend fun activateSkill(
        @Path("id") id: String,
        @Path("skillName") skillName: String,
        @Body body: WireSkillActivateRequest,
    ): WireEnvelope<WireSkillActivateResult>

    // ---- 任务 ----

    @GET("sessions/{id}/tasks")
    suspend fun listTasks(
        @Path("id") id: String,
        @Query("status") status: String? = null,
    ): WireEnvelope<WireTaskListResponse>

    @GET("sessions/{id}/tasks/{taskId}")
    suspend fun getTask(
        @Path("id") id: String,
        @Path("taskId") taskId: String,
        @Query("with_output") withOutput: Boolean? = null,
        @Query("output_bytes") outputBytes: Int? = null,
    ): WireEnvelope<WireTask>

    @POST("sessions/{id}/tasks/{taskId}:cancel")
    suspend fun cancelTask(
        @Path("id") id: String,
        @Path("taskId") taskId: String,
    ): WireEnvelope<WireTaskCancelResult>

    // ---- 文件系统 ----

    @POST("sessions/{id}/fs:list")
    suspend fun fsList(@Path("id") id: String, @Body body: WireFsListRequest): WireEnvelope<WireFsListResult>

    @POST("sessions/{id}/fs:read")
    suspend fun fsRead(@Path("id") id: String, @Body body: WireFsReadRequest): WireEnvelope<WireFsReadResult>

    @POST("sessions/{id}/fs:search")
    suspend fun fsSearch(@Path("id") id: String, @Body body: WireFsSearchRequest): WireEnvelope<WireFsSearchResult>

    @POST("sessions/{id}/fs:grep")
    suspend fun fsGrep(@Path("id") id: String, @Body body: WireFsGrepRequest): WireEnvelope<WireFsGrepResult>

    @POST("sessions/{id}/fs:git_status")
    suspend fun fsGitStatus(@Path("id") id: String, @Body body: WireFsGitStatusRequest): WireEnvelope<WireGitStatus>

    @POST("sessions/{id}/fs:diff")
    suspend fun fsDiff(@Path("id") id: String, @Body body: WireFsDiffRequest): WireEnvelope<WireFsDiffResult>

    // ---- 工作区 ----

    @GET("workspaces")
    suspend fun listWorkspaces(): WireEnvelope<WirePage<WireWorkspace>>

    @POST("workspaces")
    suspend fun createWorkspace(@Body body: WireCreateWorkspaceRequest): WireEnvelope<WireWorkspace>

    @PATCH("workspaces/{id}")
    suspend fun renameWorkspace(
        @Path("id") id: String,
        @Body body: WireRenameWorkspaceRequest,
    ): WireEnvelope<WireWorkspace>

    @DELETE("workspaces/{id}")
    suspend fun deleteWorkspace(@Path("id") id: String): WireEnvelope<JsonObject?>

    @GET
    suspend fun fsBrowse(
        @retrofit2.http.Url url: String,
        @Query("path") path: String? = null,
    ): WireEnvelope<WireFsBrowseResult>

    @GET
    suspend fun fsHome(@retrofit2.http.Url url: String): WireEnvelope<WireFsHomeResult>

    // ---- 模型与提供商 ----

    @GET("models")
    suspend fun listModels(): WireEnvelope<WireModelListResponse>

    @GET("providers")
    suspend fun listProviders(): WireEnvelope<WireProviderListResponse>

    @POST("providers")
    suspend fun createProvider(@Body body: WireProviderCreateRequest): WireEnvelope<WireProvider>

    @DELETE("providers/{id}")
    suspend fun deleteProvider(@Path("id") id: String): WireEnvelope<JsonObject?>

    @POST
    suspend fun refreshProviders(@retrofit2.http.Url url: String): WireEnvelope<WireProviderRefreshResult>

    @POST("providers/{id}:refresh")
    suspend fun refreshProvider(@Path("id") id: String): WireEnvelope<WireProviderRefreshResult>

    // ---- 文件上传后的元信息（上传本体在 FileTransfer）----

    @GET("files/{fileId}")
    suspend fun getFileMeta(@Path("fileId") fileId: String): WireEnvelope<WireFileMeta>
}

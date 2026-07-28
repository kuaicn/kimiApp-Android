package com.kimi.app.data.store

import com.kimi.app.data.wire.WireImageSource
import com.kimi.app.data.wire.WireMessage
import com.kimi.app.data.wire.WireMessageContent
import com.kimi.app.data.wire.WireTask
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// 应用层模型（≈ kimi-web 的 api/types App*）。wire DTO 经本文件映射为 camelCase 应用类型。

// ---------------------------------------------------------------------------
// 消息
// ---------------------------------------------------------------------------

enum class AppRole { USER, ASSISTANT, TOOL, SYSTEM }

sealed interface AppMessageContent {
    data class Text(val text: String) : AppMessageContent

    data class Thinking(val thinking: String, val signature: String? = null) : AppMessageContent

    data class ToolUse(
        val toolCallId: String,
        val toolName: String,
        val input: JsonElement?,
        /** 流式进度行（in-flight 播种 / tool.progress 填充） */
        val outputLines: List<String>? = null,
    ) : AppMessageContent

    data class ToolResult(
        val toolCallId: String,
        val output: JsonElement?,
        val isError: Boolean,
    ) : AppMessageContent

    data class Image(val source: WireImageSource) : AppMessageContent

    data class Video(val source: WireImageSource) : AppMessageContent

    data class FilePart(
        val fileId: String,
        val name: String,
        val mediaType: String,
        val size: Long,
    ) : AppMessageContent
}

data class AppMessage(
    val id: String,
    val sessionId: String,
    val role: AppRole,
    val content: List<AppMessageContent>,
    val createdAt: String,
    val promptId: String? = null,
    val parentMessageId: String? = null,
    val metadata: JsonObject? = null,
    val durationMs: Long? = null,
)

fun WireMessageContent.toApp(): AppMessageContent = when (this) {
    is WireMessageContent.Text -> AppMessageContent.Text(text)
    is WireMessageContent.Thinking -> AppMessageContent.Thinking(thinking, signature)
    is WireMessageContent.ToolUse -> AppMessageContent.ToolUse(tool_call_id, tool_name, input)
    is WireMessageContent.ToolResult -> AppMessageContent.ToolResult(tool_call_id, output, is_error == true)
    is WireMessageContent.Image -> AppMessageContent.Image(source)
    is WireMessageContent.Video -> AppMessageContent.Video(source)
    is WireMessageContent.FilePart -> AppMessageContent.FilePart(file_id, name, media_type, size)
}

fun WireMessage.toApp(): AppMessage = AppMessage(
    id = id,
    sessionId = session_id,
    role = when (role) {
        "user" -> AppRole.USER
        "assistant" -> AppRole.ASSISTANT
        "tool" -> AppRole.TOOL
        else -> AppRole.SYSTEM
    },
    content = content.map { it.toApp() },
    createdAt = created_at,
    promptId = prompt_id,
    parentMessageId = parent_message_id,
    metadata = metadata,
)

// ---------------------------------------------------------------------------
// 排队中的提示词（运行中发送的消息，回合结束后自动吐出 —— 服务端排队，客户端仅展示）
// ---------------------------------------------------------------------------

data class QueuedPrompt(
    val promptId: String,
    val text: String,
    val queuedAt: Long = System.currentTimeMillis(),
)

// ---------------------------------------------------------------------------
// 会话实时状态（GET status + agent.status.updated 合成）
// ---------------------------------------------------------------------------

data class SessionLiveStatus(
    val model: String? = null,
    val thinkingLevel: String = "",
    val permission: String = "",
    val planMode: Boolean = false,
    val swarmMode: Boolean = false,
    val contextTokens: Long = 0,
    val maxContextTokens: Long = 0,
)

// ---------------------------------------------------------------------------
// 通知/警告（警告 Toast + 错误提示）
// ---------------------------------------------------------------------------

data class AppNotice(
    val id: Long,
    val message: String,
    val isError: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

// ---------------------------------------------------------------------------
// 后台任务（wire WireTask + reducer 侧累计的输出）
// ---------------------------------------------------------------------------

data class AppTask(
    val id: String,
    val sessionId: String,
    val kind: String = "tool",
    val description: String = "",
    val status: String = "running",
    val command: String? = null,
    val createdAt: String = "",
    val startedAt: String? = null,
    val completedAt: String? = null,
    val outputPreview: String? = null,
    val outputBytes: Long? = null,
    val subagentPhase: String? = null,
    val subagentType: String? = null,
    val parentToolCallId: String? = null,
    val suspendedReason: String? = null,
    val swarmIndex: Int? = null,
    val runInBackground: Boolean? = null,
    /** reducer 累计的输出行（task.progress） */
    val outputLines: List<String>? = null,
    /** subagent 流式文本（task.progress kind=text 拼接） */
    val text: String? = null,
)

fun WireTask.toApp(): AppTask = AppTask(
    id = id,
    sessionId = session_id,
    kind = kind,
    description = description,
    status = status,
    command = command,
    createdAt = created_at,
    startedAt = started_at,
    completedAt = completed_at,
    outputPreview = output_preview,
    outputBytes = output_bytes,
    subagentPhase = subagent_phase,
    subagentType = subagent_type,
    parentToolCallId = parent_tool_call_id,
    suspendedReason = suspended_reason,
    swarmIndex = swarm_index,
    runInBackground = run_in_background,
)

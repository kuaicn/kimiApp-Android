@file:Suppress("PropertyName")

package com.kimi.app.data.wire

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// WS 帧模型。事件帧 payload 按 type 字符串懒解析（与 kimi-web ws.ts 的 classifyFrame 一致）。

// ---- C→S ----

@Serializable
data class WireClientHello(
    val type: String = "client_hello",
    val id: String,
    val payload: Payload,
) {
    @Serializable
    data class Payload(
        val client_id: String,
        val subscriptions: List<String>,
        val cursors: Map<String, WireSessionCursor>? = null,
    )
}

@Serializable
data class WireSubscribe(
    val type: String = "subscribe",
    val id: String,
    val payload: Payload,
) {
    @Serializable
    data class Payload(
        val session_ids: List<String>,
        val cursors: Map<String, WireSessionCursor>? = null,
    )
}

@Serializable
data class WireUnsubscribe(
    val type: String = "unsubscribe",
    val id: String,
    val payload: Payload,
) {
    @Serializable
    data class Payload(val session_ids: List<String>)
}

@Serializable
data class WirePong(
    val type: String = "pong",
    val payload: Payload,
) {
    @Serializable
    data class Payload(val nonce: String)
}

// ---- S→C 帧头 ----

/** 所有服务器帧的公共解析：先读 type/timestamp/payload，再按 type 分派 */
@Serializable
data class WireServerFrameHead(
    val type: String,
    val timestamp: String? = null,
    val seq: Long? = null,
    val session_id: String? = null,
    val payload: JsonElement? = null,
)

@Serializable
data class WireServerHello(
    val server_id: String = "",
    val heartbeat_ms: Long? = null,
    val max_event_buffer_size: Long = 0,
    val capabilities: Capabilities? = null,
) {
    @Serializable
    data class Capabilities(
        val event_batching: Boolean = false,
        val compression: Boolean = false,
    )
}

@Serializable
data class WirePingPayload(val nonce: String = "")

@Serializable
data class WireResyncPayload(
    val session_id: String,
    val reason: String = "",
    val current_seq: Long = 0,
    val epoch: String? = null,
)

@Serializable
data class WireErrorPayload(
    val code: Int = 0,
    val msg: String = "",
    val fatal: Boolean = false,
    val request_id: String? = null,
    val details: JsonElement? = null,
)

@Serializable
data class WireAckFrame(
    val type: String = "ack",
    val id: String = "",
    val code: Int = 0,
    val msg: String = "",
    val payload: JsonElement? = null,
)

// ---- 事件 payload（event.*，字段与 wire.ts 一致）----

@Serializable
data class EvSessionCreated(val session: WireSession)

@Serializable
data class EvSessionUpdated(val session: WireSession, val changed_fields: List<String> = emptyList())

@Serializable
data class EvSessionDeleted(val session_id: String)

@Serializable
data class EvSessionWorkChanged(
    val busy: Boolean,
    val main_turn_active: Boolean? = null,
    val pending_interaction: String? = null,
    val last_turn_reason: String? = null,
)

@Serializable
data class EvSessionUsageUpdated(val usage: WireSessionUsage, val delta: WireSessionUsageDelta? = null)

@Serializable
data class EvSessionHistoryCompacted(
    val before_seq: Long,
    val reason: String = "",
    val summary_message_id: String? = null,
)

@Serializable
data class EvWorkspaceChanged(val workspace: WireWorkspace)

@Serializable
data class EvWorkspaceDeleted(val workspace_id: String, val root: String = "")

@Serializable
data class EvMessageCreated(val message: WireMessage)

@Serializable
data class EvMessageUpdated(
    val message_id: String,
    val content: List<WireMessageContent> = emptyList(),
    val status: String = "pending",
)

@Serializable
data class EvAssistantDelta(
    val message_id: String,
    val content_index: Int,
    val delta: Delta,
) {
    @Serializable
    data class Delta(val text: String? = null, val thinking: String? = null)
}

@Serializable
data class EvToolOutput(val tool_call_id: String, val chunk: String, val stream: String = "stdout")

@Serializable
data class EvToolProgress(
    val tool_call_id: String,
    val progress: Double? = null,
    val message: String? = null,
)

@Serializable
data class EvApprovalResolved(
    val approval_id: String,
    val decision: String = "",
    val scope: String? = null,
    val feedback: String? = null,
    val selected_label: String? = null,
    val resolved_by: String = "",
    val resolved_at: String = "",
)

@Serializable
data class EvApprovalExpired(val approval_id: String)

@Serializable
data class EvQuestionAnswered(
    val question_id: String,
    val resolved_by: String = "",
    val resolved_at: String = "",
)

@Serializable
data class EvQuestionDismissed(
    val question_id: String,
    val dismissed_by: String = "",
    val dismissed_at: String = "",
)

@Serializable
data class EvTaskCreated(val task: WireTask)

@Serializable
data class EvTaskProgress(
    val task_id: String,
    val output_chunk: String = "",
    val stream: String = "stdout",
)

@Serializable
data class EvTaskCompleted(
    val task_id: String,
    val status: String = "completed",
    val output_preview: String? = null,
    val output_bytes: Long? = null,
)

@Serializable
data class EvGoalUpdated(val snapshot: WireGoalSnapshot? = null)

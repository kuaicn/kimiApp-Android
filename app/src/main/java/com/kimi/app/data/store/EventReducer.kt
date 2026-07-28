package com.kimi.app.data.store

import com.kimi.app.data.wire.WireApprovalRequest
import com.kimi.app.data.wire.WireGoalSnapshot
import com.kimi.app.data.wire.WireQuestionRequest
import com.kimi.app.data.wire.WireSession
import com.kimi.app.data.wire.WireSessionUsage
import com.kimi.app.data.wire.WireWorkspace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// 归一化应用事件（≈ kimi-web 的 AppEvent），由 KimiClient 从协议/原始事件合成后进 Reducer。

sealed interface AppEvent {
    val sessionId: String?

    // ---- 会话生命周期 ----
    data class SessionCreated(val session: WireSession) : AppEvent {
        override val sessionId: String get() = session.id
    }

    data class SessionUpdated(val session: WireSession) : AppEvent {
        override val sessionId: String get() = session.id
    }

    data class SessionDeleted(override val sessionId: String) : AppEvent

    data class SessionWorkChanged(
        override val sessionId: String,
        val busy: Boolean,
        val mainTurnActive: Boolean?,
        val pendingInteraction: String?,
        val lastTurnReason: String?,
    ) : AppEvent

    data class SessionMetaUpdated(
        override val sessionId: String,
        val title: String?,
        val lastPrompt: String?,
    ) : AppEvent

    data class SessionUsageUpdated(
        override val sessionId: String,
        val usage: WireSessionUsage?,
        val model: String? = null,
        val planMode: Boolean? = null,
        val thinking: String? = null,
        val permissionMode: String? = null,
        val contextTokens: Long? = null,
        val maxContextTokens: Long? = null,
    ) : AppEvent

    data class TurnActiveChanged(
        override val sessionId: String,
        val active: Boolean,
        val reason: String? = null,
    ) : AppEvent

    /** 提示词生命周期终结（prompt.completed/aborted 投影）→ 出队展示层排队项 */
    data class PromptFinished(
        override val sessionId: String,
        val promptId: String,
        val reason: String,
    ) : AppEvent

    /** delta 断档等需要全量重同步的信号（KimiClient 拦截，不进 Reducer） */
    data class ResyncRequested(override val sessionId: String) : AppEvent

    /** 流式工具调用加入当前流式消息（tool.call.started 投影） */
    data class StreamingToolUseAdded(
        override val sessionId: String,
        val messageId: String,
        val toolCallId: String,
        val toolName: String,
        val input: kotlinx.serialization.json.JsonElement?,
    ) : AppEvent

    /** 重试清空流式消息的 text/thinking/toolUse 部件（turn.step.retrying 投影） */
    data class StreamingPartsCleared(
        override val sessionId: String,
        val messageId: String,
    ) : AppEvent

    /** 流式消息收尾（turn.ended 投影）：标记完成并结算悬挂工具 */
    data class StreamingMessageFinished(
        override val sessionId: String,
        val messageId: String,
        val isError: Boolean,
        val durationMs: Long?,
    ) : AppEvent

    data class CompactionStarted(override val sessionId: String, val trigger: String) : AppEvent

    data class CompactionCompleted(
        override val sessionId: String,
        val seq: Long,
        val summary: String?,
        val tokensBefore: Long?,
        val tokensAfter: Long?,
    ) : AppEvent

    data class CompactionCancelled(override val sessionId: String) : AppEvent

    // ---- 消息 ----
    data class MessageCreated(val message: AppMessage) : AppEvent {
        override val sessionId: String get() = message.sessionId
    }

    data class MessageUpdated(
        override val sessionId: String,
        val messageId: String,
        val content: List<AppMessageContent>,
        val durationMs: Long? = null,
    ) : AppEvent

    data class AssistantDelta(
        override val sessionId: String,
        val messageId: String,
        val contentIndex: Int,
        val text: String?,
        val thinking: String?,
    ) : AppEvent

    data class ToolOutput(
        override val sessionId: String,
        val toolCallId: String,
        val outputChunk: String,
    ) : AppEvent

    // ---- 审批 / 问题 ----
    data class ApprovalRequested(val approval: WireApprovalRequest) : AppEvent {
        override val sessionId: String get() = approval.session_id
    }

    data class ApprovalFinished(override val sessionId: String, val approvalId: String) : AppEvent

    data class QuestionRequested(val question: WireQuestionRequest) : AppEvent {
        override val sessionId: String get() = question.session_id
    }

    data class QuestionFinished(override val sessionId: String, val questionId: String) : AppEvent

    // ---- 任务 ----
    data class TaskUpserted(val task: AppTask) : AppEvent {
        override val sessionId: String get() = task.sessionId
    }

    data class TaskProgressed(
        override val sessionId: String,
        val taskId: String,
        val outputChunk: String,
        val kind: String = "output",
    ) : AppEvent

    data class TaskFinished(
        override val sessionId: String,
        val taskId: String,
        val status: String,
        val outputPreview: String?,
        val outputBytes: Long?,
    ) : AppEvent

    // ---- 目标 / 工作区 / 配置 ----
    data class GoalUpdated(override val sessionId: String, val goal: WireGoalSnapshot?) : AppEvent

    data class WorkspaceUpserted(val workspace: WireWorkspace) : AppEvent {
        override val sessionId: String? get() = null
    }

    data class WorkspaceDeleted(val workspaceId: String) : AppEvent {
        override val sessionId: String? get() = null
    }

    data class NoticeAdded(val notice: AppNotice) : AppEvent {
        override val sessionId: String? get() = null
    }
}

// ---------------------------------------------------------------------------
// Reducer（移植 eventReducer.ts 的状态变更逻辑）
// ---------------------------------------------------------------------------

private const val MAX_BACKGROUND_OUTPUT_LINES = 200
const val OPTIMISTIC_ID_PREFIX = "local_"
const val COMPACTION_MARKER_METADATA_KEY = "kimi_compaction_marker"

private fun AppMessage.isOptimisticUser(): Boolean =
    role == AppRole.USER && id.startsWith(OPTIMISTIC_ID_PREFIX)

private fun AppMessage.isCronOrigin(): Boolean {
    val kind = (metadata?.get("origin") as? JsonObject)?.get("kind")?.jsonPrimitive?.content
    return kind == "cron_job" || kind == "cron_missed"
}

private fun AppMessage.textBody(): String =
    content.filterIsInstance<AppMessageContent.Text>().joinToString("\n") { it.text }

private fun sameUserMessageLoosely(a: AppMessage, b: AppMessage): Boolean {
    if (a.textBody() != b.textBody()) return false
    fun mediaCount(m: AppMessage) = m.content.count {
        it is AppMessageContent.Image || it is AppMessageContent.Video || it is AppMessageContent.FilePart
    }
    return mediaCount(a) == mediaCount(b)
}

/** 乐观用户消息回声对账：先 promptId，再全文，再宽松（文本+媒体数） */
private fun findOptimisticUserEchoIndex(messages: List<AppMessage>, incoming: AppMessage): Int {
    incoming.promptId?.let { pid ->
        val i = messages.indexOfLast { it.isOptimisticUser() && it.promptId == pid }
        if (i >= 0) return i
    }
    val i = messages.indexOfLast { it.isOptimisticUser() && it.textBody() == incoming.textBody() }
    if (i >= 0) return i
    return messages.indexOfLast { it.isOptimisticUser() && sameUserMessageLoosely(it, incoming) }
}

private fun appendToolOutput(
    messages: List<AppMessage>,
    toolCallId: String,
    chunk: String,
): List<AppMessage> {
    var changed = false
    val next = messages.map { message ->
        var contentChanged = false
        val content = message.content.map { part ->
            if (part is AppMessageContent.ToolUse && part.toolCallId == toolCallId) {
                contentChanged = true
                part.copy(outputLines = (part.outputLines ?: emptyList()) + chunk)
            } else {
                part
            }
        }
        if (contentChanged) {
            changed = true
            message.copy(content = content)
        } else {
            message
        }
    }
    return if (changed) next else messages
}

object EventReducer {

    fun reduce(state: AppState, event: AppEvent): AppState = when (event) {
        is AppEvent.SessionCreated -> {
            if (state.sessions.any { it.id == event.session.id }) state
            else state.copy(sessions = listOf(event.session) + state.sessions)
        }

        is AppEvent.SessionUpdated -> state.copy(
            sessions = state.sessions.map { if (it.id == event.session.id) event.session else it },
        )

        is AppEvent.SessionDeleted -> state.copy(
            sessions = state.sessions.filterNot { it.id == event.sessionId },
            messagesBySession = state.messagesBySession - event.sessionId,
            hasMoreMessagesBySession = state.hasMoreMessagesBySession - event.sessionId,
            tasksBySession = state.tasksBySession - event.sessionId,
            goalBySession = state.goalBySession - event.sessionId,
            approvalsBySession = state.approvalsBySession - event.sessionId,
            questionsBySession = state.questionsBySession - event.sessionId,
            lastSeqBySession = state.lastSeqBySession - event.sessionId,
            epochBySession = state.epochBySession - event.sessionId,
            turnActiveBySession = state.turnActiveBySession - event.sessionId,
            queuedBySession = state.queuedBySession - event.sessionId,
            liveStatusBySession = state.liveStatusBySession - event.sessionId,
            activeSessionId = if (state.activeSessionId == event.sessionId) null else state.activeSessionId,
        )

        is AppEvent.SessionWorkChanged -> {
            val turnActive = when {
                event.mainTurnActive == true -> true
                event.mainTurnActive == false || !event.busy -> false
                else -> state.turnActiveBySession[event.sessionId] == true
            }
            state.copy(
                sessions = state.sessions.map { s ->
                    if (s.id != event.sessionId) return@map s
                    s.copy(
                        busy = event.busy,
                        main_turn_active = event.mainTurnActive ?: (if (event.busy) s.main_turn_active else false),
                        pending_interaction = event.pendingInteraction ?: s.pending_interaction,
                        // 权威语义：缺省即"无当前结果"，不可保留旧值
                        last_turn_reason = event.lastTurnReason,
                    )
                },
                turnActiveBySession =
                if (turnActive) state.turnActiveBySession + (event.sessionId to true)
                else state.turnActiveBySession - event.sessionId,
            )
        }

        is AppEvent.SessionMetaUpdated -> state.copy(
            sessions = state.sessions.map { s ->
                if (s.id != event.sessionId) return@map s
                s.copy(
                    title = event.title ?: s.title,
                    last_prompt = event.lastPrompt ?: s.last_prompt,
                )
            },
        )

        is AppEvent.SessionUsageUpdated -> {
            val patched = event.usage?.let { usage ->
                state.sessions.map { s ->
                    if (s.id != event.sessionId) return@map s
                    s.copy(usage = usage)
                }
            } ?: state.sessions
            val live = state.liveStatusBySession[event.sessionId] ?: SessionLiveStatus()
            state.copy(
                sessions = patched,
                liveStatusBySession = state.liveStatusBySession + (event.sessionId to live.copy(
                    model = event.model?.takeIf { it.isNotBlank() } ?: live.model,
                    planMode = event.planMode ?: live.planMode,
                    thinkingLevel = event.thinking ?: live.thinkingLevel,
                    permission = event.permissionMode ?: live.permission,
                    contextTokens = event.contextTokens ?: live.contextTokens,
                    maxContextTokens = event.maxContextTokens ?: live.maxContextTokens,
                )),
            )
        }

        is AppEvent.PromptFinished -> {
            val queued = state.queuedBySession[event.sessionId] ?: return state
            state.copy(
                queuedBySession = state.queuedBySession +
                    (event.sessionId to queued.filterNot { it.promptId == event.promptId }),
            )
        }

        is AppEvent.ResyncRequested -> state // KimiClient 拦截处理

        is AppEvent.StreamingToolUseAdded -> {
            val msgs = state.messagesBySession[event.sessionId] ?: return state
            state.copy(
                messagesBySession = state.messagesBySession + (event.sessionId to msgs.map { m ->
                    if (m.id != event.messageId) return@map m
                    if (m.content.any { it is AppMessageContent.ToolUse && it.toolCallId == event.toolCallId }) {
                        return@map m
                    }
                    m.copy(
                        content = m.content + AppMessageContent.ToolUse(
                            toolCallId = event.toolCallId,
                            toolName = event.toolName,
                            input = event.input,
                        ),
                    )
                }),
            )
        }

        is AppEvent.StreamingPartsCleared -> {
            val msgs = state.messagesBySession[event.sessionId] ?: return state
            state.copy(
                messagesBySession = state.messagesBySession + (event.sessionId to msgs.map { m ->
                    if (m.id != event.messageId) return@map m
                    m.copy(
                        content = m.content.filterNot {
                            it is AppMessageContent.Text ||
                                it is AppMessageContent.Thinking ||
                                it is AppMessageContent.ToolUse
                        },
                    )
                }),
            )
        }

        is AppEvent.StreamingMessageFinished -> {
            val msgs = state.messagesBySession[event.sessionId] ?: return state
            state.copy(
                messagesBySession = state.messagesBySession + (event.sessionId to msgs.map { m ->
                    if (m.id != event.messageId) return@map m
                    m.copy(durationMs = event.durationMs ?: m.durationMs)
                }),
            )
        }

        is AppEvent.TurnActiveChanged -> state.copy(
            sessions = state.sessions.map { s ->
                if (s.id == event.sessionId) s.copy(main_turn_active = event.active) else s
            },
            turnActiveBySession =
            if (event.active) state.turnActiveBySession + (event.sessionId to true)
            else state.turnActiveBySession - event.sessionId,
        )

        is AppEvent.CompactionStarted -> state.copy(
            compactionBySession = state.compactionBySession + (event.sessionId to event.trigger),
        )

        is AppEvent.CompactionCompleted -> {
            // 追加持久"上下文已压缩"分隔标记（id 由 seq 派生，重放不会重复）
            val msgs = state.messagesBySession[event.sessionId] ?: return state.copy(
                compactionBySession = state.compactionBySession - event.sessionId,
            )
            val markerId = "compaction_${event.sessionId}_${event.seq}"
            if (msgs.any { it.id == markerId }) {
                return state.copy(compactionBySession = state.compactionBySession - event.sessionId)
            }
            val markerMeta = JsonObject(
                mapOf(
                    "origin" to JsonObject(mapOf("kind" to kotlinx.serialization.json.JsonPrimitive("compaction_summary"))),
                ),
            )
            val marker = AppMessage(
                id = markerId,
                sessionId = event.sessionId,
                role = AppRole.ASSISTANT,
                content = event.summary?.let { listOf(AppMessageContent.Text(it)) } ?: emptyList(),
                createdAt = "",
                metadata = markerMeta,
            )
            state.copy(
                compactionBySession = state.compactionBySession - event.sessionId,
                messagesBySession = state.messagesBySession + (event.sessionId to (msgs + marker)),
            )
        }

        is AppEvent.CompactionCancelled -> state.copy(
            compactionBySession = state.compactionBySession - event.sessionId,
        )

        is AppEvent.MessageCreated -> {
            val sid = event.message.sessionId
            val msgs = state.messagesBySession[sid] ?: emptyList()
            val sessions = state.sessions.map { s ->
                if (s.id == sid && event.message.createdAt > s.updated_at) {
                    s.copy(updated_at = event.message.createdAt)
                } else {
                    s
                }
            }
            if (msgs.any { it.id == event.message.id }) {
                return state.copy(sessions = sessions)
            }
            if (event.message.role == AppRole.USER && !event.message.isCronOrigin()) {
                val echoIndex = findOptimisticUserEchoIndex(msgs, event.message)
                if (echoIndex >= 0) {
                    val optimistic = msgs[echoIndex]
                    val reconciled = event.message.copy(
                        id = optimistic.id,
                        promptId = event.message.promptId ?: optimistic.promptId,
                    )
                    val updated = msgs.toMutableList().apply { set(echoIndex, reconciled) }
                    return state.copy(
                        sessions = sessions,
                        messagesBySession = state.messagesBySession + (sid to updated),
                    )
                }
            }
            state.copy(
                sessions = sessions,
                messagesBySession = state.messagesBySession + (sid to (msgs + event.message)),
            )
        }

        is AppEvent.MessageUpdated -> {
            val msgs = state.messagesBySession[event.sessionId] ?: return state
            state.copy(
                messagesBySession = state.messagesBySession + (event.sessionId to msgs.map { m ->
                    if (m.id != event.messageId) {
                        m
                    } else {
                        m.copy(
                            content = event.content,
                            durationMs = event.durationMs ?: m.durationMs,
                        )
                    }
                }),
            )
        }

        is AppEvent.AssistantDelta -> {
            val msgs = state.messagesBySession[event.sessionId] ?: return state
            state.copy(
                messagesBySession = state.messagesBySession + (event.sessionId to msgs.map { m ->
                    if (m.id != event.messageId) return@map m
                    if (event.contentIndex < 0) {
                        // 流式形式：追加到末尾同类块，末尾是工具调用则新开一块
                        val last = m.content.lastOrNull()
                        val appended = when {
                            event.text != null && last is AppMessageContent.Text ->
                                m.content.dropLast(1) + AppMessageContent.Text(last.text + event.text)

                            event.thinking != null && last is AppMessageContent.Thinking ->
                                m.content.dropLast(1) + last.copy(thinking = last.thinking + event.thinking)

                            event.text != null -> m.content + AppMessageContent.Text(event.text)
                            event.thinking != null -> m.content + AppMessageContent.Thinking(event.thinking)
                            else -> m.content
                        }
                        return@map m.copy(content = appended)
                    }
                    val content = m.content.toMutableList()
                    while (content.size <= event.contentIndex) {
                        content.add(AppMessageContent.Text(""))
                    }
                    val existing = content[event.contentIndex]
                    val patched: AppMessageContent = when {
                        event.text != null ->
                            if (existing is AppMessageContent.Text) {
                                AppMessageContent.Text(existing.text + event.text)
                            } else {
                                AppMessageContent.Text(event.text)
                            }

                        event.thinking != null ->
                            if (existing is AppMessageContent.Thinking) {
                                existing.copy(thinking = existing.thinking + event.thinking)
                            } else {
                                AppMessageContent.Thinking(event.thinking)
                            }

                        else -> existing
                    }
                    content[event.contentIndex] = patched
                    m.copy(content = content)
                }),
            )
        }

        is AppEvent.ToolOutput -> {
            val msgs = state.messagesBySession[event.sessionId] ?: return state
            state.copy(
                messagesBySession = state.messagesBySession +
                    (event.sessionId to appendToolOutput(msgs, event.toolCallId, event.outputChunk)),
            )
        }

        is AppEvent.ApprovalRequested -> {
            val sid = event.approval.session_id
            val list = state.approvalsBySession[sid] ?: emptyList()
            val added = if (list.any { it.approval_id == event.approval.approval_id }) {
                list
            } else {
                list + event.approval
            }
            // plan_review 展示留存：审批解决后 ExitPlanMode 工具卡仍能打开计划
            val planReview = run {
                val display = (event.approval.display ?: event.approval.tool_input_display) as? JsonObject
                val kind = display?.get("kind")?.jsonPrimitive?.content
                val plan = display?.get("plan")?.jsonPrimitive?.content
                if (kind == "plan_review" && !plan.isNullOrEmpty()) {
                    val path = display["path"]?.jsonPrimitive?.content
                    state.planReviewByToolCallId + (event.approval.tool_call_id to AppState.PlanReview(plan, path))
                } else {
                    state.planReviewByToolCallId
                }
            }
            state.copy(
                approvalsBySession = state.approvalsBySession + (sid to added),
                planReviewByToolCallId = planReview,
            )
        }

        is AppEvent.ApprovalFinished -> {
            val list = state.approvalsBySession[event.sessionId] ?: return state
            state.copy(
                approvalsBySession = state.approvalsBySession +
                    (event.sessionId to list.filterNot { it.approval_id == event.approvalId }),
            )
        }

        is AppEvent.QuestionRequested -> {
            val sid = event.question.session_id
            val list = state.questionsBySession[sid] ?: emptyList()
            if (list.any { it.question_id == event.question.question_id }) return state
            state.copy(questionsBySession = state.questionsBySession + (sid to (list + event.question)))
        }

        is AppEvent.QuestionFinished -> {
            val list = state.questionsBySession[event.sessionId] ?: return state
            state.copy(
                questionsBySession = state.questionsBySession +
                    (event.sessionId to list.filterNot { it.question_id == event.questionId }),
            )
        }

        is AppEvent.TaskUpserted -> {
            val sid = event.task.sessionId
            val list = state.tasksBySession[sid] ?: emptyList()
            val idx = list.indexOfFirst { it.id == event.task.id }
            val next = if (idx < 0) {
                list + event.task
            } else {
                val previous = list[idx]
                list.toMutableList().apply {
                    set(
                        idx,
                        event.task.copy(
                            outputLines = previous.outputLines,
                            text = previous.text,
                            description = event.task.description.ifBlank { previous.description },
                            swarmIndex = event.task.swarmIndex ?: previous.swarmIndex,
                            parentToolCallId = event.task.parentToolCallId ?: previous.parentToolCallId,
                            subagentType = event.task.subagentType ?: previous.subagentType,
                            runInBackground = event.task.runInBackground ?: previous.runInBackground,
                        ),
                    )
                }
            }
            state.copy(tasksBySession = state.tasksBySession + (sid to next))
        }

        is AppEvent.TaskProgressed -> {
            val list = state.tasksBySession[event.sessionId] ?: return state
            state.copy(
                tasksBySession = state.tasksBySession + (event.sessionId to list.map { t ->
                    if (t.id != event.taskId) return@map t
                    if (t.kind == "subagent" && event.kind == "text") {
                        t.copy(text = (t.text ?: "") + event.outputChunk)
                    } else {
                        val lines = t.outputLines ?: emptyList()
                        if (lines.lastOrNull() == event.outputChunk) {
                            t
                        } else {
                            val appended = lines + event.outputChunk
                            t.copy(
                                outputLines =
                                if (t.kind == "subagent") appended else appended.takeLast(MAX_BACKGROUND_OUTPUT_LINES),
                            )
                        }
                    }
                }),
            )
        }

        is AppEvent.TaskFinished -> {
            val list = state.tasksBySession[event.sessionId] ?: return state
            state.copy(
                tasksBySession = state.tasksBySession + (event.sessionId to list.map { t ->
                    if (t.id == event.taskId) {
                        t.copy(
                            status = event.status,
                            outputPreview = event.outputPreview,
                            outputBytes = event.outputBytes,
                        )
                    } else {
                        t
                    }
                }),
            )
        }

        is AppEvent.GoalUpdated -> state.copy(
            goalBySession =
            if (event.goal == null || event.goal.status == "complete") {
                state.goalBySession - event.sessionId
            } else {
                state.goalBySession + (event.sessionId to event.goal)
            },
        )

        is AppEvent.WorkspaceUpserted -> {
            val exists = state.workspaces.any { it.id == event.workspace.id }
            state.copy(
                workspaces =
                if (exists) {
                    state.workspaces.map { if (it.id == event.workspace.id) event.workspace else it }
                } else {
                    state.workspaces + event.workspace
                },
            )
        }

        is AppEvent.WorkspaceDeleted -> state.copy(
            workspaces = state.workspaces.filterNot { it.id == event.workspaceId },
        )

        is AppEvent.NoticeAdded -> state.copy(notices = state.notices + event.notice)
    }
}

package com.kimi.app.data.store

import com.github.f4b6a3.ulid.UlidCreator
import com.kimi.app.core.util.bool
import com.kimi.app.core.util.long
import com.kimi.app.core.util.obj
import com.kimi.app.core.util.str
import com.kimi.app.data.api.wireJson
import com.kimi.app.data.wire.WireGoalSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun ulid(prefix: String): String = prefix + UlidCreator.getUlid().toString().lowercase()

/**
 * 原始 agent-core 事件 → AppEvent 投影器（移植 agentEventProjector.ts 主会话路径）。
 *
 * kap-server 的流式文本走 volatile `assistant.delta`/`thinking.delta`（payload.delta 为字符串、
 * 信封带 step 相对 offset），而不是协议事件。投影器维护"当前流式 assistant 消息"，
 * 把增量对齐追加后产出 assistantDelta/messageUpdated 事件；持久化消息经协议
 * message.created 到达后由 TurnGrouper 的内容签名去重。
 */
class AgentProjector {

    private class SessionState {
        var currentPromptId: String? = null
        val turnPromptId = mutableMapOf<Long, String>()
        var currentAssistantMsgId: String? = null
        var turnTextLen = 0
        var turnThinkLen = 0
        var retryReuseMsgId: String? = null
        var model: String? = null
    }

    private val sessions = mutableMapOf<String, SessionState>()

    fun reset(sessionId: String) {
        sessions[sessionId] = SessionState()
    }

    private fun stateOf(sessionId: String): SessionState =
        sessions.getOrPut(sessionId) { SessionState() }

    // -----------------------------------------------------------------------
    // in-flight 播种（快照 → 流式中间态）
    // -----------------------------------------------------------------------

    fun seedInFlight(sessionId: String, turn: com.kimi.app.data.wire.WireInFlightTurn): List<AppEvent> {
        reset(sessionId)
        val s = stateOf(sessionId)
        val promptId = turn.current_prompt_id ?: ulid("pr_")
        s.currentPromptId = promptId
        s.turnPromptId[turn.turn_id] = promptId

        val content = mutableListOf<AppMessageContent>()
        if (turn.thinking_text.isNotEmpty()) content.add(AppMessageContent.Thinking(turn.thinking_text))
        if (turn.assistant_text.isNotEmpty()) content.add(AppMessageContent.Text(turn.assistant_text))
        for (tool in turn.running_tools) {
            content.add(
                AppMessageContent.ToolUse(
                    toolCallId = tool.tool_call_id,
                    toolName = tool.name,
                    input = tool.args,
                    outputLines = tool.last_progress?.text?.takeIf { it.isNotEmpty() }?.let { listOf(it) },
                ),
            )
        }
        // id 由 turnId 派生：重复快照替换同一条而非追加
        val msgId = "stream_${turn.turn_id}"
        s.currentAssistantMsgId = msgId
        s.turnTextLen = turn.assistant_text.length
        s.turnThinkLen = turn.thinking_text.length
        return listOf(
            AppEvent.MessageCreated(
                AppMessage(
                    id = msgId,
                    sessionId = sessionId,
                    role = AppRole.ASSISTANT,
                    content = content,
                    createdAt = "",
                    promptId = promptId,
                ),
            ),
        )
    }

    // -----------------------------------------------------------------------
    // 主分派
    // -----------------------------------------------------------------------

    /**
     * @param offset 信封级 offset（step 相对），volatile delta 专用
     */
    fun project(
        type: String,
        sessionId: String,
        offset: Long?,
        payload: JsonObject,
    ): List<AppEvent> {
        // 子代理帧（payload.agentId != "main"）不进主会话投影；任务面板走协议 task 事件
        val agentId = payload.str("agentId")
        if (agentId != null && agentId != "main") return emptyList()
        val s = stateOf(sessionId)
        return when (type) {
            "turn.started" -> {
                val turnId = payload.long("turnId")
                val promptId = s.currentPromptId ?: ulid("pr_")
                s.currentPromptId = promptId
                if (turnId != null) s.turnPromptId[turnId] = promptId
                s.turnTextLen = 0
                s.turnThinkLen = 0
                listOf(AppEvent.TurnActiveChanged(sessionId, active = true))
            }

            "prompt.submitted" -> {
                payload.str("promptId")?.let { s.currentPromptId = it }
                emptyList()
            }

            "turn.step.started" -> {
                val turnId = payload.long("turnId")
                val promptId = turnId?.let { s.turnPromptId[it] } ?: s.currentPromptId ?: ulid("pr_").also {
                    s.currentPromptId = it
                    if (turnId != null) s.turnPromptId[turnId] = it
                }
                // 新 step → 流式 offset 归零（offset 是 step 相对）
                s.turnTextLen = 0
                s.turnThinkLen = 0
                // 重试续用：retrying 清空气泡后原地复用
                val reuseId = s.retryReuseMsgId
                if (reuseId != null) {
                    s.retryReuseMsgId = null
                    s.currentAssistantMsgId = reuseId
                    return emptyList()
                }
                val msgId = ulid("stream_")
                s.currentAssistantMsgId = msgId
                listOf(
                    AppEvent.MessageCreated(
                        AppMessage(
                            id = msgId,
                            sessionId = sessionId,
                            role = AppRole.ASSISTANT,
                            content = emptyList(),
                            createdAt = "",
                            promptId = promptId,
                        ),
                    ),
                )
            }

            "assistant.delta", "thinking.delta" -> {
                val msgId = s.currentAssistantMsgId ?: return emptyList()
                val delta = payload.str("delta") ?: return emptyList()
                if (delta.isEmpty()) return emptyList()
                val isThinking = type == "thinking.delta"
                val currentLen = if (isThinking) s.turnThinkLen else s.turnTextLen
                // 错过的回合边界自愈：offset=0 且以为还在流中 → 重新计步
                if (offset == 0L && currentLen > 0) {
                    if (isThinking) s.turnThinkLen = 0 else s.turnTextLen = 0
                }
                when (alignDelta(if (isThinking) s.turnThinkLen else s.turnTextLen, offset)) {
                    DeltaAlign.SKIP -> return emptyList()
                    DeltaAlign.GAP -> return listOf(
                        // delta 断档：唯一精确恢复是全量快照（KimiClient 触发 resync）
                        AppEvent.NoticeAdded(AppNotice(id = 0, message = "流式增量断档，正在重新同步…")),
                        AppEvent.ResyncRequested(sessionId),
                    )

                    DeltaAlign.APPEND -> Unit
                }
                if (isThinking) s.turnThinkLen += delta.length else s.turnTextLen += delta.length
                listOf(
                    AppEvent.AssistantDelta(
                        sessionId = sessionId,
                        messageId = msgId,
                        // 追加到最后一个同类 part；索引由 reducer 按现有内容长度推导：
                        // 这里用 -1 表示"追加到末尾同类块"，reducer 侧特殊处理
                        contentIndex = -1,
                        text = if (isThinking) null else delta,
                        thinking = if (isThinking) delta else null,
                    ),
                )
            }

            "tool.call.started", "tool.use" -> {
                val msgId = s.currentAssistantMsgId ?: return emptyList()
                val toolCallId = payload.str("toolCallId") ?: return emptyList()
                val toolName = payload.str("name") ?: payload.str("toolName") ?: ""
                val input = payload["args"] ?: payload["input"]
                listOf(
                    AppEvent.StreamingToolUseAdded(
                        sessionId = sessionId,
                        messageId = msgId,
                        toolCallId = toolCallId,
                        toolName = toolName,
                        input = input,
                    ),
                )
            }

            "tool.progress" -> {
                val toolCallId = payload.str("toolCallId") ?: return emptyList()
                val text = payload.str("text") ?: payload.str("message") ?: return emptyList()
                if (text.isEmpty()) return emptyList()
                listOf(AppEvent.ToolOutput(sessionId, toolCallId, text))
            }

            "tool.result" -> {
                val toolCallId = payload.str("toolCallId") ?: return emptyList()
                val output = payload["output"]
                val isError = payload.bool("isError") ?: false
                val promptId = s.currentPromptId
                s.currentAssistantMsgId = null
                listOf(
                    AppEvent.MessageCreated(
                        AppMessage(
                            id = ulid("toolres_"),
                            sessionId = sessionId,
                            role = AppRole.TOOL,
                            content = listOf(
                                AppMessageContent.ToolResult(toolCallId, output, isError),
                            ),
                            createdAt = "",
                            promptId = promptId,
                        ),
                    ),
                )
            }

            "turn.step.retrying" -> {
                val msgId = s.currentAssistantMsgId ?: return emptyList()
                s.retryReuseMsgId = msgId
                s.turnTextLen = 0
                s.turnThinkLen = 0
                listOf(AppEvent.StreamingPartsCleared(sessionId, msgId))
            }

            "turn.step.completed", "turn.step.interrupted" -> emptyList()

            "turn.ended" -> {
                val reason = payload.str("reason") ?: "completed"
                val msgId = s.currentAssistantMsgId
                s.currentAssistantMsgId = null
                buildList {
                    add(AppEvent.TurnActiveChanged(sessionId, active = false, reason = reason))
                    if (msgId != null) {
                        add(
                            AppEvent.StreamingMessageFinished(
                                sessionId = sessionId,
                                messageId = msgId,
                                isError = reason == "failed" || reason == "blocked",
                                durationMs = payload.long("durationMs"),
                            ),
                        )
                    }
                }
            }

            "agent.status.updated" -> {
                if (payload.str("model") != null) s.model = payload.str("model")
                listOf(
                    AppEvent.SessionUsageUpdated(
                        sessionId = sessionId,
                        usage = null,
                        model = s.model,
                        planMode = payload.bool("planMode"),
                        thinking = payload.str("thinkingEffort")?.takeIf { it.isNotEmpty() },
                        permissionMode = payload.str("permissionMode"),
                        contextTokens = payload.long("contextTokens"),
                        maxContextTokens = payload.long("maxContextTokens"),
                    ),
                )
            }

            "session.meta.updated" -> {
                val patch = payload.obj("patch")
                val title = patch?.str("title") ?: payload.str("title")
                val lastPrompt = patch?.str("lastPrompt")
                if (title.isNullOrEmpty() && lastPrompt == null) {
                    emptyList()
                } else {
                    listOf(
                        AppEvent.SessionMetaUpdated(
                            sessionId,
                            title?.takeIf { it.isNotEmpty() },
                            lastPrompt,
                        ),
                    )
                }
            }

            "prompt.completed" -> {
                val promptId = payload.str("promptId") ?: return emptyList()
                listOf(AppEvent.PromptFinished(sessionId, promptId, payload.str("reason") ?: "completed"))
            }

            "prompt.aborted" -> {
                val promptId = payload.str("promptId") ?: return emptyList()
                listOf(AppEvent.PromptFinished(sessionId, promptId, "aborted"))
            }

            "compaction.started" -> listOf(
                AppEvent.CompactionStarted(
                    sessionId,
                    trigger = if (payload.str("trigger") == "manual") "manual" else "auto",
                ),
            )

            "compaction.completed" -> {
                val result = payload.obj("result") ?: JsonObject(emptyMap())
                listOf(
                    AppEvent.CompactionCompleted(
                        sessionId = sessionId,
                        seq = 0, // 由 KimiClient 以帧 seq 填充
                        summary = result.str("summary"),
                        tokensBefore = result.long("tokensBefore"),
                        tokensAfter = result.long("tokensAfter"),
                    ),
                )
            }

            "compaction.cancelled" -> listOf(AppEvent.CompactionCancelled(sessionId))

            "goal.updated" -> {
                val snapshot = payload["snapshot"]?.let {
                    runCatching {
                        wireJson.decodeFromJsonElement(WireGoalSnapshot.serializer(), it)
                    }.getOrNull()
                }
                listOf(AppEvent.GoalUpdated(sessionId, snapshot?.takeIf { it.status != "complete" }))
            }

            "error" -> listOf(
                AppEvent.NoticeAdded(
                    AppNotice(
                        id = 0,
                        message = payload.str("message") ?: payload.str("code") ?: "agent 错误",
                        isError = true,
                    ),
                ),
            )

            "warning" -> listOf(
                AppEvent.NoticeAdded(
                    AppNotice(id = 0, message = payload.str("message") ?: payload.str("code") ?: "agent 警告"),
                ),
            )

            else -> emptyList()
        }
    }

    private enum class DeltaAlign { APPEND, SKIP, GAP }

    private fun alignDelta(currentLen: Int, offset: Long?): DeltaAlign = when {
        offset == null -> DeltaAlign.APPEND
        offset == currentLen.toLong() -> DeltaAlign.APPEND
        offset < currentLen -> DeltaAlign.SKIP
        else -> DeltaAlign.GAP
    }
}

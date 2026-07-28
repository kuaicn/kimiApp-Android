package com.kimi.app

import com.kimi.app.data.store.AppEvent
import com.kimi.app.data.store.AppMessage
import com.kimi.app.data.store.AppMessageContent
import com.kimi.app.data.store.AppRole
import com.kimi.app.data.store.AppState
import com.kimi.app.data.store.ApprovalBlock
import com.kimi.app.data.store.EventReducer
import com.kimi.app.data.store.TurnGrouper
import com.kimi.app.data.store.TurnBlock
import com.kimi.app.data.store.TurnRole
import com.kimi.app.data.store.ToolStatus
import com.kimi.app.data.store.buildApprovalBlock
import com.kimi.app.data.wire.WireApprovalRequest
import com.kimi.app.data.wire.WireSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EventReducer 与 TurnGrouper 的核心用例（移植自 kimi-web eventReducer / messagesToTurns 语义）。
 */
class ReducerAndGrouperTest {

    // -----------------------------------------------------------------------
    // 构造辅助
    // -----------------------------------------------------------------------

    private fun msg(
        id: String,
        role: AppRole,
        content: List<AppMessageContent>,
        promptId: String? = null,
        originKind: String? = null,
        sessionId: String = "s1",
    ): AppMessage {
        val metadata = originKind?.let {
            JsonObject(mapOf("origin" to JsonObject(mapOf("kind" to JsonPrimitive(it)))))
        }
        return AppMessage(id = id, sessionId = sessionId, role = role, content = content, createdAt = "", promptId = promptId, metadata = metadata)
    }

    private fun text(t: String) = AppMessageContent.Text(t)
    private fun think(t: String) = AppMessageContent.Thinking(t)
    private fun toolUse(id: String, name: String = "Bash") = AppMessageContent.ToolUse(id, name, JsonObject(emptyMap()))
    private fun toolResult(id: String, out: String = "ok", isError: Boolean = false) =
        AppMessageContent.ToolResult(id, JsonPrimitive(out), isError)

    private fun approval(id: String, toolCallId: String, display: JsonObject? = null) = WireApprovalRequest(
        approval_id = id,
        session_id = "s1",
        tool_call_id = toolCallId,
        tool_name = "Bash",
        action = "run",
        display = display,
        expires_at = "",
        created_at = "",
    )

    // -----------------------------------------------------------------------
    // EventReducer
    // -----------------------------------------------------------------------

    @Test
    fun `messageCreated 追加且按 id 去重`() {
        val m = msg("m1", AppRole.ASSISTANT, listOf(text("a")))
        var state = AppState()
        state = EventReducer.reduce(state, AppEvent.MessageCreated(m))
        state = EventReducer.reduce(state, AppEvent.MessageCreated(m))
        assertEquals(1, state.messagesBySession["s1"]!!.size)
    }

    @Test
    fun `messageCreated 用户乐观回声按 promptId 对账`() {
        val optimistic = msg("local_abc", AppRole.USER, listOf(text("hi")))
        var state = EventReducer.reduce(AppState(), AppEvent.MessageCreated(optimistic))
        val echo = msg("msg_real", AppRole.USER, listOf(text("hi")), promptId = "p1")
        state = EventReducer.reduce(state, AppEvent.MessageCreated(echo))
        val msgs = state.messagesBySession["s1"]!!
        assertEquals(1, msgs.size)
        assertEquals("local_abc", msgs[0].id) // 保留乐观 id
        assertEquals("p1", msgs[0].promptId)
    }

    @Test
    fun `assistantDelta 按槽位追加文本与思考`() {
        val m = msg("m1", AppRole.ASSISTANT, emptyList())
        var state = EventReducer.reduce(AppState(), AppEvent.MessageCreated(m))
        state = EventReducer.reduce(state, AppEvent.AssistantDelta("s1", "m1", 0, text = "你", thinking = null))
        state = EventReducer.reduce(state, AppEvent.AssistantDelta("s1", "m1", 0, text = "好", thinking = null))
        state = EventReducer.reduce(state, AppEvent.AssistantDelta("s1", "m1", 1, text = null, thinking = "想"))
        val content = state.messagesBySession["s1"]!![0].content
        assertEquals("你好", (content[0] as AppMessageContent.Text).text)
        assertEquals("想", (content[1] as AppMessageContent.Thinking).thinking)
    }

    @Test
    fun `assistantDelta 流式形式追加到末尾同类块`() {
        val m = msg("m1", AppRole.ASSISTANT, listOf(text("a"), toolUse("t1")))
        var state = EventReducer.reduce(AppState(), AppEvent.MessageCreated(m))
        // 末尾是 toolUse：新文本应另开一块而不是并入旧文本
        state = EventReducer.reduce(state, AppEvent.AssistantDelta("s1", "m1", -1, text = "b", thinking = null))
        state = EventReducer.reduce(state, AppEvent.AssistantDelta("s1", "m1", -1, text = "c", thinking = null))
        val content = state.messagesBySession["s1"]!![0].content
        assertEquals(3, content.size)
        assertEquals("bc", (content[2] as AppMessageContent.Text).text)
    }

    @Test
    fun `审批请求与终结生命周期`() {
        var state = EventReducer.reduce(AppState(), AppEvent.ApprovalRequested(approval("a1", "t1")))
        assertEquals(1, state.approvalsBySession["s1"]!!.size)
        state = EventReducer.reduce(state, AppEvent.ApprovalFinished("s1", "a1"))
        assertEquals(0, state.approvalsBySession["s1"]!!.size)
    }

    @Test
    fun `toolOutput 追加到匹配 toolUse 的 outputLines`() {
        val m = msg("m1", AppRole.ASSISTANT, listOf(toolUse("t1")))
        var state = EventReducer.reduce(AppState(), AppEvent.MessageCreated(m))
        state = EventReducer.reduce(state, AppEvent.ToolOutput("s1", "t1", "line1"))
        val tool = state.messagesBySession["s1"]!![0].content[0] as AppMessageContent.ToolUse
        assertEquals(listOf("line1"), tool.outputLines)
    }

    // -----------------------------------------------------------------------
    // TurnGrouper
    // -----------------------------------------------------------------------

    @Test
    fun `连续 assistant 消息合并为一回合`() {
        val turns = TurnGrouper.messagesToTurns(
            messages = listOf(
                msg("u1", AppRole.USER, listOf(text("问"))),
                msg("a1", AppRole.ASSISTANT, listOf(text("答"), toolUse("t1"))),
                msg("tr1", AppRole.TOOL, listOf(toolResult("t1", "done"))),
                msg("a2", AppRole.ASSISTANT, listOf(text("完"))),
            ),
            approvals = emptyList(),
        )
        assertEquals(2, turns.size)
        assertEquals(TurnRole.USER, turns[0].role)
        assertEquals(TurnRole.ASSISTANT, turns[1].role)
        assertEquals("答\n完", turns[1].text)
        assertEquals(ToolStatus.OK, turns[1].tools!![0].status)
    }

    @Test
    fun `promptId 不同才拆组`() {
        val turns = TurnGrouper.messagesToTurns(
            messages = listOf(
                msg("a1", AppRole.ASSISTANT, listOf(text("一")), promptId = "p1"),
                msg("a2", AppRole.ASSISTANT, listOf(text("二")), promptId = "p2"),
            ),
            approvals = emptyList(),
        )
        assertEquals(2, turns.size)
    }

    @Test
    fun `流式副本与持久副本去重`() {
        // 同 promptId、同内容签名（流式副本缺 toolUse output 细节也算覆盖）
        val turns = TurnGrouper.messagesToTurns(
            messages = listOf(
                msg("stream_1", AppRole.ASSISTANT, listOf(text("你好"), toolUse("t1")), promptId = "p1"),
                msg("msg_real", AppRole.ASSISTANT, listOf(text("你好"), toolUse("t1")), promptId = "p1"),
                msg("tr1", AppRole.TOOL, listOf(toolResult("t1"))),
            ),
            approvals = emptyList(),
        )
        assertEquals(1, turns.size)
        assertEquals(1, turns[0].tools!!.size)
    }

    @Test
    fun `系统注入 user 消息不渲染`() {
        val turns = TurnGrouper.messagesToTurns(
            messages = listOf(
                msg("u1", AppRole.USER, listOf(text("注入")), originKind = "compaction_inject"),
                msg("a1", AppRole.ASSISTANT, listOf(text("答"))),
            ),
            approvals = emptyList(),
        )
        assertEquals(1, turns.size)
        assertEquals(TurnRole.ASSISTANT, turns[0].role)
    }

    @Test
    fun `compaction 摘要渲染为分隔条`() {
        val turns = TurnGrouper.messagesToTurns(
            messages = listOf(
                msg("a1", AppRole.ASSISTANT, listOf(text("旧"))),
                msg("c1", AppRole.ASSISTANT, listOf(text("摘要")), originKind = "compaction_summary"),
                msg("a2", AppRole.ASSISTANT, listOf(text("新"))),
            ),
            approvals = emptyList(),
        )
        assertEquals(3, turns.size)
        assertEquals(TurnRole.COMPACTION, turns[1].role)
    }

    @Test
    fun `非活跃会话的悬挂 running 工具按已结束结算`() {
        val turns = TurnGrouper.messagesToTurns(
            messages = listOf(msg("a1", AppRole.ASSISTANT, listOf(toolUse("t1")))),
            approvals = emptyList(),
            sessionActive = false,
        )
        assertEquals(ToolStatus.OK, turns[0].tools!![0].status)
    }

    @Test
    fun `审批块按 display kind 构造`() {
        val shell = buildApprovalBlock(
            approval(
                "a1", "t1",
                JsonObject(
                    mapOf(
                        "kind" to JsonPrimitive("shell"),
                        "command" to JsonPrimitive("rm -rf /tmp/x"),
                    ),
                ),
            ),
        )
        assertTrue(shell is ApprovalBlock.Shell)
        assertEquals("rm -rf /tmp/x", (shell as ApprovalBlock.Shell).command)

        val diff = buildApprovalBlock(
            approval(
                "a2", "t2",
                JsonObject(
                    mapOf(
                        "kind" to JsonPrimitive("diff"),
                        "path" to JsonPrimitive("a.txt"),
                        "old_text" to JsonPrimitive("旧"),
                        "new_text" to JsonPrimitive("新"),
                    ),
                ),
            ),
        )
        assertTrue(diff is ApprovalBlock.Diff)
        assertEquals("a.txt", (diff as ApprovalBlock.Diff).path)
        assertEquals(2, diff.diff.size)

        val generic = buildApprovalBlock(approval("a3", "t3", JsonObject(emptyMap())))
        assertTrue(generic is ApprovalBlock.Generic)
    }

    @Test
    fun `审批按 toolCallId 关联到回合`() {
        val turns = TurnGrouper.messagesToTurns(
            messages = listOf(msg("a1", AppRole.ASSISTANT, listOf(toolUse("t1")))),
            approvals = listOf(
                approval(
                    "a1", "t1",
                    JsonObject(mapOf("kind" to JsonPrimitive("shell"), "command" to JsonPrimitive("ls"))),
                ),
            ),
        )
        assertEquals("a1", turns[0].approvalId)
        assertTrue(turns[0].approval is ApprovalBlock.Shell)
    }
}

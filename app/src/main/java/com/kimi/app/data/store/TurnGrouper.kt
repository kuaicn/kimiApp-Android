package com.kimi.app.data.store

import com.kimi.app.core.util.arr
import com.kimi.app.core.util.bool
import com.kimi.app.core.util.long
import com.kimi.app.core.util.obj
import com.kimi.app.core.util.str
import com.kimi.app.data.wire.WireApprovalRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

// messagesToTurns.ts 的 Kotlin 移植：把扁平消息列表折叠为渲染用 ChatTurn 列表。
// 关键规则与 TS 版一致：
// - user 消息与 compaction 摘要是硬边界；连续 assistant 合并为一回合（promptId 不同才拆）
// - tool 角色消息折叠进前组；内容签名去重（流式副本 vs 持久副本）
// - 仅最后一组在会话活跃时保留 running 工具，其余悬挂工具按已结束处理

// ---------------------------------------------------------------------------
// 渲染模型
// ---------------------------------------------------------------------------

enum class TurnRole { USER, ASSISTANT, COMPACTION, CRON }

enum class ToolStatus { RUNNING, OK, ERROR }

data class ToolCallUi(
    val id: String,
    val name: String,
    val arg: String,
    val status: ToolStatus = ToolStatus.RUNNING,
    val output: List<String>? = null,
    val planPath: String? = null,
)

sealed interface TurnBlock {
    data class TextBlock(val text: String) : TurnBlock

    data class ThinkingBlock(val thinking: String) : TurnBlock

    data class ToolBlock(val tool: ToolCallUi) : TurnBlock
}

data class DiffLine(val kind: String, val gutter: String, val text: String)

data class TodoItemUi(val title: String, val status: String)

data class PlanOptionUi(val label: String, val description: String?)

/** 审批展示块（buildApprovalBlock 移植，display.kind 判别） */
sealed interface ApprovalBlock {
    data class Diff(val path: String, val diff: List<DiffLine>) : ApprovalBlock

    data class Shell(val command: String, val cwd: String?, val danger: String?) : ApprovalBlock

    data class FileContent(val path: String, val content: String, val language: String?) : ApprovalBlock

    data class FileOp(val op: String, val path: String, val detail: String?) : ApprovalBlock

    data class UrlFetch(val method: String?, val url: String) : ApprovalBlock

    data class Search(val query: String, val scope: String?) : ApprovalBlock

    data class Invocation(val kind2: String, val name: String, val description: String?) : ApprovalBlock

    data class Todo(val items: List<TodoItemUi>) : ApprovalBlock

    data class PlanReview(val plan: String, val path: String?, val options: List<PlanOptionUi>?) : ApprovalBlock

    data class Generic(val summary: String) : ApprovalBlock
}

data class TurnAttachment(
    val url: String,
    val kind: String, // image / video / audio / file
    val fileId: String? = null,
    val name: String? = null,
    val mediaType: String? = null,
    val size: Long? = null,
)

data class SkillActivationUi(val name: String, val args: String?)

data class ChatTurn(
    val id: String,
    val role: TurnRole,
    val no: Int,
    val text: String,
    val thinking: String? = null,
    val tools: List<ToolCallUi>? = null,
    val blocks: List<TurnBlock>? = null,
    val approval: ApprovalBlock? = null,
    val approvalId: String? = null,
    val attachments: List<TurnAttachment>? = null,
    val skillActivation: SkillActivationUi? = null,
    val createdAt: String = "",
    val durationMs: Long? = null,
)

// ---------------------------------------------------------------------------
// 正则与常量（与 TS 版一一对应）
// ---------------------------------------------------------------------------

private val USER_MEDIA_PATH_TAG_RE = Regex("""^<(image|video|audio)\s+path="([^"]+)">(?:</\1>)?$""")
private val FILE_STORE_ID_RE =
    Regex("""^f_(?:[0-9A-Za-z]{26}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})$""")
private val FILE_STORE_ID_AT_START_RE =
    Regex("""^f_(?:[0-9A-Za-z]{26}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})(?=-)""")
private val ATTACHED_FILE_NOTICE_RE =
    Regex("""^Attached file "(.+)" \(([^,]+), (\d+) bytes\): (.+) — open it with the Read tool$""")
private val CAPTION_OPENING = "<system>Image compressed to fit model limits:"
private val CAPTION_PATTERN = Regex("""<system>Image compressed to fit model limits:[\s\S]*?</system>""")

private fun stripImageCompressionCaptions(text: String): String {
    if (!text.contains(CAPTION_OPENING)) return text
    return text.replace(CAPTION_PATTERN, "")
}

private fun unescapeAttr(value: String): String =
    value.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

private fun mediaPathTag(text: String): Pair<String, String>? {
    val m = USER_MEDIA_PATH_TAG_RE.find(text.trim()) ?: return null
    return (m.groupValues[1]) to unescapeAttr(m.groupValues[2])
}

private fun fileIdFromCachePath(p: String): String? {
    val base = p.split('/', '\\').lastOrNull() ?: return null
    val dot = base.lastIndexOf('.')
    val id = if (dot > 0) base.substring(0, dot) else base
    return if (FILE_STORE_ID_RE.matches(id)) id else null
}

private data class AttachedFileNotice(val name: String, val mediaType: String, val size: Long, val fileId: String?)

private fun attachedFileNotice(text: String): AttachedFileNotice? {
    val m = ATTACHED_FILE_NOTICE_RE.find(text.trim()) ?: return null
    val base = m.groupValues[4].split('/', '\\').lastOrNull() ?: ""
    val idCandidate = FILE_STORE_ID_AT_START_RE.find(base)?.value
    val fileId = idCandidate?.takeIf { FILE_STORE_ID_RE.matches(it) }
    return AttachedFileNotice(
        name = m.groupValues[1],
        mediaType = m.groupValues[2],
        size = m.groupValues[3].toLongOrNull() ?: 0,
        fileId = fileId,
    )
}

// ---------------------------------------------------------------------------
// 工具输出规整（normalizeToolOutput 移植）
// ---------------------------------------------------------------------------

private fun normalizeToolOutput(output: JsonElement?): List<String>? {
    if (output == null || output is kotlinx.serialization.json.JsonNull) return null
    if (output is JsonPrimitive) {
        return if (output.isString) output.content.split('\n') else listOf(output.content)
    }
    if (output is JsonArray) {
        val lines = mutableListOf<String>()
        for (part in output) {
            when (part) {
                is JsonPrimitive -> lines.addAll(part.content.split('\n'))
                is JsonObject -> {
                    when (part.str("type")) {
                        "text" -> part.str("text")?.let { lines.addAll(it.split('\n')) }
                        "think" -> part.str("think")?.let { lines.addAll(it.split('\n')) }
                        "image_url", "image" -> lines.add("[image]")
                        else -> {
                            val type = part.str("type")
                            lines.add(if (type != null) "[$type]" else part.toString())
                        }
                    }
                }

                else -> lines.add(part.toString())
            }
        }
        return lines.ifEmpty { null }
    }
    return listOf(output.toString())
}

private fun parsePlanSavedPath(output: List<String>?): String? {
    val marker = "Plan saved to: "
    return output?.firstOrNull { it.startsWith(marker) }?.removePrefix(marker)?.trim()
}

// ---------------------------------------------------------------------------
// 审批块构造（buildApprovalBlock 移植）
// ---------------------------------------------------------------------------

private fun buildDiffLines(oldText: String, newText: String): List<DiffLine> {
    val lines = mutableListOf<DiffLine>()
    oldText.split('\n').forEachIndexed { i, text -> lines.add(DiffLine("rem", "${i + 1}", "- $text")) }
    newText.split('\n').forEachIndexed { i, text -> lines.add(DiffLine("add", "${i + 1}", "+ $text")) }
    return lines
}

fun buildApprovalBlock(a: WireApprovalRequest): ApprovalBlock {
    val d = (a.display ?: a.tool_input_display) as? JsonObject ?: JsonObject(emptyMap())
    return when (val kind = d.str("kind") ?: "") {
        "diff" -> {
            val path = d.str("path") ?: ""
            val diffArray = d.arr("diff")
            if (diffArray != null) {
                val lines = diffArray.mapNotNull { item ->
                    (item as? JsonObject)?.let {
                        DiffLine(it.str("kind") ?: "", it.str("gutter") ?: "", it.str("text") ?: "")
                    }
                }
                ApprovalBlock.Diff(path, lines)
            } else {
                val oldText = d.str("old_text")
                val newText = d.str("new_text")
                if (oldText != null && newText != null) {
                    ApprovalBlock.Diff(path, buildDiffLines(oldText, newText))
                } else {
                    ApprovalBlock.Diff(path, emptyList())
                }
            }
        }

        "shell", "command" -> ApprovalBlock.Shell(
            command = d.str("command") ?: a.action,
            cwd = d.str("cwd"),
            danger = d.str("danger"),
        )

        "file_content", "file" -> ApprovalBlock.FileContent(
            path = d.str("path") ?: "",
            content = d.str("content") ?: "",
            language = d.str("language"),
        )

        "file_op", "fileop" -> ApprovalBlock.FileOp(
            op = d.str("operation") ?: d.str("op") ?: kind,
            path = d.str("path") ?: "",
            detail = d.str("detail"),
        )

        "url_fetch", "url" -> ApprovalBlock.UrlFetch(
            method = d.str("method"),
            url = d.str("url") ?: a.action,
        )

        "search" -> ApprovalBlock.Search(
            query = d.str("query") ?: a.action,
            scope = d.str("scope"),
        )

        "invocation", "agent_call", "skill_call" -> ApprovalBlock.Invocation(
            kind2 = kind,
            name = d.str("name") ?: a.tool_name,
            description = d.str("description"),
        )

        "todo", "todo_list" -> {
            val items = d.arr("items")?.mapNotNull { item ->
                (item as? JsonObject)?.let {
                    TodoItemUi(it.str("title") ?: "", it.str("status") ?: "pending")
                }
            } ?: emptyList()
            ApprovalBlock.Todo(items)
        }

        "plan_review" -> {
            val plan = d.str("plan") ?: ""
            val path = d.str("path")
            val options = d.arr("options")?.mapNotNull { item ->
                (item as? JsonObject)?.let { o ->
                    o.str("label")?.let { PlanOptionUi(it, o.str("description")) }
                }
            }
            ApprovalBlock.PlanReview(plan, path, options?.takeIf { it.isNotEmpty() })
        }

        else -> ApprovalBlock.Generic(a.action)
    }
}

// ---------------------------------------------------------------------------
// 元数据判定
// ---------------------------------------------------------------------------

private fun AppMessage.origin(): JsonObject? = metadata?.obj("origin")

private fun AppMessage.originKind(): String? = origin()?.str("kind")

private fun AppMessage.isDisplayableUserMessage(): Boolean {
    val origin = origin() ?: return true
    return when (origin.str("kind")) {
        null, "user" -> true
        "skill_activation", "plugin_command" -> origin.str("trigger") == "user-slash"
        else -> false
    }
}

private fun AppMessage.isCompactionSummary(): Boolean = originKind() == "compaction_summary"

private fun AppMessage.cronOriginKind(): String? = when (originKind()) {
    "cron_job", "cron_missed" -> originKind()
    else -> null
}

private fun extractCronPrompt(text: String): String {
    val open = "<prompt>\n"
    val close = "\n</prompt>"
    val start = text.indexOf(open)
    val end = text.lastIndexOf(close)
    if (start >= 0 && end >= start + open.length) {
        return text.substring(start + open.length, end)
    }
    val lines = text.split('\n')
    if (lines.size >= 2 && lines[0].startsWith("<cron-fire ") && lines.last() == "</cron-fire>") {
        return lines.subList(1, lines.size - 1).joinToString("\n")
    }
    return text
}

// ---------------------------------------------------------------------------
// 去重签名（contentSig / covers 移植）
// ---------------------------------------------------------------------------

private data class ContentSig(
    val text: String,
    val thinking: String,
    val toolIds: List<String>,
    val rest: List<String>,
)

private fun contentSig(content: List<AppMessageContent>): ContentSig {
    var text = ""
    var thinking = ""
    val toolIds = mutableListOf<String>()
    val rest = mutableListOf<String>()
    for (c in content) {
        when (c) {
            is AppMessageContent.Text -> text += c.text
            is AppMessageContent.Thinking -> thinking += c.thinking
            is AppMessageContent.ToolUse -> toolIds.add(c.toolCallId)
            else -> rest.add(c.toString())
        }
    }
    return ContentSig(text, thinking, toolIds.sorted(), rest.sorted())
}

private fun covers(folded: ContentSig, incoming: ContentSig): Boolean {
    if (incoming.text.isNotEmpty() && incoming.text != folded.text) return false
    if (incoming.thinking.isNotEmpty() && incoming.thinking != folded.thinking) return false
    return folded.toolIds.containsAll(incoming.toolIds) && folded.rest.containsAll(incoming.rest)
}

// ---------------------------------------------------------------------------
// 分组主流程
// ---------------------------------------------------------------------------

private class Group(
    val id: String,
    var promptId: String?,
    var durationMs: Long?,
) {
    val textParts = mutableListOf<String>()
    val thinkingParts = mutableListOf<String>()
    val tools = mutableListOf<ToolCallUi>()
    val blocks = mutableListOf<TurnBlock>()
    var approval: ApprovalBlock? = null
    var approvalId: String? = null
    val foldedSigs = mutableListOf<ContentSig>()
}

object TurnGrouper {

    /**
     * @param sessionActive 会话是否仍在产出。非活跃会话的悬挂 running 工具按已结束渲染。
     * @param fileUrlOf fileId → 可下载 URL（附件渲染用），null 时媒体/文件附件降级为文本。
     */
    fun messagesToTurns(
        messages: List<AppMessage>,
        approvals: List<WireApprovalRequest>,
        sessionActive: Boolean = true,
        planReviewByToolCallId: Map<String, AppState.PlanReview> = emptyMap(),
        fileUrlOf: ((String) -> String)? = null,
    ): List<ChatTurn> {
        val turns = mutableListOf<ChatTurn>()
        var no = 1

        val approvalByTool = approvals.associateBy { it.tool_call_id }

        var pendingGroup: Group? = null

        fun flushGroup(final: Boolean = false) {
            val g = pendingGroup ?: return
            pendingGroup = null
            // 非最终组（或会话已空闲）的悬挂 running 工具：结果帧丢失，按已结束渲染
            if (!final || !sessionActive) {
                for (i in g.tools.indices) {
                    val t = g.tools[i]
                    if (t.status != ToolStatus.RUNNING) continue
                    val updated = t.copy(status = ToolStatus.OK)
                    g.tools[i] = updated
                    val bi = g.blocks.indexOfFirst { it is TurnBlock.ToolBlock && it.tool.id == updated.id }
                    if (bi >= 0) g.blocks[bi] = TurnBlock.ToolBlock(updated)
                }
            }
            turns.add(
                ChatTurn(
                    id = g.id,
                    role = TurnRole.ASSISTANT,
                    no = no++,
                    text = g.textParts.joinToString("\n"),
                    thinking = g.thinkingParts.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                    tools = g.tools.takeIf { it.isNotEmpty() }?.toList(),
                    blocks = g.blocks.takeIf { it.isNotEmpty() }?.toList(),
                    approval = g.approval,
                    approvalId = g.approvalId,
                    durationMs = g.durationMs,
                ),
            )
        }

        fun absorbContent(g: Group, content: List<AppMessageContent>) {
            for (c in content) {
                when (c) {
                    is AppMessageContent.Text -> if (c.text.isNotEmpty()) {
                        g.textParts.add(c.text)
                        val last = g.blocks.lastOrNull()
                        if (last is TurnBlock.TextBlock) {
                            g.blocks[g.blocks.lastIndex] = TurnBlock.TextBlock(last.text + "\n" + c.text)
                        } else {
                            g.blocks.add(TurnBlock.TextBlock(c.text))
                        }
                    }

                    is AppMessageContent.Thinking -> if (c.thinking.isNotEmpty()) {
                        g.thinkingParts.add(c.thinking)
                        val last = g.blocks.lastOrNull()
                        if (last is TurnBlock.ThinkingBlock) {
                            g.blocks[g.blocks.lastIndex] = TurnBlock.ThinkingBlock(last.thinking + "\n" + c.thinking)
                        } else {
                            g.blocks.add(TurnBlock.ThinkingBlock(c.thinking))
                        }
                    }

                    is AppMessageContent.ToolUse -> {
                        val pendingApproval = approvalByTool[c.toolCallId]
                        val tool = ToolCallUi(
                            id = c.toolCallId,
                            name = c.toolName,
                            arg = when (c.input) {
                                null -> ""
                                is JsonPrimitive -> if (c.input.isString) c.input.content else c.input.toString()
                                else -> c.input.toString()
                            },
                            status = ToolStatus.RUNNING,
                            output = c.outputLines,
                            planPath = if (c.toolName == "ExitPlanMode") {
                                planReviewByToolCallId[c.toolCallId]?.path
                            } else {
                                null
                            },
                        )
                        g.tools.add(tool)
                        g.blocks.add(TurnBlock.ToolBlock(tool))
                        if (pendingApproval != null) {
                            g.approval = buildApprovalBlock(pendingApproval)
                            g.approvalId = pendingApproval.approval_id
                        }
                    }

                    is AppMessageContent.ToolResult -> {
                        val idx = g.tools.indexOfFirst { it.id == c.toolCallId }
                        if (idx >= 0) {
                            val tool = g.tools[idx]
                            val output = normalizeToolOutput(c.output)
                            val updated = tool.copy(
                                status = if (c.isError) ToolStatus.ERROR else ToolStatus.OK,
                                output = output,
                                planPath = tool.planPath
                                    ?: if (tool.name == "ExitPlanMode") parsePlanSavedPath(output) else null,
                            )
                            g.tools[idx] = updated
                            val bi = g.blocks.indexOfFirst {
                                it is TurnBlock.ToolBlock && it.tool.id == c.toolCallId
                            }
                            if (bi >= 0) g.blocks[bi] = TurnBlock.ToolBlock(updated)
                        }
                    }

                    else -> Unit // 媒体/文件内容不进 assistant 组
                }
            }
        }

        /** 去重丢弃前，把流式副本独有的易失信息（工具进度行）并回组内 */
        fun mergeVolatileExtras(g: Group, content: List<AppMessageContent>) {
            for (c in content) {
                if (c !is AppMessageContent.ToolUse || c.outputLines.isNullOrEmpty()) continue
                val idx = g.tools.indexOfFirst { it.id == c.toolCallId }
                if (idx < 0) continue
                val tool = g.tools[idx]
                if (tool.output != null) continue
                val updated = tool.copy(output = c.outputLines)
                g.tools[idx] = updated
                val bi = g.blocks.indexOfFirst { it is TurnBlock.ToolBlock && it.tool.id == c.toolCallId }
                if (bi >= 0) g.blocks[bi] = TurnBlock.ToolBlock(updated)
            }
        }

        fun resolveMediaUrl(c: AppMessageContent): TurnAttachment? = when (c) {
            is AppMessageContent.Image -> when (val s = c.source) {
                is com.kimi.app.data.wire.WireImageSource.Url -> TurnAttachment(s.url, "image")
                is com.kimi.app.data.wire.WireImageSource.Base64 ->
                    TurnAttachment("data:${s.media_type};base64,${s.data}", "image")

                is com.kimi.app.data.wire.WireImageSource.FileRef ->
                    fileUrlOf?.let { TurnAttachment(it(s.file_id), "image", fileId = s.file_id) }
            }

            is AppMessageContent.Video -> when (val s = c.source) {
                is com.kimi.app.data.wire.WireImageSource.Url -> TurnAttachment(s.url, "video")
                is com.kimi.app.data.wire.WireImageSource.Base64 ->
                    TurnAttachment("data:${s.media_type};base64,${s.data}", "video")

                is com.kimi.app.data.wire.WireImageSource.FileRef ->
                    fileUrlOf?.let { TurnAttachment(it(s.file_id), "video", fileId = s.file_id) }
            }

            is AppMessageContent.FilePart -> when {
                fileUrlOf == null -> null
                c.mediaType.startsWith("image/") ->
                    TurnAttachment(fileUrlOf(c.fileId), "image", c.fileId, c.name)

                c.mediaType.startsWith("video/") ->
                    TurnAttachment(fileUrlOf(c.fileId), "video", c.fileId, c.name)

                else -> TurnAttachment(
                    fileUrlOf(c.fileId), "file", c.fileId, c.name, c.mediaType, c.size,
                )
            }

            else -> null
        }

        for (msg in messages) {
            if (msg.role == AppRole.SYSTEM) continue

            // compaction 摘要 → 分隔条回合
            if (msg.isCompactionSummary()) {
                flushGroup()
                turns.add(
                    ChatTurn(
                        id = msg.id,
                        role = TurnRole.COMPACTION,
                        no = no,
                        text = msg.content.filterIsInstance<AppMessageContent.Text>()
                            .joinToString("\n") { it.text },
                        createdAt = msg.createdAt,
                    ),
                )
                continue
            }

            if (msg.role == AppRole.USER) {
                flushGroup()
                val cronKind = msg.cronOriginKind()
                if (cronKind != null) {
                    val rawText = msg.content.filterIsInstance<AppMessageContent.Text>()
                        .joinToString("\n") { it.text }
                    turns.add(
                        ChatTurn(
                            id = msg.id,
                            role = TurnRole.CRON,
                            no = no++,
                            text = extractCronPrompt(rawText),
                            createdAt = msg.createdAt,
                        ),
                    )
                    continue
                }
                // 系统注入的 user 回合不渲染（TUI 对齐）
                if (!msg.isDisplayableUserMessage()) continue

                val origin = msg.origin()
                val isSkillActivation =
                    origin?.str("kind") == "skill_activation" && origin.str("trigger") == "user-slash"

                val textParts = mutableListOf<String>()
                val attachments = mutableListOf<TurnAttachment>()
                for (c in msg.content) {
                    if (c is AppMessageContent.Text) {
                        if (isSkillActivation) {
                            textParts.add(origin?.str("skillArgs") ?: "")
                        } else {
                            val tag = mediaPathTag(c.text)
                            if (tag != null && (tag.first == "video" || tag.first == "image") && fileUrlOf != null) {
                                val fileId = fileIdFromCachePath(tag.second)
                                if (fileId != null) {
                                    attachments.add(TurnAttachment(fileUrlOf(fileId), tag.first, fileId))
                                    continue
                                }
                            }
                            val notice = attachedFileNotice(c.text)
                            if (notice != null) {
                                attachments.add(
                                    TurnAttachment(
                                        url = notice.fileId?.let { fileUrlOf?.invoke(it) } ?: "",
                                        kind = "file",
                                        fileId = notice.fileId,
                                        name = notice.name,
                                        mediaType = notice.mediaType,
                                        size = notice.size,
                                    ),
                                )
                                continue
                            }
                            val stripped = stripImageCompressionCaptions(c.text)
                            if (stripped !== c.text && stripped.isBlank()) continue
                            textParts.add(stripped)
                        }
                    }
                    resolveMediaUrl(c)?.let { attachments.add(it) }
                }
                turns.add(
                    ChatTurn(
                        id = msg.id,
                        role = TurnRole.USER,
                        no = no++,
                        text = textParts.joinToString("\n"),
                        attachments = attachments.takeIf { it.isNotEmpty() },
                        skillActivation = if (isSkillActivation) {
                            SkillActivationUi(origin?.str("skillName") ?: "", origin?.str("skillArgs"))
                        } else {
                            null
                        },
                        createdAt = msg.createdAt,
                    ),
                )
                continue
            }

            // tool 角色折叠进当前组
            if (msg.role == AppRole.TOOL) {
                pendingGroup?.let { absorbContent(it, msg.content) }
                continue
            }

            // assistant：合并规则 —— 两侧 promptId 都已知且不同才拆组
            val pid = msg.promptId
            val group = pendingGroup
            val continues = group != null &&
                (group.promptId == null || pid == null || group.promptId == pid)

            val g = if (!continues) {
                flushGroup()
                Group(id = msg.id, promptId = pid, durationMs = msg.durationMs).also { pendingGroup = it }
            } else {
                group!!
            }
            if (g.promptId == null && pid != null) g.promptId = pid

            // 流式副本与持久副本去重
            val sig = contentSig(msg.content)
            if (g.promptId != null && g.foldedSigs.any { covers(it, sig) }) {
                mergeVolatileExtras(g, msg.content)
                continue
            }
            g.foldedSigs.add(sig)
            absorbContent(g, msg.content)
        }

        flushGroup(final = true)
        return turns
    }
}

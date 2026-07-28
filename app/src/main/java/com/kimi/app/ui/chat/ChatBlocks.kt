package com.kimi.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimi.app.data.store.ApprovalBlock
import com.kimi.app.data.store.DiffLine
import com.kimi.app.data.store.ToolCallUi
import com.kimi.app.data.store.ToolStatus
import com.kimi.app.data.wire.WireApprovalRequest
import com.kimi.app.data.wire.WireQuestionAnswer
import com.kimi.app.data.wire.WireQuestionRequest
import com.mikepenz.markdown.m3.Markdown

// ---------------------------------------------------------------------------
// Markdown 正文
// ---------------------------------------------------------------------------

@Composable
fun MarkdownBlock(text: String, modifier: Modifier = Modifier) {
    Markdown(
        content = text,
        modifier = modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------------------
// 思考块（默认折叠）
// ---------------------------------------------------------------------------

@Composable
fun ThinkingBlock(thinking: String, streaming: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (streaming) "正在思考…" else "思考过程",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Text(
                    thinking,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 工具调用卡片
// ---------------------------------------------------------------------------

@Composable
fun ToolCallCard(tool: ToolCallUi) {
    var expanded by rememberSaveable(tool.id) { mutableStateOf(false) }
    val (statusIcon, statusTint) = when (tool.status) {
        ToolStatus.RUNNING -> Icons.Default.HourglassTop to MaterialTheme.colorScheme.primary
        ToolStatus.OK -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        ToolStatus.ERROR -> Icons.Default.Error to MaterialTheme.colorScheme.error
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tool.status == ToolStatus.RUNNING) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = statusTint)
                } else {
                    Icon(statusIcon, contentDescription = null, modifier = Modifier.size(14.dp), tint = statusTint)
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    tool.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                    if (tool.arg.isNotBlank() && tool.arg != "{}") {
                        Text(
                            tool.arg.take(2000),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    tool.output?.takeIf { it.isNotEmpty() }?.let { lines ->
                        Spacer(Modifier.height(6.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(6.dp))
                        Column(
                            Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                lines.takeLast(200).joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (tool.status == ToolStatus.ERROR) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    tool.planPath?.let { path ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "计划文件：$path",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 审批卡片
// ---------------------------------------------------------------------------

@Composable
fun ApprovalCard(
    approval: ApprovalBlock,
    request: WireApprovalRequest?,
    onRespond: (decision: String, scope: String?, feedback: String?) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("需要审批", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            ApprovalContent(approval)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onRespond("approved", null, null) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("批准")
                }
                OutlinedButton(
                    onClick = { onRespond("rejected", null, null) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("拒绝")
                }
            }
            TextButton(
                onClick = { onRespond("approved", "session", null) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("本会话内不再询问")
            }
        }
    }
}

@Composable
private fun ApprovalContent(block: ApprovalBlock) {
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    when (block) {
        is ApprovalBlock.Shell -> {
            LabelText("执行命令")
            CodeBox(block.command, mono)
            block.cwd?.let { LabelText("目录：$it") }
            block.danger?.let { Text("危险级别：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        is ApprovalBlock.Diff -> {
            LabelText("修改文件：${block.path}")
            DiffLinesView(block.diff.take(80), mono)
        }

        is ApprovalBlock.FileContent -> {
            LabelText("写入文件：${block.path}")
            CodeBox(block.content.take(2000), mono)
        }

        is ApprovalBlock.FileOp -> {
            LabelText("文件操作：${block.op}")
            Text(block.path, style = mono)
            block.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        is ApprovalBlock.UrlFetch -> {
            LabelText("访问 URL（${block.method ?: "GET"}）")
            Text(block.url, style = mono, color = MaterialTheme.colorScheme.primary)
        }

        is ApprovalBlock.Search -> {
            LabelText("搜索")
            Text(block.query, style = mono)
            block.scope?.let { Text("范围：$it", style = MaterialTheme.typography.bodySmall) }
        }

        is ApprovalBlock.Invocation -> {
            LabelText("调用 ${block.kind2}")
            Text(block.name, style = mono)
            block.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        is ApprovalBlock.Todo -> {
            LabelText("待办列表")
            for (item in block.items) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (item.status == "completed") Icons.Default.CheckCircle else Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(item.title, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        is ApprovalBlock.PlanReview -> {
            LabelText("计划评审${block.path?.let { "：$it" } ?: ""}")
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                MarkdownBlock(block.plan)
            }
        }

        is ApprovalBlock.Generic -> {
            LabelText("操作")
            Text(block.summary, style = mono)
        }
    }
}

@Composable
private fun LabelText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun CodeBox(text: String, style: androidx.compose.ui.text.TextStyle) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
            Text(text, style = style, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
fun DiffLinesView(lines: List<DiffLine>, style: androidx.compose.ui.text.TextStyle) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(4.dp)) {
            for (line in lines) {
                val color = when (line.kind) {
                    "add" -> androidx.compose.ui.graphics.Color(0xFF2DA44E)
                    "rem" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(line.text, style = style, color = color)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 问题卡片
// ---------------------------------------------------------------------------

@Composable
fun QuestionCard(
    request: WireQuestionRequest,
    onAnswer: (Map<String, WireQuestionAnswer>) -> Unit,
    onDismiss: () -> Unit,
) {
    // 一次提交全部问题的答案（WireQuestionResponse.answers 是整表）
    val answers = remember(request.question_id) { mutableStateOf(mapOf<String, WireQuestionAnswer>()) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("需要回答", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            for (q in request.questions) {
                QuestionItem(q, answers.value[q.id]) { answer ->
                    answers.value = if (answer == null) answers.value - q.id else answers.value + (q.id to answer)
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("跳过") }
                Spacer(Modifier.width(8.dp))
                // 所有必答题（有选项且未答）都已作答才可提交；无选项的问题视为可空
                val allAnswered = request.questions.all { q ->
                    answers.value.containsKey(q.id) || (q.options.isEmpty() && q.allow_other != true)
                }
                Button(
                    onClick = { onAnswer(answers.value) },
                    enabled = answers.value.isNotEmpty() && allAnswered,
                ) {
                    Text("提交")
                }
            }
        }
    }
}

@Composable
private fun QuestionItem(
    q: com.kimi.app.data.wire.WireQuestionItem,
    current: WireQuestionAnswer?,
    onChange: (WireQuestionAnswer?) -> Unit,
) {
    Text(q.question, style = MaterialTheme.typography.bodyLarge)
    q.body?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(8.dp))

    val multi = q.multi_select == true
    var otherText by remember(q.id) {
        mutableStateOf(
            when (current) {
                is WireQuestionAnswer.Other -> current.text
                is WireQuestionAnswer.MultiWithOther -> current.other_text
                else -> ""
            },
        )
    }

    if (!multi) {
        val selectedId = (current as? WireQuestionAnswer.Single)?.option_id
        for (option in q.options) {
            val oid = option.id.ifBlank { option.label }
            Row(
                Modifier.fillMaxWidth().clickable {
                    onChange(
                        if (otherText.isNotBlank()) {
                            // 单选+其他：以其他文本为准
                            WireQuestionAnswer.Other(otherText.trim())
                        } else {
                            WireQuestionAnswer.Single(oid)
                        },
                    )
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selectedId == oid, onClick = null)
                Column {
                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    option.description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (q.allow_other == true) {
            OutlinedTextField(
                value = otherText,
                onValueChange = {
                    otherText = it
                    onChange(if (it.isBlank()) current else WireQuestionAnswer.Other(it.trim()))
                },
                placeholder = { Text(q.other_label ?: "其他（可输入）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        val selected = when (current) {
            is WireQuestionAnswer.Multi -> current.option_ids.toSet()
            is WireQuestionAnswer.MultiWithOther -> current.option_ids.toSet()
            else -> emptySet()
        }

        fun emit(ids: Set<String>, other: String) {
            onChange(
                when {
                    ids.isEmpty() && other.isBlank() -> null
                    other.isNotBlank() -> WireQuestionAnswer.MultiWithOther(ids.toList(), other.trim())
                    else -> WireQuestionAnswer.Multi(ids.toList())
                },
            )
        }

        for (option in q.options) {
            val oid = option.id.ifBlank { option.label }
            Row(
                Modifier.fillMaxWidth().clickable {
                    emit(if (oid in selected) selected - oid else selected + oid, otherText)
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = oid in selected, onCheckedChange = null)
                Column {
                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    option.description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (q.allow_other == true) {
            OutlinedTextField(
                value = otherText,
                onValueChange = {
                    otherText = it
                    emit(selected, it)
                },
                placeholder = { Text(q.other_label ?: "其他（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

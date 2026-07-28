package com.kimi.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kimi.app.data.store.QueuedPrompt
import com.kimi.app.data.wire.WireImageSource
import com.kimi.app.data.wire.WireMessageContent
import com.kimi.app.data.wire.WireSkill
import com.kimi.app.ui.AppViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

private data class ComposerAttachment(
    val name: String,
    val mediaType: String,
    val size: Long,
    val content: WireMessageContent,
)

private data class SlashItem(
    val name: String,          // "/new"
    val desc: String,
    val needSession: Boolean = true,
    val acceptsInput: Boolean = false,
)

/** 底部输入区：Goal 条、排队提示、附件、/ 与 @ 弹出菜单、输入框（草稿持久化）、发送/停止 */
@OptIn(FlowPreview::class)
@Composable
fun ChatDock(
    viewModel: AppViewModel,
    sessionId: String?,
    busy: Boolean,
    queued: List<QueuedPrompt>,
) {
    val client = viewModel.client
    val context = LocalContext.current
    val draftKey = sessionId ?: "draft_new"
    var text by rememberSaveable(draftKey) { mutableStateOf("") }
    var draftLoaded by rememberSaveable(draftKey) { mutableStateOf(false) }
    var attachments by remember(draftKey) { mutableStateOf(listOf<ComposerAttachment>()) }
    var uploading by remember(draftKey) { mutableStateOf(false) }

    // 会话技能（/ 菜单动态项）
    var skills by remember(sessionId) { mutableStateOf(listOf<WireSkill>()) }
    LaunchedEffect(sessionId) {
        skills = emptyList()
        if (sessionId != null) {
            viewModel.listSkills(sessionId) { skills = it }
        }
    }

    // ---- / 命令菜单 ----
    val slashItems = remember(skills) {
        buildList {
            add(SlashItem("/new", "新建会话", needSession = false))
            add(SlashItem("/plan", "切换计划模式"))
            add(SlashItem("/thinking", "切换思考级别"))
            add(SlashItem("/manual", "权限：手动审批"))
            add(SlashItem("/auto", "权限：自动批准"))
            add(SlashItem("/yolo", "权限：完全放行（谨慎）"))
            add(SlashItem("/compact", "压缩历史", acceptsInput = true))
            add(SlashItem("/undo", "撤销上一回合"))
            add(SlashItem("/fork", "Fork 副本"))
            add(SlashItem("/export", "导出会话"))
            add(SlashItem("/rename", "重命名会话", acceptsInput = true))
            add(SlashItem("/skill", "激活技能（/skill 名称 [参数]）", acceptsInput = true))
            for (s in skills) {
                add(SlashItem("/${s.name}", s.description.ifBlank { "技能" }, acceptsInput = true))
            }
        }
    }
    val slashActive = text.startsWith("/") && !text.startsWith("//") && !text.contains('\n')
    val slashQuery = if (slashActive) text.removePrefix("/").substringBefore(' ').lowercase() else ""
    val slashArgStarted = slashActive && text.contains(' ')
    val slashMatches = if (slashActive && !slashArgStarted) {
        slashItems.filter { it.name.removePrefix("/").lowercase().startsWith(slashQuery) }.take(6)
    } else {
        emptyList()
    }

    // ---- @ 文件提及 ----
    val at = text.lastIndexOf('@')
    val atQuery = if (at >= 0 && text.substring(at + 1).none { it.isWhitespace() }) {
        text.substring(at + 1)
    } else {
        null
    }
    var mentionResults by remember(draftKey) { mutableStateOf(listOf<String>()) }
    LaunchedEffect(atQuery) {
        if (atQuery != null && atQuery.isNotEmpty() && sessionId != null) {
            kotlinx.coroutines.delay(250)
            viewModel.fsSearch(sessionId, atQuery) { result ->
                mentionResults = result?.items?.map { it.path } ?: emptyList()
            }
        } else {
            mentionResults = emptyList()
        }
    }
    val showMentionPanel = atQuery != null && (atQuery.isEmpty() || mentionResults.isNotEmpty())

    // 附件选择（系统文件选择器，全部类型）
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploading = true
            viewModel.readAttachment(context, uri) { bytes, name, mime ->
                if (bytes == null) {
                    uploading = false
                    viewModel.notice("无法读取所选文件")
                } else {
                    viewModel.uploadFile(bytes, name, mime) { meta ->
                        uploading = false
                        if (meta == null) {
                            viewModel.notice("附件上传失败")
                        } else {
                            val content = if (meta.media_type.startsWith("image/")) {
                                WireMessageContent.Image(WireImageSource.FileRef(meta.id))
                            } else {
                                WireMessageContent.FilePart(meta.id, meta.name, meta.media_type, meta.size)
                            }
                            attachments = attachments + ComposerAttachment(meta.name, meta.media_type, meta.size, content)
                        }
                    }
                }
            }
        }
    }

    // 载入草稿（一次性）
    LaunchedEffect(draftKey) {
        if (!draftLoaded) {
            draftLoaded = true
            val draft = client.settingsDraft(draftKey)
            if (draft.isNotEmpty()) text = draft
        }
    }
    // 保存草稿（防抖 600ms）
    LaunchedEffect(draftKey) {
        snapshotFlow { text }
            .debounce(600)
            .collect { client.saveDraft(draftKey, it) }
    }

    fun executeSlash(item: SlashItem, args: String?) {
        when (item.name) {
            "/new" -> viewModel.openDraft(null)
            "/plan" -> sessionId?.let { viewModel.togglePlanMode(it) }
            "/thinking" -> sessionId?.let { viewModel.cycleThinking(it) }
            "/manual", "/auto", "/yolo" -> sessionId?.let {
                viewModel.setSessionProfile(it, permissionMode = item.name.removePrefix("/"))
            }

            "/compact" -> sessionId?.let { viewModel.compactSession(it) }
            "/undo" -> sessionId?.let { viewModel.undoSession(it) }
            "/fork" -> sessionId?.let { viewModel.forkSession(it) }
            "/export" -> sessionId?.let { sid ->
                val title = viewModel.state.value.sessions.firstOrNull { it.id == sid }?.title ?: "session"
                viewModel.exportSession(context, sid, title) { name ->
                    if (name != null) viewModel.notice("已导出到下载目录：$name")
                }
            }

            "/rename" -> args?.takeIf { it.isNotBlank() }?.let { t ->
                sessionId?.let { viewModel.renameSession(it, t.trim()) }
            }

            "/skill" -> {
                val parts = args?.trim()?.split(Regex("\\s+"), limit = 2)
                if (sessionId != null && !parts.isNullOrEmpty()) {
                    viewModel.activateSkill(sessionId, parts[0], parts.getOrNull(1))
                }
            }

            else -> {
                // 动态技能命令 /<skill-name>
                val skillName = item.name.removePrefix("/")
                if (skills.any { it.name == skillName }) {
                    sessionId?.let { viewModel.activateSkill(it, skillName, args) }
                }
            }
        }
    }

    fun doSend() {
        val body = text.trim()
        if (slashActive) {
            val parts = body.removePrefix("/").split(Regex("\\s+"), limit = 2)
            val item = slashItems.firstOrNull { it.name == "/${parts[0].lowercase()}" }
                ?: slashItems.firstOrNull { it.name.equals("/${parts[0]}", ignoreCase = true) }
            if (item != null) {
                executeSlash(item, parts.getOrNull(1))
                text = ""
                return
            }
            // 未知命令：按普通文本发送（服务端/技能可能认识）
        }
        if (body.isEmpty() && attachments.isEmpty()) return
        viewModel.sendPrompt(body, attachments.map { it.content })
        text = ""
        attachments = emptyList()
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // ---- / 命令弹出菜单 ----
        if (slashMatches.isNotEmpty()) {
            SuggestionPanel {
                for (item in slashMatches) {
                    val enabled = !item.needSession || sessionId != null
                    SuggestionRow(
                        title = item.name,
                        subtitle = if (enabled) item.desc else "${item.desc}（需要会话）",
                        enabled = enabled,
                        onClick = {
                            if (item.acceptsInput) {
                                // 补全命令名，等用户输入参数
                                text = "${item.name} "
                            } else {
                                executeSlash(item, null)
                                text = ""
                            }
                        },
                    )
                }
            }
        }

        // ---- @ 提及弹出菜单 ----
        if (!slashActive && showMentionPanel) {
            SuggestionPanel {
                if (atQuery.isNullOrEmpty()) {
                    SuggestionRow(title = "输入文件名以搜索…", subtitle = null, enabled = false, onClick = {})
                }
                for (path in mentionResults.take(6)) {
                    SuggestionRow(
                        title = path,
                        subtitle = null,
                        enabled = true,
                        onClick = {
                            val idx = text.lastIndexOf('@')
                            text = text.substring(0, idx) + "@$path "
                            mentionResults = emptyList()
                        },
                    )
                }
            }
        }

        // 排队中的提示词
        for (q in queued) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
                        Text("排队中", modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        q.text,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 附件芯片
        if (attachments.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(attachments, key = { it.name + it.size }) { att ->
                    InputChip(
                        selected = false,
                        onClick = {
                            attachments = attachments.filterNot { it == att }
                        },
                        label = { Text(att.name, maxLines = 1) },
                        trailingIcon = {
                            Icon(Icons.Default.Close, contentDescription = "移除", modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { picker.launch("*/*") }, enabled = !uploading) {
                if (uploading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.AttachFile, contentDescription = "添加附件")
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = {
                    Text(if (busy) "继续输入，消息将排队…" else "输入消息…（/ 命令、@ 提及文件）")
                },
                modifier = Modifier.weight(1f),
                maxLines = 6,
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            if (busy && sessionId != null) {
                FilledIconButton(
                    onClick = { viewModel.abort(sessionId) },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "停止")
                }
            } else {
                FilledIconButton(
                    onClick = { doSend() },
                    enabled = text.isNotBlank() || attachments.isNotEmpty(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
        if (busy && (text.isNotBlank() || attachments.isNotEmpty())) {
            // 运行中也允许发送（服务端排队），给一个发送按钮
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { doSend() }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("排队发送")
                }
            }
        }
    }
}

@Composable
private fun SuggestionPanel(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp),
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            content()
        }
    }
}

@Composable
private fun SuggestionRow(
    title: String,
    subtitle: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

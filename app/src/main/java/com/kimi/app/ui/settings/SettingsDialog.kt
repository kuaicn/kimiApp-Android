package com.kimi.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.ModelTraining
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kimi.app.core.ThemeMode
import com.kimi.app.ui.AppViewModel

/** 设置（底部弹层）：外观、模型、服务器管理、账户、关于 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val theme by viewModel.themeMode.collectAsState()
    val servers by viewModel.servers.collectAsState()
    var showServers by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "关闭") }
            }
            Spacer(Modifier.height(8.dp))

            // ---- 外观 ----
            SectionTitle("外观")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text("主题", modifier = Modifier.weight(1f))
                for ((mode, label) in listOf(
                    ThemeMode.SYSTEM to "跟随系统",
                    ThemeMode.LIGHT to "浅色",
                    ThemeMode.DARK to "深色",
                )) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { viewModel.setTheme(mode) },
                    ) {
                        RadioButton(selected = theme == mode, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // ---- 模型 ----
            SectionTitle("模型")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ModelTraining, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("默认模型", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        state.config?.default_model ?: "未配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "可用模型 ${state.models.size} 个 · 提供商 ${state.providers.size} 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // ---- 服务器 ----
            SectionTitle("服务器")
            Row(
                Modifier.fillMaxWidth().clickable { showServers = !showServers },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("服务器管理", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        viewModel.client.profile?.baseUrl ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showServers) {
                Spacer(Modifier.height(8.dp))
                ServerManageSection(viewModel)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // ---- 账户 ----
            SectionTitle("账户")
            Row(
                Modifier.fillMaxWidth().clickable { showLogoutConfirm = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Text("登出 Kimi 账号", color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // ---- 关于 ----
            SectionTitle("关于")
            Text(
                "服务端版本：${state.serverMeta?.server_version ?: "未知"}" +
                    " · 后端：${state.serverMeta?.backend ?: "未知"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "客户端：kimi-code-android 1.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("登出") },
            text = { Text("确定登出 Kimi 账号吗？登出后需要重新完成 OAuth 登录。") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    viewModel.logout()
                }) { Text("登出") }
            },
            dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * 服务器管理区块（轻量实现，不嵌套滚动容器）：
 * 列表切换/删除 + 内联表单添加新服务器。
 */
@Composable
private fun ServerManageSection(viewModel: AppViewModel) {
    val servers by viewModel.servers.collectAsState()
    val activeId by viewModel.activeServerId.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        for (server in servers) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (server.id != activeId) viewModel.switchServer(server)
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.RadioButton(selected = server.id == activeId, onClick = null)
                Column(Modifier.weight(1f)) {
                    Text(server.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        server.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { viewModel.removeServer(server) }) {
                    Icon(
                        androidx.compose.material.icons.Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        if (!showAdd) {
            TextButton(onClick = { showAdd = true }) { Text("+ 添加服务器") }
        } else {
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("服务器地址") },
                placeholder = { Text("http://192.168.1.10:58627") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Bearer Token（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showAdd = false }) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Button(
                    onClick = {
                        viewModel.connectNewServer(url, token.ifBlank { null })
                        showAdd = false
                        url = ""
                        token = ""
                    },
                    enabled = url.isNotBlank(),
                ) { Text("连接") }
            }
        }
    }
}

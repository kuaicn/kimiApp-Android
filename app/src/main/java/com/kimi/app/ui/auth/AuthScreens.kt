package com.kimi.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.kimi.app.data.wire.WireOAuthLoginStartResult
import com.kimi.app.ui.AppViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 认证闸门：后端 auth 未就绪时全屏展示，发起 OAuth 设备码登录 */
@Composable
fun AuthGateScreen(viewModel: AppViewModel) {
    val client = viewModel.client
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf<WireOAuthLoginStartResult?>(null) }
    var waiting by remember { mutableStateOf(false) }

    // 登录轮询：pending → 每 2s 查状态，authenticated 后重载
    DisposableEffect(login?.flow_id) {
        val job = scope.launch {
            val flow = login ?: return@launch
            if (flow.status != "pending") return@launch
            while (true) {
                delay(2000)
                val result = client.oauthPoll() ?: break
                if (result.status == "authenticated") {
                    waiting = false
                    client.loadDomainData()
                    break
                }
                if (result.status == "expired" || result.status == "cancelled") {
                    waiting = false
                    login = null
                    break
                }
            }
        }
        onDispose { job.cancel() }
    }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("需要登录", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "服务器尚未配置可用的模型提供商认证，请登录 Kimi 账号",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        val current = login
        if (current == null || current.status != "pending") {
            Button(
                onClick = {
                    waiting = true
                    scope.launch {
                        val result = client.oauthStart()
                        waiting = false
                        if (result != null) {
                            if (result.status == "authenticated") {
                                client.loadDomainData()
                            } else {
                                login = result
                            }
                        }
                    }
                },
                enabled = !waiting,
            ) {
                if (waiting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("登录 Kimi 账号")
            }
        } else {
            DeviceCodeCard(
                login = current,
                onCancel = {
                    scope.launch {
                        client.oauthCancel()
                        login = null
                    }
                },
            )
        }
    }
}

@Composable
private fun DeviceCodeCard(login: WireOAuthLoginStartResult, onCancel: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("请在浏览器中完成授权", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            login.user_code ?: "",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(login.user_code ?: "")) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("复制验证码")
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            uriHandler.openUri(login.verification_uri_complete ?: login.verification_uri ?: "")
        }) {
            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("打开授权页面")
        }
        Spacer(Modifier.height(8.dp))
        Row {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("等待授权…", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onCancel) { Text("取消") }
    }
}

/** 服务器凭据（Bearer Token）失效时的全局弹窗 */
@Composable
fun ServerAuthDialog(onSubmit: (String) -> Unit) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { /* 必须处理，不给绕过 */ },
        title = { Text("服务器认证") },
        text = {
            Column {
                Text("服务器拒绝了当前凭据（401）。请输入有效的 Bearer Token：")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(token.trim()) }, enabled = token.isNotBlank()) {
                Text("确定")
            }
        },
    )
}

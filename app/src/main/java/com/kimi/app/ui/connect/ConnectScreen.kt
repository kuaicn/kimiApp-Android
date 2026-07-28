package com.kimi.app.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kimi.app.ui.AppViewModel
import kotlinx.coroutines.launch

/**
 * 服务器连接页：输入地址 + Bearer Token（可选），测试并保存。
 * 已保存的服务器列表展示在下方，可点击切换。
 */
@Composable
fun ConnectScreen(viewModel: AppViewModel, showServerList: Boolean) {
    val servers by viewModel.servers.collectAsState()
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        Icon(
            Icons.Default.Dns,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("连接 Kimi 服务器", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "输入运行中的 kimi web 服务端地址（kap-server）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it; error = null },
            label = { Text("服务器地址") },
            placeholder = { Text("http://192.168.1.10:58627") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; error = null },
            label = { Text("Bearer Token（可选）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                val normalized = normalizeUrl(url) ?: run {
                    error = "地址格式不正确，示例：http://192.168.1.10:58627"
                    return@Button
                }
                testing = true
                error = null
                viewModel.connectNewServer(normalized, token.ifBlank { null })
            },
            enabled = !testing && url.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (testing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("连接")
        }

        if (servers.isNotEmpty()) {
            Spacer(Modifier.height(40.dp))
            Text(
                "已保存的服务器",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            for (server in servers) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onClick = { viewModel.switchServer(server) },
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(server.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                server.baseUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (server.token != null) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "已配置令牌",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun normalizeUrl(input: String): String? {
    var u = input.trim().trimEnd('/')
    if (u.isEmpty()) return null
    if (!u.startsWith("http://") && !u.startsWith("https://")) {
        u = "http://$u"
    }
    val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return null
    if (uri.host.isNullOrBlank()) return null
    return u
}

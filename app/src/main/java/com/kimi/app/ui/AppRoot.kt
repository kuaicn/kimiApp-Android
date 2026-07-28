package com.kimi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.kimi.app.KimiApp
import com.kimi.app.data.store.ConnectionState
import com.kimi.app.ui.auth.AuthGateScreen
import com.kimi.app.ui.auth.ServerAuthDialog
import com.kimi.app.ui.common.NoticeHost
import com.kimi.app.ui.connect.ConnectScreen
import com.kimi.app.ui.home.MainScaffold
import com.kimi.app.ui.theme.KimiTheme

/** 应用级图片加载器（带服务器认证头），由 AppRoot 提供 */
val LocalKimiImageLoader = compositionLocalOf<ImageLoader?> { null }

/**
 * 根分派（对应 kimi-web App.vue 的条件渲染）：
 * 无服务器配置 → 连接页；未初始化 → 启动页；认证未就绪 → 认证门；否则主界面。
 */
@Composable
fun AppRoot(viewModel: AppViewModel) {
    val theme by viewModel.themeMode.collectAsState()
    val imageLoader = (LocalContext.current.applicationContext as KimiApp).container.imageLoader
    KimiTheme(theme) {
        CompositionLocalProvider(LocalKimiImageLoader provides imageLoader) {
            val state by viewModel.state.collectAsState()
            val servers by viewModel.servers.collectAsState()

            Box(Modifier.fillMaxSize()) {
                when {
                    servers.isEmpty() -> ConnectScreen(viewModel, showServerList = false)

                    !state.initialized -> LoadingScreen(state.connection)

                    !state.authReady -> AuthGateScreen(viewModel)

                    else -> MainScaffold(viewModel)
                }

                // 服务器凭据失效：全局弹窗
                if (state.serverAuthRequired) {
                    ServerAuthDialog(onSubmit = { viewModel.submitServerToken(it) })
                }

                NoticeHost(
                    notices = state.notices,
                    onDismiss = { viewModel.dismissNotice(it) },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

@Composable
private fun LoadingScreen(connection: ConnectionState) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(Modifier.size(48.dp))
        Spacer(Modifier.height(24.dp))
        Text(
            if (connection == ConnectionState.CONNECTING) "正在连接服务器…" else "加载中…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

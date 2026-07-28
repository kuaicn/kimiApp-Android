package com.kimi.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.kimi.app.ui.AppViewModel
import com.kimi.app.ui.chat.ChatScreen
import com.kimi.app.ui.drawer.SessionDrawer
import kotlinx.coroutines.launch

/** 主界面：左侧抽屉（会话列表）+ 内容区（聊天页 / 空态） */
@Composable
fun MainScaffold(viewModel: AppViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawer(
                viewModel = viewModel,
                onNavigate = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            ChatScreen(
                viewModel = viewModel,
                onOpenDrawer = { scope.launch { drawerState.open() } },
            )
        }
    }
}

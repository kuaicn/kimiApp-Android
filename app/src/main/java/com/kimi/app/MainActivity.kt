package com.kimi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.kimi.app.ui.AppRoot
import com.kimi.app.ui.AppViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels {
        AppViewModel.Factory((application as KimiApp).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Android 13+ 通知权限（后台回合提醒）
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val container = (application as KimiApp).container
        // 应用级启动：读取服务器配置并连接（幂等，只执行一次）
        container.appScope.launch { container.client.start() }
        setContent {
            AppRoot(viewModel)
        }
    }
}

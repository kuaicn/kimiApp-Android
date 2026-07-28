package com.kimi.app

import android.app.Application
import com.kimi.app.core.TurnNotifier

class KimiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 后台通知（回合完成/待审批/待回答）
        TurnNotifier(this, container.client).start()
    }
}

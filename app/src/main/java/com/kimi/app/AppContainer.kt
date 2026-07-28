package com.kimi.app

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.kimi.app.core.SettingsStore
import com.kimi.app.data.store.KimiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** 手动 DI 容器：应用级单例。 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(appContext)
    val client = KimiClient(settings, appScope)

    /**
     * 图片加载器：复用客户端的 OkHttp（拦截器自带 Bearer/X-Kimi-* 头），
     * callFactory 每次取图时动态取当前连接的 client，服务器切换后自动生效。
     */
    val imageLoader: ImageLoader = ImageLoader.Builder(appContext)
        .components {
            add(
                OkHttpNetworkFetcherFactory(
                    callFactory = { client.okHttpClient() ?: OkHttpClient() },
                ),
            )
        }
        .build()
}

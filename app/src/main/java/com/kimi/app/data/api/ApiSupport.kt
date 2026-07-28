package com.kimi.app.data.api

import com.github.f4b6a3.ulid.UlidCreator
import com.kimi.app.data.wire.WireEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 服务端信封错误码：触发服务器重新认证（与 kimi-web 的 SERVER_AUTH_UNAUTHORIZED_CODE 对齐） */
const val SERVER_AUTH_UNAUTHORIZED_CODE = 40101

class ApiException(
    val code: Int,
    message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause) {
    val isAuthFailure: Boolean get() = code == SERVER_AUTH_UNAUTHORIZED_CODE || httpCode == 401
}

val wireJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    classDiscriminator = "type"
}

/** 解包统一信封：code != 0 抛 ApiException；data 为 null 抛 ApiException(-1) */
fun <T> WireEnvelope<T>.unwrap(): T {
    if (code != 0) throw ApiException(code, msg.ifBlank { "服务器错误 ($code)" })
    return data ?: throw ApiException(-1, "响应数据为空")
}

/** 解包允许 data 为空的信封 */
fun <T> WireEnvelope<T>.unwrapOrNull(): T? {
    if (code != 0) throw ApiException(code, msg.ifBlank { "服务器错误 ($code)" })
    return data
}

/** 服务器连接配置（DataStore 持久化，支持多服务器切换） */
data class ServerProfile(
    val id: String,
    val name: String,
    val baseUrl: String, // 形如 http://192.168.1.10:58627（不含 /api/v1）
    val token: String?,
) {
    val apiBaseUrl: String get() = baseUrl.trimEnd('/') + "/api/v1/"
    val wsUrl: String
        get() {
            val http = baseUrl.trimEnd('/')
            val ws = if (http.startsWith("https")) "wss" + http.removePrefix("https")
            else "ws" + http.removePrefix("http")
            return "$ws/api/v1/ws"
        }
}

object ApiHeaders {
    const val CLIENT_NAME = "kimi-code-android"
    const val CLIENT_VERSION = "1.0"
    const val UI_MODE = "android"
}

/** 通用请求头（对齐 kimi-web）：X-Request-Id / Bearer / X-Kimi-Client-* */
private class HeaderInterceptor(
    private val profileProvider: () -> ServerProfile?,
    private val clientIdProvider: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val profile = profileProvider()
        val builder = chain.request().newBuilder()
            .header("X-Request-Id", UlidCreator.getUlid().toString())
            .header("X-Kimi-Client-Id", clientIdProvider())
            .header("X-Kimi-Client-Name", ApiHeaders.CLIENT_NAME)
            .header("X-Kimi-Client-Version", ApiHeaders.CLIENT_VERSION)
            .header("X-Kimi-Client-Ui-Mode", ApiHeaders.UI_MODE)
        profile?.token?.takeIf { it.isNotBlank() }?.let {
            builder.header("Authorization", "Bearer $it")
        }
        val response = chain.proceed(builder.build())
        if (response.code == 401) {
            throw ApiException(SERVER_AUTH_UNAUTHORIZED_CODE, "服务器认证失败", httpCode = 401)
        }
        return response
    }
}

fun buildOkHttpClient(
    profileProvider: () -> ServerProfile?,
    clientIdProvider: () -> String,
): OkHttpClient {
    val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
        redactHeader("Authorization")
    }
    return OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // WS 长连接：ping 由服务端协议帧负责，关闭 OkHttp 层 ping
        .pingInterval(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(HeaderInterceptor(profileProvider, clientIdProvider))
        .addInterceptor(logging)
        .build()
}

val emptyJsonBody = JsonObject(emptyMap())

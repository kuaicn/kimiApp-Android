package com.kimi.app.data.ws

import com.github.f4b6a3.ulid.UlidCreator
import com.kimi.app.data.api.ServerProfile
import com.kimi.app.data.api.wireJson
import com.kimi.app.data.wire.WireClientHello
import com.kimi.app.data.wire.WirePingPayload
import com.kimi.app.data.wire.WirePong
import com.kimi.app.data.wire.WireResyncPayload
import com.kimi.app.data.wire.WireServerHello
import com.kimi.app.data.wire.WireSessionCursor
import com.kimi.app.data.wire.WireSubscribe
import com.kimi.app.data.wire.WireUnsubscribe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.pow

/**
 * /api/v1/ws 客户端 —— 移植 apps/kimi-web/src/api/daemon/ws.ts 的状态机：
 * server_hello → client_hello 握手、subscribe/unsubscribe、ping/pong、
 * 陈旧检测（2×心跳、下限 30s）、指数退避重连（≤30s）、resync_required 上抛。
 *
 * 帧分派：
 * - `event.*` → [Handler.onProtocolEvent]（协议事件，payload 按类型反序列化）
 * - 控制帧（server_hello/ping/ack/resync_required/error/terminal_*）内部处理或忽略
 * - 其余 → [Handler.onRawEvent]（原始 agent-core 帧：turn.started / agent.status.updated 等）
 */
class KimiSocket(
    private val okHttp: OkHttpClient,
    private val scope: CoroutineScope,
) {

    interface Handler {
        fun onProtocolEvent(type: String, sessionId: String?, seq: Long, payload: JsonObject)
        fun onRawEvent(type: String, sessionId: String?, seq: Long, volatile: Boolean, offset: Long?, payload: JsonObject)
        fun onResync(sessionId: String, currentSeq: Long, epoch: String?)
        fun onConnectionState(connected: Boolean)
        fun onError(code: Int, msg: String, fatal: Boolean)
    }

    var handler: Handler? = null

    private var ws: WebSocket? = null
    private var profile: ServerProfile? = null
    private var clientId: String = ""
    private val active = AtomicBoolean(false)
    private var connected = false

    /** 会话订阅表：sessionId → 订阅游标（重连时随 client_hello 重发） */
    private val subscriptions = LinkedHashMap<String, WireSessionCursor?>()

    private var heartbeatMs = 30_000L
    @Volatile private var lastActivityAt = 0L
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null

    fun connect(profile: ServerProfile, clientId: String) {
        if (active.get() && this.profile == profile) return
        disconnect()
        this.profile = profile
        this.clientId = clientId
        active.set(true)
        reconnectAttempts = 0
        openSocket()
        startWatchdog()
    }

    fun disconnect() {
        active.set(false)
        connected = false
        reconnectJob?.cancel()
        watchdogJob?.cancel()
        ws?.close(1000, "client closing")
        ws = null
    }

    fun subscribe(sessionId: String, cursor: WireSessionCursor?) {
        subscriptions[sessionId] = cursor ?: subscriptions[sessionId]
        if (!connected) return
        send(
            wireJson.encodeToString(
                WireSubscribe.serializer(),
                WireSubscribe(
                    id = UlidCreator.getUlid().toString(),
                    payload = WireSubscribe.Payload(
                        session_ids = listOf(sessionId),
                        cursors = cursor?.let { mapOf(sessionId to it) },
                    ),
                ),
            ),
        )
    }

    fun unsubscribe(sessionId: String) {
        subscriptions.remove(sessionId)
        if (!connected) return
        send(
            wireJson.encodeToString(
                WireUnsubscribe.serializer(),
                WireUnsubscribe(
                    id = UlidCreator.getUlid().toString(),
                    payload = WireUnsubscribe.Payload(session_ids = listOf(sessionId)),
                ),
            ),
        )
    }

    /** 推进订阅游标（每个持久帧后调用），重连时携带 */
    fun trackCursor(sessionId: String, seq: Long, epoch: String? = null) {
        if (!subscriptions.containsKey(sessionId)) return
        val cur = subscriptions[sessionId]
        if (cur == null || seq > cur.seq) {
            subscriptions[sessionId] = WireSessionCursor(seq, epoch ?: cur?.epoch)
        }
    }

    // -----------------------------------------------------------------------

    private fun openSocket() {
        val profile = this.profile ?: return
        lastActivityAt = System.currentTimeMillis()
        val url = "${profile.wsUrl}?client_id=$clientId"
        val requestBuilder = Request.Builder().url(url)
        profile.token?.takeIf { it.isNotBlank() }?.let {
            // 与浏览器一致：Bearer 凭据走 Sec-WebSocket-Protocol 子协议
            requestBuilder.header("Sec-WebSocket-Protocol", "kimi-code.bearer.$it")
        }
        ws = okHttp.newWebSocket(requestBuilder.build(), SocketListener())
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            lastActivityAt = System.currentTimeMillis()
            handleFrame(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onSocketDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (response?.code == 401) {
                handler?.onError(40101, "服务器认证失败", fatal = true)
            }
            onSocketDown()
        }
    }

    private fun onSocketDown() {
        val wasConnected = connected
        connected = false
        ws = null
        if (wasConnected) handler?.onConnectionState(false)
        if (active.get()) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val backoff = min(30_000.0, 1000.0 * 2.0.pow(reconnectAttempts.toDouble())).toLong()
            reconnectAttempts++
            delay(backoff)
            if (active.get()) openSocket()
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (active.get()) {
                delay(15_000)
                val staleAfter = maxOf(2 * heartbeatMs, 30_000L)
                if (System.currentTimeMillis() - lastActivityAt > staleAfter) {
                    // 半开连接：主动关闭触发重连
                    ws?.close(1001, "stale socket")
                }
            }
        }
    }

    private fun handleFrame(text: String) {
        val obj = try {
            wireJson.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            handler?.onError(-1, "WS 帧解析失败: ${e.message}", fatal = false)
            return
        }
        val type = obj["type"]?.jsonPrimitive?.content ?: return
        val seq = obj["seq"]?.jsonPrimitive?.longOrNull ?: 0L
        val sessionId = obj["session_id"]?.jsonPrimitive?.content
        val payload = (obj["payload"] as? JsonObject) ?: JsonObject(emptyMap())

        when {
            type == "server_hello" -> {
                val hello = runCatching {
                    wireJson.decodeFromJsonElement(WireServerHello.serializer(), payload)
                }.getOrNull()
                heartbeatMs = hello?.heartbeat_ms ?: 30_000L
                connected = true
                reconnectAttempts = 0
                sendClientHello()
                handler?.onConnectionState(true)
            }

            type == "ping" -> {
                val nonce = runCatching {
                    wireJson.decodeFromJsonElement(WirePingPayload.serializer(), payload).nonce
                }.getOrDefault("")
                send(wireJson.encodeToString(WirePong.serializer(), WirePong(payload = WirePong.Payload(nonce))))
            }

            type == "resync_required" -> {
                val resync = runCatching {
                    wireJson.decodeFromJsonElement(WireResyncPayload.serializer(), payload)
                }.getOrNull() ?: return
                // 订阅表里的游标已失效，让上层重新快照后以新水位重新订阅
                handler?.onResync(resync.session_id, resync.current_seq, resync.epoch)
            }

            type == "error" -> {
                // 有 session_id 的是会话级 agent 错误（如模型未配置/供应商 429），
                // 走原始事件路由浮出水面；无 session_id 的才是连接级控制错误
                if (sessionId != null) {
                    handler?.onRawEvent("error", sessionId, seq, false, null, payload)
                } else {
                    val code = payload["code"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0
                    val msg = payload["msg"]?.jsonPrimitive?.content
                        ?: payload["message"]?.jsonPrimitive?.content ?: ""
                    val fatal = payload["fatal"]?.jsonPrimitive?.content == "true"
                    handler?.onError(code, msg, fatal)
                }
            }

            type == "ack" || type.startsWith("terminal_") -> Unit

            else -> when (classifyFrame(type, payload)) {
                FrameRoute.PROTOCOL -> handler?.onProtocolEvent(type, sessionId, seq, payload)
                FrameRoute.AGENT -> {
                    val agentType = type.removePrefix("event.")
                    val offset = obj["offset"]?.jsonPrimitive?.longOrNull
                    val volatile = obj["volatile"]?.jsonPrimitive?.content == "true"
                    handler?.onRawEvent(agentType, sessionId, seq, volatile, offset, payload)
                }

                FrameRoute.IGNORE -> Unit
            }
        }
    }

    // -----------------------------------------------------------------------
    // classifyFrame 移植（agentEventProjector.ts）：
    // kap-server 的原始 agent-core 事件也带 event. 前缀，必须按名称二段分类，
    // 否则 event.turn.started 会被当成协议事件。
    // -----------------------------------------------------------------------

    private enum class FrameRoute { PROTOCOL, AGENT, IGNORE }

    private val ambiguousDeltaNames = setOf("assistant.delta", "thinking.delta")

    private val knownAgentCoreTypes = setOf(
        "turn.started", "turn.step.started", "turn.step.completed", "turn.step.retrying",
        "turn.step.interrupted", "turn.ended", "thinking.delta", "assistant.delta",
        "tool.call.started", "tool.use", "tool.call.delta", "tool.progress", "tool.result",
        "agent.status.updated", "prompt.submitted", "prompt.completed", "prompt.aborted",
        "session.meta.updated", "compaction.started", "compaction.completed", "compaction.cancelled",
        "goal.updated", "error", "warning",
        "subagent.spawned", "subagent.started", "subagent.suspended", "subagent.completed", "subagent.failed",
        "task.started", "task.terminated", "background.task.started", "background.task.terminated",
        "cron.fired",
    )

    private val protocolEventNames = setOf(
        "session.created", "session.updated", "session.deleted", "session.status_changed",
        "session.usage_updated", "session.history_compacted", "session.work_changed",
        "message.created", "message.updated",
        "approval.requested", "approval.resolved", "approval.expired",
        "question.requested", "question.answered", "question.dismissed",
        "task.created", "task.progress", "task.completed",
        "assistant.tool_use_started", "assistant.tool_use_delta", "assistant.tool_use_completed",
        "assistant.completed", "tool.started", "tool.output", "tool.completed",
        "workspace.created", "workspace.updated", "workspace.deleted",
        "config.changed", "model_catalog.changed",
    )

    private fun classifyFrame(type: String, payload: JsonObject): FrameRoute {
        val hasPrefix = type.startsWith("event.")
        val name = if (hasPrefix) type.removePrefix("event.") else type

        // assistant.delta / thinking.delta 按载荷形状消歧：
        // 原始形式 payload.delta 是字符串且无 message_id/content_index
        if (name in ambiguousDeltaNames) {
            val isRaw = !payload.containsKey("message_id") &&
                !payload.containsKey("content_index") &&
                payload["delta"]?.let { it is kotlinx.serialization.json.JsonPrimitive && it.isString } == true
            return if (isRaw) FrameRoute.AGENT else FrameRoute.PROTOCOL
        }
        if (!hasPrefix) return FrameRoute.AGENT
        if (name in protocolEventNames) return FrameRoute.PROTOCOL
        if (name in knownAgentCoreTypes) return FrameRoute.AGENT
        return FrameRoute.PROTOCOL
    }

    private fun sendClientHello() {
        val cursors = subscriptions.mapNotNull { (sid, cursor) -> cursor?.let { sid to it } }
            .toMap().ifEmpty { null }
        send(
            wireJson.encodeToString(
                WireClientHello.serializer(),
                WireClientHello(
                    id = UlidCreator.getUlid().toString(),
                    payload = WireClientHello.Payload(
                        client_id = clientId,
                        subscriptions = subscriptions.keys.toList(),
                        cursors = cursors,
                    ),
                ),
            ),
        )
    }

    private fun send(text: String) {
        ws?.send(text)
    }
}

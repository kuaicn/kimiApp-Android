package com.kimi.app.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.kimi.app.data.store.AppState
import com.kimi.app.data.store.KimiClient
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 后台本地通知（低配版，对齐 kimi-web 的通知思路）：
 * 应用退到后台后，会话回合结束 / 出现待审批 / 待回答时发通知，点击回到应用。
 */
class TurnNotifier(
    private val context: Context,
    private val client: KimiClient,
) {
    companion object {
        const val CHANNEL_ID = "kimi_turns"
    }

    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** 每个会话上次已通知的签名，避免重复打扰 */
    private val notified = mutableMapOf<String, String>()

    fun start() {
        createChannel()
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            var wasForeground = true
            var prevState = client.state.value
            client.state.collect { state ->
                val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
                    androidx.lifecycle.Lifecycle.State.STARTED,
                )
                if (!foreground) {
                    detectTransitions(prevState, state)
                }
                wasForeground = foreground
                prevState = state
            }
        }
    }

    private fun detectTransitions(prev: AppState, next: AppState) {
        // 待审批/待回答：数量增加即通知
        for ((sid, approvals) in next.approvalsBySession) {
            val prevCount = prev.approvalsBySession[sid]?.size ?: 0
            if (approvals.size > prevCount) {
                val session = next.sessions.firstOrNull { it.id == sid }
                notifyOnce(
                    key = "$sid:approval:${approvals.last().approval_id}",
                    title = "需要审批：${session?.title ?: "会话"}",
                    text = approvals.last().action.ifBlank { "有操作等待你的确认" },
                )
            }
        }
        for ((sid, questions) in next.questionsBySession) {
            val prevCount = prev.questionsBySession[sid]?.size ?: 0
            if (questions.size > prevCount) {
                val session = next.sessions.firstOrNull { it.id == sid }
                notifyOnce(
                    key = "$sid:question:${questions.last().question_id}",
                    title = "需要回答：${session?.title ?: "会话"}",
                    text = questions.last().questions.firstOrNull()?.question ?: "有问题等待你回答",
                )
            }
        }
        // 回合结束：busy → 空闲
        for (session in next.sessions) {
            val wasBusy = prev.sessions.firstOrNull { it.id == session.id }?.busy == true ||
                prev.turnActiveBySession[session.id] == true
            val isBusy = session.busy || next.turnActiveBySession[session.id] == true
            if (wasBusy && !isBusy && session.last_turn_reason == "completed") {
                notifyOnce(
                    key = "${session.id}:done:${session.updated_at}",
                    title = "回合完成：${session.title.ifBlank { "会话" }}",
                    text = session.last_prompt?.take(80) ?: "Kimi 已完成当前回合",
                )
            }
        }
    }

    private fun notifyOnce(key: String, title: String, text: String) {
        if (notified.containsKey(key)) return
        notified[key] = key
        if (notified.size > 200) notified.clear()
        notify(title, text)
    }

    private fun notify(title: String, text: String) {
        if (androidx.core.app.ActivityCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "回合与审批",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Kimi 回合完成、待审批与待回答提醒"
        }
        manager.createNotificationChannel(channel)
    }
}

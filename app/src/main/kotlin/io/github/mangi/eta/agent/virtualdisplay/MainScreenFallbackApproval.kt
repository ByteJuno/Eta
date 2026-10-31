package io.github.mangi.eta.agent.virtualdisplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.toolDisplayNameResource
import io.github.mangi.eta.ui.MainActivity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal object MainScreenFallbackApproval {
    private const val CHANNEL = "eta_ui_fallback"
    private const val NOTIFICATION_ID = 1111
    internal const val ACTION_ALLOW = "io.github.mangi.eta.action.ALLOW_MAIN_SCREEN_FALLBACK"
    internal const val ACTION_DENY = "io.github.mangi.eta.action.DENY_MAIN_SCREEN_FALLBACK"
    private const val TOKEN = "fallback_request"

    private data class Pending(
        val request: MainScreenFallbackRequest,
        val manager: NotificationManager
    )

    private var appContext: Context? = null
    private val pending = ConcurrentHashMap<String, Pending>()
    internal data class Prompt(val token: String, val reason: String)
    private val prompts = MutableStateFlow<List<Prompt>>(emptyList())
    val state = prompts.asStateFlow()

    fun request(
        context: Context,
        owner: String,
        tool: String,
        reason: String,
        isCancelled: () -> Boolean,
        stillAllowed: () -> Boolean,
    ): MainScreenFallbackDecision {
        if (owner.isBlank() || Looper.myLooper() == Looper.getMainLooper()) return MainScreenFallbackDecision.UNAVAILABLE
        val app = context.applicationContext
        appContext = app
        val manager = app.getSystemService(NotificationManager::class.java)
        var canNotify = app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager.areNotificationsEnabled()
        // 应用内对话框、悬浮窗与通知任一可用即可请求。
        val canOverlay = MainScreenFallbackOverlay.available(app)
        val canShowInApp = io.github.mangi.eta.agent.runtime.EtaMainVisibility.visible
        if (!canNotify && !canOverlay && !canShowInApp) return MainScreenFallbackDecision.UNAVAILABLE
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                app.getString(R.string.main_screen_fallback_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            canNotify = false
            if (!canOverlay && !canShowInApp) return MainScreenFallbackDecision.UNAVAILABLE
        }
        val ticket = MainScreenFallbackRequest(owner)
        pending[ticket.token] = Pending(ticket, manager)
        try {
            val reasonText = app.getString(R.string.main_screen_fallback_reason_action)
            val open = PendingIntent.getActivity(
                app, NOTIFICATION_ID, Intent(app, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val label = toolDisplayNameResource(tool)?.let { app.getString(it) } ?: app.getString(R.string.settings_virtual_display_title)
            val text = app.getString(R.string.main_screen_fallback_notification_body, label, reasonText)
            // 每个请求都进弹窗队列：在 Eta 内显示应用内对话框，在其他界面显示系统悬浮窗，通知作后备。
            prompts.update { it + Prompt(ticket.token, text) }
            MainScreenFallbackOverlay.ensure(app)
            val builder = Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(app.getString(R.string.main_screen_fallback_notification_title))
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(Notification.CATEGORY_ERROR).setTimeoutAfter(180_000)
                .setDeleteIntent(action(app, ticket.token, ACTION_DENY))
            val notification = builder
                .addAction(
                    Notification.Action.Builder(
                        null, app.getString(R.string.main_screen_fallback_allow),
                        action(app, ticket.token, ACTION_ALLOW)
                    ).setAuthenticationRequired(true).build()
                )
                .addAction(
                    Notification.Action.Builder(
                        null, app.getString(R.string.main_screen_fallback_deny),
                        action(app, ticket.token, ACTION_DENY)
                    ).build()
                )
                .build()
            if (canNotify) manager.notify(ticket.token, NOTIFICATION_ID, notification)
            return ticket.await(isCancelled, stillAllowed)
        } catch (_: RuntimeException) {
            return MainScreenFallbackDecision.UNAVAILABLE
        } finally {
            pending.remove(ticket.token)
            prompts.update { list -> list.filterNot { it.token == ticket.token } }
            ticket.resolve(MainScreenFallbackDecision.CANCELLED)
            manager.cancel(ticket.token, NOTIFICATION_ID)
        }
    }

    private fun action(context: Context, token: String, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, MainScreenFallbackReceiver::class.java).setAction(action)
                .setData(Uri.parse("eta-ui-fallback://request/$token/$action"))
                .putExtra(TOKEN, token),
            PendingIntent.FLAG_IMMUTABLE,
        )

    fun receive(intent: Intent) {
        val token = intent.getStringExtra(TOKEN) ?: return
        val item = pending[token] ?: return
        val decision = when (intent.action) {
            ACTION_ALLOW -> MainScreenFallbackDecision.ALLOWED
            ACTION_DENY -> MainScreenFallbackDecision.DENIED
            else -> return
        }
        resolve(token, decision)
    }

    fun resolve(token: String, decision: MainScreenFallbackDecision) {
        val item = pending[token] ?: return
        if (decision == MainScreenFallbackDecision.ALLOWED &&
            appContext?.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) return
        if (item.request.resolve(decision)) {
            item.manager.cancel(token, NOTIFICATION_ID)
            prompts.update { list -> list.filterNot { it.token == token } }
        }
    }

    fun cancelOwner(owner: String) =
        pending.values.filter { it.request.owner == owner }.forEach(::cancel)

    fun cancelAll() = pending.values.forEach(::cancel)
    private fun cancel(item: Pending) {
        item.request.resolve(MainScreenFallbackDecision.CANCELLED)
        item.manager.cancel(item.request.token, NOTIFICATION_ID)
    }
}

internal class MainScreenFallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) =
        MainScreenFallbackApproval.receive(intent)
}

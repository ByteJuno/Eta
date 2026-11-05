package io.github.bytejuno.theta.agent.device

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.bytejuno.theta.data.repository.NotificationHistoryRepository
import java.util.concurrent.Executors

class AgentNotificationHistoryService : NotificationListenerService() {
    private val repository by lazy { NotificationHistoryRepository(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val content = NotificationContentExtractor.extract(sbn.notification)
        // Listener callbacks arrive on the main thread; keep SQLite work off it.
        writer.execute { record(sbn, content) }
        publishTrigger("notification_posted", sbn, content)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        publishTrigger("notification_removed", sbn, NotificationContentExtractor.extract(sbn.notification))
    }

    private fun publishTrigger(type: String, sbn: StatusBarNotification, content: NotificationContentExtractor.Content) {
        if (sbn.packageName == packageName) return
        // Group summaries duplicate their children and carry no content of their own.
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 &&
            content.title == null && content.text == null) return
        io.github.bytejuno.theta.agent.automation.AgentTaskScheduler.publish(this, type, "${sbn.key}:${sbn.postTime}", org.json.JSONObject()
            .put("packageName", sbn.packageName).put("title", content.title?.take(1000).orEmpty())
            .put("text", content.text?.take(2000).orEmpty()))
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectedService = this
        val snapshot = runCatching { activeNotifications }.getOrNull().orEmpty()
            .map { it to NotificationContentExtractor.extract(it.notification) }
        writer.execute { snapshot.forEach { (sbn, content) -> record(sbn, content) } }
    }

    override fun onListenerDisconnected() {
        if (connectedService === this) connectedService = null
        super.onListenerDisconnected()
        // OEM power managers may unbind listeners; ask the system to reconnect while access is granted.
        runCatching { requestRebind(ComponentName(this, AgentNotificationHistoryService::class.java)) }
    }

    override fun onDestroy() {
        if (connectedService === this) connectedService = null
        super.onDestroy()
    }

    private fun record(sbn: StatusBarNotification, content: NotificationContentExtractor.Content) {
        runCatching {
            repository.record(
                key = sbn.key,
                packageName = sbn.packageName,
                title = content.title,
                text = content.text,
                subText = content.subText,
                postedAt = sbn.postTime,
                updatesInPlace = updatesInPlace(sbn.notification),
            )
        }
    }

    companion object {
        /** Long-lived status notifications rewrite one row; messages keep their history. */
        internal fun updatesInPlace(notification: Notification): Boolean =
            notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0 ||
                notification.category in setOf(
                    Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE,
                    Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_NAVIGATION,
                    Notification.CATEGORY_STOPWATCH,
                )

        private val writer = Executors.newSingleThreadExecutor { task ->
            Thread(task, "eta-notification-history").apply { isDaemon = true }
        }

        @Volatile
        private var connectedService: AgentNotificationHistoryService? = null

        internal fun currentNotifications(): Array<StatusBarNotification>? {
            val service = connectedService ?: return null
            return try {
                service.activeNotifications
            } catch (_: RuntimeException) {
                null
            }
        }

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
                ?: return false
            return manager.isNotificationListenerAccessGranted(
                ComponentName(context, AgentNotificationHistoryService::class.java),
            )
        }
    }
}

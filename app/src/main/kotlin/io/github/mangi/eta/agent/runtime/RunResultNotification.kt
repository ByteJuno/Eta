package io.github.mangi.eta.agent.runtime

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.MainActivity

/**
 * Durable completion notice for background runs.
 *
 * The overlay result card is transient: it cannot be drawn while the screen is off or
 * the keyguard is up, and it auto-dismisses after a few seconds. A run that finishes while
 * the user is away therefore needs a regular notification that survives until it is read.
 */
internal object RunResultNotification {
    private const val CHANNEL = "eta_run_results"
    // The framework Builder has no setSilent(); silence is a channel property instead.
    private const val QUIET_CHANNEL = "eta_run_results_quiet"
    private const val TAG = "eta-run-result"
    private const val MAX_TEXT_CHARS = 600

    enum class Outcome { SUCCESS, FAILED, STOPPED, PAUSED }

    /** Card visibility depends on an unlocked, lit screen; otherwise the result needs a notice. */
    fun userCanSeeOverlay(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return power?.isInteractive == true && keyguard?.isKeyguardLocked != true
    }

    fun show(
        context: Context,
        runId: String,
        outcome: Outcome,
        detail: String,
        conversationId: String?,
        archive: Pair<String, String>?,
        silent: Boolean = false,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.run_result_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    QUIET_CHANNEL,
                    context.getString(R.string.run_result_quiet_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            val channel = if (silent) QUIET_CHANNEL else CHANNEL
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply {
                    when {
                        conversationId != null -> {
                            action = MainActivity.ACTION_OPEN_CONVERSATION_ID
                            putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                        }
                        archive != null -> {
                            action = MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION
                            putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, archive.first)
                            putExtra(MainActivity.EXTRA_CONVERSATION_KEY, archive.second)
                        }
                    }
                }
            val pending = PendingIntent.getActivity(
                context, runId.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val title = context.getString(
                when (outcome) {
                    Outcome.SUCCESS -> R.string.run_result_done
                    Outcome.FAILED -> R.string.run_result_failed
                    Outcome.STOPPED -> R.string.run_result_stopped
                    Outcome.PAUSED -> R.string.run_result_paused
                },
            )
            val text = detail.trim().ifBlank { context.getString(R.string.run_result_open_hint) }
                .take(MAX_TEXT_CHARS)
            val notification = Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_STATUS)
                // Answers may contain private data; the lock screen shows only the title.
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    Notification.Builder(context, channel)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(title)
                        .build(),
                )
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            manager.notify(TAG, runId.hashCode(), notification)
        }.onFailure { failure ->
            AndroidAgentLogger.warn("Run result notification failed: type=${failure.safeLogType()}")
        }
    }

    /** Opening the conversation in Eta already shows the answer; drop the duplicate notice. */
    fun cancel(context: Context, runId: String) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(TAG, runId.hashCode())
        }
    }
}

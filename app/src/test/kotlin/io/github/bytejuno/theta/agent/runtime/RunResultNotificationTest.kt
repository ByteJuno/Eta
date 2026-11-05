package io.github.bytejuno.theta.agent.runtime

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import io.github.bytejuno.theta.R
import io.github.bytejuno.theta.ui.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RunResultNotificationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @After
    fun tearDown() = manager.cancelAll()

    @Test
    fun finishedRunPostsPrivateNotificationThatOpensItsConversation() {
        RunResultNotification.show(
            context, "run-1", RunResultNotification.Outcome.SUCCESS,
            detail = "已整理好今天的账单", conversationId = "conv-1", archive = null,
        )

        val posted = manager.activeNotifications.single()
        val notification = posted.notification
        assertEquals(context.getString(R.string.run_result_done), notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("已整理好今天的账单", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        // The lock-screen version must not leak the answer.
        assertNotNull(notification.publicVersion)
        assertEquals(null, notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)

        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity.ACTION_OPEN_CONVERSATION_ID, intent.action)
        assertEquals("conv-1", intent.getStringExtra(MainActivity.EXTRA_CONVERSATION_ID))
    }

    @Test
    fun externalEntryOpensArchiveAndFailureHasDistinctTitle() {
        RunResultNotification.show(
            context, "run-2", RunResultNotification.Outcome.FAILED,
            detail = "模型接口暂时限流", conversationId = null, archive = "eta_voice" to "key-9",
        )

        val notification = manager.activeNotifications.single().notification
        assertEquals(context.getString(R.string.run_result_failed), notification.extras.getString(Notification.EXTRA_TITLE))
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION, intent.action)
        assertEquals("eta_voice", intent.getStringExtra(MainActivity.EXTRA_HANDOFF_SOURCE))
        assertEquals("key-9", intent.getStringExtra(MainActivity.EXTRA_CONVERSATION_KEY))
    }

    @Test
    fun separateRunsKeepSeparateNoticesAndCancelRemovesOnlyItsOwn() {
        RunResultNotification.show(context, "a", RunResultNotification.Outcome.SUCCESS, "A", "c-a", null)
        RunResultNotification.show(context, "b", RunResultNotification.Outcome.SUCCESS, "B", "c-b", null)
        assertEquals(2, manager.activeNotifications.size)

        RunResultNotification.cancel(context, "a")

        val left = manager.activeNotifications.single().notification
        assertEquals("B", left.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun blankAnswerFallsBackToHintAndLongAnswerIsBounded() {
        RunResultNotification.show(context, "blank", RunResultNotification.Outcome.SUCCESS, "  ", null, null)
        assertEquals(
            context.getString(R.string.run_result_open_hint),
            manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        manager.cancelAll()

        RunResultNotification.show(context, "long", RunResultNotification.Outcome.SUCCESS, "字".repeat(2_000), null, null)
        assertEquals(600, manager.activeNotifications.single().notification.extras.getCharSequence(Notification.EXTRA_TEXT)!!.length)
    }

    @Test
    fun silentNoticeUsesLowImportanceChannelSoTheShownCardIsNotDoubled() {
        RunResultNotification.show(context, "quiet", RunResultNotification.Outcome.SUCCESS, "已完成", "c", null, silent = true)
        RunResultNotification.show(context, "loud", RunResultNotification.Outcome.SUCCESS, "已完成", "c2", null)

        val byText = manager.activeNotifications.associate { it.tag + it.id to it.notification.channelId }
        val quietChannel = manager.getNotificationChannel(byText.values.first { it.endsWith("_quiet") })
        val loudChannel = manager.getNotificationChannel(byText.values.first { !it.endsWith("_quiet") })
        assertEquals(NotificationManager.IMPORTANCE_LOW, quietChannel.importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, loudChannel.importance)
    }
}

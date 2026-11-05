package io.github.bytejuno.theta.agent.device

import android.app.Application
import android.app.Notification
import android.app.Person
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NotificationContentExtractorTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun messagingStyleKeepsSendersAndRealLinesInsteadOfCounter() {
        val me = Person.Builder().setName("我").build()
        val friend = Person.Builder().setName("小王").build()
        val notification = Notification.Builder(context, "chat")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("干饭团伙")
            .setContentText("[2条]小王: 今晚吃啥")
            .setStyle(
                Notification.MessagingStyle(me)
                    .setConversationTitle("干饭团伙")
                    .addMessage("几点下班", 1L, friend)
                    .addMessage("今晚吃啥", 2L, friend),
            )
            .build()

        val content = NotificationContentExtractor.extract(notification)

        assertEquals("干饭团伙", content.title)
        assertEquals("小王: 几点下班\n小王: 今晚吃啥", content.text)
    }

    @Test
    fun inboxAndBigTextAreUsedBeforeTruncatedSummary() {
        val inbox = Notification.Builder(context, "mail")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("3 封新邮件")
            .setContentText("3 封新邮件")
            .setStyle(Notification.InboxStyle().addLine("账单已出").addLine("快递到了"))
            .build()
        assertEquals("账单已出\n快递到了", NotificationContentExtractor.extract(inbox).text)

        val big = Notification.Builder(context, "news")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("取件码")
            .setContentText("您的包裹已到…")
            .setStyle(Notification.BigTextStyle().bigText("您的包裹已到驿站，取件码 8-2-1234"))
            .build()
        assertEquals("您的包裹已到驿站，取件码 8-2-1234", NotificationContentExtractor.extract(big).text)
    }

    @Test
    fun plainNotificationAndEmptyExtrasAreStable() {
        val plain = Notification.Builder(context, "plain")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("  订单  ")
            .setContentText("骑手已取餐")
            .setSubText("外卖")
            .build()
        val content = NotificationContentExtractor.extract(plain)
        assertEquals("订单", content.title)
        assertEquals("骑手已取餐", content.text)
        assertEquals("外卖", content.subText)

        val empty = NotificationContentExtractor.extract(Bundle())
        assertNull(empty.title)
        assertNull(empty.text)
        assertNull(NotificationContentExtractor.extract(null as Bundle?).text)
    }

    @Test
    fun onlyTheMostRecentEightMessagesAreKept() {
        val friend = Person.Builder().setName("A").build()
        val style = Notification.MessagingStyle(Person.Builder().setName("我").build())
        (1..12).forEach { style.addMessage("m$it", it.toLong(), friend) }
        val notification = Notification.Builder(context, "chat")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(style)
            .build()

        val lines = NotificationContentExtractor.extract(notification).text!!.lines()

        assertEquals(8, lines.size)
        assertEquals("A: m12", lines.last())
    }
}

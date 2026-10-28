package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import android.content.pm.ServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 34], application = Application::class)
class AgentExecutionServiceTest {
    @Test
    @Config(sdk = [33, 36])
    fun promotionTracksTheActualAgentLeaseAndEndsWhileTerminalRemains() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        AgentExecutionService.acquire(context, "test:terminal-session") {}
        AgentExecutionService.acquire(context, "run:promotion") {}
        AgentExecutionService.updateRunStatus(context, AgentOverlayStatus.Reasoning)
        shadowOf(Looper.getMainLooper()).idle()
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val notification = requireNotNull(shadowOf(controller.get()).lastForegroundNotification)
            assertEquals(Build.VERSION.SDK_INT >= 36, notification.extras.getBoolean("android.requestPromotedOngoing"))
            if (Build.VERSION.SDK_INT >= 36) {
                assertEquals(Notification.ProgressStyle::class.java.name, notification.extras.getString(Notification.EXTRA_TEMPLATE))
                assertEquals(context.getString(R.string.overlay_reasoning), notification.extras.getString("android.shortCriticalText"))
            }
            AgentExecutionService.release("run:promotion")
            AgentExecutionService.updateRunStatus(context, null)
            shadowOf(Looper.getMainLooper()).idle()
            val remaining = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
            assertFalse(remaining.extras.getBoolean("android.requestPromotedOngoing"))
            assertFalse(remaining.extras.containsKey("android.shortCriticalText"))
            assertTrue(remaining.flags and Notification.FLAG_ONGOING_EVENT != 0)
        } finally {
            AgentExecutionService.release("run:promotion")
            AgentExecutionService.release("test:terminal-session")
            controller.destroy()
        }
    }

    @Test
    fun backgroundLeaseIsNotPresentedAsAnAssistantRun() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        AgentExecutionService.acquire(context, "terminal-background-commands", onStop = {})
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val notification = shadowOf(controller.get()).lastForegroundNotification
            assertEquals(context.getString(io.github.mangi.eta.R.string.execution_background_title),
                notification.extras.getString(android.app.Notification.EXTRA_TITLE))
            assertEquals(android.app.Notification.VISIBILITY_PRIVATE, notification.visibility)
        } finally {
            AgentExecutionService.release("terminal-background-commands")
            controller.destroy()
        }
    }

    @Test
    fun activeAgentLeaseTakesPrecedenceOverBackgroundLease() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        AgentExecutionService.acquire(context, "terminal-background-commands", onStop = {})
        AgentExecutionService.acquire(context, "run:test", onStop = {})
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val notification = shadowOf(controller.get()).lastForegroundNotification
            assertEquals(context.getString(io.github.mangi.eta.R.string.execution_title),
                notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        } finally {
            AgentExecutionService.release("run:test")
            AgentExecutionService.release("terminal-background-commands")
            controller.destroy()
        }
    }

    @Test
    fun foregroundExecutionUsesOnlyServiceTypesSupportedByThePlatform() {
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            val service = controller.get()
            assertNotNull(shadowOf(service).lastForegroundNotification)
            assertEquals(
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE,
                service.foregroundServiceType,
            )
        } finally {
            controller.destroy()
        }
    }
}

package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.content.pm.ServiceInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

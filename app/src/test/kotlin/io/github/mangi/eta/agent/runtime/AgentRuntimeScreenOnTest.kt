package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.os.Looper
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import androidx.compose.ui.platform.ComposeView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.robolectric.Shadows.shadowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeScreenOnTest {
    @Test
    fun notificationFollowsInteractionOwnerThenRemainingBackgroundRun() {
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = controller.get()
            val background = AgentRuntimeSession("background")
            val owner = AgentRuntimeSession("gui")
            service.registerTestRun(background)
            service.setRunStatus(background, AgentOverlayStatus.RunningTool("terminal_job"))
            service.assertStatus(AgentOverlayStatus.RunningTool("terminal_job"))
            service.registerTestRun(owner, selected = false)
            service.setRunStatus(owner, AgentOverlayStatus.GeneratingAnswer)
            service.callRuntime("markInteractionOwner", owner)
            shadowOf(Looper.getMainLooper()).idle()
            service.assertStatus(AgentOverlayStatus.GeneratingAnswer)
            owner.controller.pause()
            service.assertStatus(AgentOverlayStatus.Paused)
            owner.complete(AgentRuntimeWire.RunResult(owner.runId, true, "完成"))
            service.assertStatus(AgentOverlayStatus.RunningTool("terminal_job"))
            service.callRuntime("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            background.complete(AgentRuntimeWire.RunResult(background.runId, true, "完成"))
            service.assertStatus(null)
            service.callRuntime("updateKeepScreenOn")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
        } finally { controller.destroy() }
    }

    @Test
    fun backgroundCompletionDoesNotClearAnotherRunsScreenRequest() {
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = controller.get()
            val owner = AgentRuntimeSession("gui")
            val background = AgentRuntimeSession("background")
            service.registerTestRun(owner)
            service.registerTestRun(background, selected = false)
            background.complete(AgentRuntimeWire.RunResult(background.runId, true, "完成"))
            service.callRuntime("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
        } finally { controller.destroy() }
    }

    private fun AgentRuntimeService.setRunStatus(session: AgentRuntimeSession, status: AgentOverlayStatus) {
        val run = requireNotNull(testRegistry().active(session.runId))
        run.javaClass.getDeclaredField("overlayState").apply { isAccessible = true }
            .set(run, AgentOverlayState.Initial.copy(status = status))
    }

    private fun AgentRuntimeService.assertStatus(status: AgentOverlayStatus?) {
        callRuntime("publishExecutionState")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(status, AgentExecutionService::class.java.getDeclaredField("runStatus").apply {
            isAccessible = true
        }.get(null))
    }

    @Test
    fun pauseResumeAndCancellationUpdateBothAppAndGuiWindowState() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val session = AgentRuntimeSession("active-run")
            val capsule = ComposeView(service)
            service.registerTestRun(session)
            service.setField("capsuleView", capsule)

            service.invoke("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            assertTrue(capsule.keepScreenOn)

            service.invoke("requestPause")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(capsule.keepScreenOn)

            service.invoke("requestResume")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            assertTrue(capsule.keepScreenOn)

            session.controller.cancel()
            service.invoke("updateKeepScreenOn")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(capsule.keepScreenOn)

            service.invoke("requestResume")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
            assertFalse(capsule.keepScreenOn)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun completedRunStopsKeepingScreenOnBeforeServiceDestruction() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val session = AgentRuntimeSession("completed-run")
            service.registerTestRun(session)
            service.invoke("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)

            session.complete(AgentRuntimeWire.RunResult(session.runId, true, "完成"))
            service.invoke("updateKeepScreenOn")
            assertFalse(AgentRuntimeService.keepScreenOn.value)

            service.invoke("requestResume")
            assertFalse(AgentRuntimeService.keepScreenOn.value)
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun destroyingServiceClearsActiveRunScreenRequest() {
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            service.registerTestRun(AgentRuntimeSession("destroyed-run"))
            service.invoke("updateKeepScreenOn")
            assertTrue(AgentRuntimeService.keepScreenOn.value)
        } finally {
            serviceController.destroy()
        }
        assertFalse(AgentRuntimeService.keepScreenOn.value)
    }

    private fun AgentRuntimeService.setField(name: String, value: Any) {
        AgentRuntimeService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.set(this, value)
    }

    private fun AgentRuntimeService.invoke(name: String) {
        AgentRuntimeService::class.java.getDeclaredMethod(name).apply {
            isAccessible = true
        }.invoke(this)
    }
}

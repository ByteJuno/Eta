package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeParallelServiceTest {
    @Test
    fun cancellingNonSelectedRunDoesNotCancelTheSelectedConversationOrItsScreenLease() {
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = controller.get()
            val a = AgentRuntimeSession("a")
            val b = AgentRuntimeSession("b")
            service.registerTestRun(a)
            service.registerTestRun(b)
            service.callRuntime("cancelRun", "a")
            assertTrue(a.controller.isCancelled)
            assertFalse(b.controller.isCancelled)
            assertSame(b, service.readRuntimeField("activeSession"))
            assertTrue(AgentRuntimeService.keepScreenOn.value)
        } finally { controller.destroy() }
    }

    @Test
    fun completingSelectedRunSelectsAnotherRunAndKeepsRuntimeAlive() {
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = controller.get()
            val a = AgentRuntimeSession("a")
            val b = AgentRuntimeSession("b")
            service.registerTestRun(b)
            service.registerTestRun(a)
            val result = AgentRuntimeWire.RunResult("a", true, "done")
            a.complete(result)
            service.callRuntime("postTerminalOverlay", a, result, null, null)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("b"), service.testRegistry().runIds())
            assertSame(b, service.readRuntimeField("activeSession"))
            assertFalse(b.controller.isCancelled)
            assertTrue(AgentRuntimeService.keepScreenOn.value)
            assertFalse(shadowOf(service).isStoppedBySelf)
        } finally { controller.destroy() }
    }

    @Test
    fun cancellingPendingRequestLeavesOtherPreparationAndExecutionIntact() {
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = controller.get()
            val active = AgentRuntimeSession("active")
            service.registerTestRun(active)
            service.registerTestPending(runtimeTestRequest("pending-a"))
            service.registerTestPending(runtimeTestRequest("pending-b"))
            service.callRuntime("cancelRun", "pending-a")
            assertEquals(listOf("active", "pending-b"), service.testRegistry().runIds())
            assertNotNull(service.testRegistry().pending("pending-b"))
            assertFalse(active.controller.isCancelled)
            assertFalse(shadowOf(service).isStoppedBySelf)
        } finally { controller.destroy() }
    }

    @Test
    fun activeQueryIncludesEveryRunAndPendingRequestAndAttachTargetsItsOwnRun() {
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = controller.get()
            val a = AgentRuntimeSession("a")
            val b = AgentRuntimeSession("b")
            service.registerTestRun(a)
            service.registerTestRun(b)
            service.registerTestPending(runtimeTestRequest("preparing"))
            val messages = mutableListOf<Message>()
            val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) { messages += Message.obtain(msg) }
            })
            service.callRuntime("sendActiveRun", receiver)
            service.callRuntime("attachRun", "a", receiver)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("a", "b", "preparing"), AgentRuntimeWire.activeRunIdsFromBundle(
                messages.single { it.what == AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE }.data,
            ))
            assertTrue(AgentRuntimeWire.attachRunSucceeded(
                messages.single { it.what == AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE }.data,
            ))
            assertSame(b, service.readRuntimeField("activeSession"))
        } finally { controller.destroy() }
    }

    @Test
    fun backgroundSteeringOnlyChangesThatRunsControllerAndSupplementHistory() {
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = controller.get()
            val a = AgentRuntimeSession("a")
            val b = AgentRuntimeSession("b")
            service.registerTestRun(a)
            service.registerTestRun(b)
            assertEquals(true, service.callRuntime("requestSupplement", "only a", "a"))
            assertTrue(a.controller.hasPendingSteering)
            assertFalse(b.controller.hasPendingSteering)
            assertSame(b, service.readRuntimeField("activeSession"))
        } finally { controller.destroy() }
    }

    @Test
    fun pendingDuplicateSubscribesToTheOriginalRequestsTerminalResult() {
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = controller.get()
            val first = mutableListOf<Message>()
            val second = mutableListOf<Message>()
            fun receiver(messages: MutableList<Message>) = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) { messages += Message.obtain(msg) }
            })
            val request = runtimeTestRequest("preparing")
            val original = service.registerTestPending(request, receiver(first))
            val retryReceiver = receiver(second)
            repeat(2) {
                service.callRuntime("ingestRunRequest", AgentRuntimeWire.IncomingRunRequest(request, emptyList()), retryReceiver, true)
            }
            assertSame(original, service.testRegistry().pending("preparing"))
            service.callRuntime("cancelRun", "preparing")
            (service.readRuntimeField("resultIo") as java.util.concurrent.ExecutorService)
                .submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, first.count { it.what == AgentRuntimeWire.MSG_RESULT })
            assertEquals(1, second.count { it.what == AgentRuntimeWire.MSG_RESULT })
            assertEquals("preparing", AgentRuntimeWire.runIdFromBundle(second.single {
                it.what == AgentRuntimeWire.MSG_RESULT
            }.data))
        } finally { controller.destroy() }
    }

    @Test
    fun terminalDuplicateRetryReceivesTheCachedResultBeforeOverlayRetirement() {
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = controller.get()
            val session = AgentRuntimeSession("terminal-retry", resultSink = { result ->
                service.callRuntime("publishRunResult", result, emptyList<Messenger>())
            })
            service.registerTestRun(session)
            val completed = AgentRuntimeWire.RunResult(session.runId, true, "finished before overlay retirement")
            session.complete(completed)
            val messages = mutableListOf<Message>()
            val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) { messages += Message.obtain(msg) }
            })
            service.callRuntime("ingestRunRequest", AgentRuntimeWire.IncomingRunRequest(
                runtimeTestRequest(session.runId), emptyList(),
            ), receiver, true)
            (service.readRuntimeField("resultIo") as java.util.concurrent.ExecutorService)
                .submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(AgentRuntimeWire.attachRunSucceeded(messages.single {
                it.what == AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE
            }.data))
            assertEquals(completed, AgentRuntimeWire.runResultFromBundle(messages.single {
                it.what == AgentRuntimeWire.MSG_RESULT
            }.data))
        } finally { controller.destroy() }
    }

    @Test
    fun newTextRunCannotReplaceTheInteractionOwnersControls() {
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = controller.get()
            val owner = AgentRuntimeSession("interaction")
            val text = AgentRuntimeSession("text")
            service.registerTestRun(owner)
            service.callRuntime("markInteractionOwner", owner)
            shadowOf(Looper.getMainLooper()).idle()
            service.registerTestRun(text, selected = false)
            service.callRuntime("selectNewRun", service.testRegistry().active("text"))
            assertSame(owner, service.readRuntimeField("activeSession"))
            service.callRuntime("requestPause")
            assertTrue(owner.controller.isPaused)
            assertFalse(text.controller.isPaused)
            assertTrue(AgentRuntimeService.keepScreenOn.value)
        } finally { controller.destroy() }
    }

    @Test
    fun nextTurnCanBeAdmittedAfterTerminalPublicationBeforeOverlayRetirement() {
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = controller.get()
            val previous = AgentRuntimeSession("previous")
            service.registerTestRun(previous, conversationId = "conversation")
            previous.complete(AgentRuntimeWire.RunResult("previous", true, "done"))
            val next = runtimeTestRequest("next", "conversation")
            service.callRuntime("ingestRunRequest", AgentRuntimeWire.IncomingRunRequest(next, emptyList()), null, true)
            assertNotNull(service.testRegistry().pending("next"))
            assertNotNull(service.testRegistry().active("previous"))
            service.callRuntime("cancelRun", "next")
        } finally { controller.destroy() }
    }
}

package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AgentLogger
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentLocalToolsInteractionTest {
    @Test(timeout = 10_000)
    fun cancellationDuringAcquisitionCallbackNeverReachesToolBoundary() {
        val stopped = AtomicBoolean()
        val reached = AtomicBoolean()
        val executor = tools(isCancelled = stopped::get,
            onAcquired = { stopped.set(true) },
            beforeTool = { reached.set(true); reject() })
        try {
            assertEquals("DISPLAY_CANCELLED", code(executor, "observe_screen"))
            assertFalse(reached.get())
        } finally { executor.close() }
    }

    @Test(timeout = 10_000)
    fun acquisitionIsAnnouncedOnceAndNeverForNonUiTools() {
        val count = java.util.concurrent.atomic.AtomicInteger()
        val executor = tools(onAcquired = { count.incrementAndGet() })
        try {
            assertEquals("TEST_BOUNDARY", code(executor, "search_apps"))
            assertEquals(0, count.get())
            assertEquals("TEST_BOUNDARY", code(executor, "observe_screen"))
            assertEquals("TEST_BOUNDARY", code(executor, "observe_screen"))
            assertEquals(1, count.get())
        } finally { executor.close() }
    }

    @Test(timeout = 10_000)
    fun waitingConversationCanUseNonUiToolsAndCancelWithoutAffectingOwner() {
        val waiting = CountDownLatch(1)
        val stopped = AtomicBoolean()
        val first = tools()
        val second = tools(isCancelled = { waiting.countDown(); stopped.get() })
        val worker = worker()
        try {
            assertEquals("TEST_BOUNDARY", code(first, "observe_screen"))
            val pendingUi = worker.submit<String> { code(second, "observe_screen") }
            assertTrue(waiting.await(1, TimeUnit.SECONDS))
            assertFalse(pendingUi.isDone)
            assertEquals("TEST_BOUNDARY", code(second, "search_apps"))
            stopped.set(true)
            second.close()
            assertEquals("DISPLAY_CANCELLED", pendingUi.get(1, TimeUnit.SECONDS))
            assertEquals("TEST_BOUNDARY", code(first, "observe_screen"))
        } finally {
            first.close()
            second.close()
            worker.shutdownNow()
        }
    }

    @Test(timeout = 10_000)
    fun executorCloseDoesNotHandOffUntilItsActiveToolReturns() {
        val active = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val nextStarted = CountDownLatch(1)
        val first = tools(beforeTool = {
            active.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            reject()
        })
        val second = tools(beforeTool = { nextStarted.countDown(); reject() })
        val workers = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "local-tools-interaction-test").apply { isDaemon = true }
        }
        try {
            val firstResult = workers.submit<String> { code(first, "observe_screen") }
            assertTrue(active.await(1, TimeUnit.SECONDS))
            first.close()
            val secondResult = workers.submit<String> { code(second, "observe_screen") }
            assertFalse(nextStarted.await(150, TimeUnit.MILLISECONDS))
            finish.countDown()
            assertEquals("TEST_BOUNDARY", firstResult.get(1, TimeUnit.SECONDS))
            assertEquals("TEST_BOUNDARY", secondResult.get(1, TimeUnit.SECONDS))
        } finally {
            finish.countDown()
            first.close()
            second.close()
            workers.shutdownNow()
        }
    }

    private fun tools(
        isCancelled: () -> Boolean = { false },
        onAcquired: () -> Unit = {},
        beforeTool: (String) -> ToolExecutionDecision = { reject() },
    ) = AgentLocalTools(
        context = RuntimeEnvironment.getApplication() as Context,
        logger = NoOpLogger,
        browserRunId = UUID.randomUUID().toString(),
        browserToolsEnabled = { false },
        terminalToolsEnabled = { false },
        deviceDirectToolsEnabled = { true },
        memoryToolsEnabled = { false },
        rootAvailable = { false },
        virtualDisplayEnabled = { false },
        beforeToolExecution = beforeTool,
        isRunCancelled = isCancelled,
        onInteractionAcquired = onAcquired,
    )

    private fun code(tools: AgentLocalTools, name: String): String = JSONObject(
        tools.execute(AgentModelClient.ToolCall("test-$name", name, "{}")).content,
    ).getString("code")

    private fun worker() = Executors.newSingleThreadExecutor { task ->
        Thread(task, "local-tools-interaction-test").apply { isDaemon = true }
    }

    private fun reject() = ToolExecutionDecision.Reject("TEST_BOUNDARY", "Tool boundary reached")

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}

package io.github.mangi.eta.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class AgentRunPauseHoldTest {
    @Test
    fun resumeCannotBypassViewerHoldAndLastHoldWakesCheckpoint() {
        val controller = AgentRunController()
        val one = controller.holdPause()
        val two = controller.holdPause()
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val worker =
            thread(isDaemon = true) {
                started.countDown()
                controller.throwIfCancelled()
                done.countDown()
            }
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            controller.resume()
            one.close()
            one.close()
            assertFalse(done.await(100, TimeUnit.MILLISECONDS))
            two.close()
            assertTrue(done.await(1, TimeUnit.SECONDS))
        } finally {
            controller.cancel()
            worker.join(1000)
        }
    }

    @Test
    fun cancelUnblocksPausedCheckpoint() {
        val controller = AgentRunController()
        val hold = controller.holdPause()
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val worker =
            thread(isDaemon = true) {
                try {
                    controller.throwIfCancelled()
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    done.countDown()
                }
            }
        try {
            controller.cancel()
            assertTrue(done.await(1, TimeUnit.SECONDS))
            assertTrue(failure.get() is AgentRunCancelledException)
            hold.close()
            assertFalse(controller.isPaused)
        } finally {
            controller.cancel()
            worker.join(1000)
        }
    }
}

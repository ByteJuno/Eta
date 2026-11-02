package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.virtualdisplay.MainScreenApprovalPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentInteractionLeaseTest {
    @Test(timeout = 5_000)
    fun waitingNotificationIsBalancedAndDoesNotOwnTheMonitor() {
        val coordinator = AgentInteractionLeaseCoordinator()
        val first = coordinator.newLease()
        val second = coordinator.newLease()
        val events = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val started = CountDownLatch(1)
        val worker = worker()
        try {
            assertTrue(first.enter({ false }))
            first.leave()
            val pending = worker.submit<Boolean> {
                second.enter({ false }, onWaiting = {
                    events.add(it)
                    if (it) started.countDown()
                }).also { if (it) second.leave() }
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            first.close()
            assertTrue(pending.get(1, TimeUnit.SECONDS))
            org.junit.Assert.assertEquals(listOf(true, false), events.toList())
        } finally { first.close(); second.close(); worker.shutdownNow() }
    }

    @Test(timeout = 5_000)
    fun closedOwnerCannotReacquireAfterHandoff() {
        val coordinator = AgentInteractionLeaseCoordinator()
        val first = coordinator.newLease()
        val second = coordinator.newLease()
        val worker = worker()
        try {
            assertTrue(first.enter({ false }))
            first.leave()
            val pending = worker.submit<Boolean> {
                second.enter({ false }).also { if (it) second.leave() }
            }
            assertPending(pending)
            // Closing transfers ownership; the old executor must never reacquire it.
            assertTrue(first.isOwner)
            first.close()
            assertTrue(pending.get(1, TimeUnit.SECONDS))
            assertFalse(first.enter({ false }))
        } finally {
            first.close()
            second.close()
            worker.shutdownNow()
        }
    }

    @Test(timeout = 5_000)
    fun ownershipSurvivesToolBoundariesUntilExecutorCloses() {
        val coordinator = AgentInteractionLeaseCoordinator()
        val first = coordinator.newLease()
        val second = coordinator.newLease()
        val worker = worker()
        try {
            assertTrue(first.enter({ false }))
            first.leave()
            val entered = worker.submit<Boolean> {
                second.enter({ false }).also { if (it) second.leave() }
            }
            assertPending(entered)
            assertTrue(first.isOwner)
            first.close()
            assertTrue(entered.get(1, TimeUnit.SECONDS))
        } finally {
            first.close()
            second.close()
            worker.shutdownNow()
        }
    }

    @Test(timeout = 5_000)
    fun cancellationDoesNotReleaseAnActionStillUnwinding() {
        val coordinator = AgentInteractionLeaseCoordinator()
        val first = coordinator.newLease()
        val second = coordinator.newLease()
        val worker = worker()
        try {
            assertTrue(first.enter({ false }))
            first.close()
            val entered = worker.submit<Boolean> {
                second.enter({ false }).also { if (it) second.leave() }
            }
            assertPending(entered)
            assertTrue(first.isOwner)
            first.leave()
            assertTrue(entered.get(1, TimeUnit.SECONDS))
        } finally {
            first.close()
            second.close()
            worker.shutdownNow()
        }
    }

    @Test(timeout = 5_000)
    fun waitingCanBeCancelledWithoutStealingAnotherRunsLease() {
        val coordinator = AgentInteractionLeaseCoordinator()
        val first = coordinator.newLease()
        val second = coordinator.newLease()
        val cancelled = AtomicBoolean()
        val waiting = CountDownLatch(1)
        val worker = worker()
        try {
            assertTrue(first.enter({ false }))
            first.leave()
            val entered = worker.submit<Boolean> {
                second.enter({ waiting.countDown(); cancelled.get() })
            }
            assertTrue(waiting.await(1, TimeUnit.SECONDS))
            cancelled.set(true)
            assertFalse(entered.get(1, TimeUnit.SECONDS))
            assertTrue(first.isOwner)
            assertFalse(second.isOwner)
            first.close()
            cancelled.set(false)
            assertTrue(second.enter(cancelled::get))
            second.leave()
        } finally {
            first.close()
            second.close()
            worker.shutdownNow()
        }
    }

    @Test(timeout = 5_000)
    fun pausedWaiterDoesNotAcquireOrBlockOtherReadyRuns() {
        val coordinator = AgentInteractionLeaseCoordinator()
        val pausedRun = coordinator.newLease()
        val readyRun = coordinator.newLease()
        val paused = AtomicBoolean(true)
        val waiting = CountDownLatch(1)
        val worker = worker()
        try {
            val entered = worker.submit<Boolean> {
                pausedRun.enter({ false }, { waiting.countDown(); paused.get() })
                    .also { if (it) pausedRun.leave() }
            }
            assertTrue(waiting.await(1, TimeUnit.SECONDS))
            assertTrue(readyRun.enter({ false }))
            readyRun.leave()
            readyRun.close()
            assertPending(entered)
            paused.set(false)
            assertTrue(entered.get(1, TimeUnit.SECONDS))
        } finally {
            pausedRun.close()
            readyRun.close()
            worker.shutdownNow()
        }
    }

    @Test(timeout = 5_000)
    fun closingWaitingExecutorWakesItWithoutWaitingForOwner() {
        val coordinator = AgentInteractionLeaseCoordinator()
        val first = coordinator.newLease()
        val second = coordinator.newLease()
        val waiting = CountDownLatch(1)
        val worker = worker()
        try {
            assertTrue(first.enter({ false }))
            first.leave()
            val entered = worker.submit<Boolean> {
                second.enter({ waiting.countDown(); false })
            }
            assertTrue(waiting.await(1, TimeUnit.SECONDS))
            second.close()
            assertFalse(entered.get(1, TimeUnit.SECONDS))
            assertTrue(first.isOwner)
        } finally {
            first.close()
            second.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun onlySharedInteractionToolsNeedTheLease() {
        (MainScreenApprovalPolicy.sharedInteractionTools + setOf( "get_current_context", "app_state_control"))
            .forEach { assertTrue(it, AgentInteractionLeaseCoordinator.requiresLease(it)) }
        listOf("web_search", "fetch_url", "search_apps", "terminal", "terminal_job", "read_file", "skills_read", "memory_get")
            .forEach { assertFalse(it, AgentInteractionLeaseCoordinator.requiresLease(it)) }
    }

    private fun worker() = Executors.newSingleThreadExecutor { task ->
        Thread(task, "interaction-lease-test").apply { isDaemon = true }
    }

    private fun assertPending(future: Future<*>) {
        try {
            future.get(150, TimeUnit.MILLISECONDS)
            fail("Another run acquired a lease that should still be held")
        } catch (_: TimeoutException) {
            // The resource must remain reserved across model turns and cancellation cleanup.
        }
    }
}

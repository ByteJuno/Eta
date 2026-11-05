package io.github.bytejuno.theta.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeRunRegistryTest {
    @Test
    fun conversationsReserveIndependentlyAcrossPreparationAndExecution() {
        val registry = AgentRuntimeRunRegistry<Any, Any>()
        val pendingA = Any()
        val pendingB = Any()
        val activeA = Any()
        assertEquals(AgentRuntimeRunRegistry.Admission.ACCEPTED, registry.reserve("a", "conversation-a", pendingA))
        assertEquals(AgentRuntimeRunRegistry.Admission.ACCEPTED, registry.reserve("b", "conversation-b", pendingB))
        assertEquals(AgentRuntimeRunRegistry.Admission.CONVERSATION_BUSY, registry.reserve("a2", "conversation-a", Any()))
        assertEquals(AgentRuntimeRunRegistry.Admission.DUPLICATE_RUN, registry.reserve("a", "different", Any()))
        assertTrue(registry.promote("a", pendingA, activeA))
        assertEquals(AgentRuntimeRunRegistry.Admission.CONVERSATION_BUSY, registry.reserve("a2", "conversation-a", Any()))
        assertEquals(listOf("a", "b"), registry.runIds())
        assertSame(pendingB, registry.removePending("b"))
        assertSame(activeA, registry.active("a"))
        assertFalse(registry.isEmpty())
        assertTrue(registry.removeActive("a", activeA))
        assertTrue(registry.isEmpty())
        assertEquals(AgentRuntimeRunRegistry.Admission.ACCEPTED, registry.reserve("a2", "conversation-a", Any()))
    }

    @Test
    fun cancelledPreparationCannotPromoteOrRemoveANewerRun() {
        val registry = AgentRuntimeRunRegistry<Any, Any>()
        val oldPending = Any()
        val replacementPending = Any()
        registry.reserve("run", "conversation", oldPending)
        registry.removePending("run", oldPending)
        registry.reserve("run", "conversation", replacementPending)
        assertFalse(registry.promote("run", oldPending, Any()))
        assertNull(registry.removePending("run", oldPending))
        assertSame(replacementPending, registry.pending("run"))
        val active = Any()
        assertTrue(registry.promote("run", replacementPending, active))
        assertFalse(registry.removeActive("run", Any()))
        assertSame(active, registry.active("run"))
    }

    @Test
    fun concurrentRequestsCannotBothOwnTheSameConversation() {
        val registry = AgentRuntimeRunRegistry<Any, Any>()
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val admissions = java.util.Collections.synchronizedList(mutableListOf<AgentRuntimeRunRegistry.Admission>())
        repeat(2) { index ->
            Thread {
                try {
                    check(start.await(2, TimeUnit.SECONDS))
                    admissions += registry.reserve("run-$index", "same-conversation", Any())
                } finally { finished.countDown() }
            }.start()
        }
        start.countDown()
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertEquals(1, admissions.count { it == AgentRuntimeRunRegistry.Admission.ACCEPTED })
        assertEquals(1, admissions.count { it == AgentRuntimeRunRegistry.Admission.CONVERSATION_BUSY })
        assertEquals(1, registry.runIds().size)
    }
}

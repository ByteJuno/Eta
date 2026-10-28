package io.github.mangi.eta.agent.display

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.junit.Assert.*
import org.junit.Test

class VirtualScreenManualControlTest {
    @Test
    fun takeoverWaitsForAcknowledgementAndReleaseKeepsUserPause() {
        val run = AgentRunController()
        val gate = VirtualScreenManualControl()
        gate.bind(run)
        val enter = requireNotNull(gate.request("viewer", true))
        assertTrue(run.isPaused)
        assertNull(gate.token("viewer"))
        assertTrue(gate.applied(enter))
        assertEquals(enter.generation, gate.token("viewer"))
        run.pause()
        val exit = requireNotNull(gate.request("viewer", false))
        assertNull(gate.token("viewer"))
        assertTrue(run.isPaused)
        assertTrue(gate.applied(exit))
        assertTrue(run.isPaused)
        run.resume()
        assertFalse(run.isPaused)
    }

    @Test
    fun delayedExitAcknowledgementCannotReleaseNewTakeover() {
        val run = AgentRunController()
        val gate = VirtualScreenManualControl()
        gate.bind(run)
        val first = requireNotNull(gate.request("old", true))
        gate.applied(first)
        val exit = requireNotNull(gate.request("old", false))
        val next = requireNotNull(gate.request("new", true))
        assertFalse(gate.applied(exit))
        assertTrue(run.isPaused)
        assertTrue(gate.applied(next))
        assertFalse(gate.accepts("old", first.generation))
        assertFalse(gate.accepts("new", first.generation))
        assertTrue(gate.accepts("new", next.generation))
        assertNull(gate.request("old", false))
        assertTrue(run.isPaused)
        gate.close()
        assertFalse(run.isPaused)
    }

    @Test
    fun multipleViewersAndRunReplacementRetainTheCorrectHold() {
        val old = AgentRunController()
        val next = AgentRunController()
        val gate = VirtualScreenManualControl()
        gate.bind(old)
        gate.applied(requireNotNull(gate.request("one", true)))
        assertNull(gate.request("two", true))
        assertNull(gate.request("one", false))
        gate.bind(next)
        assertFalse(old.isPaused)
        assertTrue(next.isPaused)
        next.resume()
        assertTrue(next.isPaused)
        assertNotNull(gate.token("two"))
        old.cancel()
        assertTrue(next.isPaused)
        gate.applied(requireNotNull(gate.request("two", false)))
        assertFalse(next.isPaused)
    }
}

package io.github.bytejuno.theta.ui.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationRunRegistryTest {
    @Test
    fun differentConversationsRunIndependentlyButEachConversationStaysSerial() {
        val runs = AgentConversationRunRegistry()
        assertTrue(runs.register("run-a", "conversation-a"))
        assertTrue(runs.register("run-b", "conversation-b"))
        assertFalse(runs.register("run-a-next", "conversation-a"))
        assertFalse(runs.register("run-a", "conversation-c"))

        assertEquals(setOf("run-a", "run-b"), runs.runIds)
        assertEquals("run-a", runs.runIdForConversation("conversation-a"))
        assertEquals("run-b", runs.runIdForConversation("conversation-b"))
        assertNull(runs.runIdForConversation(null))
        assertNull(runs.runIdForConversation("new-conversation"))
    }

    @Test
    fun finishingOneRunReleasesOnlyItsConversationAndLateCompletionCannotRemoveItsSuccessor() {
        val runs = AgentConversationRunRegistry()
        runs.register("run-a", "conversation-a")
        runs.register("run-b", "conversation-b")

        assertTrue(runs.finish("run-a"))
        assertTrue(runs.register("run-a-next", "conversation-a"))
        assertFalse(runs.finish("run-a"))
        assertEquals("run-a-next", runs.runIdForConversation("conversation-a"))
        assertEquals("run-b", runs.runIdForConversation("conversation-b"))
        assertTrue(runs.isNotEmpty)
    }
}

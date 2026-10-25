package io.github.mangi.eta.agent.tool

import org.junit.Assert.*
import org.junit.Test

class InteractionWaitStateTest {
    @Test fun finishingOneWaiterDoesNotClearAnotherConversation() {
        try {
            InteractionWaitState.update("a", "conversation-a", true)
            InteractionWaitState.update("b", "conversation-b", true)
            InteractionWaitState.update("a", "conversation-a", false)
            assertFalse("conversation-a" in InteractionWaitState.conversations.value)
            assertTrue("conversation-b" in InteractionWaitState.conversations.value)
        } finally {
            InteractionWaitState.update("a", "conversation-a", false)
            InteractionWaitState.update("b", "conversation-b", false)
        }
    }
}

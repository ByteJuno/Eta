package io.github.mangi.eta.agent.virtualdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDisplayChoiceTest {
    @Test
    fun chipOrderAndStoredChoiceKeepTheExistingProtocol() {
        assertEquals(listOf(false, true), ConversationDisplayChoice.entries.map { it.usesPrimary })
        assertTrue(ConversationDisplayChoice.fromStored("primary").usesPrimary)
        listOf(null, "", "virtual", "unknown").forEach {
            assertFalse(ConversationDisplayChoice.fromStored(it).usesPrimary)
        }
    }
}

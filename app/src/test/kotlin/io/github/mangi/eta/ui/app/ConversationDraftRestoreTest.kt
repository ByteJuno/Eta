package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDraftRestoreTest {
    private fun state() = AgentChatHomeUiState(
        messages = emptyList(), input = "", isStreaming = false, thinkingEnabled = false,
    )

    @Test
    fun restoresRuntimeInputAndVersionWithoutReplacingLoadedHistory() {
        val loaded = state().copy(messages = listOf(UserMessageUi("message", "saved history")))
        val draft = state().copy(
            input = "unsent draft", inputSyncVersion = 7, focusInputOnSync = false,
            isConversationLoaded = false,
            pendingImages = listOf(PendingImageUi("image", "cached.png", "preview.png", "image/png")),
        )
        val restored = loaded.withConversationDraftFrom(draft)
        assertEquals("unsent draft", restored.input)
        assertEquals(7, restored.inputSyncVersion)
        assertFalse(restored.focusInputOnSync)
        assertEquals(draft.pendingImages, restored.pendingImages)
        assertEquals(loaded.messages, restored.messages)
        assertTrue(restored.isConversationLoaded)
    }

    @Test
    fun rejectedSubmissionRestoresExactTextAndKeepsAttachments() {
        val original = state().copy(
            input = "older draft", inputSyncVersion = 8, focusInputOnSync = false,
            pendingImages = listOf(PendingImageUi("image", "cached.png", "preview.png", "image/png")),
        )
        val text = "  keep indentation\nnext line  "
        val restored = original.withRejectedSubmission(text)
        assertEquals(original.copy(input = text, inputSyncVersion = 9, focusInputOnSync = true), restored)
    }

    @Test
    fun rejectedSubmissionWithoutExplicitTextKeepsStoredDraft() {
        val original = state().copy(input = "stored draft")
        assertEquals("stored draft", original.withRejectedSubmission(null).input)
        assertEquals("", original.withRejectedSubmission("").input)
    }

    @Test
    fun newerEmptyDraftClearsOldInputAndAttachments() {
        val earlier = state().copy(
            input = "old draft", inputSyncVersion = 3,
            pendingImages = listOf(PendingImageUi("image", "cached.png", "preview.png", "image/png")),
        )
        val latest = state().copy(inputSyncVersion = 4)
        val restored = earlier.withConversationDraftFrom(latest)
        assertEquals("", restored.input)
        assertEquals(4, restored.inputSyncVersion)
        assertTrue(restored.pendingImages.isEmpty())
        assertTrue(restored.pendingFileReferences.isEmpty())
    }
}

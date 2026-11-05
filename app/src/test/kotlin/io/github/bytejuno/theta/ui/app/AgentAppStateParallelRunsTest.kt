package io.github.bytejuno.theta.ui.app

import android.content.Context
import io.github.bytejuno.theta.agent.model.AgentModelClient
import io.github.bytejuno.theta.agent.runtime.AgentRunCheckpointStore
import io.github.bytejuno.theta.agent.runtime.AgentRuntimeWire
import io.github.bytejuno.theta.ui.model.AgentChatHomeUiState
import io.github.bytejuno.theta.ui.model.QueuedMessageUi
import io.github.bytejuno.theta.ui.model.UserMessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentAppStateParallelRunsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun sendingInAnotherConversationStartsItsRunWithoutStoppingTheFirstConversation() {
        val state = appState(selected = "b", streaming = false)
        state.markRunning("a")

        state.sendCurrentMessage("new task for b")

        assertEquals("run-a", state.runs.runIdForConversation("a"))
        assertNotNull(state.runs.runIdForConversation("b"))
        assertTrue(state.field<Set<String>>("stopRequestedRunIds").isEmpty())
        assertTrue(state.conversation("a").isStreaming)
        assertTrue(state.homeState.isStreaming)
        assertEquals("new task for b", state.homeState.messages.filterIsInstance<UserMessageUi>().last().content)
        assertEquals(setOf("a", "b"), state.conversationPaneState.conversations.filter { it.isActiveRun }.map { it.id }.toSet())
    }

    @Test
    fun stoppingSelectedConversationDoesNotStopTheMostRecentlyStartedOtherConversation() {
        val state = runningState(selected = "a")

        state.stopCurrentRun()

        assertEquals(setOf("run-a"), state.field<Set<String>>("stopRequestedRunIds"))
        assertEquals(setOf("run-a"), state.field<Set<String>>("userStoppedRunIds"))
        assertTrue(state.conversation("b").isStreaming)
        assertEquals("run-b", state.runs.runIdForConversation("b"))
    }

    @Test
    fun interruptingSelectedConversationQueuesItsMessageWithoutTouchingOtherRuns() {
        val state = runningState(selected = "b")
        state.selectConversation("a")

        state.interruptAndSendCurrentMessage("change a's task")

        assertEquals(setOf("run-a"), state.field<Set<String>>("stopRequestedRunIds"))
        assertTrue(state.field<Set<String>>("userStoppedRunIds").isEmpty())
        assertEquals("change a's task", state.homeState.queuedMessages.single().content)
        assertTrue(state.homeState.queuedMessages.single().immediateInterrupt)
        assertTrue(state.conversation("b").queuedMessages.isEmpty())
        assertTrue(state.conversation("b").isStreaming)
    }

    @Test
    fun finishingBackgroundConversationLeavesSelectedConversationStreamingAndHistoryUntouched() {
        val state = runningState(selected = "b")
        val selectedBefore = state.homeState

        state.complete("run-a")

        assertEquals(selectedBefore, state.homeState)
        assertTrue(state.homeState.isStreaming)
        assertEquals("run-b", state.runs.runIdForConversation("b"))
        assertNull(state.runs.runIdForConversation("a"))
        assertFalse(state.conversation("a").isStreaming)
        assertEquals("finished a", state.conversation("a").history.last().content)
        assertFalse(state.homeState.history.any { it.content == "finished a" })
        assertEquals(setOf("b"), state.conversationPaneState.conversations.filter { it.isActiveRun }.map { it.id }.toSet())
    }

    @Test
    fun finishingBackgroundConversationDispatchesItsQueueWhileAnotherConversationKeepsRunning() {
        val queued = QueuedMessageUi("queue-a", "next task for a")
        val state = runningState(selected = "b", queueA = listOf(queued))
        val selectedBefore = state.homeState

        state.complete("run-a")

        val nextRunId = state.runs.runIdForConversation("a")
        assertNotNull(nextRunId)
        assertNotEquals("run-a", nextRunId)
        assertEquals("run-b", state.runs.runIdForConversation("b"))
        assertEquals(selectedBefore, state.homeState)
        assertTrue(state.conversation("a").isStreaming)
        assertTrue(state.conversation("a").queuedMessages.isEmpty())
        assertEquals("next task for a", state.conversation("a").messages.filterIsInstance<UserMessageUi>().last().content)
        assertEquals(setOf("a", "b"), state.conversationPaneState.conversations.filter { it.isActiveRun }.map { it.id }.toSet())
    }

    @Test
    fun recoveredRunsRestoreStreamingStateForEveryConversationAndRemainIndependentlyStoppable() {
        val state = appState(selected = "b", streaming = false)
        state.reattach("run-a", "a")
        state.reattach("run-b", "b")

        assertTrue(state.conversation("a").isStreaming)
        assertTrue(state.homeState.isStreaming)
        assertEquals(setOf("run-a", "run-b"), state.runs.runIds)
        state.stopCurrentRun()
        assertEquals(setOf("run-b"), state.field<Set<String>>("stopRequestedRunIds"))
        assertTrue(state.conversation("a").isStreaming)
    }

    @Test
    fun compactingIdleConversationIsAllowedWhileAnotherConversationRuns() {
        val state = appState(selected = "a", streaming = false)
        state.markRunning("b")
        val existing = state.conversation("a")
        state.update("a", existing.copy(history = existing.history + AgentModelClient.ConversationMessage(
            role = "assistant", content = "previous answer",
        )))

        state.compactCurrentContext()

        assertTrue(state.homeState.isCompacting)
        assertTrue(state.homeState.isStreaming)
        assertEquals("run-b", state.runs.runIdForConversation("b"))
        assertTrue(state.conversation("b").isStreaming)
        assertTrue(state.field<Set<String>>("stopRequestedRunIds").isEmpty())
    }

    private fun runningState(selected: String, queueA: List<QueuedMessageUi> = emptyList()): AgentAppState =
        appState(selected, streaming = true, queueA = queueA).also { state ->
            state.runs.register("run-a", "a")
            state.runs.register("run-b", "b")
            state.field<MutableMap<String, String>>("runConversationIds").putAll(mapOf("run-a" to "a", "run-b" to "b"))
        }

    private fun appState(selected: String, streaming: Boolean, queueA: List<QueuedMessageUi> = emptyList()): AgentAppState {
        val conversations = listOf("a", "b").associateWith { id ->
            val user = UserMessageUi("user-run-$id", "initial $id")
            AgentChatHomeUiState(
                messages = listOf(user),
                history = listOf(AgentModelClient.ConversationMessage(role = "user", content = user.content, messageId = user.id)),
                input = "draft $id",
                isStreaming = streaming,
                thinkingEnabled = false,
                queuedMessages = if (id == "a") queueA else emptyList(),
            )
        }
        // Exercise synchronous UI transitions without starting real models, Binder, or persistence workers.
        val scope = CoroutineScope(SupervisorJob().also { it.cancel() } + Dispatchers.Main.immediate)
        return AgentAppState(context, scope, AgentConversationStore.Snapshot(
            selectedConversationId = selected,
            conversationsById = conversations,
            titles = mapOf("a" to "A", "b" to "B"),
            updatedAt = mapOf("a" to 1L, "b" to 2L),
        ))
    }

    private val AgentAppState.runs: AgentConversationRunRegistry get() = field("activeRuns")

    private fun AgentAppState.conversation(id: String): AgentChatHomeUiState =
        field<Map<String, AgentChatHomeUiState>>("conversationsById").getValue(id)

    private fun AgentAppState.markRunning(conversationId: String) {
        val runId = "run-$conversationId"
        runs.register(runId, conversationId)
        field<MutableMap<String, String>>("runConversationIds")[runId] = conversationId
        update(conversationId, conversation(conversationId).copy(isStreaming = true))
    }

    private fun AgentAppState.update(conversationId: String, conversation: AgentChatHomeUiState) {
        AgentAppState::class.java.getDeclaredMethod("updateConversation", String::class.java,
            AgentChatHomeUiState::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(this, conversationId, conversation, false)
    }

    private fun AgentAppState.complete(runId: String) {
        AgentAppState::class.java.getDeclaredMethod("applyRunResult", String::class.java,
            AgentRuntimeWire.RunResult::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(this, runId, AgentRuntimeWire.RunResult(runId, true, "finished a", transcript = listOf(
                AgentModelClient.ConversationMessage(role = "assistant", content = "finished a"),
            )), false)
    }

    private fun AgentAppState.reattach(runId: String, conversationId: String) {
        val checkpoint = AgentRunCheckpointStore.Checkpoint(
            runId = runId, ownerInstanceId = "runtime-owner",
            handoff = AgentRuntimeWire.EntryHandoff(runId, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, conversationId),
            events = emptyList(), createdAt = 1L, updatedAt = 2L,
        )
        AgentAppState::class.java.getDeclaredMethod("startReattachedRun", AgentRunCheckpointStore.Checkpoint::class.java)
            .apply { isAccessible = true }.invoke(this, checkpoint)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> AgentAppState.field(name: String): T =
        AgentAppState::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as T
}

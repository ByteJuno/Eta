package io.github.bytejuno.theta.ui.app

import io.github.bytejuno.theta.agent.runtime.AgentRunCheckpointStore
import io.github.bytejuno.theta.agent.runtime.AgentRuntimeWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunRecoveryCoordinatorTest {
    @Test
    fun allActiveConversationsReattachWhileOnlyStaleCheckpointIsInterrupted() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-a"), checkpoint("run-b"), checkpoint("run-stale")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunIds = setOf("run-a", "run-b"),
            locallyObservedRunIds = emptySet(),
        )

        assertEquals(listOf("run-a", "run-b"), plan.reattach.map { it.runId })
        assertEquals(listOf("run-stale"), plan.interrupted.map { it.runId })
    }

    @Test
    fun locallyObservedRunsDoNotBlockRecoveringOtherConversationsOrReplayTheirOwnResults() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-a"), checkpoint("run-b"), checkpoint("run-c")),
            completedRuns = listOf(completed("run-a"), completed("run-b")),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunIds = setOf("run-a", "run-b", "run-c"),
            locallyObservedRunIds = setOf("run-a", "run-b"),
        )

        assertTrue(plan.completed.isEmpty())
        assertTrue(plan.interrupted.isEmpty())
        assertEquals(listOf("run-c"), plan.reattach.map { it.runId })
    }

    @Test
    fun completedConversationAndMultipleActiveConversationsRecoverTogether() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-a"), checkpoint("run-b"), checkpoint("run-c")),
            completedRuns = listOf(completed("run-b")),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunIds = setOf("run-a", "run-c"),
            locallyObservedRunIds = emptySet(),
        )

        assertEquals(listOf("run-b"), plan.completed.map { it.result.result.runId })
        assertEquals(listOf("run-a", "run-c"), plan.reattach.map { it.runId })
        assertTrue(plan.interrupted.isEmpty())
    }

    @Test
    fun sameProcessActiveCheckpointIsReattached() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-active", owner = "same-process")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunIds = setOf("run-active"),
            locallyObservedRunIds = emptySet(),
        )

        assertEquals("run-active", plan.reattach.single().runId)
        assertTrue(plan.interrupted.isEmpty())
    }

    @Test
    fun terminalResultTakesPrecedenceAndKeepsCheckpointForTraceReplay() {
        val checkpoint = checkpoint("run-complete")
        val completed = completed("run-complete")

        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint),
            completedRuns = listOf(completed),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunIds = setOf("run-complete"),
            locallyObservedRunIds = emptySet(),
        )

        assertEquals(completed, plan.completed.single().result)
        assertEquals(checkpoint, plan.completed.single().checkpoint)
        assertTrue(plan.reattach.isEmpty())
        assertTrue(plan.interrupted.isEmpty())
    }

    @Test
    fun inactiveCheckpointIsInterruptedButLocallyObservedRunIsIgnored() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-stale"), checkpoint("run-local")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = true,
            activeRunIds = emptySet(),
            locallyObservedRunIds = setOf("run-local"),
        )

        assertEquals(listOf("run-stale"), plan.interrupted.map { it.runId })
        assertTrue(plan.reattach.isEmpty())
    }

    @Test
    fun unavailableRuntimeLeavesUnresolvedCheckpointUntouched() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-unknown")),
            completedRuns = emptyList(),
            activeStateKnown = false,
            terminalStateKnown = false,
            activeRunIds = emptySet(),
            locallyObservedRunIds = emptySet(),
        )

        assertTrue(plan.interrupted.isEmpty())
        assertTrue(plan.reattach.isEmpty())
    }

    @Test
    fun knownActiveRunCanReattachEvenWhenTerminalQueryFailed() {
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = listOf(checkpoint("run-active"), checkpoint("run-unknown")),
            completedRuns = emptyList(),
            activeStateKnown = true,
            terminalStateKnown = false,
            activeRunIds = setOf("run-active"),
            locallyObservedRunIds = emptySet(),
        )

        assertEquals("run-active", plan.reattach.single().runId)
        assertTrue(plan.interrupted.isEmpty())
    }

    private fun checkpoint(
        runId: String,
        owner: String = "old-process",
    ) = AgentRunCheckpointStore.Checkpoint(
        runId = runId,
        ownerInstanceId = owner,
        handoff = AgentRuntimeWire.EntryHandoff(
            id = runId,
            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            payload = "conversation-1",
        ),
        events = emptyList(),
        createdAt = 1L,
        updatedAt = 2L,
    )

    private fun completed(runId: String) = AgentRuntimeWire.CompletedRun(
        handoff = AgentRuntimeWire.EntryHandoff(
            id = runId,
            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            payload = "conversation-1",
        ),
        result = AgentRuntimeWire.RunResult(
            runId = runId,
            ok = true,
            content = "完成",
        ),
        createdAt = 3L,
    )
}

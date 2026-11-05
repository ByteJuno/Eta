package io.github.bytejuno.theta.agent.plugin.calendar

import org.junit.Assert.*
import org.junit.Test

class CalendarWriteCoordinatorTest {
    private val plan = CalendarWritePlan("create_calendar_event", "{\"calendar_id\":\"1\"}")
    private fun context(cancelled: () -> Boolean = { false }, allowed: () -> Boolean = { true }) =
        CalendarWriteContext("run", "builtin.calendar", 1, canRequestApproval = true, isCancelled = cancelled,
            isPaused = { false }, stillAllowed = allowed)
    private fun approved() = CalendarHostApproval { request ->
        assertTrue(request.approve())
        assertFalse(request.approve())
        AutoCloseable { }
    }
    private fun coordinator(store: Store, host: CalendarHostApproval? = approved(), clock: () -> Long = { 100 }) =
        CalendarWriteCoordinator(store, "process", { false }, host, clock)

    @Test fun persistsIntentAndInflightBeforeDelegate() {
        val store = Store()
        val outcome = coordinator(store).execute<String>(plan, context()) {
            assertEquals(CalendarOperationState.IN_FLIGHT, store.records.values.single().state)
            CalendarWriteExecution("result", CalendarWriteEffect.VERIFIED,
                CalendarOperationEvidence(listOf("42"), true))
        }
        assertEquals(CalendarOperationState.SUCCEEDED, outcome.state)
        assertEquals(listOf("42"), store.evidence?.eventIds)
    }

    @Test fun failedInsertNeverCallsUiOrDelegate() {
        val store = Store().apply { failInsert = true }
        val outcome = coordinator(store, CalendarHostApproval { error("must not show") })
            .execute<String>(plan, context()) { error("must not execute") }
        assertEquals("CALENDAR_LEDGER_OR_APPROVAL_FAILED", outcome.code)
    }

    @Test fun failedInflightCommitNeverCallsDelegate() {
        val store = Store().apply { failAt = CalendarOperationState.IN_FLIGHT }
        coordinator(store).execute<String>(plan, context()) { error("must not execute") }
        assertEquals(CalendarOperationState.CANCELLED_NOT_STARTED, store.records.values.single().state)
    }

    @Test fun noUiFailsClosed() {
        val store = Store()
        val outcome = coordinator(store, null).execute<String>(plan, context()) { error("must not execute") }
        assertEquals("APPROVAL_REQUIRED", outcome.code)
        assertEquals(CalendarOperationState.REJECTED, outcome.state)
    }

    @Test fun backgroundEntryNeverShowsAvailableHostUi() {
        val outcome = coordinator(Store(), CalendarHostApproval { error("must not show") })
            .execute<String>(plan, context().copy(canRequestApproval = false)) { error("must not execute") }
        assertEquals("APPROVAL_REQUIRED", outcome.code)
    }

    @Test fun synchronousToolBridgeUsesApprovedPlanNotOriginalArguments() {
        val store = Store()
        val bridge = CalendarWriteToolExecutor(coordinator(store), { context() }, { plan },
            io.github.bytejuno.theta.agent.model.AgentModelClient.ToolExecutor { call ->
                assertEquals(plan.normalizedArguments, call.argumentsJson)
                assertEquals(CalendarOperationState.IN_FLIGHT, store.records.values.single().state)
                io.github.bytejuno.theta.agent.model.AgentModelClient.ToolResult("done", sensitive = true)
            }, { CalendarWriteExecution(it, CalendarWriteEffect.VERIFIED) })
        val result = bridge.execute(io.github.bytejuno.theta.agent.model.AgentModelClient.ToolCall(
            "model-call", plan.tool, "model supplied unnormalized arguments"))
        assertEquals("done", result.content)
    }

    @Test fun approvalCannotBeReusedAcrossIdenticalOperations() {
        val store = Store()
        lateinit var old: CalendarApprovalRequest
        val first = coordinator(store, CalendarHostApproval { old = it; it.approve(); AutoCloseable { } })
            .execute<String>(plan, context()) { CalendarWriteExecution("one", CalendarWriteEffect.VERIFIED) }
        val second = coordinator(store, CalendarHostApproval {
            assertFalse(old.approve())
            assertEquals(CalendarApprovalRequest.Decision.PENDING, it.current())
            it.deny()
            AutoCloseable { }
        }).execute<String>(plan, context()) { error("must not execute") }
        assertNotEquals(first.operationId, second.operationId)
        assertEquals("APPROVAL_DENIED", second.code)
        assertEquals(1, store.records.values.map { it.parameterHash }.distinct().size)
    }

    @Test fun childCancellationDuringApprovalClosesTicket() {
        val store = Store()
        var cancelled = false
        lateinit var ticket: CalendarApprovalRequest
        val result = coordinator(store, CalendarHostApproval {
            ticket = it; cancelled = true; AutoCloseable { }
        }).execute<String>(plan, context(cancelled = { cancelled })) { error("must not execute") }
        assertEquals(CalendarOperationState.CANCELLED_NOT_STARTED, result.state)
        assertFalse(ticket.approve())
    }

    @Test fun permissionRevokedAfterApprovalNeverExecutes() {
        val store = Store()
        var permitted = true
        val result = coordinator(store, CalendarHostApproval {
            it.approve(); permitted = false; AutoCloseable { }
        }).execute<String>(plan, context(allowed = { permitted })) { error("must not execute") }
        assertEquals(CalendarOperationState.CANCELLED_NOT_STARTED, result.state)
    }

    @Test fun expiredApprovalIsNotConsumed() {
        var time = 100L
        val result = coordinator(Store(), CalendarHostApproval {
            time = it.operation.expiresAt
            assertFalse(it.approve())
            AutoCloseable { }
        }, { time }).execute<String>(plan, context()) { error("must not execute") }
        assertEquals("APPROVAL_EXPIRED", result.code)
    }

    @Test fun exceptionAfterDelegationIsUnknownAndNeverRetried() {
        val store = Store()
        var calls = 0
        val result = coordinator(store).execute<String>(plan, context()) { calls++; error("readback failed") }
        assertEquals(1, calls)
        assertEquals(CalendarOperationState.UNKNOWN, result.state)
        assertEquals(CalendarOperationState.UNKNOWN, store.records.values.single().state)
    }

    @Test fun failedTerminalCommitLeavesRecoverableInflight() {
        val store = Store().apply { failTerminal = true }
        val result = coordinator(store).execute<String>(plan, context()) {
            CalendarWriteExecution("already written", CalendarWriteEffect.VERIFIED)
        }
        assertEquals("OPERATION_UNCONFIRMED", result.code)
        assertEquals(CalendarOperationState.IN_FLIGHT, store.records.values.single().state)
        store.recoverInterrupted("new-process")
        store.recoverInterrupted("new-process")
        assertEquals(CalendarOperationState.UNKNOWN, store.records.values.single().state)
    }

    @Test fun mainThreadNeverTouchesLedger() {
        val store = Store()
        val result = CalendarWriteCoordinator(store, "process", { true })
            .execute<String>(plan, context()) { error("must not execute") }
        assertEquals("CALENDAR_WORKER_REQUIRED", result.code)
        assertTrue(store.records.isEmpty())
    }

    @Test fun terminalStatesCannotAdmitAgain() {
        for (state in listOf(CalendarOperationState.UNKNOWN, CalendarOperationState.SUCCEEDED,
            CalendarOperationState.REJECTED, CalendarOperationState.CANCELLED_NOT_STARTED)) {
            assertFalse(CalendarOperationTransitions.allows(state, CalendarOperationState.ADMITTED))
            assertFalse(CalendarOperationTransitions.allows(state, CalendarOperationState.IN_FLIGHT))
        }
    }

    /** Test double only: NOT a production persistence implementation. */
    private class Store : CalendarOperationLedger {
        val records = linkedMapOf<String, CalendarOperation>()
        var evidence: CalendarOperationEvidence? = null
        var failInsert = false
        var failAt: CalendarOperationState? = null
        var failTerminal = false
        override fun insertPrepared(operation: CalendarOperation): Boolean {
            if (failInsert) error("disk unavailable")
            if (records.containsKey(operation.operationId)) return false
            records[operation.operationId] = operation
            return true
        }
        override fun transition(operation: CalendarOperation, expected: CalendarOperationState,
            next: CalendarOperationState, evidence: CalendarOperationEvidence?): Boolean {
            if (next == failAt || failTerminal && expected == CalendarOperationState.IN_FLIGHT) error("disk unavailable")
            val current = records[operation.operationId] ?: return false
            if (current.state != expected || current.copy(state = operation.state) != operation) return false
            check(CalendarOperationTransitions.allows(expected, next))
            records[operation.operationId] = current.copy(state = next)
            this.evidence = evidence
            return true
        }
        override fun recoverInterrupted(currentProcessOwner: String) {
            records.replaceAll { _, record ->
                if (record.processOwner == currentProcessOwner) record else when (record.state) {
                    CalendarOperationState.IN_FLIGHT -> record.copy(state = CalendarOperationState.UNKNOWN)
                    CalendarOperationState.PREPARED, CalendarOperationState.WAITING_APPROVAL,
                    CalendarOperationState.ADMITTED -> record.copy(state = CalendarOperationState.CANCELLED_NOT_STARTED)
                    else -> record
                }
            }
        }
    }
}

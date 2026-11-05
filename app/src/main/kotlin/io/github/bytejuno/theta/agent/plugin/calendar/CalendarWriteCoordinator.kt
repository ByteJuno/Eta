package io.github.bytejuno.theta.agent.plugin.calendar

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Built by the host after validation/default resolution, never by decoding approval fields. */
internal data class CalendarWritePlan(
    val tool: String,
    val normalizedArguments: String,
    /** Host preparation display data only; never forwarded as execution arguments or persisted. */
    val targetPreviewJson: String? = null,
)

internal data class CalendarWriteContext(
    val runId: String,
    val connectionId: String,
    val generation: Long,
    val parentCallId: String? = null,
    val childId: String? = null,
    /** Host entry policy, not model arguments. Background/unknown entries default to no UI. */
    val canRequestApproval: Boolean = false,
    /** Include parent AND child cancellation, pending steering and executor closure. */
    val isCancelled: () -> Boolean,
    val isPaused: () -> Boolean,
    /** Recheck permissions, target preconditions, binding generation and entry policy. */
    val stillAllowed: () -> Boolean,
)

/** Only the host UI receives this object. Never serialize it into tools, IPC or model text. */
internal class CalendarApprovalRequest internal constructor(
    val operation: CalendarOperation,
    val plan: CalendarWritePlan,
    private val now: () -> Long,
) {
    private val decision = AtomicReference(Decision.PENDING)
    private val wake = CountDownLatch(1)
    internal enum class Decision { PENDING, APPROVED, DENIED, CLOSED }

    fun approve(): Boolean = resolve(Decision.APPROVED)
    fun deny(): Boolean = resolve(Decision.DENIED)

    private fun resolve(value: Decision): Boolean {
        if (now() >= operation.expiresAt) { close(); return false }
        val changed = decision.compareAndSet(Decision.PENDING, value)
        if (changed) wake.countDown()
        return changed
    }

    internal fun current(): Decision = decision.get()
    internal fun awaitTick() { wake.await(100, TimeUnit.MILLISECONDS) }
    internal fun close() { decision.set(Decision.CLOSED); wake.countDown() }
}

/** show must dispatch to the main thread and return promptly; close removes that exact card. */
internal fun interface CalendarHostApproval {
    fun show(request: CalendarApprovalRequest): AutoCloseable
}

/** This classification is host adapter evidence, not a model-controlled JSON status. */
internal enum class CalendarWriteEffect { VERIFIED, NO_EFFECT, UNCERTAIN }
internal data class CalendarWriteExecution<T>(
    val value: T,
    val effect: CalendarWriteEffect,
    val evidence: CalendarOperationEvidence? = null,
)
internal data class CalendarWriteOutcome<T>(
    val operationId: String?,
    val state: CalendarOperationState?,
    val code: String?,
    val value: T? = null,
)

/** Synchronous by design: intended to run inside ToolExecutor, never on the Android main thread. */
internal class CalendarWriteCoordinator(
    private val ledger: CalendarOperationLedger,
    private val processOwner: String,
    private val isMainThread: () -> Boolean,
    private val approval: CalendarHostApproval? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val approvalTimeoutMs: Long = 180_000,
) {
    init { require(processOwner.isNotBlank()); require(approvalTimeoutMs in 1..180_000) }

    fun <T> execute(
        plan: CalendarWritePlan,
        context: CalendarWriteContext,
        delegate: (CalendarWritePlan) -> CalendarWriteExecution<T>,
    ): CalendarWriteOutcome<T> {
        if (isMainThread()) return CalendarWriteOutcome(null, null, "CALENDAR_WORKER_REQUIRED")
        require(plan.tool in WRITE_TOOLS)
        require(context.runId.isNotBlank() && context.connectionId.isNotBlank())
        val operation = CalendarOperation(
            operationId = UUID.randomUUID().toString(), processOwner = processOwner,
            runId = context.runId, parentCallId = context.parentCallId, childId = context.childId,
            tool = plan.tool, connectionId = context.connectionId, generation = context.generation,
            parameterHash = hash(plan.normalizedArguments), expiresAt = now() + approvalTimeoutMs,
        )
        var state = CalendarOperationState.PREPARED
        var inserted = false
        var mayHaveExecuted = false
        var request: CalendarApprovalRequest? = null
        var presentation: AutoCloseable? = null
        fun move(next: CalendarOperationState, evidence: CalendarOperationEvidence? = null) {
            check(CalendarOperationTransitions.allows(state, next))
            check(ledger.transition(operation, state, next, evidence)) { "CALENDAR_LEDGER_CAS_FAILED" }
            state = next
        }
        fun rejected(code: String, terminal: CalendarOperationState): CalendarWriteOutcome<T> {
            move(terminal)
            return CalendarWriteOutcome(operation.operationId, state, code)
        }
        fun stopped(): Boolean = context.isCancelled() || Thread.currentThread().isInterrupted
        try {
            check(ledger.insertPrepared(operation)) { "CALENDAR_LEDGER_INSERT_FAILED" }
            inserted = true
            if (stopped()) return rejected("CALENDAR_CANCELLED", CalendarOperationState.CANCELLED_NOT_STARTED)
            if (!context.stillAllowed()) return rejected("CALENDAR_NOT_ALLOWED", CalendarOperationState.CANCELLED_NOT_STARTED)
            move(CalendarOperationState.WAITING_APPROVAL)
            val host = approval?.takeIf { context.canRequestApproval }
                ?: return rejected("APPROVAL_REQUIRED", CalendarOperationState.REJECTED)
            val ticket = CalendarApprovalRequest(operation, plan, now)
            request = ticket
            presentation = host.show(ticket)
            while (true) {
                if (stopped()) return rejected("CALENDAR_CANCELLED", CalendarOperationState.CANCELLED_NOT_STARTED)
                if (now() >= operation.expiresAt) return rejected("APPROVAL_EXPIRED", CalendarOperationState.REJECTED)
                if (!context.stillAllowed()) return rejected("CALENDAR_NOT_ALLOWED", CalendarOperationState.CANCELLED_NOT_STARTED)
                when (ticket.current()) {
                    CalendarApprovalRequest.Decision.DENIED -> return rejected("APPROVAL_DENIED", CalendarOperationState.REJECTED)
                    CalendarApprovalRequest.Decision.CLOSED -> return rejected("APPROVAL_EXPIRED", CalendarOperationState.REJECTED)
                    CalendarApprovalRequest.Decision.APPROVED -> if (!context.isPaused()) break
                    CalendarApprovalRequest.Decision.PENDING -> Unit
                }
                // An already-resolved latch must not busy-spin while the run is paused.
                if (ticket.current() == CalendarApprovalRequest.Decision.PENDING) ticket.awaitTick()
                else Thread.sleep(100)
            }
            move(CalendarOperationState.ADMITTED)
            ticket.close()
            if (stopped() || context.isPaused() || !context.stillAllowed() || now() >= operation.expiresAt) {
                return rejected("APPROVAL_EXPIRED", CalendarOperationState.CANCELLED_NOT_STARTED)
            }
            // Commit BEFORE invoking any Provider/Root delegate. No lock is held during approval.
            move(CalendarOperationState.IN_FLIGHT)
            // The durable commit may have blocked while the host cancelled/revoked this call.
            if (stopped() || context.isPaused() || !context.stillAllowed() || now() >= operation.expiresAt) {
                move(CalendarOperationState.FAILED_NO_EFFECT)
                return CalendarWriteOutcome(operation.operationId, state, "CALENDAR_NOT_STARTED")
            }
            mayHaveExecuted = true
            val execution = delegate(plan)
            val terminal = when (execution.effect) {
                CalendarWriteEffect.VERIFIED -> CalendarOperationState.SUCCEEDED
                CalendarWriteEffect.NO_EFFECT -> CalendarOperationState.FAILED_NO_EFFECT
                CalendarWriteEffect.UNCERTAIN -> CalendarOperationState.UNKNOWN
            }
            move(terminal, execution.evidence)
            return CalendarWriteOutcome(operation.operationId, state,
                if (terminal == CalendarOperationState.UNKNOWN) "OPERATION_UNCONFIRMED" else null, execution.value)
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            val terminal = if (mayHaveExecuted) CalendarOperationState.UNKNOWN else CalendarOperationState.CANCELLED_NOT_STARTED
            if (inserted && CalendarOperationTransitions.allows(state, terminal)) {
                runCatching { move(terminal) }
            }
            // A failed final commit leaves IN_FLIGHT for startup recovery. Never retry delegate.
            return CalendarWriteOutcome(operation.operationId, if (mayHaveExecuted) CalendarOperationState.UNKNOWN else state,
                if (mayHaveExecuted) "OPERATION_UNCONFIRMED" else "CALENDAR_LEDGER_OR_APPROVAL_FAILED")
        } finally {
            request?.close()
            runCatching { presentation?.close() }
        }
    }

    companion object {
        val WRITE_TOOLS = setOf("create_calendar_event", "create_calendar_events", "update_calendar_event", "delete_calendar_event")
        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

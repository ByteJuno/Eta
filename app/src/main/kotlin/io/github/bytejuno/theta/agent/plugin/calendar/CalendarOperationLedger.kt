package io.github.bytejuno.theta.agent.plugin.calendar

/** Host-only metadata. Neither arguments nor calendar content belong in this record. */
internal data class CalendarOperation(
    val operationId: String,
    val processOwner: String,
    val runId: String,
    val parentCallId: String?,
    val childId: String?,
    val tool: String,
    val connectionId: String,
    val generation: Long,
    val parameterHash: String,
    val expiresAt: Long,
    val state: CalendarOperationState = CalendarOperationState.PREPARED,
)

internal enum class CalendarOperationState {
    PREPARED, WAITING_APPROVAL, ADMITTED, IN_FLIGHT,
    SUCCEEDED, FAILED_NO_EFFECT, UNKNOWN, REJECTED, CANCELLED_NOT_STARTED,
}

/**
 * Production boundary implemented by CalendarSqliteOperationLedger; no in-memory production fallback.
 * Every method must return only after its durable transaction commits, or throw. CAS must
 * compare the full immutable binding AND expected state, not just a model call ID or digest.
 * Do not attach this coordinator until a durable implementation is supplied.
 *
 * Sensitive plans are not stored here. If a future implementation persists them, it must use
 * authenticated Keystore encryption. Evidence must be encrypted before persistence as well;
 * a ciphertext/reference alone is not proof that a mutation was verified.
 */
internal interface CalendarOperationLedger {
    /** Insert-only; a collision must not overwrite an existing operation. */
    fun insertPrepared(operation: CalendarOperation): Boolean

    /** Atomic compare-and-set. Evidence is committed with the terminal state, never logged. */
    fun transition(
        operation: CalendarOperation,
        expected: CalendarOperationState,
        next: CalendarOperationState,
        evidence: CalendarOperationEvidence? = null,
    ): Boolean

    /**
     * Startup in the authoritative App process, before accepting calendar writes:
     * atomically mark other process owners' IN_FLIGHT as UNKNOWN, and their PREPARED,
     * WAITING_APPROVAL and ADMITTED as CANCELLED_NOT_STARTED. Never replay a delegate.
     * Terminal records and the current process owner's records must remain unchanged.
     */
    fun recoverInterrupted(currentProcessOwner: String)
}

/** Raw values are transient; the durable implementation MUST encrypt them at rest. */
internal data class CalendarOperationEvidence(val eventIds: List<String>, val verified: Boolean)

internal object CalendarOperationTransitions {
    fun allows(from: CalendarOperationState, to: CalendarOperationState): Boolean = when (from) {
        CalendarOperationState.PREPARED -> to in setOf(
            CalendarOperationState.WAITING_APPROVAL, CalendarOperationState.CANCELLED_NOT_STARTED,
        )
        CalendarOperationState.WAITING_APPROVAL -> to in setOf(
            CalendarOperationState.ADMITTED, CalendarOperationState.REJECTED,
            CalendarOperationState.CANCELLED_NOT_STARTED,
        )
        CalendarOperationState.ADMITTED -> to in setOf(
            CalendarOperationState.IN_FLIGHT, CalendarOperationState.CANCELLED_NOT_STARTED,
        )
        CalendarOperationState.IN_FLIGHT -> to in setOf(
            CalendarOperationState.SUCCEEDED, CalendarOperationState.FAILED_NO_EFFECT,
            CalendarOperationState.UNKNOWN,
        )
        else -> false
    }
}

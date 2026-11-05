package io.github.bytejuno.theta.agent.plugin.calendar

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-process memory only. StateFlow delivers cards to the Compose main-thread collector. */
internal object CalendarApprovalBridge : CalendarHostApproval {
    private val lock = Any()
    private var foreground: Pair<Any, String>? = null
    private val active = mutableListOf<CalendarApprovalRequest>()
    private val pending = MutableStateFlow<List<CalendarApprovalRequest>>(emptyList())
    val requests: StateFlow<List<CalendarApprovalRequest>> = pending.asStateFlow()

    fun canPresent(runId: String): Boolean = synchronized(lock) {
        runId.isNotBlank() && foreground?.second == runId
    }

    /** UI owns this lease only while the selected conversation is visible and RESUMED. */
    fun registerForeground(runId: String): AutoCloseable = synchronized(lock) {
        require(runId.isNotBlank())
        revokeAll()
        val owner = Any()
        foreground = owner to runId
        AutoCloseable {
            synchronized(lock) {
                if (foreground?.first === owner) {
                    foreground = null
                    revokeAll()
                }
            }
        }
    }

    override fun show(request: CalendarApprovalRequest): AutoCloseable {
        val owner = synchronized(lock) {
            foreground?.takeIf { it.second == request.operation.runId }?.first
        }
        // Capture the exact UI lease, not just runId: background → foreground must not
        // resurrect a delayed presentation. close marks the ticket before any queued post.
        Handler(Looper.getMainLooper()).post {
            synchronized(lock) {
                if (owner == null || foreground?.first !== owner ||
                    request.current() != CalendarApprovalRequest.Decision.PENDING) {
                    request.close()
                } else {
                    if (active.none { it === request }) active += request
                    publish()
                }
            }
        }
        return AutoCloseable {
            synchronized(lock) {
                active.removeAll { it === request }
                request.close()
                publish()
            }
        }
    }

    fun approve(request: CalendarApprovalRequest): Boolean = synchronized(lock) {
        val accepted = active.any { it === request } &&
            foreground?.second == request.operation.runId && request.approve()
        // Keep approved tickets tracked until the coordinator closes them, so losing the
        // foreground also revokes an approved ticket still waiting behind a paused run.
        publish()
        accepted
    }

    fun deny(request: CalendarApprovalRequest) = synchronized(lock) {
        if (active.any { it === request }) request.deny()
        publish()
    }

    private fun revokeAll() {
        active.forEach { it.close() }
        active.clear()
        publish()
    }

    private fun publish() {
        pending.value = active.filter { it.current() == CalendarApprovalRequest.Decision.PENDING }
    }
}

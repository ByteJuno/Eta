package io.github.bytejuno.theta.agent.runtime

/** Owns admission across request preparation and execution, independently of the selected overlay. */
internal class AgentRuntimeRunRegistry<P : Any, A : Any> {
    enum class Admission { ACCEPTED, DUPLICATE_RUN, CONVERSATION_BUSY }

    private data class Entry<T>(val conversationId: String, val value: T)
    private val pending = linkedMapOf<String, Entry<P>>()
    private val active = linkedMapOf<String, Entry<A>>()

    @Synchronized
    fun reserve(runId: String, conversationId: String, value: P, isActiveBusy: (A) -> Boolean = { true }): Admission {
        if (runId in pending || runId in active) return Admission.DUPLICATE_RUN
        if (pending.values.any { it.conversationId == conversationId } ||
            active.values.any { it.conversationId == conversationId && isActiveBusy(it.value) }) return Admission.CONVERSATION_BUSY
        pending[runId] = Entry(conversationId, value)
        return Admission.ACCEPTED
    }

    @Synchronized
    fun promote(runId: String, expected: P, value: A): Boolean {
        val entry = pending[runId]?.takeIf { it.value === expected } ?: return false
        pending.remove(runId)
        active[runId] = Entry(entry.conversationId, value)
        return true
    }

    @Synchronized fun pending(runId: String): P? = pending[runId]?.value
    @Synchronized fun active(runId: String): A? = active[runId]?.value
    @Synchronized fun pendingValues(): List<P> = pending.values.map { it.value }
    @Synchronized fun activeValues(): List<A> = active.values.map { it.value }
    @Synchronized fun runIds(): List<String> = (active.keys + pending.keys).toList()
    @Synchronized fun isEmpty(): Boolean = pending.isEmpty() && active.isEmpty()

    @Synchronized
    fun removePending(runId: String, expected: P? = null): P? {
        val entry = pending[runId] ?: return null
        if (expected != null && entry.value !== expected) return null
        return pending.remove(runId)?.value
    }

    @Synchronized
    fun removeActive(runId: String, expected: A): Boolean {
        if (active[runId]?.value !== expected) return false
        active.remove(runId)
        return true
    }
}

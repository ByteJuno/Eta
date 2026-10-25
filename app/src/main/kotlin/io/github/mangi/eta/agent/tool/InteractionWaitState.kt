package io.github.mangi.eta.agent.tool

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process projection only; never changes resource ownership or triggers a screen overlay. */
internal object InteractionWaitState {
    private val waitingRuns = mutableMapOf<String, String>()
    private val mutable = MutableStateFlow<Set<String>>(emptySet())
    val conversations = mutable.asStateFlow()

    @Synchronized
    fun update(runId: String, conversationId: String, waiting: Boolean) {
        if (waiting) waitingRuns[runId] = conversationId else waitingRuns.remove(runId)
        mutable.value = waitingRuns.values.toSet()
    }
}

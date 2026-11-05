package io.github.bytejuno.theta.agent.runtime

/** Terminal result presentation is separate from foreground execution/entry dismissal. */
internal object AgentTerminalResultPolicy {
    fun showVirtualResult(
        virtualUiRouted: Boolean,
        automation: Boolean,
        stopped: Boolean,
        paused: Boolean,
    ): Boolean = virtualUiRouted && !automation && !stopped && !paused
}

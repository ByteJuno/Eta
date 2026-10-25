package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.display.VirtualScreenRoutingPolicy

/**
 * Screen coordinates and the shared WebView remain valid between a run's tool calls.
 * Hold one lease from its first interaction until its executor closes, including model
 * thinking and pause intervals. Text, HTTP requests and independent shell sessions do
 * not need this resource. This does not isolate files or arbitrary shell commands.
 */
internal class AgentInteractionLeaseCoordinator {
    private val monitor = Object()
    private val waiters = linkedSetOf<Lease>()
    private var owner: Lease? = null

    fun newLease(): Lease = Lease()

    inner class Lease internal constructor() : AutoCloseable {
        private var closed = false
        private var activeCalls = 0

        val isOwner: Boolean get() = synchronized(monitor) { owner === this }

        /** Callbacks must not block; they are evaluated outside the coordinator lock. */
        fun enter(
            isCancelled: () -> Boolean,
            isPaused: () -> Boolean = { false },
            onWaiting: (Boolean) -> Unit = {},
        ): Boolean {
            var announcedWaiting = false
            try {
                while (true) {
                    if (isCancelled() || Thread.currentThread().isInterrupted) return false
                    val paused = isPaused()
                    synchronized(monitor) {
                        if (closed) return false
                        if (!paused && owner === this) {
                            activeCalls += 1
                            return true
                        }
                        if (paused) waiters.remove(this) else waiters.add(this)
                        if (!paused && owner == null && waiters.firstOrNull() === this) {
                            owner = this
                            activeCalls += 1
                            waiters.remove(this)
                            return true
                        }
                    }
                    // Notify outside the monitor: UI observers must never delay lease release.
                    if (!announcedWaiting) {
                        announcedWaiting = true
                        onWaiting(true)
                    }
                    synchronized(monitor) {
                        try {
                            monitor.wait(WAIT_CHECK_MS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return false
                        }
                    }
                }
            } finally {
                if (announcedWaiting) onWaiting(false)
                synchronized(monitor) {
                    if (waiters.remove(this)) monitor.notifyAll()
                }
            }
        }

        fun leave() = synchronized(monitor) {
            check(activeCalls > 0) { "Interaction call was not admitted" }
            activeCalls -= 1
            releaseIfClosed()
        }

        override fun close() = synchronized(monitor) {
            closed = true
            waiters.remove(this)
            releaseIfClosed()
            monitor.notifyAll()
        }

        private fun releaseIfClosed() {
            // Cancellation closes executors synchronously. Do not let another run
            // in while a cancelled gesture, root IPC or WebView call is unwinding.
            if (closed && activeCalls == 0 && owner === this) {
                owner = null
                monitor.notifyAll()
            }
        }
    }

    companion object {
        val shared = AgentInteractionLeaseCoordinator()

        private const val WAIT_CHECK_MS = 100L

        private val additionalSharedTools = setOf(
            "virtual_screen", "get_current_context", "app_state_control",
            "set_device_state", "set_setting", "set_brightness",
            "set_screen_timeout",
        )

        fun requiresLease(toolName: String): Boolean =
            toolName in VirtualScreenRoutingPolicy.uiTools || toolName in additionalSharedTools
    }
}

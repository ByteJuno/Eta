package io.github.mangi.eta.agent.display

import io.github.mangi.eta.agent.runtime.AgentRunController

/** Phone control is acknowledged by the backend before the agent's pause is released. */
internal class VirtualScreenManualControl {
    data class Transition(val generation: Long, val enabled: Boolean)

    private val viewers = mutableSetOf<String>()
    private var generation = 0L
    private var appliedGeneration = 0L
    private var controller: AgentRunController? = null
    private var pause: AgentRunController.ResourceBinding? = null
    @Volatile
    var isBlocking = false
        private set

    @Volatile
    var isReady = false
        private set

    @Synchronized
    fun bind(controller: AgentRunController?) {
        if (this.controller === controller) return
        val previous = pause
        this.controller = controller
        pause = if (isBlocking) controller?.holdPause() else null
        previous?.close()
    }

    @Synchronized
    fun request(viewer: String, enabled: Boolean): Transition? {
        val wasEnabled = viewers.isNotEmpty()
        if (enabled) viewers.add(viewer) else viewers.remove(viewer)
        val nextEnabled = viewers.isNotEmpty()
        if (wasEnabled == nextEnabled) return null
        generation++
        isBlocking = true
        isReady = false
        if (pause == null) pause = controller?.holdPause()
        return Transition(generation, nextEnabled)
    }

    @Synchronized
    fun isCurrent(transition: Transition): Boolean =
        generation == transition.generation && viewers.isNotEmpty() == transition.enabled

    @Synchronized
    fun applied(transition: Transition): Boolean {
        if (!isCurrent(transition)) return false
        appliedGeneration = transition.generation
        isReady = transition.enabled
        isBlocking = transition.enabled
        if (!transition.enabled) {
            pause?.close()
            pause = null
        }
        return true
    }

    @Synchronized
    fun token(viewer: String): Long? =
        generation.takeIf { isReady && appliedGeneration == generation && viewer in viewers }

    @Synchronized fun accepts(viewer: String, value: Long): Boolean = token(viewer) == value

    @Synchronized
    fun close() {
        viewers.clear()
        generation++
        isReady = false
        isBlocking = false
        pause?.close()
        pause = null
        controller = null
    }
}

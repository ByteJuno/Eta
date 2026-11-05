package io.github.bytejuno.theta.agent.runtime

import android.os.Messenger
import io.github.bytejuno.theta.agent.model.AgentModelClient
import io.github.bytejuno.theta.agent.overlay.AgentOverlayState

// fork 的多会话运行记录，从 AgentRuntimeService 拆出，减少与上游同一文件的合并冲突。

internal data class PendingStartRequest(
    val incoming: AgentRuntimeWire.IncomingRunRequest,
    val replyTo: Messenger?,
) {
    val subscribers = mutableListOf<Messenger>().apply { replyTo?.let(::add) }

    fun subscribe(target: Messenger?) {
        if (target != null && subscribers.none { it.binder == target.binder }) subscribers += target
    }
}

internal class RuntimeRun(
    val request: AgentRuntimeWire.RunRequest,
    val session: AgentRuntimeSession,
) {
    var overlayState = AgentOverlayState.Initial
    var hasExecutedForegroundTool = false
    var hasInteractionLease = false
    var revealAfterInteraction = false
    var foregroundToolStarted = false
    @Volatile var terminalResult: AgentRuntimeWire.RunResult? = null
    val supplementsLock = Any()
    val supplements = mutableListOf<AgentUiHandoffPayload.Supplement>()
    var nextSupplementIndex = 1

    init {
        request.handoff?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }?.let {
            val payload = AgentUiHandoffPayload.from(it.payload)
            supplements += payload.supplements
            nextSupplementIndex = payload.lastSupplementIndex + 1
        }
    }
}

internal data class CompletedRunContext(
    val request: AgentRuntimeWire.RunRequest,
    val response: AgentModelClient.ModelResponse.Text,
)


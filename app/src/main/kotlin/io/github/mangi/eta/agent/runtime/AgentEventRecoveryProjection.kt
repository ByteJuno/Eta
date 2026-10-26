package io.github.mangi.eta.agent.runtime

/** 工具参数增量不用于展示；搜索建议只允许活动 run 的内存重连回放。 */
internal fun AgentEvent.replayProjection(): AgentEvent? = when (this) {
    is AgentEvent.AssistantBlockDelta ->
        takeUnless { kind == AgentEvent.AssistantBlockKind.TOOL_CALL }
    else -> this
}

/** checkpoint 与归档均不能存储搜索建议 HTML。 */
internal fun AgentEvent.recoveryProjection(): AgentEvent? = when (this) {
    is AgentEvent.SearchSuggestions -> null
    else -> replayProjection()
}

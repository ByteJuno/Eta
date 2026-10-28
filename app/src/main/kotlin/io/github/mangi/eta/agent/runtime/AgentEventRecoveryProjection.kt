package io.github.mangi.eta.agent.runtime

/** 工具参数增量不用于展示；搜索建议只允许活动 run 的内存重连回放。 */
internal fun AgentEvent.replayProjection(): AgentEvent? = when (this) {
    is AgentEvent.AssistantBlockDelta ->
        takeUnless { kind == AgentEvent.AssistantBlockKind.TOOL_CALL }
    else -> this
}

/** 搜索建议 HTML 和 Subagent 中间进度不写入 checkpoint 或归档。 */
internal fun AgentEvent.recoveryProjection(): AgentEvent? = when (this) {
    is AgentEvent.SearchSuggestions, is AgentEvent.SubagentProgress -> null
    else -> replayProjection()
}

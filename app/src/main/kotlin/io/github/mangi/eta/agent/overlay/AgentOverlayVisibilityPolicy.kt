package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentEvent

/**
 * Decides when the system-level operation overlay should become visible.
 *
 * Chat, reasoning, shell diagnostics, file reads, skill reads and app search
 * all have good homes in the main conversation UI. The global overlay is
 * reserved for tools that actively inspect or drive the foreground Android
 * interface.
 */
internal object AgentOverlayVisibilityPolicy {
    fun shouldRevealFor(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.AssistantBlockStart ->
            event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL &&
                event.name.isForegroundDrivingTool()
        is AgentEvent.AssistantBlockEnd ->
            event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL &&
                event.name.isForegroundDrivingTool()
        is AgentEvent.AssistantReceived -> event.toolNames.any { it.isForegroundDrivingTool() }
        is AgentEvent.ToolStarted -> event.name.isForegroundDrivingTool()
        is AgentEvent.ToolFinished -> event.name.isForegroundOperationTool()
        is AgentEvent.ToolImagesAttached -> event.toolName.isForegroundOperationTool()
        else -> false
    }

    fun shouldDismissEntrySurfaceFor(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.AssistantBlockStart ->
            event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL &&
                event.name.requiresEntrySurfaceDismissal()
        is AgentEvent.AssistantBlockEnd ->
            event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL &&
                event.name.requiresEntrySurfaceDismissal()
        is AgentEvent.AssistantReceived -> event.toolNames.any { it.requiresEntrySurfaceDismissal() }
        is AgentEvent.ToolStarted -> event.name.requiresEntrySurfaceDismissal()
        is AgentEvent.ToolFinished -> event.name.requiresEntrySurfaceDismissal()
        is AgentEvent.ToolImagesAttached -> event.toolName.requiresEntrySurfaceDismissal()
        else -> false
    }

    internal fun isForegroundOperationTool(name: String?): Boolean =
        name.isForegroundOperationTool()

    internal fun requiresEntrySurfaceDismissal(name: String?): Boolean =
        name.requiresEntrySurfaceDismissal()

    internal fun shouldRecordForegroundExecution(
        event: AgentEvent,
        entrySurfaceReady: Boolean,
    ): Boolean =
        entrySurfaceReady &&
            event is AgentEvent.ToolStarted &&
            event.name.isForegroundOperationTool()

    private fun String?.isForegroundOperationTool(): Boolean =
        this?.trim()?.lowercase() in foregroundOperationTools

    private fun String?.isForegroundDrivingTool(): Boolean =
        this?.trim()?.lowercase() in foregroundDrivingTools

    private fun String?.requiresEntrySurfaceDismissal(): Boolean =
        this?.trim()?.lowercase() in entrySurfaceDismissalTools

    private val foregroundDrivingTools = setOf(
        "launch_app",
        "open_uri",
        "tap",
        "tap_area",
        "tap_element",
        "long_press",
        "long_press_element",
        "swipe",
        "scroll",
        "scroll_element",
        "type_text",
        "input_text",
        "replace_text",
        "clear_text",
        "paste_text",
        "press_key",
        "open_system_panel",
    )

    private val foregroundOperationTools = setOf(
        "observe_screen",
        *foregroundDrivingTools.toTypedArray(),
    )

    private val entrySurfaceDismissalTools =
        foregroundOperationTools + setOf("set_alarm", "set_timer")
    /**
     * App 自身聊天页发起的任务，当用户仍停留在 Eta 前台且未启用后台虚拟屏时不再叠加系统浮窗，
     * 避免遮挡已经在展示实时过程的对话流；若后台虚拟屏已激活，则保留顶部微缩镜胶囊供用户随时展开查看或接管。
     */
    internal fun shouldSuppressOverlayInEtaUi(
        handoffSource: String?,
        etaUiInForeground: Boolean,
        virtualDisplayActive: Boolean = false,
    ): Boolean = !virtualDisplayActive &&
        etaUiInForeground &&
        handoffSource == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE

    internal fun shouldShowResultCard(
        hasExecutedForegroundTool: Boolean,
        handoffSource: String?,
        etaUiInForeground: Boolean,
    ): Boolean = hasExecutedForegroundTool && !shouldSuppressOverlayInEtaUi(handoffSource, etaUiInForeground, virtualDisplayActive = false)
}

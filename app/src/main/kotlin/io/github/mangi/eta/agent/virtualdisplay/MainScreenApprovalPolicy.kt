package io.github.mangi.eta.agent.virtualdisplay

/** 画布路由只选择目标；任何会写入物理主屏的 GUI 动作都单独检查本轮用户许可。 */
internal object MainScreenApprovalPolicy {
    val screenMutationTools = setOf(
        "tap", "tap_area", "tap_element", "long_press", "long_press_element", "swipe",
        "scroll", "scroll_element", "type_text", "input_text", "replace_text", "clear_text",
        "paste_text", "press_key", "set_clipboard",
    )
    val launchTools = setOf("launch_app", "open_uri", "open_system_panel", "set_alarm", "set_timer")
    val observationTools = setOf("observe_screen", "wait", "wait_for_text", "wait_for_package", "get_clipboard")
    val sharedInteractionTools = screenMutationTools + launchTools + observationTools +
        setOf("present_to_user", "browser_use")

    val globalKeys = setOf("HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS")

    fun actsOnMainScreen(tool: String, virtualCanvas: Boolean, button: String = ""): Boolean =
        tool in setOf("open_system_panel", "set_clipboard") ||
            (tool == "press_key" && button.uppercase(java.util.Locale.ROOT) in globalKeys) ||
            (!virtualCanvas && tool in screenMutationTools)

    fun requiresApproval(
        virtualDisplayEnabled: Boolean,
        conversationUsesPrimary: Boolean,
        mainScreenFallbackApproved: Boolean,
        tool: String,
        onMainDisplay: Boolean,
    ): Boolean = virtualDisplayEnabled && !conversationUsesPrimary && !mainScreenFallbackApproved &&
        onMainDisplay && tool in (screenMutationTools + launchTools)
}

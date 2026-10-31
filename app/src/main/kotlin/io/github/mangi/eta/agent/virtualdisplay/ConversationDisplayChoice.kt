package io.github.mangi.eta.agent.virtualdisplay

/** 选项顺序与输入栏一致；旧 SharedPreferences 协议保持 primary，其余值默认虚拟屏。 */
internal enum class ConversationDisplayChoice(val usesPrimary: Boolean) {
    VIRTUAL(false),
    PRIMARY(true);

    companion object {
        fun fromStored(value: String?): ConversationDisplayChoice = if (value == "primary") PRIMARY else VIRTUAL
    }
}

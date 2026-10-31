package io.github.mangi.eta.agent.virtualdisplay

/** 已有上游设置优先；旧版关闭也保留，避免升级后意外开启。无旧值时使用上游默认值。 */
internal object VirtualDisplayPreferenceMigration {
    fun valueToMigrate(current: Boolean?, legacy: Boolean?): Boolean? =
        if (current == null) legacy else null
}

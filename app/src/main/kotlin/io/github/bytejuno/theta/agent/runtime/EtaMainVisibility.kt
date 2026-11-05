package io.github.bytejuno.theta.agent.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** MainActivity and RuntimeService share the main process. Track instances, not a single flag,
 * so an old Activity's onStop cannot hide its replacement during recreation.
 */
internal class StartedActivityTracker(private val onChange: (Boolean) -> Unit = {}) {
    private val instances = mutableSetOf<Any>()
    @Synchronized fun started(token: Any) { instances.add(token); onChange(instances.isNotEmpty()) }
    @Synchronized fun stopped(token: Any) { instances.remove(token); onChange(instances.isNotEmpty()) }
    val visible: Boolean @Synchronized get() = instances.isNotEmpty()
}

internal object EtaMainVisibility {
    private val visibleState = MutableStateFlow(false)
    /** 主屏回退弹窗据此在应用内对话框与系统悬浮窗之间切换，避免两个同时出现。 */
    val flow: StateFlow<Boolean> = visibleState.asStateFlow()
    val tracker = StartedActivityTracker { visibleState.value = it }
    val visible: Boolean get() = tracker.visible
}

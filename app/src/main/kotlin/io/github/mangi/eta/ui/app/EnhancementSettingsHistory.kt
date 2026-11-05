package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.SharedPreferences
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs

/**
 * 断连后的界面展示快照；不接收离线配置修改。唯一的回写是 [restoreMissingInto]：
 * 远程配置整键丢失时补回最后一次已连接时的值，不覆盖远程已有的键。
 */
internal class EnhancementSettingsHistory(context: Context) {
    private val snapshot = context.applicationContext.getSharedPreferences("eta_enhancement_ui_history", Context.MODE_PRIVATE)

    val hasConnected: Boolean get() = snapshot.getBoolean("has_connected", false)
    val hasUsedSystemizer: Boolean get() = snapshot.getBoolean("has_used_systemizer", false)

    fun captureConnected(preferences: SharedPreferences) {
        snapshot.edit().apply {
            putBoolean("has_connected", true)
            Prefs.Keys.BOOLEAN_DEFAULTS.filterKeys { it !in Prefs.Keys.LOCAL_AGENT_KEYS }.forEach { (key, default) ->
                putBoolean(key, preferences.getBoolean(key, default))
            }
            putString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, Prefs.powerAssistantTarget(preferences).persistedValue)
        }.apply()
    }

    /**
     * LSPosed 在模块重装后可能丢失远程配置内存副本（上游 #215），Hook 端随之读到默认值。
     * 连接时把远程缺失、但本地快照里有过提交记录的键补写回去；必须先于 [captureConnected]，
     * 否则快照会被远程默认值覆盖。返回补写的键数，commit 失败返回 0。
     */
    fun restoreMissingInto(preferences: SharedPreferences): Int {
        if (!hasConnected) return 0
        val editor = preferences.edit()
        var restored = 0
        Prefs.Keys.BOOLEAN_DEFAULTS.keys
            .filter { it !in Prefs.Keys.LOCAL_AGENT_KEYS && snapshot.contains(it) }
            .filterNot { preferences.safeContains(it) }
            .forEach { key ->
                editor.putBoolean(key, snapshot.getBoolean(key, Prefs.Keys.BOOLEAN_DEFAULTS.getValue(key)))
                restored++
            }
        val target = Prefs.Keys.POWER_KEY_ASSISTANT_TARGET
        if (snapshot.contains(target) && !preferences.safeContains(target)) {
            editor.putString(target, snapshot.getString(target, null))
            restored++
        }
        if (restored == 0) return 0
        return if (runCatching { editor.commit() }.getOrDefault(false)) restored else 0
    }

    private fun SharedPreferences.safeContains(key: String): Boolean =
        runCatching { contains(key) }.getOrDefault(true)

    fun checked(key: String, default: Boolean): Boolean = snapshot.getBoolean(key, default)

    fun powerTarget(): PowerAssistantTarget = Prefs.powerAssistantTarget(snapshot)

    fun recordCommittedBoolean(key: String, value: Boolean) {
        if (key !in Prefs.Keys.LOCAL_AGENT_KEYS) snapshot.edit().putBoolean(key, value).apply()
    }

    fun recordCommittedTarget(target: PowerAssistantTarget) {
        snapshot.edit().putString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, target.persistedValue).apply()
    }

    fun recordSystemizerUse() {
        snapshot.edit().putBoolean("has_used_systemizer", true).apply()
    }
}

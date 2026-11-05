package io.github.bytejuno.theta.ui.app

import android.content.Context
import io.github.bytejuno.theta.config.PowerAssistantTarget
import io.github.bytejuno.theta.config.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EnhancementSettingsHistoryTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun clearHistory() {
        context.getSharedPreferences("eta_enhancement_ui_history", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun historyPreservesDisplayedValuesWithoutWritingBackToTheRemoteSource() {
        val source = context.getSharedPreferences("test_remote_snapshot", Context.MODE_PRIVATE)
        source.edit().clear()
            .putBoolean(Prefs.Keys.HOTWORD_SELF_HEAL, true)
            .putString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, PowerAssistantTarget.GEMINI.persistedValue)
            .commit()
        val history = EnhancementSettingsHistory(context)
        assertFalse(history.hasConnected)
        history.captureConnected(source)
        history.recordCommittedBoolean(Prefs.Keys.HOTWORD_SELF_HEAL, false)
        val restored = EnhancementSettingsHistory(context)
        assertTrue(restored.hasConnected)
        assertFalse(restored.checked(Prefs.Keys.HOTWORD_SELF_HEAL, true))
        assertEquals(PowerAssistantTarget.GEMINI, restored.powerTarget())
        assertTrue(source.getBoolean(Prefs.Keys.HOTWORD_SELF_HEAL, false))
    }

    @Test
    fun restoreMissingIntoRefillsOnlyKeysLostByRemoteStore() {
        val source = context.getSharedPreferences("test_remote_lost", Context.MODE_PRIVATE)
        source.edit().clear()
            .putBoolean(Prefs.Keys.HOTWORD_SELF_HEAL, true)
            .putString(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET, PowerAssistantTarget.ETA.persistedValue)
            .commit()
        val history = EnhancementSettingsHistory(context)
        assertEquals(0, history.restoreMissingInto(source))
        history.captureConnected(source)

        // 模拟 LSPosed 重装后远程存储被清空，另有一个键已被用户在新连接下改过。
        source.edit().clear().putBoolean(Prefs.Keys.HOTWORD_SELF_HEAL, false).commit()
        val restored = history.restoreMissingInto(source)

        assertTrue(restored > 0)
        assertEquals(PowerAssistantTarget.ETA, Prefs.powerAssistantTarget(source))
        assertFalse(source.getBoolean(Prefs.Keys.HOTWORD_SELF_HEAL, true))
        assertEquals(0, history.restoreMissingInto(source))
    }

    @Test
    fun existingSystemizerEntrySurvivesLossOfCurrentRootAccess() {
        val history = EnhancementSettingsHistory(context)
        assertFalse(history.hasUsedSystemizer)
        history.recordSystemizerUse()
        assertTrue(EnhancementSettingsHistory(context).hasUsedSystemizer)
    }
}

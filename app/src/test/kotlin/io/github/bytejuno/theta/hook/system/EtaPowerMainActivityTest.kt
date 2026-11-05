package io.github.bytejuno.theta.hook.system

import android.app.Application
import android.content.Intent
import io.github.bytejuno.theta.config.PowerAssistantTarget
import io.github.bytejuno.theta.config.Prefs
import io.github.bytejuno.theta.core.ModuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class EtaPowerMainActivityTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun registerMainActivity() {
        val component = EtaPowerMainActivity.mainActivityIntent().component!!
        shadowOf(context.packageManager).addActivityIfNotPresent(component)
    }

    @Test
    fun `default preference keeps the existing voice wake path`() {
        assertFalse(Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.POWER_KEY_ETA_OPEN_MAIN))
        assertFalse(
            EtaPowerMainActivity.shouldOpenMain(
                target = PowerAssistantTarget.ETA,
                openMainEnabled = false,
            )
        )
    }

    @Test
    fun `non eta targets never open the main screen`() {
        assertFalse(
            EtaPowerMainActivity.shouldOpenMain(
                target = PowerAssistantTarget.GEMINI,
                openMainEnabled = true,
            )
        )
        assertFalse(
            EtaPowerMainActivity.shouldOpenMain(
                target = PowerAssistantTarget.OEM,
                openMainEnabled = true,
            )
        )
        assertTrue(
            EtaPowerMainActivity.shouldOpenMain(
                target = PowerAssistantTarget.ETA,
                openMainEnabled = true,
            )
        )
    }

    @Test
    fun `intent targets the launcher MainActivity with task reuse flags`() {
        val intent = EtaPowerMainActivity.mainActivityIntent()

        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertEquals(ModuleConfig.ETA_PACKAGE, intent.component?.packageName)
        assertEquals("io.github.bytejuno.theta.ui.MainActivity", intent.component?.className)
        assertEquals(setOf(Intent.CATEGORY_LAUNCHER), intent.categories)
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
            intent.flags,
        )
    }

    @Test
    fun `launch succeeds for the installed component`() {
        var started: Intent? = null

        val launched = EtaPowerMainActivity.launch(
            context = context,
            start = { _, intent -> started = intent },
        )

        assertTrue(launched)
        assertEquals(Intent.ACTION_MAIN, started?.action)
        assertEquals("io.github.bytejuno.theta.ui.MainActivity", started?.component?.className)
    }

    @Test
    fun `missing component fails without starting anything`() {
        var startCalled = false
        val missing = Intent(Intent.ACTION_MAIN).setClassName(
            ModuleConfig.ETA_PACKAGE,
            "${ModuleConfig.ETA_MAIN_ACTIVITY_CLASS}Missing",
        )

        val launched = EtaPowerMainActivity.launch(
            context = context,
            intent = missing,
            start = { _, _ -> startCalled = true },
        )

        assertFalse(launched)
        assertFalse(startCalled)
    }

    @Test
    fun `start failure asks the caller to fall back`() {
        val launched = EtaPowerMainActivity.launch(
            context = context,
            start = { _, _ -> throw IllegalStateException("activity start rejected") },
        )

        assertFalse(launched)
    }
}

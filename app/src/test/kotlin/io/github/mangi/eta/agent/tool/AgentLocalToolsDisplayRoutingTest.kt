package io.github.mangi.eta.agent.tool

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.virtualdisplay.MainScreenFallbackDecision
import io.github.mangi.eta.core.AgentLogger
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentLocalToolsDisplayRoutingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val logger = object : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }

    @Test
    fun unavailableBackendAsksOncePerRunBeforeStartingPhysicalActivity() {
        installUriHandler()
        var approvals = 0
        repeat(2) {
            tools { approvals++; MainScreenFallbackDecision.ALLOWED }.use { executor ->
                repeat(2) {
                    assertTrue(openUri(executor).getBoolean("ok"))
                    assertEquals(Intent.ACTION_VIEW, shadowOf(context).nextStartedActivity.action)
                }
            }
        }
        assertEquals(2, approvals)
    }

    @Test
    fun denialIsStickyAndNeverStartsPhysicalActivity() {
        installUriHandler()
        var approvals = 0
        tools { approvals++; MainScreenFallbackDecision.DENIED }.use { executor ->
            repeat(2) { assertEquals("MAIN_SCREEN_FALLBACK_DENIED", openUri(executor).getString("code")) }
        }
        assertEquals(1, approvals)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun conversationPrimaryBypassesVirtualDisplayAndApproval() {
        installUriHandler()
        tools(primary = true) { error("已明确选择主屏，不应再次审批") }.use { executor ->
            val result = openUri(executor)
            assertTrue(result.getBoolean("ok"))
            assertEquals("main", result.getString("canvas"))
            assertEquals(0, result.getInt("display_id"))
        }
    }

    @Test
    fun cancellationDuringApprovalCannotStartTheActivity() {
        installUriHandler()
        var cancelled = false
        tools(cancelled = { cancelled }) {
            cancelled = true
            MainScreenFallbackDecision.ALLOWED
        }.use { executor -> assertFalse(openUri(executor).getBoolean("ok")) }
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun approvalNeverReplaysThePendingTapAndRequiresANewObservation() {
        var approvals = 0
        tools { approvals++; MainScreenFallbackDecision.ALLOWED }.use { executor ->
            val tap = AgentModelClient.ToolCall("tap", "tap", "{\"x\":0.5,\"y\":0.5,\"coordinate_space\":\"normalized\"}")
            assertEquals("MAIN_SCREEN_OBSERVATION_REQUIRED", JSONObject(executor.execute(tap).content).getString("code"))
            assertEquals("NO_OBSERVATION", JSONObject(executor.execute(tap).content).getString("code"))
        }
        assertEquals(1, approvals)
    }

    private fun tools(
        primary: Boolean = false,
        cancelled: () -> Boolean = { false },
        approval: () -> MainScreenFallbackDecision,
    ) = AgentLocalTools(
        context = context,
        logger = logger,
        browserRunId = UUID.randomUUID().toString(),
        rootAvailable = { false },
        lsposedAvailable = { false },
        virtualDisplayEnabled = { true },
        deviceDirectToolsEnabled = { true },
        primaryDisplay = primary,
        isRunCancelled = cancelled,
        fallbackApproval = { _, _ -> approval() },
    )

    private fun openUri(tools: AgentLocalTools): JSONObject = JSONObject(tools.execute(
        AgentModelClient.ToolCall("uri", "open_uri", JSONObject().put("uri", URI).toString()),
    ).content)

    private fun installUriHandler() {
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_VIEW, Uri.parse(URI)),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "test.uri.handler"
                    name = "Handler"
                    applicationInfo = ApplicationInfo().apply { packageName = "test.uri.handler" }
                }
            },
        )
    }

    companion object {
        private const val URI = "https://example.org/display-routing"
    }
}

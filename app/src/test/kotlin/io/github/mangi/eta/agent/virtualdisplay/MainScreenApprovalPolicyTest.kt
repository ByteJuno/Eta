package io.github.mangi.eta.agent.virtualdisplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenApprovalPolicyTest {
    @Test
    fun globalKeysPanelsAndSystemClipboardAreMainScreenActionsEvenOnVirtualCanvas() {
        MainScreenApprovalPolicy.globalKeys.forEach { key ->
            assertTrue(MainScreenApprovalPolicy.actsOnMainScreen("press_key", true, key))
        }
        assertFalse(MainScreenApprovalPolicy.actsOnMainScreen("press_key", true, "BACK"))
        assertTrue(MainScreenApprovalPolicy.actsOnMainScreen("open_system_panel", true))
        assertTrue(MainScreenApprovalPolicy.actsOnMainScreen("set_clipboard", true))
        assertFalse(MainScreenApprovalPolicy.actsOnMainScreen("observe_screen", true))
    }

    @Test
    fun everyMainScreenMutationRequiresApprovalIncludingExplicitMainAndSystemUi() {
        (MainScreenApprovalPolicy.screenMutationTools + MainScreenApprovalPolicy.launchTools).forEach { tool ->
            assertTrue(tool, MainScreenApprovalPolicy.requiresApproval(true, false, false, tool, true))
            assertFalse(tool, MainScreenApprovalPolicy.requiresApproval(true, false, false, tool, false))
            assertFalse(tool, MainScreenApprovalPolicy.requiresApproval(true, true, false, tool, true))
            assertFalse(tool, MainScreenApprovalPolicy.requiresApproval(true, false, true, tool, true))
            assertFalse(tool, MainScreenApprovalPolicy.requiresApproval(false, false, false, tool, true))
        }
    }

    @Test
    fun routingExceptionsAndUnavailableBackendNeverBypassApproval() {
        listOf(
            CanvasRoutingPolicy.decideLaunchTarget(true, false),
            CanvasRoutingPolicy.decideLaunchTarget(true, true, CanvasRoutingPolicy.RequestedDisplay.MAIN),
            CanvasRoutingPolicy.decideLaunchTarget(true, true, targetPackage = "com.android.systemui"),
            CanvasRoutingPolicy.decideLaunchTarget(true, true, targetPackage = "com.oppo.launcher"),
            CanvasRoutingPolicy.decideLaunchTarget(true, true, targetPackage = "io.github.mangi.eta"),
            CanvasRoutingPolicy.decideLaunchTarget(true, true, targetPackage = "app", foregroundPackageOnMainDisplay = "app"),
        ).forEach { target ->
            assertTrue(MainScreenApprovalPolicy.requiresApproval(true, false, false, "launch_app",
                target == CanvasRoutingPolicy.CanvasTarget.MAIN_DISPLAY))
        }
    }

    @Test
    fun observationsAndHandoffToUserDoNotAuthorizeLaterActions() {
        (MainScreenApprovalPolicy.observationTools + "present_to_user").forEach { tool ->
            assertFalse(tool, MainScreenApprovalPolicy.requiresApproval(true, false, false, tool, true))
        }
        assertTrue(MainScreenApprovalPolicy.requiresApproval(true, false, false, "tap", true))
    }
}

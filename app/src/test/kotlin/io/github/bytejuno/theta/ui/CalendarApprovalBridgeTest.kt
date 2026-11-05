package io.github.bytejuno.theta.ui

import android.os.Looper
import io.github.bytejuno.theta.agent.plugin.calendar.CalendarApprovalBridge
import io.github.bytejuno.theta.agent.plugin.calendar.CalendarApprovalRequest
import io.github.bytejuno.theta.agent.plugin.calendar.CalendarOperation
import io.github.bytejuno.theta.agent.plugin.calendar.CalendarWritePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CalendarApprovalBridgeTest {
    private fun request(id: String, run: String = "run") = CalendarApprovalRequest(
        CalendarOperation(id, "owner", run, null, null, "create_calendar_event", "calendar", 1, "hash", 1000),
        CalendarWritePlan("create_calendar_event", "{\"calendar_id\":1}"),
        now = { 0L },
    )

    @Test fun closeBeforeMainDispatchCannotCreateGhostCard() {
        CalendarApprovalBridge.registerForeground("run").use {
            val request = request("closed")
            CalendarApprovalBridge.show(request).close()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(CalendarApprovalBridge.requests.value.isEmpty())
            assertFalse(CalendarApprovalBridge.approve(request))
        }
    }

    @Test fun changedForegroundLeaseRejectsDelayedCardEvenForSameRun() {
        val old = CalendarApprovalBridge.registerForeground("run")
        val request = request("delayed")
        CalendarApprovalBridge.show(request).use {
            old.close()
            CalendarApprovalBridge.registerForeground("run").use {
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(CalendarApprovalBridge.requests.value.isEmpty())
                assertFalse(CalendarApprovalBridge.approve(request))
            }
        }
    }

    @Test fun closingOneRequestDoesNotRemoveOtherQueuedRequests() {
        CalendarApprovalBridge.registerForeground("run").use {
            val first = request("first")
            val second = request("second")
            val firstHandle = CalendarApprovalBridge.show(first)
            CalendarApprovalBridge.show(second).use {
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(2, CalendarApprovalBridge.requests.value.size)
                firstHandle.close()
                assertEquals(listOf(second), CalendarApprovalBridge.requests.value)
                CalendarApprovalBridge.deny(second)
                assertFalse(CalendarApprovalBridge.approve(second))
            }
        }
    }

    @Test fun backgroundRevokesApprovedButNotYetAdmittedTicket() {
        val lease = CalendarApprovalBridge.registerForeground("run")
        val request = request("approved")
        CalendarApprovalBridge.show(request).use {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(CalendarApprovalBridge.approve(request))
            lease.close()
            assertEquals(CalendarApprovalRequest.Decision.CLOSED, request.current())
            assertFalse(CalendarApprovalBridge.canPresent("run"))
        }
    }

    @Test fun otherRunNeverReceivesApproval() {
        CalendarApprovalBridge.registerForeground("run").use {
            val request = request("other", "background-run")
            CalendarApprovalBridge.show(request).use {
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(CalendarApprovalBridge.canPresent("background-run"))
                assertFalse(CalendarApprovalBridge.approve(request))
                assertTrue(CalendarApprovalBridge.requests.value.isEmpty())
            }
        }
    }
}

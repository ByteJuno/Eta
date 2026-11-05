package io.github.bytejuno.theta.ui

import android.os.Looper
import io.github.bytejuno.theta.agent.plugin.McpTargetSelectionBridge
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
class McpTargetSelectionBridgeTest {
    private val candidates = listOf("personal" to "Notes", "work" to "Notes")

    private fun awaitCard() = run {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            McpTargetSelectionBridge.requests.value.firstOrNull()?.let { return@run it }
            Thread.sleep(10)
        }
        error("No target selection request")
    }

    @Test fun selectionRequiresExplicitKnownIdsAndAllowsMultipleAccounts() {
        McpTargetSelectionBridge.registerForeground("run").use {
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit<Set<String>> { McpTargetSelectionBridge.select("run", candidates) { false } }
                val card = awaitCard()
                assertFalse(card.approve(emptySet()))
                assertFalse(card.approve(setOf("invented")))
                assertTrue(card.approve(setOf("personal", "work")))
                assertEquals(setOf("personal", "work"), result.get(2, TimeUnit.SECONDS))
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun foregroundDepartureCancelsWaitingDecision() {
        val lease = McpTargetSelectionBridge.registerForeground("run")
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Set<String>> { McpTargetSelectionBridge.select("run", candidates) { false } }
            val card = awaitCard()
            lease.close()
            assertEquals(emptySet<String>(), result.get(2, TimeUnit.SECONDS))
            assertFalse(card.approve(setOf("personal")))
        } finally { lease.close(); worker.shutdownNow() }
    }

    @Test fun cancellationRemovesOnlyPendingRequestWithoutDefaultChoice() {
        McpTargetSelectionBridge.registerForeground("run").use {
            val cancelled = AtomicBoolean(false)
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit<Set<String>> {
                    McpTargetSelectionBridge.select("run", candidates) { cancelled.get() }
                }
                awaitCard()
                cancelled.set(true)
                assertEquals(emptySet<String>(), result.get(2, TimeUnit.SECONDS))
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(McpTargetSelectionBridge.requests.value.isEmpty())
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun wrongRunCannotPresentOrSelectAccounts() {
        McpTargetSelectionBridge.registerForeground("run").use {
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit<Set<String>> { McpTargetSelectionBridge.select("other", candidates) { false } }
                assertEquals(emptySet<String>(), result.get(2, TimeUnit.SECONDS))
                assertFalse(McpTargetSelectionBridge.canPresent("other"))
            } finally { worker.shutdownNow() }
        }
    }
}

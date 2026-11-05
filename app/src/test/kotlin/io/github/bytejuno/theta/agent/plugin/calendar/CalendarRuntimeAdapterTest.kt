package io.github.bytejuno.theta.agent.plugin.calendar

import io.github.bytejuno.theta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CalendarRuntimeAdapterTest {
    private val context = CalendarWriteContext("run", "builtin.calendar", 1,
        canRequestApproval = true, isCancelled = { false }, isPaused = { false }, stillAllowed = { true })
    private val ledger = object : CalendarOperationLedger {
        var writes = 0
        override fun insertPrepared(operation: CalendarOperation): Boolean { writes++; return true }
        override fun transition(operation: CalendarOperation, expected: CalendarOperationState,
            next: CalendarOperationState, evidence: CalendarOperationEvidence?) = true
        override fun recoverInterrupted(currentProcessOwner: String) = Unit
    }
    private val coordinator = CalendarWriteCoordinator(ledger, "process", { false },
        CalendarHostApproval { it.approve(); AutoCloseable { } })
    private val call = AgentModelClient.ToolCall("call", "create_calendar_event", "{}")

    @Test fun prepareFailurePreservesOriginalResultAndNeverDelegates() {
        val failure = JSONObject().put("ok", false).put("code", "CALENDAR_SELECTION_REQUIRED").put("extra", "keep")
        val adapter = CalendarRuntimeAdapter { _, _ -> failure }
        val result = adapter.wrap(AgentModelClient.ToolExecutor { error("must not execute") }, { context }, coordinator).execute(call)
        assertEquals(failure.toString(), result.content)
        assertTrue(result.sensitive)
        assertEquals(0, ledger.writes)
    }

    @Test fun invalidPreparationDoesNotThrowOrWriteIntent() {
        val adapter = CalendarRuntimeAdapter { _, _ -> JSONObject().put("ok", true) }
        val result = adapter.wrap(AgentModelClient.ToolExecutor { error("must not execute") }, { context }, coordinator).execute(call)
        assertEquals("CALENDAR_PREPARE_INVALID", JSONObject(result.content).getString("code"))
        assertEquals(0, ledger.writes)
    }

    @Test fun executesHostNormalizedArgumentsAndOverwritesInjectedDigest() {
        val adapter = CalendarRuntimeAdapter { tool, _ -> JSONObject().put("ok", true).put("write_tool", tool)
            .put("target_digest", "b".repeat(64)).put("normalized_arguments",
                JSONObject().put("calendar_id", "3").put("expected_target_digest", "injected")) }
        val result = adapter.wrap(AgentModelClient.ToolExecutor {
            val args = JSONObject(it.argumentsJson)
            assertEquals("3", args.getString("calendar_id"))
            assertEquals("b".repeat(64), args.getString("expected_target_digest"))
            AgentModelClient.ToolResult("{\"ok\":true,\"verified\":true,\"event_id\":\"42\"}")
        }, { context }, coordinator).execute(call)
        assertTrue(JSONObject(result.content).getBoolean("ok"))
        assertTrue(result.sensitive)
    }

    @Test fun targetPreviewReachesApprovalButNotDelegate() {
        val approvalCoordinator = CalendarWriteCoordinator(ledger, "process", { false }, CalendarHostApproval {
            assertEquals("old title", org.json.JSONArray(it.plan.targetPreviewJson).getJSONObject(0).getString("title"))
            it.approve(); AutoCloseable { }
        })
        val adapter = CalendarRuntimeAdapter { tool, _ -> JSONObject().put("ok", true).put("write_tool", tool)
            .put("target_digest", "c".repeat(64)).put("normalized_arguments", JSONObject().put("event_id", "42"))
            .put("targets", org.json.JSONArray().put(JSONObject().put("title", "old title"))) }
        adapter.wrap(AgentModelClient.ToolExecutor {
            assertFalse(JSONObject(it.argumentsJson).has("targets"))
            assertFalse(it.argumentsJson.contains("old title"))
            AgentModelClient.ToolResult("{\"ok\":true,\"verified\":true}")
        }, { context }, approvalCoordinator).execute(call)
    }

    @Test fun partialCreateFailureRetainsReturnedEventIdsAsUnverifiedEvidence() {
        val adapter = CalendarRuntimeAdapter { _, _ -> error("unused") }
        val result = adapter.classify(AgentModelClient.ToolResult(
            """{"ok":false,"status":"unconfirmed","verified":false,"event_ids":["42","43","42","invalid"],"events":[{"event_id":"43"}]}"""))
        assertEquals(CalendarWriteEffect.UNCERTAIN, result.effect)
        assertEquals(listOf("42", "43"), result.evidence?.eventIds)
        assertEquals(false, result.evidence?.verified)
    }

    @Test fun classifiesConservativelyAndCollectsOnlyUniqueIds() {
        val adapter = CalendarRuntimeAdapter { _, _ -> error("unused") }
        val verified = adapter.classify(AgentModelClient.ToolResult(
            """{"ok":true,"verified":true,"event_id":"42","events":[{"event_id":"42"},{"event_id":"43","title":"secret"}]}"""))
        assertEquals(CalendarWriteEffect.VERIFIED, verified.effect)
        assertEquals(listOf("42", "43"), verified.evidence?.eventIds)
        assertEquals(CalendarWriteEffect.UNCERTAIN,
            adapter.classify(AgentModelClient.ToolResult("{\"ok\":false,\"code\":\"PHONE_OPERATION_FAILED\"}")).effect)
        assertEquals(CalendarWriteEffect.UNCERTAIN,
            adapter.classify(AgentModelClient.ToolResult("{\"ok\":true,\"verified\":\"true\"}")).effect)
        assertEquals(CalendarWriteEffect.NO_EFFECT,
            adapter.classify(AgentModelClient.ToolResult("{\"ok\":false,\"status\":\"rejected\"}")).effect)
    }
}

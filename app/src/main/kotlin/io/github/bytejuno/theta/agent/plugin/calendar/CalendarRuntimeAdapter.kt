package io.github.bytejuno.theta.agent.plugin.calendar

import io.github.bytejuno.theta.agent.model.AgentModelClient
import io.github.bytejuno.theta.agent.phone.PhoneAppTools
import io.github.bytejuno.theta.agent.phone.PhoneOperationFailure
import org.json.JSONObject

/** Non-owning bridge: caller owns PhoneAppTools' Root executor and the borrowed ToolExecutor. */
internal class CalendarRuntimeAdapter(
    private val prepareCalendarWrite: (String, JSONObject) -> JSONObject,
) {
    constructor(phoneApps: PhoneAppTools) : this(phoneApps::prepareCalendarWrite)

    fun wrap(
        delegate: AgentModelClient.ToolExecutor,
        context: () -> CalendarWriteContext,
        coordinator: CalendarWriteCoordinator,
    ): AgentModelClient.ToolExecutor = AgentModelClient.ToolExecutor { call ->
        if (call.name !in CalendarWriteCoordinator.WRITE_TOOLS) {
            delegate.execute(call)
        } else {
            val prepared = try {
                prepareCalendarWrite(call.name, JSONObject(call.argumentsJson.ifBlank { "{}" }))
            } catch (cancelled: io.github.bytejuno.theta.agent.runtime.AgentRunCancelledException) {
                throw cancelled
            } catch (failure: Exception) {
                JSONObject().put("ok", false).put("code", when (failure) {
                    is PhoneOperationFailure -> failure.code
                    is org.json.JSONException, is IllegalArgumentException -> "INVALID_ARGUMENT"
                    else -> "CALENDAR_PREPARE_FAILED"
                })
            }
            if (prepared.opt("ok") != true) {
                AgentModelClient.ToolResult(prepared.toString(), sensitive = true)
            } else {
                val plan = plan(call.name, prepared)
                if (plan == null) {
                    error("CALENDAR_PREPARE_INVALID")
                } else {
                    val outcome = coordinator.execute(plan, context()) { approved ->
                        classify(delegate.execute(call.copy(argumentsJson = approved.normalizedArguments)))
                    }
                    if (outcome.code == null && outcome.value != null) outcome.value.copy(sensitive = true)
                    else AgentModelClient.ToolResult(
                        JSONObject().put("ok", false).put("code", outcome.code ?: "OPERATION_UNCONFIRMED")
                            .put("operation_id", outcome.operationId).put("status", outcome.state?.name)
                            .put("message", "日历操作尚未完成确认；请核查操作记录，不要自动重放。")
                            .toString(), sensitive = true,
                    )
                }
            }
        }
    }

    private fun plan(tool: String, prepared: JSONObject): CalendarWritePlan? {
        if (prepared.optString("write_tool") != tool) return null
        val normalized = prepared.optJSONObject("normalized_arguments") ?: return null
        val digest = prepared.opt("target_digest") as? String ?: return null
        if (!digest.matches(Regex("[a-f0-9]{64}"))) return null
        // Never reuse a model-supplied digest. The host's preparation result is authoritative.
        val arguments = JSONObject(normalized.toString()).put("expected_target_digest", digest)
        val preview = prepared.opt("targets")?.takeIf { it is org.json.JSONArray || it is JSONObject }?.toString()
        return CalendarWritePlan(tool, arguments.toString(), targetPreviewJson = preview)
    }

    /** Only feed results from the local Phone/LocalTools adapter, never MCP/model JSON. */
    internal fun classify(result: AgentModelClient.ToolResult): CalendarWriteExecution<AgentModelClient.ToolResult> {
        val payload = runCatching { JSONObject(result.content) }.getOrNull()
        val verified = payload?.opt("ok") == true && payload.opt("verified") == true
        val effect = when {
            verified -> CalendarWriteEffect.VERIFIED
            payload?.optString("status") == "rejected" && payload.opt("ok") == false -> CalendarWriteEffect.NO_EFFECT
            else -> CalendarWriteEffect.UNCERTAIN
        }
        val ids = linkedSetOf<String>()
        fun collect(value: Any?) {
            val id = value as? String ?: return
            if (id.matches(Regex("[0-9]{1,19}")) && id.toLongOrNull()?.let { it > 0 } == true) ids += id
        }
        collect(payload?.opt("event_id"))
        payload?.optJSONArray("event_ids")?.let { eventIds ->
            for (index in 0 until minOf(eventIds.length(), 30)) collect(eventIds.opt(index))
        }
        payload?.optJSONArray("events")?.let { events ->
            for (index in 0 until minOf(events.length(), 30)) collect(events.optJSONObject(index)?.opt("event_id"))
        }
        return CalendarWriteExecution(result.copy(sensitive = true), effect,
            ids.takeIf { it.isNotEmpty() }?.let { CalendarOperationEvidence(it.take(30), verified) })
    }

    private fun error(code: String) = AgentModelClient.ToolResult(
        JSONObject().put("ok", false).put("code", code).toString(), sensitive = true,
    )
}

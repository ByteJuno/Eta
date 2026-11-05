package io.github.bytejuno.theta.agent.plugin.calendar

import io.github.bytejuno.theta.agent.model.AgentModelClient
import org.json.JSONObject

/**
 * Non-owning synchronous bridge. Runtime still owns/ closes the borrowed LocalTools executor.
 * prepare must validate and resolve calendar/timezone defaults without mutation. A new context
 * instance is required for a child executor; do not hide child controllers in ThreadLocal state.
 * The production integration must supply an initialized durable ledger to the coordinator.
 */
internal class CalendarWriteToolExecutor(
    private val coordinator: CalendarWriteCoordinator,
    private val context: () -> CalendarWriteContext,
    private val prepare: (AgentModelClient.ToolCall) -> CalendarWritePlan,
    private val delegate: AgentModelClient.ToolExecutor,
    /** Classify only local trusted adapter evidence, never approval/stop fields in model JSON. */
    private val classify: (AgentModelClient.ToolResult) -> CalendarWriteExecution<AgentModelClient.ToolResult>,
) : AgentModelClient.ToolExecutor {
    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        if (toolCall.name !in CalendarWriteCoordinator.WRITE_TOOLS) return delegate.execute(toolCall)
        val plan = prepare(toolCall)
        require(plan.tool == toolCall.name)
        val outcome = coordinator.execute(plan, context()) { approvedPlan ->
            classify(delegate.execute(toolCall.copy(argumentsJson = approvedPlan.normalizedArguments)))
        }
        // Do not turn an uncertain/failed commit into the delegate's apparent success.
        if (outcome.code == null && outcome.value != null) return outcome.value
        return AgentModelClient.ToolResult(
            content = JSONObject().put("ok", false)
                .put("code", outcome.code ?: "OPERATION_UNCONFIRMED")
                .put("operation_id", outcome.operationId)
                .put("status", outcome.state?.name)
                .put("message", "日历操作未获完整确认；请核查操作记录，不要自动重放。")
                .toString(),
            sensitive = true,
        )
    }
}

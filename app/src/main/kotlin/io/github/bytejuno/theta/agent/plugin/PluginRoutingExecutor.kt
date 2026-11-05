package io.github.bytejuno.theta.agent.plugin

import io.github.bytejuno.theta.agent.model.AgentModelClient
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** 绑定命中后只调用指定执行端；失败绝不回落到本地同名工具。 */
internal class PluginRoutingExecutor(
    private val bindings: RunToolBindings,
    private val local: AgentModelClient.ToolExecutor,
    private val remote: AgentModelClient.ToolExecutor,
    private val closeResources: () -> Unit,
) : AgentModelClient.ToolExecutor, AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        if (closed.get()) return AgentModelClient.ToolResult(
            JSONObject().put("ok", false).put("code", "PLUGIN_SESSION_CLOSED").toString(),
        )
        return when {
            bindings.resolve(toolCall.name)?.let(BuiltinCalendarAdapter::owns) == true -> local.execute(toolCall)
            bindings.resolve(toolCall.name) != null -> remote.execute(toolCall)
            toolCall.name in BuiltinCalendarAdapter.toolNames -> AgentModelClient.ToolResult(
                JSONObject().put("ok", false).put("code", "PLUGIN_BINDING_MISSING").toString(),
            )
            io.github.bytejuno.theta.agent.tool.AgentToolRequirements.find(toolCall.name) != null ->
                local.execute(toolCall)
            else -> AgentModelClient.ToolResult(
                JSONObject().put("ok", false).put("code", "UNKNOWN_PLUGIN_TOOL").toString(),
            )
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) closeResources()
    }
}

package io.github.bytejuno.theta.agent.plugin

import io.github.bytejuno.theta.agent.model.AgentModelClient
import io.github.bytejuno.theta.agent.model.AgentToolCatalog
import io.github.bytejuno.theta.agent.tool.AgentToolCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test

class BuiltinCalendarAdapterTest {
    @Test
    fun sevenExistingSchemasRegisterWithoutPublishingRemoteDuplicates() {
        val catalog = AgentToolCatalog.build(
            terminalTools = false, browserTools = false,
            deviceSensitiveReadTools = true, deviceSensitiveActionTools = true,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
        )
        val descriptors = BuiltinCalendarAdapter.descriptors(catalog)
        assertEquals(BuiltinCalendarAdapter.toolNames, descriptors.map { it.modelName }.toSet())
        val bindings = PluginRegistry.buildRunBindings(descriptors, emptySet())
        assertEquals(7, bindings.size)
        assertEquals(0, bindings.modelTools(McpPluginCatalog.PLUGIN_ID).length())
        var localCalls = 0
        var remoteCalls = 0
        val routing = PluginRoutingExecutor(bindings,
            AgentModelClient.ToolExecutor { localCalls++; AgentModelClient.ToolResult("local") },
            AgentModelClient.ToolExecutor { remoteCalls++; AgentModelClient.ToolResult("remote") }, {})
        descriptors.forEach { routing.execute(AgentModelClient.ToolCall("id", it.modelName, "{}")) }
        assertEquals(7, localCalls)
        assertEquals(0, remoteCalls)
    }

    @Test
    fun calendarWithoutRunBindingDoesNotReachLegacyExecutor() {
        var calls = 0
        val delegate = AgentModelClient.ToolExecutor { calls++; AgentModelClient.ToolResult("unexpected") }
        val routing = PluginRoutingExecutor(PluginRegistry.buildRunBindings(emptyList(), emptySet()),
            delegate, delegate, {})
        routing.execute(AgentModelClient.ToolCall("id", "create_calendar_event", "{}"))
        assertEquals(0, calls)
    }
}

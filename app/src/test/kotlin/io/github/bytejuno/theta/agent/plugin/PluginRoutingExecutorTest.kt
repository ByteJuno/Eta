package io.github.bytejuno.theta.agent.plugin

import io.github.bytejuno.theta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginRoutingExecutorTest {
    @Test
    fun boundRemoteFailureNeverFallsBackToLocal() {
        var localCalls = 0
        val routing = PluginRoutingExecutor(bindings(),
            AgentModelClient.ToolExecutor { localCalls++; AgentModelClient.ToolResult("local") },
            AgentModelClient.ToolExecutor { throw IllegalStateException("remote failed") }, {})
        assertThrows(IllegalStateException::class.java) { routing.execute(call("opaque_name")) }
        assertEquals(0, localCalls)
    }

    @Test
    fun unknownNamesDoNotReachEitherExecutorAndCloseIsIdempotent() {
        var calls = 0
        var closes = 0
        val delegate = AgentModelClient.ToolExecutor { calls++; AgentModelClient.ToolResult("ok") }
        val routing = PluginRoutingExecutor(bindings(), delegate, delegate, { closes++ })
        routing.execute(call("mcp_unbound"))
        assertEquals(0, calls)
        routing.execute(call("opaque_name"))
        routing.execute(call("read_file"))
        assertEquals(2, calls)
        routing.close()
        routing.close()
        routing.execute(call("opaque_name"))
        assertEquals(2, calls)
        assertEquals(1, closes)
    }

    private fun call(name: String) = AgentModelClient.ToolCall("call", name, "{}")

    private fun bindings() = PluginRegistry.buildRunBindings(listOf(
        PluginToolDescriptor(PluginToolIdentity("connector.mcp", "account", "search"), "opaque_name",
            """{"type":"function","function":{"name":"opaque_name","parameters":{"type":"object"}}}"""),
    ), emptySet())
}

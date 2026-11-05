package io.github.bytejuno.theta.agent.plugin

import io.github.bytejuno.theta.agent.mcp.McpRunSnapshot
import io.github.bytejuno.theta.agent.mcp.McpRunTool
import io.github.bytejuno.theta.data.model.McpServerSetting
import io.github.bytejuno.theta.data.model.McpToolDefinition
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class McpPluginCatalogTest {
    @Test
    fun projectionPreservesExistingSchemaAndDoesNotCopyCredentials() {
        val snapshot = McpRunSnapshot(listOf(tool("account-a", "existing_a"), tool("account-b", "existing_b")))
        val legacy = JSONArray().also(snapshot::appendModelTools)
        val bindings = McpPluginCatalog.buildRunBindings(snapshot, setOf("read_file"))
        val projected = bindings.modelTools()

        assertEquals(legacy.toString(), projected.toString())
        assertEquals("account-a", bindings.resolve("existing_a")?.identity?.connectionId)
        assertEquals("account-b", bindings.resolve("existing_b")?.identity?.connectionId)
        assertEquals("search/notes", bindings.resolve("existing_a")?.identity?.toolId)
        assertEquals("connector.mcp", bindings.resolve("existing_a")?.identity?.pluginId)
        assertFalse(projected.toString().contains("test-only-token"))
        assertFalse(bindings.resolve("existing_a").toString().contains("test-only-token"))
    }

    @Test
    fun bindingsDoNotFollowLaterSnapshotListChanges() {
        val tools = mutableListOf(tool("account-a", "existing_a"))
        val bindings = McpPluginCatalog.buildRunBindings(McpRunSnapshot(tools), emptySet())
        tools.clear()

        assertEquals(1, bindings.size)
        assertEquals("existing_a", bindings.modelTools().getJSONObject(0).getJSONObject("function").getString("name"))
    }

    private fun tool(connection: String, modelName: String): McpRunTool = McpRunTool(
        modelName = modelName,
        server = McpServerSetting(
            id = connection,
            name = "Notes",
            url = "https://example.invalid/mcp",
        ),
        definition = McpToolDefinition(
            name = "search/notes",
            description = " Search notes ",
            inputSchemaJson = """{"type":"object","properties":{"query":{"type":"string"}}}""",
        ),
        bearerToken = "test-only-token",
    )
}

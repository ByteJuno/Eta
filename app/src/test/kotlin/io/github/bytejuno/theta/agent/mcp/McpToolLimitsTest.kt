package io.github.bytejuno.theta.agent.mcp

import io.github.bytejuno.theta.data.model.McpServerSetting
import io.github.bytejuno.theta.data.model.McpToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolLimitsTest {
    @Test
    fun countLimitStopsAtMaxAndIsReportedAsTruncated() {
        val tools = (0 until McpToolLimits.MAX_DISCOVERED_TOOLS).map { tool("t$it") }
        assertFalse(McpToolLimits.fits(tools, tool("extra")))
        assertTrue(McpToolLimits.isLikelyTruncated(tools))
        assertFalse(McpToolLimits.isLikelyTruncated(tools.take(229)))
    }

    @Test
    fun sizeBudgetRejectsOversizedCatalog() {
        val big = tool("big", schemaChars = McpToolLimits.MAX_CATALOG_CHARS)
        assertFalse(McpToolLimits.fits(emptyList(), big))
        val half = tool("half", schemaChars = McpToolLimits.MAX_CATALOG_CHARS / 2)
        assertTrue(McpToolLimits.fits(emptyList(), half))
        assertFalse(McpToolLimits.fits(listOf(half, half), half))
    }

    @Test
    fun enabledAcrossCountsOnlyEnabledServersAndTools() {
        val tools = listOf(tool("a"), tool("b"), tool("c"))
        val servers = listOf(
            McpServerSetting(id = "1", name = "one", url = "https://a.test", tools = tools, enabledToolNames = setOf("a", "b")),
            McpServerSetting(id = "2", name = "two", url = "https://b.test", enabled = false, tools = tools, enabledToolNames = setOf("a")),
        )
        assertEquals(2, McpToolLimits.enabledAcross(servers))
    }

    private fun tool(name: String, schemaChars: Int = 40) = McpToolDefinition(
        name = name,
        inputSchemaJson = "{\"type\":\"object\"}".padEnd(schemaChars, ' '),
    )
}

package io.github.bytejuno.theta.data.model

import org.junit.Assert.*
import org.junit.Test

class McpToolAuthorizationTest {
    private val tool = McpToolDefinition("write", description = "Write", inputSchemaJson = """{"type":"object","properties":{}}""")
    private val server = McpServerSetting("a", "A", "https://example.com", tools = listOf(tool), enabledToolNames = setOf("write"))

    @Test fun schemaDescriptionAndAnnotationsEachInvalidateGrant() {
        listOf(
            tool.copy(inputSchemaJson = """{"type":"object","required":["all"]}"""),
            tool.copy(description = "Delete"),
            tool.copy(readOnlyHint = true),
            tool.copy(destructiveHint = true),
            tool.copy(idempotentHint = true),
            tool.copy(openWorldHint = true),
        ).forEach { changed -> assertTrue(retainedMcpToolGrants(server, listOf(changed)).isEmpty()) }
    }

    @Test fun newAndDuplicateNamesAndMalformedSchemasCannotInheritGrant() {
        assertTrue(retainedMcpToolGrants(server, listOf(tool.copy(name = "new"))).isEmpty())
        assertTrue(retainedMcpToolGrants(server, listOf(tool, tool)).isEmpty())
        assertTrue(retainedMcpToolGrants(server, listOf(tool.copy(inputSchemaJson = "invalid"))).isEmpty())
    }

    @Test fun schemaObjectOrderingIsCanonicalButArrayOrderingIsNot() {
        fun digest(schema: String) = mcpToolDefinitionDigest(schema, "Write", "{}")
        assertEquals(digest("""{"a":1,"b":{"x":true,"y":null}}"""), digest("""{ "b": {"y":null,"x":true}, "a":1 }"""))
        assertNotEquals(digest("""{"enum":["a","b"]}"""), digest("""{"enum":["b","a"]}"""))
    }

    @Test fun fullWireDigestCoversUnknownAnnotationsAndUntruncatedDescription() {
        val first = mcpToolDefinitionDigest("{}", "x".repeat(5000) + "one", """{"custom":{"scope":"one"}}""")
        val descriptionChanged = mcpToolDefinitionDigest("{}", "x".repeat(5000) + "two", """{"custom":{"scope":"one"}}""")
        val annotationChanged = mcpToolDefinitionDigest("{}", "x".repeat(5000) + "one", """{"custom":{"scope":"two"}}""")
        assertNotEquals(first, descriptionChanged)
        assertNotEquals(first, annotationChanged)
        val old = server.copy(tools = listOf(tool.copy(authorizationDefinitionDigest = first)))
        assertTrue(retainedMcpToolGrants(old, listOf(tool.copy(authorizationDefinitionDigest = annotationChanged))).isEmpty())
    }

    @Test fun pendingCredentialUpdateHasNoActiveTools() {
        assertTrue(server.copy(credentialUpdatePending = true).activeTools.isEmpty())
    }
}

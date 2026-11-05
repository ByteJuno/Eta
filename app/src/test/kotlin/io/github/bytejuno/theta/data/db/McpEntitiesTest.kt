package io.github.bytejuno.theta.data.db

import io.github.bytejuno.theta.data.model.McpAuthorizationType
import io.github.bytejuno.theta.data.model.McpProtocolMode
import io.github.bytejuno.theta.data.model.McpServerSetting
import io.github.bytejuno.theta.data.model.McpToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Test

class McpEntitiesTest {
    @Test
    fun authorityVersionsBarrierAndWireDigestRoundTrip() {
        val server = McpServerSetting(
            "server", "Server", "https://example.com/mcp",
            tools = listOf(McpToolDefinition("write", inputSchemaJson = "{}", authorizationDefinitionDigest = "digest")),
            enabledToolNames = setOf("write"), revision = 7, stateVersion = 11, credentialUpdatePending = true,
        )
        assertEquals(server, server.toEntity().toDomain())
        assertEquals(emptyList<McpToolDefinition>(), server.toEntity().toDomain().activeTools)
    }

    @Test
    fun legacyUnavailableReasonIsIgnoredWhenLoadingCachedTools() {
        val entity = McpServerEntity(
            id = "server",
            name = "Server",
            url = "https://example.com/mcp",
            enabled = true,
            protocolMode = McpProtocolMode.AUTO,
            authorizationType = McpAuthorizationType.NONE,
            toolsJson = """[{"name":"create_task","inputSchemaJson":"{\"type\":\"object\",\"anyOf\":[]}","unavailableReason":"暂不支持 schema 关键字 anyOf"}]""",
            enabledToolNamesJson = """["create_task"]""",
            createdAt = 1L,
            sortOrder = 0,
            lastRefreshedAt = null,
            lastProtocolVersion = null,
            toolsExpireAt = null,
        )

        val server = entity.toDomain()

        assertEquals(listOf("create_task"), server.tools.map { it.name })
        assertEquals(listOf("create_task"), server.activeTools.map { it.name })
    }
}

package io.github.bytejuno.theta.ui.model

import io.github.bytejuno.theta.data.model.McpServerSetting
import io.github.bytejuno.theta.data.model.McpToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpConnectionUiStateTest {
    private val server = McpServerSetting(
        id = "personal",
        name = "Personal",
        url = "https://example.invalid/mcp",
        tools = listOf(McpToolDefinition(name = "notes", inputSchemaJson = "{}")),
        enabledToolNames = setOf("notes", "removed"),
        lastRefreshedAt = 123L,
    )

    @Test fun disabledConnectionRetainsGrantCountWithoutClaimingAvailability() {
        val state = server.copy(enabled = false).toConnectionUiState()
        assertEquals(McpConnectionStatus.DISABLED, state.status)
        assertEquals(1, state.grantedToolCount)
        assertEquals(1, state.cachedToolCount)
        assertEquals(123L, state.lastCheckedAt)
    }

    @Test fun cachedToolsDoNotImplyAuthorization() {
        val state = server.copy(enabledToolNames = emptySet()).toConnectionUiState()
        assertEquals(McpConnectionStatus.NEEDS_GRANT, state.status)
        assertEquals(0, state.grantedToolCount)
    }

    @Test fun pendingCredentialUpdateTakesPrecedenceOverEnabledAndGrants() {
        val state = server.copy(credentialUpdatePending = true).toConnectionUiState()
        assertEquals(McpConnectionStatus.CREDENTIAL_UPDATE_PENDING, state.status)
    }

    @Test fun missingRefreshTimeStaysUnknown() {
        assertNull(server.copy(lastRefreshedAt = null).toConnectionUiState().lastCheckedAt)
    }

    @Test fun connectionsDoNotShareGrantCounts() {
        val work = server.copy(id = "work", enabledToolNames = emptySet())
        assertEquals(1, server.toConnectionUiState().grantedToolCount)
        assertEquals(0, work.toConnectionUiState().grantedToolCount)
    }
}

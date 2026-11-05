package io.github.bytejuno.theta.agent.mcp

import io.github.bytejuno.theta.data.model.McpServerSetting
import io.github.bytejuno.theta.data.model.retainedMcpToolGrants
import io.github.bytejuno.theta.data.repository.McpServerRepository

internal object McpServerManager {
    fun discover(
        server: McpServerSetting,
        bearerToken: String?,
        registerCancellation: ((() -> Unit) -> AutoCloseable)? = null,
    ): McpServerSetting = McpHttpClient(server, bearerToken).use { client ->
        val cancellation = registerCancellation?.invoke(client::close)
        val discovery = try {
            client.discoverTools()
        } finally {
            cancellation?.close()
        }
        val refreshedAt = System.currentTimeMillis()
        server.copy(
            tools = discovery.tools,
            enabledToolNames = retainedMcpToolGrants(server, discovery.tools),
            lastRefreshedAt = refreshedAt,
            lastProtocolVersion = discovery.protocolVersion,
            toolsExpireAt = discovery.cacheTtlMs?.let { ttl ->
                if (ttl > Long.MAX_VALUE - refreshedAt) Long.MAX_VALUE else refreshedAt + ttl
            },
        )
    }

    suspend fun refresh(serverId: String): McpServerSetting {
        val snapshot = requireNotNull(McpServerRepository.captureSnapshot(serverId)) {
            "MCP 连接不存在或凭据更新未完成"
        }
        // Network happens outside the repository lock; the write rejects stale stateVersion/revision.
        val refreshed = discover(snapshot.server, snapshot.bearerToken)
        return McpServerRepository.update(refreshed)
    }
}

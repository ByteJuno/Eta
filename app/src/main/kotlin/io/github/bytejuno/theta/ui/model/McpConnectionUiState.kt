package io.github.bytejuno.theta.ui.model

import io.github.bytejuno.theta.data.model.McpServerSetting

internal enum class McpConnectionStatus { CREDENTIAL_UPDATE_PENDING, DISABLED, NEEDS_GRANT, ENABLED }

internal data class McpConnectionUiState(
    val status: McpConnectionStatus,
    val grantedToolCount: Int,
    val cachedToolCount: Int,
    val lastCheckedAt: Long?,
)

/** A cached discovery result is not evidence of a currently live connection. */
internal fun McpServerSetting.toConnectionUiState(): McpConnectionUiState {
    val granted = tools.count { it.name in enabledToolNames }
    return McpConnectionUiState(
        status = when {
            credentialUpdatePending -> McpConnectionStatus.CREDENTIAL_UPDATE_PENDING
            !enabled -> McpConnectionStatus.DISABLED
            granted == 0 -> McpConnectionStatus.NEEDS_GRANT
            else -> McpConnectionStatus.ENABLED
        },
        grantedToolCount = granted,
        cachedToolCount = tools.size,
        lastCheckedAt = lastRefreshedAt,
    )
}

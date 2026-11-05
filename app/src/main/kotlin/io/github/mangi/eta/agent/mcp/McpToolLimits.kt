package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition

/**
 * MCP 工具目录与单次运行的上限（上游 #216）。
 *
 * 发现阶段同时限制数量与体积：目录以 JSON 存入 Room 单行，CJK 文本按 UTF-8 约 3 字节/字，
 * 预算需远低于 CursorWindow 的 2 MB。超限不再静默：界面通过 [isLikelyTruncated] 提示。
 */
internal object McpToolLimits {
    const val MAX_DISCOVERED_TOOLS = 512
    const val MAX_CATALOG_CHARS = 500_000

    /** 每次运行最多向模型暴露的 MCP 工具总数（跨服务器）。 */
    const val MAX_RUN_TOOLS = 64

    fun fits(existing: List<McpToolDefinition>, next: McpToolDefinition): Boolean =
        existing.size < MAX_DISCOVERED_TOOLS &&
            catalogChars(existing) + next.storedChars() <= MAX_CATALOG_CHARS

    fun isLikelyTruncated(tools: List<McpToolDefinition>): Boolean =
        tools.size >= MAX_DISCOVERED_TOOLS || catalogChars(tools) >= MAX_CATALOG_CHARS * 9 / 10

    /** 已启用服务器中被用户勾选的工具总数；超过 [MAX_RUN_TOOLS] 时排在后面的不会进入本轮。 */
    fun enabledAcross(servers: List<McpServerSetting>): Int = servers.sumOf { it.activeTools.size }

    private fun catalogChars(tools: List<McpToolDefinition>): Int = tools.sumOf { it.storedChars() }

    private fun McpToolDefinition.storedChars(): Int =
        name.length + title.length + description.length + inputSchemaJson.length + STORED_OVERHEAD_CHARS

    private const val STORED_OVERHEAD_CHARS = 128
}

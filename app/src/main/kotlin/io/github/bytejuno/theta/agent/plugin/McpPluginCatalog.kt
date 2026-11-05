package io.github.bytejuno.theta.agent.plugin

import io.github.bytejuno.theta.agent.mcp.McpRunSnapshot

/** 只投影已有 run 快照，不重新发现服务、不读取凭据，也不改变现有执行路由。 */
internal object McpPluginCatalog {
    const val PLUGIN_ID = "connector.mcp"

    fun buildRunBindings(
        snapshot: McpRunSnapshot,
        occupiedModelNames: Set<String>,
        builtinDescriptors: List<PluginToolDescriptor> = emptyList(),
    ): RunToolBindings = PluginRegistry.buildRunBindings(
        descriptors = builtinDescriptors + snapshot.tools.map { tool ->
            PluginToolDescriptor(
                identity = PluginToolIdentity(
                    pluginId = PLUGIN_ID,
                    connectionId = tool.server.id,
                    toolId = tool.definition.name,
                ),
                modelName = tool.modelName,
                modelSchemaJson = tool.modelSchema().toString(),
            )
        },
        occupiedModelNames = occupiedModelNames,
    )
}

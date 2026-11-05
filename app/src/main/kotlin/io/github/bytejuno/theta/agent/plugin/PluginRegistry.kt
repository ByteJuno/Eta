package io.github.bytejuno.theta.agent.plugin

import io.github.bytejuno.theta.agent.model.AgentBatchToolCatalog
import io.github.bytejuno.theta.agent.model.AgentConversationToolCatalog
import io.github.bytejuno.theta.agent.model.AgentLazyToolGroups
import io.github.bytejuno.theta.agent.subagent.AgentSubagentToolCatalog
import org.json.JSONArray
import org.json.JSONObject

/** 每轮一次性注册。任一冲突使整次构建失败，不发布部分目录。 */
internal object PluginRegistry {
    fun buildRunBindings(
        descriptors: List<PluginToolDescriptor>,
        occupiedModelNames: Set<String>,
    ): RunToolBindings {
        val names = occupiedModelNames.toMutableSet().apply {
            add(AgentBatchToolCatalog.NAME)
            add(AgentLazyToolGroups.LOADER_NAME)
            add(AgentSubagentToolCatalog.TOOL_NAME)
            add(AgentSubagentToolCatalog.PROFILE_TOOL_NAME)
            add(AgentConversationToolCatalog.READ_HISTORY)
        }
        val identities = mutableSetOf<PluginToolIdentity>()
        val frozen = descriptors.toList()
        frozen.forEach { descriptor ->
            require(BuiltinCalendarAdapter.owns(descriptor) ||
                io.github.bytejuno.theta.agent.tool.AgentToolRequirements.find(descriptor.modelName) == null) {
                "PLUGIN_TOOL_CONFLICT: local tool name"
            }
            require(names.add(descriptor.modelName)) { "PLUGIN_TOOL_CONFLICT: model name" }
            require(identities.add(descriptor.identity)) { "PLUGIN_TOOL_CONFLICT: tool identity" }
        }
        return RunToolBindings(frozen)
    }
}

/** 目录与路由身份来自同一组冻结值；每次投影返回独立 JSON，调用者不能改写绑定。 */
internal class RunToolBindings internal constructor(descriptors: List<PluginToolDescriptor>) {
    private val tools = descriptors.toList()
    private val byModelName = tools.associateBy(PluginToolDescriptor::modelName)

    val size: Int get() = tools.size

    fun resolve(modelName: String): PluginToolDescriptor? = byModelName[modelName]

    fun modelTools(pluginId: String? = null): JSONArray = JSONArray().apply {
        tools.filter { pluginId == null || it.identity.pluginId == pluginId }
            .forEach { put(JSONObject(it.modelSchemaJson)) }
    }
}

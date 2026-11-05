package io.github.bytejuno.theta.agent.plugin

import org.json.JSONObject

internal data class PluginToolIdentity(
    val pluginId: String,
    val connectionId: String,
    val toolId: String,
) {
    init {
        require(pluginId.isNotBlank()) { "pluginId must not be blank" }
        require(connectionId.isNotBlank()) { "connectionId must not be blank" }
        require(toolId.isNotBlank()) { "toolId must not be blank" }
    }
}

/** 仅保存目录值；不持有 token、客户端或可变 JSON 对象，也不表示已获执行授权。 */
internal data class PluginToolDescriptor(
    val identity: PluginToolIdentity,
    val modelName: String,
    val modelSchemaJson: String,
) {
    init {
        require(modelName.isNotBlank()) { "modelName must not be blank" }
        val schema = JSONObject(modelSchemaJson)
        require(schema.getString("type") == "function") { "Expected function schema" }
        val function = schema.getJSONObject("function")
        require(function.getString("name") == modelName) { "Schema name must match modelName" }
        function.getJSONObject("parameters")
    }
}

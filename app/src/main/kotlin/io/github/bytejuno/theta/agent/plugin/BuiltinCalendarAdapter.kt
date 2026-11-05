package io.github.bytejuno.theta.agent.plugin

import org.json.JSONArray

/** Schema stays owned by the existing catalogs; execution still passes through LocalTools. */
internal object BuiltinCalendarAdapter {
    const val PLUGIN_ID = "builtin.calendar"
    const val CONNECTION_ID = "device.calendar"
    val toolNames = setOf(
        "list_calendars", "read_calendar_event", "search_calendar_events",
        "create_calendar_event", "create_calendar_events", "update_calendar_event", "delete_calendar_event",
    )

    fun descriptors(localCatalog: JSONArray): List<PluginToolDescriptor> = buildList {
        for (index in 0 until localCatalog.length()) {
            val schema = localCatalog.getJSONObject(index)
            val name = schema.getJSONObject("function").getString("name")
            if (name in toolNames) add(PluginToolDescriptor(
                PluginToolIdentity(PLUGIN_ID, CONNECTION_ID, name), name, schema.toString(),
            ))
        }
    }

    fun owns(descriptor: PluginToolDescriptor): Boolean =
        descriptor.identity.pluginId == PLUGIN_ID &&
            descriptor.identity.connectionId == CONNECTION_ID &&
            descriptor.identity.toolId == descriptor.modelName && descriptor.modelName in toolNames
}

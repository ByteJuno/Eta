package io.github.bytejuno.theta.data.model

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Stable JSON key ordering; array order remains significant. Invalid JSON fails closed. */
internal fun mcpToolDefinitionDigest(
    schemaJson: String,
    description: String,
    annotationsJson: String,
    title: String = "",
): String {
    fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map { canonical(it) })
        else -> value
    }
    val definition = JsonArray(listOf(
        canonical(Json.parseToJsonElement(schemaJson)),
        JsonPrimitive(description),
        canonical(Json.parseToJsonElement(annotationsJson)),
        JsonPrimitive(title),
    ))
    return MessageDigest.getInstance("SHA-256").digest(definition.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal object McpProtocolMode {
    const val AUTO = "auto"
    const val LATEST = "2026-07-28"
    const val LEGACY = "2025-11-25"
}

internal object McpAuthorizationType {
    const val NONE = "none"
    const val BEARER = "bearer"
}

@Serializable
internal data class McpToolDefinition(
    val name: String,
    val title: String = "",
    val description: String = "",
    val inputSchemaJson: String,
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
    // Covers full wire description/annotations, including metadata omitted from display fields.
    val authorizationDefinitionDigest: String? = null,
) {
    fun definitionDigest(): String = mcpToolDefinitionDigest(
        schemaJson = inputSchemaJson,
        description = description,
        annotationsJson = JsonObject(mapOf(
            "readOnlyHint" to JsonPrimitive(readOnlyHint),
            "destructiveHint" to JsonPrimitive(destructiveHint),
            "idempotentHint" to JsonPrimitive(idempotentHint),
            "openWorldHint" to JsonPrimitive(openWorldHint),
        )).toString(),
        title = title,
    ) + ":" + authorizationDefinitionDigest.orEmpty()
}

/** Only previously approved, unchanged definitions retain authority. Duplicate names fail closed. */
internal fun retainedMcpToolGrants(
    previous: McpServerSetting,
    tools: List<McpToolDefinition>,
): Set<String> {
    val old = previous.tools.groupBy { it.name }
    val fresh = tools.groupBy { it.name }
    return previous.enabledToolNames.filterTo(linkedSetOf()) { name ->
        val before = old[name]?.singleOrNull()
        val after = fresh[name]?.singleOrNull()
        before != null && after != null && runCatching {
            before.definitionDigest() == after.definitionDigest()
        }.getOrDefault(false)
    }
}

@Serializable
internal data class McpServerSetting(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val protocolMode: String = McpProtocolMode.AUTO,
    val authorizationType: String = McpAuthorizationType.NONE,
    val tools: List<McpToolDefinition> = emptyList(),
    val enabledToolNames: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0,
    val lastRefreshedAt: Long? = null,
    val lastProtocolVersion: String? = null,
    val toolsExpireAt: Long? = null,
    val revision: Long = 0,
    /** Optimistic write version; cache-only writes do not invalidate run revisions. */
    val stateVersion: Long = 0,
    /** Durable fail-closed barrier across Room and the independent credential store. */
    val credentialUpdatePending: Boolean = false,
) {
    val activeTools: List<McpToolDefinition>
        get() = if (!enabled || credentialUpdatePending) {
            emptyList()
        } else {
            tools.filter { it.name in enabledToolNames }
        }
}

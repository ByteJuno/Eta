package io.github.bytejuno.theta.agent.mcp

import android.util.Base64
import io.github.bytejuno.theta.agent.model.AgentModelClient
import io.github.bytejuno.theta.agent.plugin.McpPluginCatalog
import io.github.bytejuno.theta.agent.plugin.PluginCallJournal
import io.github.bytejuno.theta.agent.plugin.PluginCallStatus
import io.github.bytejuno.theta.data.repository.McpAdmissionException
import io.github.bytejuno.theta.data.model.McpProtocolMode
import io.github.bytejuno.theta.data.model.McpServerSetting
import io.github.bytejuno.theta.data.model.McpToolDefinition
import io.github.bytejuno.theta.data.repository.McpServerRepository
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject

internal class McpRunTool(
    val modelName: String,
    val server: McpServerSetting,
    val definition: McpToolDefinition,
    val bearerToken: String?,
) {
    fun modelSchema(): JSONObject {
        val description = buildString {
            append("MCP 服务器「").append(server.name).append("」提供的工具")
            definition.description.trim().takeIf { it.isNotBlank() }?.let {
                append("。 ").append(it)
            }
        }
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", modelName)
                    .put("description", description)
                    .put("parameters", JSONObject(definition.inputSchemaJson)),
            )
    }
}

/** 一次 run 使用冻结的 MCP 工具目录，设置变更从下一次 run 生效。 */
internal class McpRunSnapshot(
    val tools: List<McpRunTool>,
) {
    private val byModelName = tools.associateBy(McpRunTool::modelName)

    fun resolve(modelName: String): McpRunTool? = byModelName[modelName]

    fun appendModelTools(destination: JSONArray) {
        tools.forEach { tool -> destination.put(tool.modelSchema()) }
    }

    companion object {
        val EMPTY = McpRunSnapshot(emptyList())

        suspend fun load(
            registerCancellation: ((() -> Unit) -> AutoCloseable)? = null,
            checkCancelled: () -> Unit = {},
        ): McpRunSnapshot {
            val projected = mutableListOf<McpRunTool>()
            val now = System.currentTimeMillis()
            McpServerRepository.captureEnabledSnapshots().forEach { captured ->
                checkCancelled()
                val configured = captured.server
                val bearerToken = captured.bearerToken
                val needsRefresh = configured.lastProtocolVersion == null ||
                    configured.lastProtocolVersion == McpProtocolMode.LATEST &&
                    (configured.toolsExpireAt == null || configured.toolsExpireAt <= now)
                val server = if (needsRefresh) {
                    runCatching {
                        val discovered = McpServerManager.discover(configured, bearerToken, registerCancellation)
                        checkCancelled()
                        McpServerRepository.update(discovered)
                    }.getOrNull()
                        ?: return@forEach
                } else {
                    configured
                }
                server.activeTools.forEach { tool ->
                    if (projected.size >= McpToolLimits.MAX_RUN_TOOLS) return@forEach
                    projected += McpRunTool(
                        modelName = modelToolName(server.id, tool.name),
                        server = server,
                        definition = tool,
                        bearerToken = bearerToken,
                    )
                }
            }
            return McpRunSnapshot(projected)
        }

        private fun modelToolName(serverId: String, toolName: String): String {
            val serverPart = serverId.filter(Char::isLetterOrDigit).take(8).ifBlank { "server" }
            val normalized = toolName
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .trim('_')
                .take(30)
                .ifBlank { "tool" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$serverId\u0000$toolName".toByteArray())
                .take(4)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return "mcp_${serverPart}_${normalized}_$digest"
        }
    }
}

internal class McpToolExecutor(
    private val snapshot: McpRunSnapshot,
    private val isCancelled: () -> Boolean = { false },
    private val isExecutionAllowed: () -> Boolean = { true },
    private val runId: String = "",
    private val journal: PluginCallJournal? = null,
    private val connectionLocks: Map<String, java.util.concurrent.locks.ReentrantLock> =
        snapshot.tools.associate { it.server.id to java.util.concurrent.locks.ReentrantLock() },
    private val journalProvider: (() -> PluginCallJournal)? = null,
) : AgentModelClient.ToolExecutor, AutoCloseable {
    private val lifecycleLock = Any()
    private val clients = mutableMapOf<String, McpHttpClient>()
    private var closed = false
    private val monitor = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        monitor.launch {
            McpServerRepository.serversFlow().collect { servers ->
                val current = servers.associateBy { it.id }
                val staleIds = snapshot.tools.filter { tool ->
                    val server = current[tool.server.id]
                    server == null || !server.enabled || server.credentialUpdatePending ||
                        server.revision != tool.server.revision
                }.mapTo(mutableSetOf()) { it.server.id }
                val closing = synchronized(lifecycleLock) {
                    staleIds.mapNotNull { clients.remove(it) }
                }
                closing.forEach { runCatching { it.close() } }
            }
        }
    }

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val tool = snapshot.resolve(toolCall.name)
        synchronized(lifecycleLock) {
            if (closed) return attributed(failure("MCP_EXECUTOR_CLOSED", "MCP 工具执行器已关闭"), tool)
        }
        if (isCancelled()) return attributed(failure("MCP_CALL_CANCELLED", "任务已取消，本次调用未执行"), tool)
        if (tool == null) return failure("UNKNOWN_MCP_TOOL", "MCP 工具未在本次运行中启用")
        val arguments = runCatching { JSONObject(toolCall.argumentsJson.ifBlank { "{}" }) }
            .getOrElse { return attributed(failure("INVALID_ARGUMENT", "MCP 工具参数不是 JSON object"), tool) }
        val connectionLock = connectionLocks[tool.server.id]
            ?: return attributed(failure("PLUGIN_CONNECTION_LOCK_MISSING", "连接执行锁不可用，本次调用未执行"), tool)
        try {
            while (true) {
                if (isCancelled()) return attributed(failure("MCP_CALL_CANCELLED", "任务已取消，本次调用未执行"), tool)
                synchronized(lifecycleLock) {
                    if (closed) return attributed(failure("MCP_EXECUTOR_CLOSED", "MCP 工具执行器已关闭"), tool)
                }
                if (connectionLock.tryLock(100, java.util.concurrent.TimeUnit.MILLISECONDS)) break
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return attributed(failure("MCP_CALL_CANCELLED", "等待连接时已中断，本次调用未执行"), tool)
        }
        return try {
            executeAdmitted(toolCall, tool, arguments)
        } finally {
            connectionLock.unlock()
        }
    }

    private fun executeAdmitted(
        toolCall: AgentModelClient.ToolCall,
        tool: McpRunTool,
        arguments: JSONObject,
    ): AgentModelClient.ToolResult {
        var operationId: String? = null
        var callJournal: PluginCallJournal? = null
        var status = PluginCallStatus.UNKNOWN
        val result = try {
            // Validate first, but never perform journal I/O while holding the repository mutex.
            admit(tool) { Unit }
            callJournal = try {
                journalProvider?.invoke() ?: journal
            } catch (_: Exception) {
                return attributed(failure("PLUGIN_JOURNAL_UNAVAILABLE", "调用历史不可用，本次调用未执行"), tool)
            }
            if (callJournal != null) {
                operationId = try {
                    callJournal.recordStart(runId, toolCall.id, McpPluginCatalog.PLUGIN_ID,
                        tool.server.id, tool.definition.name, tool.server.revision)
                } catch (_: Exception) {
                    return attributed(failure("PLUGIN_JOURNAL_UNAVAILABLE", "调用历史未能落盘，本次调用未执行"), tool)
                }
            }
            // Admission may have changed during disk I/O. Revalidate before creating/sending a client.
            val client = admit(tool) {
                synchronized(lifecycleLock) {
                    if (closed) throw McpAdmissionException("MCP_EXECUTOR_CLOSED")
                    clients[tool.server.id] ?: McpHttpClient(
                        server = tool.server,
                        bearerToken = tool.bearerToken,
                    ).also { clients[tool.server.id] = it }
                }
            }
            val adapted = adaptResult(tool, client.callTool(tool.definition, arguments))
            // A remote error can occur after a side effect; it is never evidence of no effect.
            if (JSONObject(adapted.content).optBoolean("ok", false)) status = PluginCallStatus.SUCCEEDED
            adapted
        } catch (rejected: McpAdmissionException) {
            status = PluginCallStatus.REJECTED
            failure(rejected.code, "连接、工具授权或执行入口已变化；本次调用未接纳")
        } catch (_: Exception) {
            failure("MCP_CALL_FAILED", "调用失败或已中断；若请求已发出，远端结果可能不确定，请勿自动重试写操作")
        }
        val id = operationId
        val finishingJournal = callJournal
        if (id != null && finishingJournal != null) {
            val recorded = runCatching { finishingJournal.recordFinish(id, status) }.getOrDefault(false)
            if (!recorded) {
                return attributed(failure("OPERATION_UNCONFIRMED", "无法确认调用终态，远端可能已执行；请勿自动重试"), tool, id)
            }
        }
        return attributed(result, tool, id)
    }

    private fun <T> admit(tool: McpRunTool, block: () -> T): T = kotlinx.coroutines.runBlocking {
        McpServerRepository.withAdmission(tool.server.id, tool.server.revision, tool.definition.name) {
            if (isCancelled()) throw McpAdmissionException("MCP_CALL_CANCELLED")
            if (!isExecutionAllowed()) throw McpAdmissionException("PLUGIN_ENTRY_UNAVAILABLE")
            synchronized(lifecycleLock) {
                if (closed) throw McpAdmissionException("MCP_EXECUTOR_CLOSED")
            }
            block()
        }
    }

    private fun attributed(
        result: AgentModelClient.ToolResult,
        tool: McpRunTool?,
        operationId: String? = null,
    ): AgentModelClient.ToolResult {
        val payload = JSONObject(result.content)
        if (tool != null) {
            payload.put("plugin_id", McpPluginCatalog.PLUGIN_ID)
                .put("connection_id", tool.server.id)
                .put("tool_id", tool.definition.name)
                .put("revision", tool.server.revision)
        }
        if (operationId != null) payload.put("operation_id", operationId)
        return result.copy(content = payload.toString())
    }

    fun contains(toolName: String): Boolean = snapshot.resolve(toolName) != null

    override fun close() {
        monitor.cancel()
        val closing = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            clients.values.toList().also { clients.clear() }
        }
        closing.forEach { runCatching { it.close() } }
    }

    private fun adaptResult(tool: McpRunTool, result: JSONObject): AgentModelClient.ToolResult {
        val resultType = result.optString("resultType")
        if (resultType == "input_required") {
            return failure(
                code = "MCP_INPUT_REQUIRED_UNSUPPORTED",
                message = "当前版本暂不支持 MCP 工具在执行中请求补充输入",
            )
        }
        if (tool.server.lastProtocolVersion == McpProtocolMode.LATEST && resultType != "complete") {
            return failure(
                code = "MCP_RESULT_TYPE_UNSUPPORTED",
                message = "MCP 工具返回了不支持的结果类型",
            )
        }
        val content = result.optJSONArray("content") ?: JSONArray()
        val textItems = JSONArray()
        val images = mutableListOf<AgentModelClient.ModelImage>()
        var textBytes = 0
        var imageBytes = 0
        var truncated = false
        var omittedItems = 0
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "text" -> {
                    val text = item.optString("text")
                    val remaining = MAX_TEXT_BYTES - textBytes
                    if (remaining > 0) {
                        val bounded = text.takeUtf8Bytes(remaining)
                        textItems.put(bounded)
                        textBytes += bounded.toByteArray().size
                        if (bounded.length < text.length) truncated = true
                    } else if (text.isNotEmpty()) {
                        truncated = true
                    }
                }
                "image" -> {
                    val mimeType = item.optString("mimeType")
                    val data = item.optString("data")
                    if (!mimeType.startsWith("image/") || data.isBlank()) {
                        omittedItems += 1
                        continue
                    }
                    val bytes = runCatching { Base64.decode(data, Base64.DEFAULT).size }.getOrNull()
                    if (bytes == null || bytes <= 0 || imageBytes + bytes > MAX_IMAGE_BYTES) {
                        omittedItems += 1
                        continue
                    }
                    images += AgentModelClient.ModelImage(
                        reference = "data:$mimeType;base64,$data",
                        mimeType = mimeType,
                        bytes = bytes,
                        source = "mcp",
                    )
                    imageBytes += bytes
                }
                "resource" -> {
                    val resource = item.optJSONObject("resource")
                    val resourceText = resource?.optString("text").orEmpty()
                    val remaining = MAX_TEXT_BYTES - textBytes
                    if (resourceText.isNotBlank() && remaining > 0) {
                        val bounded = resourceText.takeUtf8Bytes(remaining)
                        textItems.put(bounded)
                        textBytes += bounded.toByteArray().size
                        if (bounded.length < resourceText.length) truncated = true
                    } else if (resource?.has("blob") == true || resourceText.isNotBlank()) {
                        omittedItems += 1
                    }
                }
                "resource_link" -> {
                    val uri = item.optString("uri")
                    if (uri.isNotBlank()) {
                        val label = item.optString("name").ifBlank { item.optString("title") }
                        val rendered = if (label.isBlank()) uri else "$label: $uri"
                        val remaining = MAX_TEXT_BYTES - textBytes
                        if (remaining > 0) {
                            val bounded = rendered.takeUtf8Bytes(remaining)
                            textItems.put(bounded)
                            textBytes += bounded.toByteArray().size
                            if (bounded.length < rendered.length) truncated = true
                        } else {
                            truncated = true
                        }
                    } else {
                        omittedItems += 1
                    }
                }
                "audio" -> omittedItems += 1
                else -> omittedItems += 1
            }
        }
        val payload = JSONObject()
            .put("ok", !result.optBoolean("isError", false))
            .put("server", tool.server.name)
            .put("tool", tool.definition.name)
            .put("content", textItems)
        result.opt("structuredContent")?.takeUnless { it == JSONObject.NULL }?.let { structured ->
            val encoded = structured.toString()
            if (encoded.toByteArray().size <= MAX_STRUCTURED_BYTES) {
                payload.put("structured_content", structured)
            } else {
                truncated = true
            }
        }
        if (truncated) payload.put("truncated", true)
        if (omittedItems > 0) payload.put("omitted_items", omittedItems)
        if (result.optBoolean("isError", false)) payload.put("code", "MCP_TOOL_ERROR")
        return AgentModelClient.ToolResult(
            content = payload.toString(),
            images = images,
            sensitive = true,
        )
    }

    private fun failure(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = true,
        )

    private fun String.takeUtf8Bytes(maxBytes: Int): String {
        if (toByteArray().size <= maxBytes) return this
        var low = 0
        var high = length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (substring(0, middle).toByteArray().size <= maxBytes) low = middle else high = middle - 1
        }
        return substring(0, low)
    }

    private companion object {
        const val MAX_TEXT_BYTES = 64 * 1024
        const val MAX_STRUCTURED_BYTES = 64 * 1024
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    }
}

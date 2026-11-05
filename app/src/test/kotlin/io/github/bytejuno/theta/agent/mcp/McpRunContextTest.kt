package io.github.bytejuno.theta.agent.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.bytejuno.theta.agent.model.AgentModelClient
import io.github.bytejuno.theta.data.model.McpProtocolMode
import io.github.bytejuno.theta.data.model.McpServerSetting
import io.github.bytejuno.theta.data.model.McpToolDefinition
import io.github.bytejuno.theta.data.db.EtaDatabase
import io.github.bytejuno.theta.data.db.toEntity
import io.github.bytejuno.theta.data.repository.McpServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertThrows
import org.robolectric.RuntimeEnvironment
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class McpRunContextTest {
    private val executors = mutableListOf<McpToolExecutor>()

    @Before
    fun initializeRepository() = runBlocking {
        McpServerRepository.init(RuntimeEnvironment.getApplication())
        withContext(Dispatchers.IO) {
            val dao = EtaDatabase.get(RuntimeEnvironment.getApplication()).mcpServerDao()
            dao.servers().forEach { dao.delete(it.id) }
        }
    }

    @After
    fun closeExecutors() {
        executors.forEach { it.close() }
    }

    /** Real Room storage and the production admission coordinator, no executor authorization bypass. */
    private fun authorize(setting: McpServerSetting) = runBlocking {
        withContext(Dispatchers.IO) {
            EtaDatabase.get(RuntimeEnvironment.getApplication()).mcpServerDao().insert(setting.toEntity())
        }
    }

    private fun tracked(snapshot: McpRunSnapshot): McpToolExecutor =
        McpToolExecutor(snapshot).also { executors += it }

    @Test
    fun enabledToolIsActiveRegardlessOfSchemaKeywords() {
        val definition = McpToolDefinition(
            name = "create_task",
            inputSchemaJson = """{"type":"object","anyOf":[]}""",
        )
        val server = McpServerSetting(
            id = "server",
            name = "Server",
            url = "https://example.com/mcp",
            tools = listOf(definition),
            enabledToolNames = setOf(definition.name),
        )

        assertEquals(listOf(definition), server.activeTools)
    }

    @Test
    fun snapshotUsesFrozenTokenAndReportsTruncatedOrUnsupportedContent() {
        val authorization = AtomicReference<String>()
        val server = localServer { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.respond(
                """{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","content":[{"type":"resource_link","name":"Docs","uri":"https://example.com/docs"},{"type":"audio","mimeType":"audio/wav","data":"AA=="},{"type":"text","text":"${"x".repeat(70_000)}"}]}}""",
            )
        }
        try {
            val definition = McpToolDefinition(
                name = "search",
                inputSchemaJson = """{"type":"object"}""",
            )
            val setting = McpServerSetting(
                id = "server",
                name = "Server",
                url = server.url(),
                lastProtocolVersion = McpProtocolMode.LATEST,
                tools = listOf(definition),
                enabledToolNames = setOf(definition.name),
            )
            authorize(setting)
            val executor = tracked(
                McpRunSnapshot(
                    listOf(McpRunTool("mcp_server_search", setting, definition, "run-token"))
                )
            )

            val result = executor.execute(
                AgentModelClient.ToolCall("call-1", "mcp_server_search", "{}")
            )
            executor.close()
            val payload = JSONObject(result.content)

            assertEquals("Bearer run-token", authorization.get())
            assertTrue(result.sensitive)
            assertTrue(payload.getBoolean("truncated"))
            assertEquals(1, payload.getInt("omitted_items"))
            assertTrue(payload.getJSONArray("content").getString(0).contains("https://example.com/docs"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun closedExecutorDoesNotStartFirstRemoteCall() {
        val requests = AtomicInteger()
        val server = localServer { exchange ->
            requests.incrementAndGet()
            exchange.respond("""{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","content":[]}}""")
        }
        try {
            val definition = McpToolDefinition("search", inputSchemaJson = """{"type":"object"}""")
            val setting = McpServerSetting(
                id = "server",
                name = "Server",
                url = server.url(),
                lastProtocolVersion = McpProtocolMode.LATEST,
                tools = listOf(definition),
                enabledToolNames = setOf(definition.name),
            )
            authorize(setting)
            val executor = tracked(
                McpRunSnapshot(
                    listOf(McpRunTool("mcp_server_search", setting, definition, null))
                )
            )
            executor.close()

            val result = executor.execute(
                AgentModelClient.ToolCall("call-1", "mcp_server_search", "{}")
            )

            assertEquals("MCP_EXECUTOR_CLOSED", JSONObject(result.content).getString("code"))
            assertEquals(0, requests.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun disabledConnectionRejectsFrozenSnapshotWithoutNetwork() = assertAdmissionRejected("PLUGIN_DISABLED") {
        McpServerRepository.setEnabled(it.id, false)
    }

    @Test
    fun reenabledConnectionDoesNotReviveOldGenerationOrSendNetwork() = assertAdmissionRejected("PLUGIN_BINDING_STALE") {
        McpServerRepository.setEnabled(it.id, false)
        McpServerRepository.setEnabled(it.id, true)
    }

    @Test
    fun definitionChangeRejectsFrozenBindingWithoutNetwork() = assertAdmissionRejected("PLUGIN_BINDING_STALE") {
        McpServerRepository.update(it.copy(tools = it.tools.map { tool -> tool.copy(description = "changed semantics") }))
    }

    private fun assertAdmissionRejected(code: String, mutate: suspend (McpServerSetting) -> Unit) {
        val requests = AtomicInteger()
        val server = localServer { exchange ->
            requests.incrementAndGet()
            exchange.respond("""{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","content":[]}}""")
        }
        try {
            val definition = McpToolDefinition("write", inputSchemaJson = """{"type":"object"}""")
            val setting = McpServerSetting("server", "Server", server.url(), lastProtocolVersion = McpProtocolMode.LATEST,
                tools = listOf(definition), enabledToolNames = setOf(definition.name))
            authorize(setting)
            val executor = tracked(McpRunSnapshot(listOf(McpRunTool("mcp_write", setting, definition, null))))
            runBlocking { withContext(Dispatchers.IO) { mutate(setting) } }
            val result = executor.execute(AgentModelClient.ToolCall("call", "mcp_write", "{}"))
            assertEquals(code, JSONObject(result.content).getString("code"))
            assertEquals(0, requests.get())
            executor.close()
        } finally { server.stop(0) }
    }

    @Test
    fun legacy404OnSideEffectCallDoesNotReinitializeOrReplay() {
        val initializeCalls = AtomicInteger()
        val writeCalls = AtomicInteger()
        val initializedNotifications = AtomicInteger()
        val sessions = AtomicReference<String>()
        val server = localServer { exchange ->
            if (exchange.requestMethod == "DELETE") {
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            } else {
                val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                when (request.getString("method")) {
                    "initialize" -> {
                        initializeCalls.incrementAndGet()
                        exchange.responseHeaders.set("Mcp-Session-Id", "session-one")
                        exchange.respond(JSONObject().put("jsonrpc", "2.0").put("id", request.get("id"))
                            .put("result", JSONObject().put("protocolVersion", McpProtocolMode.LEGACY)
                                .put("capabilities", JSONObject()).put("serverInfo", JSONObject().put("name", "test").put("version", "1"))).toString())
                    }
                    "notifications/initialized" -> {
                        initializedNotifications.incrementAndGet()
                        exchange.sendResponseHeaders(202, -1)
                        exchange.close()
                    }
                    "tools/call" -> {
                        writeCalls.incrementAndGet() // Server may have committed before returning 404.
                        sessions.set(exchange.requestHeaders.getFirst("Mcp-Session-Id"))
                        exchange.respond("""{"error":"session lost after write"}""", status = 404)
                    }
                    else -> exchange.respond("{}", status = 500)
                }
            }
        }
        try {
            McpHttpClient(McpServerSetting("legacy", "Legacy", server.url(),
                protocolMode = McpProtocolMode.LEGACY, lastProtocolVersion = McpProtocolMode.LEGACY), null).use { client ->
                val tool = McpToolDefinition("create_note", inputSchemaJson = """{"type":"object"}""", readOnlyHint = false)
                assertThrows(Exception::class.java) { client.callTool(tool, JSONObject().put("text", "one note")) }
                assertEquals(1, initializeCalls.get())
                assertEquals(1, initializedNotifications.get())
                assertEquals(1, writeCalls.get())
                assertEquals("session-one", sessions.get())
            }
        } finally { server.stop(0) }
    }

    private fun localServer(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp", handler)
            start()
        }

    private fun HttpServer.url(): String = "http://127.0.0.1:${address.port}/mcp"

    private fun HttpExchange.respond(body: String, status: Int = 200) {
        responseHeaders.set("Content-Type", "application/json")
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}

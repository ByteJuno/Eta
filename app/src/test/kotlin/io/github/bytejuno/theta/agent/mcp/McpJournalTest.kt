package io.github.bytejuno.theta.agent.mcp

import com.sun.net.httpserver.HttpServer
import io.github.bytejuno.theta.agent.model.AgentModelClient
import io.github.bytejuno.theta.agent.plugin.PluginCallJournal
import io.github.bytejuno.theta.agent.plugin.PluginCallStatus
import io.github.bytejuno.theta.data.db.EtaDatabase
import io.github.bytejuno.theta.data.db.toEntity
import io.github.bytejuno.theta.data.model.McpProtocolMode
import io.github.bytejuno.theta.data.model.McpServerSetting
import io.github.bytejuno.theta.data.model.McpToolDefinition
import io.github.bytejuno.theta.data.repository.McpServerRepository
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class McpJournalTest {
    @Before
    fun initRepository() = runBlocking {
        McpServerRepository.init(RuntimeEnvironment.getApplication())
        withContext(Dispatchers.IO) {
            val dao = EtaDatabase.get(RuntimeEnvironment.getApplication()).mcpServerDao()
            dao.servers().forEach { dao.delete(it.id) }
        }
    }

    @Test
    fun successRecordsAttributionBeforeNetworkWithoutRawPayloads() = worker {
        fixture { f ->
            f.journal().use { journal ->
                f.beforeResponse = { assertEquals(PluginCallStatus.ADMITTED, journal.list().single().status) }
                f.executor(journal).use { executor ->
                    val result = JSONObject(executor.execute(f.call()).content)
                    assertTrue(result.getBoolean("ok"))
                    val record = journal.list().single()
                    assertEquals(record.operationId, result.getString("operation_id"))
                    assertEquals("run", record.runId)
                    assertEquals("call", record.callId)
                    assertEquals(f.setting.id, record.connectionId)
                    assertEquals("write", record.toolId)
                    assertEquals("connector.mcp", record.pluginId)
                    assertEquals(PluginCallStatus.SUCCEEDED, record.status)
                    assertFalse(record.toString().contains("secret-parameter"))
                    assertFalse(record.toString().contains("secret-result"))
                    assertEquals(1, f.requests.get())
                }
            }
        }
    }

    @Test
    fun remoteErrorAndUnsupportedResultRemainUnknownWithoutRetry() = worker {
        for (body in listOf(
            """{"resultType":"complete","isError":true,"content":[]}""",
            """{"resultType":"input_required","content":[]}""",
        )) fixture { f ->
            f.result = body
            f.journal().use { journal ->
                f.executor(journal).use { executor ->
                    assertFalse(JSONObject(executor.execute(f.call()).content).getBoolean("ok"))
                    assertEquals(PluginCallStatus.UNKNOWN, journal.list().single().status)
                    assertEquals(1, f.requests.get())
                }
            }
        }
    }

    @Test
    fun transportFailureRecordsUnknownWithoutRetry() = worker {
        fixture { f ->
            f.httpStatus = 500
            f.journal().use { journal ->
                f.executor(journal).use { executor ->
                    assertEquals("MCP_CALL_FAILED", JSONObject(executor.execute(f.call()).content).getString("code"))
                    assertEquals(PluginCallStatus.UNKNOWN, journal.list().single().status)
                    assertEquals(1, f.requests.get())
                }
            }
        }
    }

    @Test
    fun startStorageFailureDoesNotSendRequest() = worker {
        fixture { f ->
            val journal = f.journal()
            journal.close()
            f.executor(journal).use { executor ->
                val payload = JSONObject(executor.execute(f.call()).content)
                assertEquals("PLUGIN_JOURNAL_UNAVAILABLE", payload.getString("code"))
                assertEquals(f.setting.id, payload.getString("connection_id"))
                assertEquals("write", payload.getString("tool_id"))
                assertEquals(0, f.requests.get())
                assertFalse(payload.toString().contains("secret-token"))
            }
        }
    }

    @Test
    fun finishStorageFailureReturnsUnconfirmedWithOperationId() = worker {
        fixture { f ->
            val journal = f.journal()
            try {
                f.beforeResponse = { journal.close() }
                f.executor(journal).use { executor ->
                    val payload = JSONObject(executor.execute(f.call()).content)
                    assertEquals("OPERATION_UNCONFIRMED", payload.getString("code"))
                    assertTrue(payload.getString("operation_id").isNotBlank())
                    assertEquals(1, f.requests.get())
                }
                f.journal().use { assertEquals(PluginCallStatus.ADMITTED, it.list().single().status) }
            } finally { journal.close() }
        }
    }

    @Test
    fun revocationDuringJournalInsertIsRecheckedAndRejectedWithoutNetwork() = worker {
        fixture { f ->
            var clockCalls = 0
            f.journal(clock = {
                clockCalls++
                // Constructor recovery is first; recordStart is second, outside repository mutex.
                if (clockCalls == 2) runBlocking { McpServerRepository.setEnabled(f.setting.id, false) }
                100L
            }).use { journal ->
                f.executor(journal).use { executor ->
                    assertEquals("PLUGIN_DISABLED", JSONObject(executor.execute(f.call()).content).getString("code"))
                    assertEquals(PluginCallStatus.REJECTED, journal.list().single().status)
                    assertEquals(0, f.requests.get())
                }
            }
        }
    }

    @Test
    fun waitingChildCancellationDoesNotAdmitOrSendAndDoesNotCancelSibling() = worker {
        fixture { f ->
            val lock = ReentrantLock()
            val locks = mapOf(f.setting.id to lock)
            val cancelled = AtomicBoolean(false)
            val workerStarted = CountDownLatch(1)
            val pool = Executors.newSingleThreadExecutor()
            f.journal().use { journal ->
                f.executor(journal, cancelled = { cancelled.get() }, locks = locks).use { child ->
                    lock.lock()
                    try {
                        val waiting = pool.submit(Callable {
                            workerStarted.countDown()
                            child.execute(f.call())
                        })
                        assertTrue(workerStarted.await(3, TimeUnit.SECONDS))
                        cancelled.set(true)
                        assertEquals("MCP_CALL_CANCELLED", JSONObject(waiting.get(3, TimeUnit.SECONDS).content).getString("code"))
                        assertTrue(journal.list().isEmpty())
                        assertEquals(0, f.requests.get())
                    } finally { lock.unlock() }
                    // A sibling sharing the same connection lock still works normally.
                    f.executor(journal, locks = locks).use { sibling ->
                        assertTrue(JSONObject(sibling.execute(f.call()).content).getBoolean("ok"))
                    }
                }
            }
            pool.shutdownNow()
        }
    }

    @Test
    fun sharedConnectionLockSerializesParentAndChildBeforeJournalAdmission() = worker {
        fixture { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val waitingStarted = CountDownLatch(1)
            val lock = ReentrantLock()
            val locks = mapOf(f.setting.id to lock)
            val pool = Executors.newFixedThreadPool(2)
            f.beforeResponse = {
                if (f.requests.get() == 1) {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
            }
            try {
                f.journal().use { journal ->
                    f.executor(journal, locks = locks).use { parent ->
                        f.executor(journal, locks = locks).use { child ->
                            val first = pool.submit(Callable { parent.execute(f.call()) })
                            assertTrue(entered.await(3, TimeUnit.SECONDS))
                            val second = pool.submit(Callable {
                                waitingStarted.countDown()
                                child.execute(f.call().copy(id = "child-call"))
                            })
                            assertTrue(waitingStarted.await(3, TimeUnit.SECONDS))
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                            while (!lock.hasQueuedThreads() && System.nanoTime() < deadline) Thread.yield()
                            assertTrue(lock.hasQueuedThreads())
                            assertEquals(1, journal.list().size)
                            assertEquals(1, f.requests.get())
                            release.countDown()
                            assertTrue(JSONObject(first.get(5, TimeUnit.SECONDS).content).getBoolean("ok"))
                            assertTrue(JSONObject(second.get(5, TimeUnit.SECONDS).content).getBoolean("ok"))
                            assertEquals(2, journal.list().size)
                            assertEquals(2, f.requests.get())
                        }
                    }
                }
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun lazyJournalProviderIsNotOpenedUntilAdmittedAndIsResolvedOncePerCall() = worker {
        fixture { f ->
            f.journal().use { journal ->
                val opened = AtomicInteger()
                val provider = { opened.incrementAndGet(); journal }
                f.executor(null, provider = provider).use { executor ->
                    assertEquals(0, opened.get())
                    executor.execute(f.call().copy(name = "unknown"))
                    assertEquals(0, opened.get())
                    assertTrue(JSONObject(executor.execute(f.call()).content).getBoolean("ok"))
                    assertEquals(1, opened.get())
                    assertEquals(PluginCallStatus.SUCCEEDED, journal.list().single().status)
                }
                f.executor(null, provider = { error("secret-provider-failure") }).use { executor ->
                    val result = JSONObject(executor.execute(f.call()).content)
                    assertEquals("PLUGIN_JOURNAL_UNAVAILABLE", result.getString("code"))
                    assertFalse(result.toString().contains("secret-provider-failure"))
                    assertEquals(1, f.requests.get())
                }
                runBlocking { McpServerRepository.setEnabled(f.setting.id, false) }
                f.executor(null, provider = provider).use { executor ->
                    assertEquals("PLUGIN_DISABLED", JSONObject(executor.execute(f.call()).content).getString("code"))
                    assertEquals(1, opened.get())
                }
            }
        }
    }

    private class Fixture(val server: HttpServer, val setting: McpServerSetting, val definition: McpToolDefinition) {
        val requests = AtomicInteger()
        val databaseName = "mcp-journal-${UUID.randomUUID()}.db"
        @Volatile var result = """{"resultType":"complete","content":[{"type":"text","text":"secret-result"}]}"""
        @Volatile var httpStatus = 200
        @Volatile var beforeResponse: () -> Unit = {}
        fun journal(clock: () -> Long = System::currentTimeMillis) = PluginCallJournal(
            RuntimeEnvironment.getApplication(), databaseName, clock = clock, isAuthoritativeProcess = { true },
        )
        fun executor(
            journal: PluginCallJournal?,
            cancelled: () -> Boolean = { false },
            locks: Map<String, ReentrantLock> = mapOf(setting.id to ReentrantLock()),
            provider: (() -> PluginCallJournal)? = null,
        ) = McpToolExecutor(
            McpRunSnapshot(listOf(McpRunTool("mcp_write", setting, definition, "secret-token"))),
            isCancelled = cancelled, runId = "run", journal = journal, connectionLocks = locks,
            journalProvider = provider,
        )
        fun call() = AgentModelClient.ToolCall("call", "mcp_write", """{"value":"secret-parameter"}""")
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val definition = McpToolDefinition("write", inputSchemaJson = """{"type":"object"}""")
        val setting = McpServerSetting(
            id = UUID.randomUUID().toString(), name = "Test", url = "http://127.0.0.1:${server.address.port}/mcp",
            lastProtocolVersion = McpProtocolMode.LATEST, tools = listOf(definition), enabledToolNames = setOf("write"),
        )
        val fixture = Fixture(server, setting, definition)
        server.createContext("/mcp") { exchange ->
            fixture.requests.incrementAndGet()
            try {
                val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                fixture.beforeResponse()
                val body = JSONObject().put("jsonrpc", "2.0").put("id", request.get("id"))
                    .put("result", JSONObject(fixture.result)).toString().toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(fixture.httpStatus, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally { exchange.close() }
        }
        server.start()
        try {
            runBlocking { withContext(Dispatchers.IO) {
                EtaDatabase.get(RuntimeEnvironment.getApplication()).mcpServerDao().insert(setting.toEntity())
            } }
            block(fixture)
        } finally {
            server.stop(0)
            android.database.sqlite.SQLiteDatabase.deleteDatabase(File(RuntimeEnvironment.getApplication().noBackupFilesDir, fixture.databaseName))
        }
    }

    private fun worker(block: () -> Unit) {
        val pool = Executors.newSingleThreadExecutor()
        try { pool.submit(Callable { block() }).get(15, TimeUnit.SECONDS) }
        finally { pool.shutdownNow() }
    }
}

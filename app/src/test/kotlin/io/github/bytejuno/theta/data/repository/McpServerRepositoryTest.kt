package io.github.bytejuno.theta.data.repository

import io.github.bytejuno.theta.data.db.McpServerDao
import io.github.bytejuno.theta.data.db.McpServerEntity
import io.github.bytejuno.theta.data.db.toEntity
import io.github.bytejuno.theta.data.model.McpAuthorizationType
import io.github.bytejuno.theta.data.model.McpServerSetting
import io.github.bytejuno.theta.data.model.McpToolDefinition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class McpServerRepositoryTest {
    private val tool = McpToolDefinition("write", description = "Write one note", inputSchemaJson = """{"type":"object","properties":{}}""")
    private fun server() = McpServerSetting("server", "Notes", "https://example.com/mcp", tools = listOf(tool), enabledToolNames = setOf("write"))

    @Test fun cacheOnlyUpdatesKeepRunRevisionButRejectStaleFullWrites() = runBlocking {
        val dao = FakeDao(server())
        val store = McpRepositoryStore(dao, FakeSecrets())
        val old = store.serverById("server")!!
        val fresh = store.update(old.copy(lastRefreshedAt = 4, toolsExpireAt = 10))
        assertEquals(0L, fresh.revision)
        assertEquals(1L, fresh.stateVersion)
        expect<McpStaleWriteException> { store.update(old.copy(name = "Stale UI")) }
        assertEquals(7, store.withAdmission("server", 0, "write") { 7 })
    }

    @Test fun changedDefinitionsRevokeAndNewToolsAreNeverGrantedByFullUpdate() = runBlocking {
        val dao = FakeDao(server())
        val store = McpRepositoryStore(dao, FakeSecrets())
        val added = tool.copy(name = "new")
        val fresh = store.update(server().copy(tools = listOf(tool.copy(description = "Delete all notes"), added), enabledToolNames = setOf("write", "new")))
        assertTrue(fresh.enabledToolNames.isEmpty())
        assertEquals(1L, fresh.revision)
        expect<McpStaleWriteException> { store.setToolEnabled("server", "write", true, 0) }
        assertCode("TOOL_GRANT_REQUIRED") { store.withAdmission("server", 1, "write") {} }
        val approved = store.setToolEnabled("server", "write", true, 1)
        assertEquals(setOf("write"), approved.enabledToolNames)
    }

    @Test fun canonicalSchemaOrderingDoesNotRevokeOrAdvanceRevision() = runBlocking {
        val store = McpRepositoryStore(FakeDao(server()), FakeSecrets())
        val updated = store.update(server().copy(tools = listOf(tool.copy(inputSchemaJson = """{ "properties": {}, "type": "object" }"""))))
        assertEquals(0L, updated.revision)
        assertEquals(setOf("write"), updated.enabledToolNames)
    }

    @Test fun disableThenEnableNeverResurrectsOldRun() = runBlocking {
        val store = McpRepositoryStore(FakeDao(server()), FakeSecrets())
        store.setEnabled("server", false)
        assertCode("PLUGIN_DISABLED") { store.withAdmission("server", 0, "write") {} }
        val enabled = store.setEnabled("server", true)
        assertCode("PLUGIN_BINDING_STALE") { store.withAdmission("server", 0, "write") {} }
        store.withAdmission("server", enabled.revision, "write") {}
    }

    @Test fun endpointAndAuthModeChangesRevokeAuthority() = runBlocking {
        for (changed in listOf(server().copy(url = "https://other.example/mcp"), server().copy(authorizationType = McpAuthorizationType.BEARER))) {
            val store = McpRepositoryStore(FakeDao(server()), FakeSecrets())
            val result = store.update(changed, if (changed.authorizationType == McpAuthorizationType.BEARER) "secret" else null)
            assertTrue(result.enabledToolNames.isEmpty())
            assertEquals(1L, result.revision)
        }
    }

    @Test fun tokenWriteFailureAfterSecretMutationSurvivesProcessRestartAsBarrier() = runBlocking {
        val original = server().copy(authorizationType = McpAuthorizationType.BEARER)
        val dao = FakeDao(original)
        val secrets = FakeSecrets().apply { tokens["server"] = "old"; failAfterWrite = true }
        val store = McpRepositoryStore(dao, secrets)
        expect<IllegalStateException> { store.update(original, "new") }
        assertEquals("new", secrets.tokens["server"])
        val restarted = McpRepositoryStore(dao, secrets)
        assertTrue(restarted.captureEnabledSnapshots().isEmpty())
        assertCode("PLUGIN_DISABLED") { restarted.withAdmission("server", 0, "write") {} }
        val barrier = restarted.serverById("server")!!
        assertTrue(barrier.credentialUpdatePending)
        assertTrue(barrier.enabledToolNames.isEmpty())
        secrets.failAfterWrite = false
        val recovered = restarted.update(barrier, "confirmed")
        assertFalse(recovered.credentialUpdatePending)
        assertTrue(recovered.enabledToolNames.isEmpty())
        assertEquals("confirmed", restarted.captureEnabledSnapshots().single().bearerToken)
        assertFalse(restarted.captureEnabledSnapshots().single().toString().contains("confirmed"))
    }

    @Test fun failedFinalRoomWriteKeepsDurableCredentialBarrier() = runBlocking {
        val original = server().copy(authorizationType = McpAuthorizationType.BEARER)
        val dao = FakeDao(original).apply { failUpdateAt = 2 }
        val secrets = FakeSecrets().apply { tokens["server"] = "old" }
        expect<IllegalStateException> { McpRepositoryStore(dao, secrets).update(original, "new") }
        val restarted = McpRepositoryStore(dao, secrets)
        assertTrue(restarted.captureEnabledSnapshots().isEmpty())
        assertTrue(restarted.serverById("server")!!.credentialUpdatePending)
    }

    @Test fun failedBarrierDoesNotTouchSecretAndDeniesCurrentProcess() = runBlocking {
        val original = server().copy(authorizationType = McpAuthorizationType.BEARER)
        val dao = FakeDao(original).apply { failUpdateAt = 1 }
        val secrets = FakeSecrets().apply { tokens["server"] = "old" }
        val store = McpRepositoryStore(dao, secrets)
        expect<IllegalStateException> { store.update(original, "new") }
        assertEquals("old", secrets.tokens["server"])
        assertCode("PLUGIN_DISABLED") { store.withAdmission("server", 0, "write") {} }
    }

    @Test fun failedWritePublishesFailClosedStateEvenWithoutRoomEmission() = runBlocking {
        val dao = FakeDao(server()).apply { failUpdateAt = 1 }
        val store = McpRepositoryStore(dao, FakeSecrets())
        val blocked = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(2_000) { store.serversFlow().first { it.single().credentialUpdatePending } }
        }
        expect<IllegalStateException> { store.setEnabled("server", false) }
        assertFalse(blocked.await().single().enabled)
    }

    @Test fun failedDeleteLeavesRetryableDurableBarrier() = runBlocking {
        val dao = FakeDao(server())
        val secrets = FakeSecrets().apply { failClear = true }
        expect<IllegalStateException> { McpRepositoryStore(dao, secrets).delete("server") }
        val restarted = McpRepositoryStore(dao, secrets)
        assertCode("PLUGIN_DISABLED") { restarted.withAdmission("server", 0, "write") {} }
        secrets.failClear = false
        restarted.delete("server")
        assertNull(restarted.serverById("server"))
    }

    @Test fun queuedAdmissionWaitsForDisablePersistenceAndThenRejects() = runBlocking {
        val dao = FakeDao(server())
        val store = McpRepositoryStore(dao, FakeSecrets())
        dao.updateEntered = CompletableDeferred()
        dao.releaseUpdate = CompletableDeferred()
        val disable = async { store.setEnabled("server", false) }
        dao.updateEntered!!.await()
        var ran = false
        val queued = async { assertCode("PLUGIN_DISABLED") { store.withAdmission("server", 0, "write") { ran = true } } }
        dao.releaseUpdate!!.complete(Unit)
        disable.await()
        queued.await()
        assertFalse(ran)
    }

    @Test fun snapshotsWaitForCredentialCommitAndNeverMixGenerations() = runBlocking {
        val original = server().copy(authorizationType = McpAuthorizationType.BEARER)
        val dao = FakeDao(original)
        val secrets = FakeSecrets().apply { tokens["server"] = "old" }
        val store = McpRepositoryStore(dao, secrets)
        dao.updateEntered = CompletableDeferred()
        dao.releaseUpdate = CompletableDeferred()
        val updating = async { store.update(original, "new") }
        dao.updateEntered!!.await()
        val snapshots = async { store.captureEnabledSnapshots() }
        dao.releaseUpdate!!.complete(Unit)
        updating.await()
        val snapshot = snapshots.await().single()
        assertEquals("new", snapshot.bearerToken)
        assertEquals(1L, snapshot.server.revision)
        assertTrue(snapshot.server.enabledToolNames.isEmpty())
    }

    @Test fun failedAddFinalCommitLeavesRecoverableUnapprovedRow() = runBlocking {
        val dao = FakeDao().apply { failUpdateAt = 1 }
        val secrets = FakeSecrets()
        val original = server().copy(authorizationType = McpAuthorizationType.BEARER)
        expect<IllegalStateException> { McpRepositoryStore(dao, secrets).add(original, "new") }
        val restarted = McpRepositoryStore(dao, secrets)
        assertTrue(restarted.captureEnabledSnapshots().isEmpty())
        val pending = restarted.servers().single()
        assertTrue(pending.credentialUpdatePending)
        assertTrue(pending.enabledToolNames.isEmpty())
        assertEquals("new", secrets.tokens[pending.id])
    }

    @Test fun currentAdmissionCompletesBeforeFollowingDisable() = runBlocking {
        val store = McpRepositoryStore(FakeDao(server()), FakeSecrets())
        val order = mutableListOf<String>()
        store.withAdmission("server", 0, "write") { order += "admitted" }
        store.setEnabled("server", false)
        order += "disabled"
        assertEquals(listOf("admitted", "disabled"), order)
        assertCode("PLUGIN_DISABLED") { store.withAdmission("server", 0, "write") { order += "unexpected" } }
    }

    @Test fun twoConnectionsNeverShareTokensGrantsOrRevisions() = runBlocking {
        val first = server().copy(authorizationType = McpAuthorizationType.BEARER)
        val second = first.copy(id = "other")
        val secrets = FakeSecrets().apply { tokens["server"] = "one"; tokens["other"] = "two" }
        val store = McpRepositoryStore(FakeDao(first, second), secrets)
        store.setEnabled("server", false)
        val snapshot = store.captureEnabledSnapshots().single()
        assertEquals("other", snapshot.server.id)
        assertEquals("two", snapshot.bearerToken)
        assertEquals(0L, snapshot.server.revision)
        store.withAdmission("other", 0, "write") {}
    }

    @Test fun addNeverImportsGrantsOrReusesCallerIdentity() = runBlocking {
        val store = McpRepositoryStore(FakeDao(), FakeSecrets())
        val added = store.add(server(), "")
        assertNotEquals("server", added.id)
        assertEquals(0L, added.revision)
        assertTrue(added.enabledToolNames.isEmpty())
    }

    private suspend inline fun <reified T : Throwable> expect(crossinline block: suspend () -> Unit): T {
        try { block() } catch (error: Throwable) { assertTrue(error.toString(), error is T); return error as T }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
    private suspend fun assertCode(code: String, block: suspend () -> Unit) {
        assertEquals(code, expect<McpAdmissionException> { block() }.code)
    }

    private class FakeSecrets : McpCredentialStore {
        val tokens = mutableMapOf<String, String>()
        var failAfterWrite = false
        var failClear = false
        override fun bearerToken(serverId: String) = tokens[serverId]
        override fun setBearerToken(serverId: String, token: String) {
            tokens[serverId] = token
            check(!failAfterWrite) { "Injected secret commit failure" }
        }
        override fun clear(serverId: String) { check(!failClear); tokens.remove(serverId) }
    }

    private class FakeDao(vararg initial: McpServerSetting) : McpServerDao {
        private val rows = initial.associate { it.id to it.toEntity() }.toMutableMap()
        private val flow = MutableStateFlow(rows.values.toList())
        var updates = 0
        var failUpdateAt = -1
        var updateEntered: CompletableDeferred<Unit>? = null
        var releaseUpdate: CompletableDeferred<Unit>? = null
        override fun serversFlow(): Flow<List<McpServerEntity>> = flow
        override suspend fun servers() = rows.values.toList()
        override suspend fun serverById(id: String) = rows[id]
        override suspend fun insert(server: McpServerEntity) { check(server.id !in rows); rows[server.id] = server; publish() }
        override suspend fun update(server: McpServerEntity): Int {
            updates++
            updateEntered?.complete(Unit)
            releaseUpdate?.await()
            check(updates != failUpdateAt) { "Injected Room write failure" }
            if (server.id !in rows) return 0
            rows[server.id] = server
            publish()
            return 1
        }
        override suspend fun delete(id: String): Int { val removed = rows.remove(id); publish(); return if (removed == null) 0 else 1 }
        private fun publish() { flow.value = rows.values.toList() }
    }
}

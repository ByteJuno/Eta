package io.github.bytejuno.theta.data.repository

import android.content.Context
import io.github.bytejuno.theta.data.db.EtaDatabase
import io.github.bytejuno.theta.data.db.McpServerDao
import io.github.bytejuno.theta.data.db.toDomain
import io.github.bytejuno.theta.data.db.toEntity
import io.github.bytejuno.theta.data.model.McpAuthorizationType
import io.github.bytejuno.theta.data.model.McpServerSetting
import io.github.bytejuno.theta.data.model.retainedMcpToolGrants
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Deliberately not a data class: logs/destructuring must not accidentally expose credentials. */
internal class McpServerSnapshot(val server: McpServerSetting, val bearerToken: String?) {
    override fun toString(): String = "McpServerSnapshot(revision=${server.revision}, credentials=[REDACTED])"
}

internal class McpAdmissionException(val code: String) : IllegalStateException(code)
internal class McpStaleWriteException : IllegalStateException("MCP 配置已变化，请重新加载后重试")

internal object McpServerRepository {
    @Volatile private lateinit var applicationContext: Context
    private val store by lazy { McpRepositoryStore(EtaDatabase.get(context()).mcpServerDao(), McpSecretStore(context())) }

    fun init(context: Context) {
        if (!::applicationContext.isInitialized) applicationContext = context.applicationContext
    }

    fun serversFlow(): Flow<List<McpServerSetting>> = store.serversFlow()
    suspend fun servers(): List<McpServerSetting> = store.servers()
    suspend fun enabledServers(): List<McpServerSetting> = servers().filter { it.enabled && !it.credentialUpdatePending }
    suspend fun serverById(id: String): McpServerSetting? = store.serverById(id)
    suspend fun captureEnabledSnapshots(): List<McpServerSnapshot> = store.captureEnabledSnapshots()
    suspend fun captureSnapshot(serverId: String): McpServerSnapshot? = store.captureSnapshot(serverId)
    suspend fun add(server: McpServerSetting, bearerToken: String): McpServerSetting = store.add(server, bearerToken)
    suspend fun update(server: McpServerSetting, bearerToken: String? = null): McpServerSetting = store.update(server, bearerToken)
    suspend fun setToolEnabled(serverId: String, toolName: String, enabled: Boolean, expectedRevision: Long) =
        store.setToolEnabled(serverId, toolName, enabled, expectedRevision)
    suspend fun setEnabled(serverId: String, enabled: Boolean): McpServerSetting = store.setEnabled(serverId, enabled)
    suspend fun delete(serverId: String) = store.delete(serverId)
    suspend fun <T> withAdmission(serverId: String, expectedRevision: Long, toolName: String, block: () -> T): T =
        store.withAdmission(serverId, expectedRevision, toolName, block)

    private fun context(): Context {
        check(::applicationContext.isInitialized) { "McpServerRepository.init(context) must be called in Application.onCreate()" }
        return applicationContext
    }
}

/** One process-wide owner in production; injectable stores permit crash/failure tests without Keystore. */
internal class McpRepositoryStore(private val dao: McpServerDao, private val secrets: McpCredentialStore) {
    private val mutex = Mutex()
    // A failed persistence operation must not restore admission in this process.
    private val denied = MutableStateFlow<Set<String>>(emptySet())

    fun serversFlow(): Flow<List<McpServerSetting>> = combine(dao.serversFlow(), denied) { _, _ ->
        // A queued Flow emission may predate a completed write; re-read under coordination.
        // In-memory fail-closed changes must emit even when Room's failed write emits nothing.
        mutex.withLock { dao.servers().map { row -> visible(row.toDomain()) } }
    }
    suspend fun servers(): List<McpServerSetting> = mutex.withLock { dao.servers().map { visible(it.toDomain()) } }
    suspend fun serverById(id: String): McpServerSetting? = mutex.withLock { current(id)?.let(::visible) }

    suspend fun captureEnabledSnapshots(): List<McpServerSnapshot> = mutex.withLock {
        dao.servers().mapNotNull { row ->
            val server = row.toDomain()
            if (!server.enabled || server.credentialUpdatePending || server.id in denied.value) null else snapshot(server)
        }
    }
    suspend fun captureSnapshot(serverId: String): McpServerSnapshot? = mutex.withLock {
        current(serverId)?.takeUnless { it.credentialUpdatePending || it.id in denied.value }?.let(::snapshot)
    }

    /** block only marks admission locally; never perform network, wait, or re-enter this store here. */
    suspend fun <T> withAdmission(serverId: String, expectedRevision: Long, toolName: String, block: () -> T): T = mutex.withLock {
        val server = current(serverId)
        if (server == null || !server.enabled || server.credentialUpdatePending || serverId in denied.value) {
            throw McpAdmissionException("PLUGIN_DISABLED")
        }
        if (server.revision != expectedRevision) throw McpAdmissionException("PLUGIN_BINDING_STALE")
        if (toolName !in server.enabledToolNames || server.tools.count { it.name == toolName } != 1) {
            throw McpAdmissionException("TOOL_GRANT_REQUIRED")
        }
        if (server.authorizationType == McpAuthorizationType.BEARER && secrets.bearerToken(serverId).isNullOrBlank()) {
            throw McpAdmissionException("PLUGIN_DISABLED")
        }
        block()
    }

    suspend fun add(server: McpServerSetting, bearerToken: String): McpServerSetting = mutex.withLock {
        // IDs are host-owned and never reused, including after deletion (prevents old run resurrection).
        val added = server.copy(
            id = UUID.randomUUID().toString(),
            sortOrder = (dao.servers().maxOfOrNull { it.sortOrder } ?: -1) + 1,
            enabledToolNames = emptySet(), revision = 0, stateVersion = 0, credentialUpdatePending = true,
        )
        dao.insert(added.toEntity()) // durable barrier BEFORE touching independent storage
        try {
            writeSecret(added, bearerToken)
            persist(added.copy(credentialUpdatePending = false, stateVersion = 1))
        } catch (failure: Throwable) {
            denied.value = denied.value + added.id
            throw failure
        }
    }

    suspend fun update(server: McpServerSetting, bearerToken: String? = null): McpServerSetting = mutex.withLock {
        val previous = requireCurrent(server.id)
        if (server.revision != previous.revision || server.stateVersion != previous.stateVersion) throw McpStaleWriteException()
        updateLocked(previous, server, bearerToken)
    }

    suspend fun setEnabled(serverId: String, enabled: Boolean): McpServerSetting = mutex.withLock {
        val previous = requireCurrent(serverId)
        updateLocked(previous, previous.copy(enabled = enabled), null)
    }

    suspend fun setToolEnabled(serverId: String, toolName: String, enabled: Boolean, expectedRevision: Long): McpServerSetting = mutex.withLock {
        val previous = requireCurrent(serverId)
        if (previous.revision != expectedRevision) throw McpStaleWriteException()
        check(!previous.credentialUpdatePending && serverId !in denied.value) { "MCP 凭据更新未完成，请先重新保存凭据" }
        if (enabled) {
            val tool = requireNotNull(previous.tools.singleOrNull { it.name == toolName }) { "MCP 工具不存在或名称重复" }
            tool.definitionDigest() // malformed schema must not receive authority
        }
        val names = previous.enabledToolNames.toMutableSet().apply { if (enabled) add(toolName) else remove(toolName) }
        if (names == previous.enabledToolNames) return@withLock previous
        persist(previous.copy(enabledToolNames = names, revision = next(previous.revision), stateVersion = next(previous.stateVersion)))
    }

    suspend fun delete(serverId: String) = mutex.withLock {
        val previous = requireCurrent(serverId)
        // Reuse the durable barrier for deletion recovery; failed cleanup stays visible and retryable.
        persist(previous.copy(enabled = false, enabledToolNames = emptySet(), credentialUpdatePending = true,
            revision = next(previous.revision), stateVersion = next(previous.stateVersion)))
        try {
            secrets.clear(serverId)
            check(dao.delete(serverId) == 1) { "MCP 服务器不存在" }
            denied.value = denied.value - serverId
            Unit
        } catch (failure: Throwable) {
            denied.value = denied.value + serverId
            throw failure
        }
    }

    private suspend fun updateLocked(previous: McpServerSetting, requested: McpServerSetting, token: String?): McpServerSetting {
        val identityChanged = previous.url != requested.url || previous.authorizationType != requested.authorizationType ||
            previous.protocolMode != requested.protocolMode
        val writesSecret = token != null || previous.authorizationType != requested.authorizationType
        check(!previous.credentialUpdatePending || writesSecret) { "MCP 凭据更新未完成，请重新保存凭据或删除连接" }
        check(previous.id !in denied.value || writesSecret || !requested.enabled) { "MCP 更新失败，请先停用或重新保存凭据" }
        val retained = retainedMcpToolGrants(previous, requested.tools)
        // Full-object updates may revoke grants, but never grant new/redefined tools.
        val grants = if (identityChanged || writesSecret || previous.id in denied.value) emptySet()
            else requested.enabledToolNames.intersect(retained)
        val definitionsChanged = definitionMap(previous) != definitionMap(requested)
        val securityChanged = identityChanged || writesSecret || definitionsChanged || previous.enabled != requested.enabled ||
            previous.enabledToolNames != grants || previous.id in denied.value
        var updated = requested.copy(
            createdAt = previous.createdAt,
            enabledToolNames = grants,
            revision = if (securityChanged) next(previous.revision) else previous.revision,
            stateVersion = next(previous.stateVersion),
            credentialUpdatePending = false,
            lastProtocolVersion = if (identityChanged || writesSecret) null else requested.lastProtocolVersion,
            toolsExpireAt = if (identityChanged || writesSecret) null else requested.toolsExpireAt,
        )
        if (writesSecret) {
            // Never roll back this barrier to old grants: commit() can fail after changing memory/disk.
            val barrier = previous.copy(enabledToolNames = emptySet(), credentialUpdatePending = true,
                revision = updated.revision, stateVersion = updated.stateVersion)
            persist(barrier)
            try {
                writeSecret(updated, token.orEmpty())
                updated = updated.copy(stateVersion = next(barrier.stateVersion))
                return persist(updated)
            } catch (failure: Throwable) {
                denied.value = denied.value + previous.id
                throw failure
            }
        }
        return persist(updated)
    }

    private fun definitionMap(server: McpServerSetting): Map<String, List<String?>> = server.tools.groupBy { it.name }
        .mapValues { (_, tools) -> tools.map { runCatching { it.definitionDigest() }.getOrNull() } }

    private suspend fun persist(server: McpServerSetting): McpServerSetting = try {
        check(dao.update(server.toEntity()) == 1) { "MCP 服务器不存在" }
        denied.value = denied.value - server.id
        server
    } catch (failure: Throwable) {
        denied.value = denied.value + server.id
        throw failure
    }

    private fun visible(server: McpServerSetting) = if (server.id in denied.value) {
        server.copy(enabled = false, credentialUpdatePending = true)
    } else server
    private suspend fun current(id: String) = dao.serverById(id)?.toDomain()
    private suspend fun requireCurrent(id: String) = requireNotNull(current(id)) { "MCP 服务器不存在" }
    private fun snapshot(server: McpServerSetting): McpServerSnapshot? {
        val token = if (server.authorizationType == McpAuthorizationType.BEARER) secrets.bearerToken(server.id) else null
        if (server.authorizationType == McpAuthorizationType.BEARER && token.isNullOrBlank()) return null
        return McpServerSnapshot(server, token)
    }
    private fun writeSecret(server: McpServerSetting, token: String) {
        if (server.authorizationType == McpAuthorizationType.BEARER) secrets.setBearerToken(server.id, token)
        else secrets.clear(server.id)
    }
    private fun next(value: Long): Long = Math.addExact(value, 1L)
}

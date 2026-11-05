package io.github.bytejuno.theta.agent.plugin

import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class McpTargetSelectionRequest internal constructor(
    val runId: String,
    val connections: List<Pair<String, String>>,
    internal val owner: Any,
) {
    val id: String = UUID.randomUUID().toString()
    internal val wake = CountDownLatch(1)
    internal var decision: Set<String>? = null
    internal var presented = false
    fun approve(connectionIds: Set<String>): Boolean = connectionIds.isNotEmpty() &&
        McpTargetSelectionBridge.resolve(this, connectionIds)
    fun deny() { McpTargetSelectionBridge.resolve(this, emptySet()) }
}

/** One explicit target decision, in memory only. It never enables tools or chooses defaults. */
internal object McpTargetSelectionBridge {
    private val lock = Any()
    private var foreground: Pair<Any, String>? = null
    private val active = mutableListOf<McpTargetSelectionRequest>()
    private val pending = MutableStateFlow<List<McpTargetSelectionRequest>>(emptyList())
    val requests: StateFlow<List<McpTargetSelectionRequest>> = pending.asStateFlow()

    fun canPresent(runId: String): Boolean = synchronized(lock) {
        runId.isNotBlank() && foreground?.second == runId
    }

    fun registerForeground(runId: String): AutoCloseable = synchronized(lock) {
        require(runId.isNotBlank())
        revokeAll()
        val owner = Any()
        foreground = owner to runId
        AutoCloseable {
            synchronized(lock) {
                if (foreground?.first === owner) {
                    foreground = null
                    revokeAll()
                }
            }
        }
    }

    fun select(
        runId: String,
        connections: List<Pair<String, String>>,
        isCancelled: () -> Boolean,
    ): Set<String> {
        check(Looper.myLooper() != Looper.getMainLooper()) { "MCP_TARGET_WORKER_REQUIRED" }
        if (connections.isEmpty() || connections.any { it.first.isBlank() } ||
            connections.map { it.first }.distinct().size != connections.size || isCancelled()) return emptySet()
        val request = synchronized(lock) {
            val host = foreground?.takeIf { it.second == runId } ?: return emptySet()
            McpTargetSelectionRequest(runId, connections.map { it.first to it.second }, host.first).also { active += it }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180)
        Handler(Looper.getMainLooper()).post {
            synchronized(lock) {
                if (foreground?.first !== request.owner || request !in active) {
                    request.decision = emptySet()
                    request.wake.countDown()
                } else {
                    request.presented = true
                }
                publish()
            }
        }
        try {
            while (System.nanoTime() < deadline && !isCancelled()) {
                synchronized(lock) {
                    if (foreground?.first !== request.owner) return emptySet()
                    request.decision?.let { return it.toSet() }
                }
                request.wake.await(100, TimeUnit.MILLISECONDS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            synchronized(lock) {
                active.remove(request)
                request.decision = emptySet()
                request.wake.countDown()
                publish()
            }
        }
        return emptySet()
    }

    internal fun resolve(request: McpTargetSelectionRequest, ids: Set<String>): Boolean = synchronized(lock) {
        if (request !in active || request.decision != null || foreground?.first !== request.owner) return false
        val allowed = request.connections.mapTo(hashSetOf()) { it.first }
        if (!allowed.containsAll(ids)) return false
        request.decision = ids.toSet()
        request.wake.countDown()
        publish()
        true
    }

    private fun revokeAll() {
        active.forEach { it.decision = emptySet(); it.wake.countDown() }
        active.clear()
        publish()
    }

    private fun publish() {
        pending.value = active.filter { it.presented && it.decision == null && foreground?.first === it.owner }
    }
}

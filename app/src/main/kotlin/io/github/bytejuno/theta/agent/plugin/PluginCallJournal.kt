package io.github.bytejuno.theta.agent.plugin

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Looper
import java.io.File
import java.util.UUID

internal enum class PluginCallStatus { ADMITTED, UNKNOWN, SUCCEEDED, FAILED, REJECTED }

/** Attribution only: no raw parameters, results, credentials, or error payloads. */
internal data class PluginCallRecord(
    val operationId: String,
    val runId: String,
    val callId: String,
    val pluginId: String,
    val connectionId: String,
    val toolId: String,
    val revision: Long,
    val status: PluginCallStatus,
    val startedAt: Long,
    val finishedAt: Long?,
)

/**
 * App-main-process-only history sidecar, independent of Room and connection deletion.
 * Construction, all operations, and close require a worker thread. recordStart must successfully
 * return before the host invokes the remote tool; storage failures propagate and deny admission.
 * This journal records uncertainty after process death, never authorizes replay of remote effects.
 */
internal class PluginCallJournal(
    context: Context,
    databaseName: String = "plugin_calls_v1.db",
    private val clock: () -> Long = System::currentTimeMillis,
    private val isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
    private val isAuthoritativeProcess: () -> Boolean = {
        Application.getProcessName() == context.packageName
    },
) : AutoCloseable {
    private val helper: Helper
    private var closed = false

    init {
        checkWorker()
        check(isAuthoritativeProcess()) { "PLUGIN_JOURNAL_APP_PROCESS_REQUIRED" }
        require(databaseName.matches(Regex("[A-Za-z0-9_.-]+")) && databaseName !in setOf(".", ".."))
        helper = Helper(context.applicationContext, File(context.noBackupFilesDir, databaseName).absolutePath)
        try {
            // All instances in this process share one owner: opening another run is not a crash.
            helper.writableDatabase.update("calls", ContentValues().apply {
                put("status", PluginCallStatus.UNKNOWN.name)
                put("finished_at", clock())
            }, "process_owner!=? AND status=?", arrayOf(PROCESS_OWNER, PluginCallStatus.ADMITTED.name))
        } catch (failure: Throwable) {
            helper.close()
            throw failure
        }
    }

    @Synchronized
    fun recordStart(
        runId: String,
        callId: String,
        pluginId: String,
        connectionId: String,
        toolId: String,
        revision: Long,
    ): String {
        checkOpen()
        require(listOf(runId, callId, pluginId, connectionId, toolId).all { it.isNotBlank() })
        require(revision >= 0)
        val operationId = UUID.randomUUID().toString()
        transaction { db ->
            db.insertOrThrow("calls", null, ContentValues().apply {
                put("operation_id", operationId)
                put("process_owner", PROCESS_OWNER)
                put("run_id", runId)
                put("call_id", callId)
                put("plugin_id", pluginId)
                put("connection_id", connectionId)
                put("tool_id", toolId)
                put("revision", revision)
                put("status", PluginCallStatus.ADMITTED.name)
                put("started_at", clock())
            })
        }
        return operationId
    }

    /** Only ADMITTED -> terminal; retries of the same terminal status are idempotent. */
    @Synchronized
    fun recordFinish(operationId: String, status: PluginCallStatus): Boolean {
        checkOpen()
        require(operationId.isNotBlank())
        require(status != PluginCallStatus.ADMITTED) { "Terminal status required" }
        return transaction { db ->
            val changed = db.update("calls", ContentValues().apply {
                put("status", status.name)
                put("finished_at", clock())
            }, "operation_id=? AND process_owner=? AND status=?",
                arrayOf(operationId, PROCESS_OWNER, PluginCallStatus.ADMITTED.name))
            if (changed == 1) true else {
                db.query("calls", arrayOf("status"), "operation_id=? AND process_owner=?",
                    arrayOf(operationId, PROCESS_OWNER), null, null, null).use {
                    it.moveToFirst() && it.getString(0) == status.name
                }
            }
        }
    }

    /** Newest first, optionally scoped to exactly one connection (including deleted connections). */
    @Synchronized
    fun list(limit: Int = 100, connectionId: String? = null): List<PluginCallRecord> {
        checkOpen()
        require(limit in 1..1000)
        require(connectionId == null || connectionId.isNotBlank())
        return helper.readableDatabase.query(
            "calls", null,
            if (connectionId == null) null else "connection_id=?",
            connectionId?.let { arrayOf(it) }, null, null,
            "started_at DESC, rowid DESC", limit.toString(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(decode(cursor)) } }
    }

    @Synchronized
    override fun close() {
        checkWorker()
        if (!closed) {
            helper.close()
            closed = true
        }
    }

    private fun checkWorker() { check(!isMainThread()) { "PLUGIN_JOURNAL_WORKER_REQUIRED" } }
    private fun checkOpen() {
        checkWorker()
        check(!closed) { "PLUGIN_JOURNAL_CLOSED" }
    }

    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun decode(cursor: Cursor): PluginCallRecord {
        fun text(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
        fun number(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
        val finishedIndex = cursor.getColumnIndexOrThrow("finished_at")
        return PluginCallRecord(
            operationId = text("operation_id"), runId = text("run_id"), callId = text("call_id"),
            pluginId = text("plugin_id"), connectionId = text("connection_id"), toolId = text("tool_id"),
            revision = number("revision"), status = PluginCallStatus.valueOf(text("status")),
            startedAt = number("started_at"),
            finishedAt = if (cursor.isNull(finishedIndex)) null else cursor.getLong(finishedIndex),
        )
    }

    private class Helper(context: Context, path: String) : SQLiteOpenHelper(context, path, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE calls (
                    operation_id TEXT PRIMARY KEY NOT NULL,
                    process_owner TEXT NOT NULL,
                    run_id TEXT NOT NULL,
                    call_id TEXT NOT NULL,
                    plugin_id TEXT NOT NULL,
                    connection_id TEXT NOT NULL,
                    tool_id TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('ADMITTED','UNKNOWN','SUCCEEDED','FAILED','REJECTED')),
                    started_at INTEGER NOT NULL,
                    finished_at INTEGER
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX calls_connection_time ON calls(connection_id, started_at)")
            db.execSQL("CREATE INDEX calls_time ON calls(started_at)")
            db.execSQL("CREATE INDEX calls_owner_status ON calls(process_owner, status)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Unsupported plugin journal migration: $oldVersion -> $newVersion")
        }
    }

    companion object {
        private val PROCESS_OWNER = UUID.randomUUID().toString()
    }
}

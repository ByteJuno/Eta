package io.github.bytejuno.theta.agent.plugin.calendar

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Looper
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-main-process-only private ledger, independent of Room. SQLite serializes writers across
 * helper instances; each CAS is a transaction comparing the complete immutable binding.
 * All methods (including construction/recovery) require a worker thread. close waits for this
 * instance's active method, is idempotent, and never cancels or replays an operation.
 * Stored under noBackupFilesDir: Keystore-bound evidence must not be restored onto another device.
 */
internal class CalendarSqliteOperationLedger(
    context: Context,
    private val codec: CalendarEvidenceCodec = CalendarAesGcmEvidenceCodec(),
    databaseName: String = "calendar_operations_v1.db",
    private val isMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
    private val isAuthoritativeProcess: () -> Boolean = {
        android.app.Application.getProcessName() == context.packageName
    },
) : CalendarOperationLedger, AutoCloseable {
    private val helper: Helper
    private var closed = false

    init {
        check(!isMainThread()) { "CALENDAR_WORKER_REQUIRED" }
        check(isAuthoritativeProcess()) { "CALENDAR_APP_PROCESS_REQUIRED" }
        require(databaseName.matches(Regex("[A-Za-z0-9_.-]+")) && databaseName !in setOf(".", ".."))
        helper = Helper(context.applicationContext, File(context.noBackupFilesDir, databaseName).absolutePath)
        try { recoverInterrupted(PROCESS_OWNER) }
        catch (failure: Exception) { helper.close(); throw failure }
    }

    val processOwner: String get() = PROCESS_OWNER

    @Synchronized
    override fun insertPrepared(operation: CalendarOperation): Boolean {
        checkOpen()
        require(operation.state == CalendarOperationState.PREPARED)
        require(operation.processOwner == PROCESS_OWNER)
        require(operation.operationId.isNotBlank() && operation.runId.isNotBlank())
        require(operation.connectionId.isNotBlank() && operation.tool in CalendarWriteCoordinator.WRITE_TOOLS)
        require(operation.parameterHash.matches(Regex("[a-f0-9]{64}")))
        return transaction { db ->
            db.insertWithOnConflict("operations", null, values(operation), SQLiteDatabase.CONFLICT_IGNORE) != -1L
        }
    }

    @Synchronized
    override fun transition(operation: CalendarOperation, expected: CalendarOperationState,
        next: CalendarOperationState, evidence: CalendarOperationEvidence?): Boolean {
        checkOpen()
        require(operation.processOwner == PROCESS_OWNER)
        require(CalendarOperationTransitions.allows(expected, next))
        require(evidence == null || expected == CalendarOperationState.IN_FLIGHT)
        // Encryption fails closed, before changing any durable state. Never persist plaintext.
        val encrypted = evidence?.let {
            require(it.eventIds.size <= 30 && it.eventIds.all { id -> id.matches(Regex("[0-9]{1,19}")) })
            codec.encrypt(JSONObject().put("ids", JSONArray(it.eventIds)).put("verified", it.verified)
                .toString().toByteArray(Charsets.UTF_8), binding(operation))
        }
        return transaction { db ->
            val stored = read(db, operation.operationId) ?: return@transaction false
            if (stored.state != expected || stored.copy(state = operation.state) != operation) return@transaction false
            db.update("operations", ContentValues().apply {
                put("state", next.name)
                if (encrypted != null) put("evidence", encrypted)
            }, "operation_id=? AND state=?", arrayOf(operation.operationId, expected.name)) == 1
        }
    }

    @Synchronized
    override fun recoverInterrupted(currentProcessOwner: String) {
        checkOpen()
        // Callers cannot invent an owner that marks the current process's active work abandoned.
        require(currentProcessOwner == PROCESS_OWNER)
        transaction { db ->
            db.execSQL("UPDATE operations SET state='UNKNOWN' WHERE process_owner!=? AND state='IN_FLIGHT'",
                arrayOf(currentProcessOwner))
            db.execSQL("UPDATE operations SET state='CANCELLED_NOT_STARTED' WHERE process_owner!=? " +
                "AND state IN ('PREPARED','WAITING_APPROVAL','ADMITTED')", arrayOf(currentProcessOwner))
        }
    }

    @Synchronized
    fun get(operationId: String): CalendarOperation? {
        checkOpen()
        return read(helper.readableDatabase, operationId)
    }

    /** Recovery reads evidence only; unavailable/invalid keys throw, never imply NO_EFFECT. */
    @Synchronized
    fun evidence(operationId: String): CalendarOperationEvidence? {
        checkOpen()
        return transaction { db ->
            val operation = read(db, operationId) ?: return@transaction null
            db.query("operations", arrayOf("evidence"), "operation_id=?", arrayOf(operationId), null, null, null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) return@transaction null
                val json = JSONObject(codec.decrypt(cursor.getBlob(0), binding(operation)).toString(Charsets.UTF_8))
                val ids = json.getJSONArray("ids")
                CalendarOperationEvidence((0 until ids.length()).map(ids::getString), json.getBoolean("verified"))
            }
        }
    }

    /** Bounded metadata page; caller may query UNKNOWN records and fetch encrypted evidence by ID. */
    @Synchronized
    fun list(state: CalendarOperationState, limit: Int = 100): List<CalendarOperation> {
        checkOpen()
        require(limit in 1..200)
        return helper.readableDatabase.query("operations", null, "state=?", arrayOf(state.name), null, null,
            "expires_at ASC, operation_id ASC", limit.toString()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(decode(cursor)) }
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) { helper.close(); closed = true }
    }

    private fun checkOpen() { check(!closed) { "CALENDAR_LEDGER_CLOSED" }; check(!isMainThread()) { "CALENDAR_WORKER_REQUIRED" } }
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try { val result = block(db); db.setTransactionSuccessful(); return result }
        finally { db.endTransaction() }
    }
    private fun read(db: SQLiteDatabase, id: String): CalendarOperation? =
        db.query("operations", null, "operation_id=?", arrayOf(id), null, null, null).use {
            if (it.moveToFirst()) decode(it) else null
        }
    private fun decode(c: Cursor): CalendarOperation {
        fun text(key: String) = c.getString(c.getColumnIndexOrThrow(key))
        fun optional(key: String): String? = c.getColumnIndexOrThrow(key).let { if (c.isNull(it)) null else c.getString(it) }
        return CalendarOperation(text("operation_id"), text("process_owner"), text("run_id"),
            optional("parent_call_id"), optional("child_id"), text("tool"), text("connection_id"),
            c.getLong(c.getColumnIndexOrThrow("generation")), text("parameter_hash"),
            c.getLong(c.getColumnIndexOrThrow("expires_at")), CalendarOperationState.valueOf(text("state")))
    }
    private fun values(o: CalendarOperation) = ContentValues().apply {
        put("operation_id", o.operationId); put("process_owner", o.processOwner); put("run_id", o.runId)
        put("parent_call_id", o.parentCallId); put("child_id", o.childId); put("tool", o.tool)
        put("connection_id", o.connectionId); put("generation", o.generation)
        put("parameter_hash", o.parameterHash); put("expires_at", o.expiresAt); put("state", o.state.name)
    }
    private fun binding(o: CalendarOperation): ByteArray = JSONArray(listOf("calendar-evidence-v1",
        o.operationId, o.processOwner, o.runId, o.parentCallId, o.childId, o.tool, o.connectionId,
        o.generation, o.parameterHash, o.expiresAt)).toString().toByteArray(Charsets.UTF_8)

    private class Helper(context: Context, path: String) : SQLiteOpenHelper(context, path, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE operations (operation_id TEXT PRIMARY KEY NOT NULL, process_owner TEXT NOT NULL, " +
                "run_id TEXT NOT NULL, parent_call_id TEXT, child_id TEXT, tool TEXT NOT NULL, connection_id TEXT NOT NULL, " +
                "generation INTEGER NOT NULL, parameter_hash TEXT NOT NULL, expires_at INTEGER NOT NULL, " +
                "state TEXT NOT NULL, evidence BLOB)")
            db.execSQL("CREATE INDEX operation_state ON operations(state, expires_at)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Unsupported calendar ledger migration: $oldVersion -> $newVersion")
        }
    }

    companion object {
        // One token shared by all helpers/runs in this App process, never one token per run.
        private val PROCESS_OWNER = UUID.randomUUID().toString()
    }
}

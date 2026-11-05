package io.github.bytejuno.theta.agent.plugin

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PluginCallJournalTest {
    @Test
    fun admissionIsDurableBeforeReturningAndTerminalCannotBeOverwritten() = onWorker {
        withDatabase { name, path ->
            open(name).use { journal ->
                val id = journal.start()
                SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT status FROM calls WHERE operation_id=?", arrayOf(id)).use {
                        assertTrue(it.moveToFirst())
                        assertEquals("ADMITTED", it.getString(0))
                    }
                }
                assertEquals(PluginCallStatus.ADMITTED, journal.list().single().status)
                assertNull(journal.list().single().finishedAt)
                assertTrue(journal.recordFinish(id, PluginCallStatus.SUCCEEDED))
                val finished = journal.list().single().finishedAt
                assertNotNull(finished)
                assertTrue(journal.recordFinish(id, PluginCallStatus.SUCCEEDED))
                assertFalse(journal.recordFinish(id, PluginCallStatus.FAILED))
                assertEquals(finished, journal.list().single().finishedAt)
                assertFalse(journal.recordFinish("not-found", PluginCallStatus.REJECTED))
            }
            open(name).use { assertEquals(PluginCallStatus.SUCCEEDED, it.list().single().status) }
        }
    }

    @Test
    fun recoveryMarksOnlyOldOwnerAdmissionsUnknown() = onWorker {
        withDatabase { name, path ->
            val (oldAdmitted, oldFinished, liveAdmitted) = open(name).use { journal ->
                val oldAdmitted = journal.start(callId = "old-admitted")
                val oldFinished = journal.start(callId = "old-finished")
                assertTrue(journal.recordFinish(oldFinished, PluginCallStatus.FAILED))
                val liveAdmitted = journal.start(callId = "live")
                // Another helper in the same process must not mark a live call abandoned.
                open(name).use { second ->
                    assertEquals(PluginCallStatus.ADMITTED, second.list().first { it.operationId == liveAdmitted }.status)
                }
                Triple(oldAdmitted, oldFinished, liveAdmitted)
            }
            SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.update("calls", ContentValues().apply { put("process_owner", "previous-process") },
                    "operation_id IN (?,?)", arrayOf(oldAdmitted, oldFinished))
            }
            open(name).use { journal ->
                val records = journal.list().associateBy { it.operationId }
                assertEquals(PluginCallStatus.UNKNOWN, records.getValue(oldAdmitted).status)
                assertNotNull(records.getValue(oldAdmitted).finishedAt)
                assertEquals(PluginCallStatus.FAILED, records.getValue(oldFinished).status)
                assertEquals(PluginCallStatus.ADMITTED, records.getValue(liveAdmitted).status)
                assertFalse(journal.recordFinish(oldAdmitted, PluginCallStatus.SUCCEEDED))
            }
        }
    }

    @Test
    fun connectionFilterRetainsAttributionWithoutAnyConnectionTable() = onWorker {
        withDatabase { name, path ->
            open(name).use { journal ->
                journal.start(connectionId = "deleted-account-a", callId = "a")
                journal.start(connectionId = "account-b", callId = "b")
                val last = journal.start(connectionId = "deleted-account-a", callId = "c")
                assertTrue(journal.recordFinish(last, PluginCallStatus.REJECTED))
                assertEquals(listOf("c", "a"), journal.list(connectionId = "deleted-account-a").map { it.callId })
                assertEquals("c", journal.list(limit = 1).single().callId)
                assertTrue(journal.list(connectionId = "unknown' OR 1=1 --").isEmpty())
                assertTrue(path.isFile)
                assertEquals(RuntimeEnvironment.getApplication().noBackupFilesDir, path.parentFile)
            }
            // No connection FK: this independent store remains readable after a connection is gone.
            open(name).use { journal ->
                val record = journal.list(connectionId = "deleted-account-a").first()
                assertEquals("run", record.runId)
                assertEquals("plugin", record.pluginId)
                assertEquals("tool", record.toolId)
                assertEquals(4L, record.revision)
            }
        }
    }

    @Test
    fun schemaHasOnlyAttributionAndNoSensitivePayloadColumns() = onWorker {
        withDatabase { name, path ->
            open(name).use { it.start() }
            SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val columns = db.rawQuery("PRAGMA table_info(calls)", null).use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    }
                }
                assertEquals(setOf("operation_id", "process_owner", "run_id", "call_id", "plugin_id",
                    "connection_id", "tool_id", "revision", "status", "started_at", "finished_at"), columns)
                db.rawQuery("PRAGMA foreign_key_list(calls)", null).use { assertFalse(it.moveToFirst()) }
            }
        }
    }

    @Test
    fun allTerminalStatusesAreAcceptedAndInvalidInputsDoNotInsert() = onWorker {
        withDatabase { name, _ ->
            open(name).use { journal ->
                for (status in PluginCallStatus.entries.filter { it != PluginCallStatus.ADMITTED }) {
                    val id = journal.start(callId = status.name)
                    assertTrue(journal.recordFinish(id, status))
                }
                expectFailure<IllegalArgumentException> { journal.recordFinish("id", PluginCallStatus.ADMITTED) }
                expectFailure<IllegalArgumentException> { journal.start(connectionId = "") }
                expectFailure<IllegalArgumentException> { journal.list(limit = 0) }
                assertEquals(4, journal.list().size)
            }
        }
    }

    @Test
    fun closedStoreFailsAdmissionAndRequiresWorkerThread() = onWorker {
        withDatabase { name, _ ->
            val journal = open(name)
            journal.close()
            journal.close()
            expectFailure<IllegalStateException> { journal.start() }
            expectFailure<IllegalStateException> { journal.list() }
            expectFailure<IllegalStateException> {
                PluginCallJournal(RuntimeEnvironment.getApplication(), name,
                    isMainThread = { true }, isAuthoritativeProcess = { true })
            }
            expectFailure<IllegalStateException> {
                PluginCallJournal(RuntimeEnvironment.getApplication(), name,
                    isAuthoritativeProcess = { false })
            }
        }
    }

    private fun open(name: String) = PluginCallJournal(
        RuntimeEnvironment.getApplication(), name,
        clock = { 1234L }, isAuthoritativeProcess = { true },
    )

    private fun PluginCallJournal.start(connectionId: String = "connection", callId: String = "call") =
        recordStart("run", callId, "plugin", connectionId, "tool", 4L)

    private fun withDatabase(block: (String, File) -> Unit) {
        val name = "plugin-test-${UUID.randomUUID()}.db"
        val path = File(RuntimeEnvironment.getApplication().noBackupFilesDir, name)
        try { block(name, path) } finally { SQLiteDatabase.deleteDatabase(path) }
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            if (failure is T) return
            throw failure
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    private fun onWorker(block: () -> Unit) {
        val pool = Executors.newSingleThreadExecutor()
        try { pool.submit(Callable { block() }).get(10, TimeUnit.SECONDS) }
        finally { pool.shutdownNow() }
    }
}

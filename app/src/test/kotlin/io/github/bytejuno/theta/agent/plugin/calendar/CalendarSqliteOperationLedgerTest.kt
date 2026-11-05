package io.github.bytejuno.theta.agent.plugin.calendar

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CalendarSqliteOperationLedgerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val codec = CalendarAesGcmEvidenceCodec { key }
    private fun open(name: String, crypto: CalendarEvidenceCodec = codec) =
        CalendarSqliteOperationLedger(context, crypto, name, { false }, { true })
    private fun operation(store: CalendarSqliteOperationLedger) = CalendarOperation(
        UUID.randomUUID().toString(), store.processOwner, "run", null, null,
        "create_calendar_event", "builtin.calendar", 1, "a".repeat(64), 200_000)
    private fun advance(store: CalendarSqliteOperationLedger, op: CalendarOperation) {
        assertTrue(store.transition(op, CalendarOperationState.PREPARED, CalendarOperationState.WAITING_APPROVAL))
        assertTrue(store.transition(op, CalendarOperationState.WAITING_APPROVAL, CalendarOperationState.ADMITTED))
        assertTrue(store.transition(op, CalendarOperationState.ADMITTED, CalendarOperationState.IN_FLIGHT))
    }

    @Test fun persistsAcrossCloseAndEncryptsEvidence() {
        val name = "${UUID.randomUUID()}.db"
        val op = open(name).use { store ->
            val o = operation(store)
            assertTrue(store.insertPrepared(o)); advance(store, o)
            assertTrue(store.transition(o, CalendarOperationState.IN_FLIGHT, CalendarOperationState.SUCCEEDED,
                CalendarOperationEvidence(listOf("123456789"), true)))
            o
        }
        open(name).use { reopened ->
            assertEquals(CalendarOperationState.SUCCEEDED, reopened.get(op.operationId)?.state)
            assertEquals(listOf("123456789"), reopened.evidence(op.operationId)?.eventIds)
        }
        SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT evidence FROM operations", null).use {
                assertTrue(it.moveToFirst())
                assertFalse(it.getBlob(0).toString(Charsets.UTF_8).contains("123456789"))
            }
            db.rawQuery("PRAGMA table_info(operations)", null).use { c ->
                val columns = mutableListOf<String>()
                while (c.moveToNext()) columns += c.getString(c.getColumnIndexOrThrow("name"))
                assertFalse(columns.any { it.contains("arguments") || it.contains("plan") })
            }
        }
    }

    @Test fun casBindsOperationAndHasOneWinnerAcrossHelpers() {
        val name = "${UUID.randomUUID()}.db"
        open(name).use { first -> open(name).use { second ->
            val op = operation(first)
            assertTrue(first.insertPrepared(op)); assertFalse(second.insertPrepared(op))
            assertFalse(second.transition(op.copy(runId = "forged"), CalendarOperationState.PREPARED,
                CalendarOperationState.WAITING_APPROVAL))
            val pool = Executors.newFixedThreadPool(2)
            try {
                val results = pool.invokeAll(listOf(first, second).map { store -> Callable {
                    store.transition(op, CalendarOperationState.PREPARED, CalendarOperationState.WAITING_APPROVAL)
                } }).map { it.get() }
                assertEquals(1, results.count { it })
            } finally { pool.shutdownNow() }
        } }
    }

    @Test fun sameProcessReopenDoesNotRecoverActiveRunButOldOwnerBecomesUnknown() {
        val name = "${UUID.randomUUID()}.db"
        val op = open(name).use { store -> operation(store).also { store.insertPrepared(it); advance(store, it) } }
        open(name).use { store ->
            assertEquals(CalendarOperationState.IN_FLIGHT, store.get(op.operationId)?.state)
            assertThrows(IllegalArgumentException::class.java) { store.recoverInterrupted("invented-owner") }
        }
        // Fault injection simulates the previous App process, without changing production owner injection.
        SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE operations SET process_owner='dead-process'")
        }
        open(name).use { store ->
            assertEquals(CalendarOperationState.UNKNOWN, store.get(op.operationId)?.state)
            store.recoverInterrupted(store.processOwner)
            assertEquals(1, store.list(CalendarOperationState.UNKNOWN).size)
        }
    }

    @Test fun recoveryCancelsOldUnstartedOperationsWithoutReplaying() {
        val name = "${UUID.randomUUID()}.db"
        val ids = open(name).use { store ->
            (0..2).map { index ->
                val op = operation(store); store.insertPrepared(op)
                if (index >= 1) store.transition(op, CalendarOperationState.PREPARED, CalendarOperationState.WAITING_APPROVAL)
                if (index >= 2) store.transition(op, CalendarOperationState.WAITING_APPROVAL, CalendarOperationState.ADMITTED)
                op.operationId
            }
        }
        SQLiteDatabase.openDatabase(File(context.noBackupFilesDir, name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE operations SET process_owner='dead-process'")
        }
        open(name).use { store ->
            ids.forEach { assertEquals(CalendarOperationState.CANCELLED_NOT_STARTED, store.get(it)?.state) }
        }
    }

    @Test fun encryptionFailureDoesNotCommitTerminalState() {
        val name = "${UUID.randomUUID()}.db"
        val broken = object : CalendarEvidenceCodec {
            override fun encrypt(plain: ByteArray, binding: ByteArray): ByteArray = error("keystore unavailable")
            override fun decrypt(encrypted: ByteArray, binding: ByteArray): ByteArray = error("keystore unavailable")
        }
        open(name, broken).use { store ->
            val op = operation(store); store.insertPrepared(op); advance(store, op)
            assertThrows(IllegalStateException::class.java) {
                store.transition(op, CalendarOperationState.IN_FLIGHT, CalendarOperationState.SUCCEEDED,
                    CalendarOperationEvidence(listOf("42"), true))
            }
            assertEquals(CalendarOperationState.IN_FLIGHT, store.get(op.operationId)?.state)
        }
    }

    @Test fun ciphertextCannotBeMovedOrModified() {
        val encoded = codec.encrypt("secret".toByteArray(), "op1".toByteArray())
        assertEquals("secret", codec.decrypt(encoded, "op1".toByteArray()).toString(Charsets.UTF_8))
        assertThrows(Exception::class.java) { codec.decrypt(encoded, "op2".toByteArray()) }
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { codec.decrypt(encoded, "op1".toByteArray()) }
    }

    @Test fun closedLedgerDoesNotReopenAndAuxiliaryProcessCannotRecover() {
        val name = "${UUID.randomUUID()}.db"
        val store = open(name)
        store.close(); store.close()
        assertThrows(IllegalStateException::class.java) { store.get("anything") }
        assertThrows(IllegalStateException::class.java) {
            CalendarSqliteOperationLedger(context, codec, name, { false }, { false })
        }
    }
}

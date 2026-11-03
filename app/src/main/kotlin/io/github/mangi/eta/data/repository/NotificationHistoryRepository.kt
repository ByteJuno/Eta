package io.github.mangi.eta.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** 仅在用户授予通知访问后保存有限期通知，内容不进入 Agent 会话持久记录。 */
internal class NotificationHistoryRepository(context: Context) {
    private val database = Database(context.applicationContext)

    /**
     * Chat apps (WeChat in particular) keep one notification per conversation and keep
     * replacing its text with the latest message. Replacing the stored row would lose every
     * earlier message, so non-ongoing notifications append a row whenever the visible content
     * changes. Ongoing/foreground notifications (step counters, players, progress) keep a single
     * row that updates in place so they do not flood the history.
     */
    fun record(
        key: String,
        packageName: String,
        title: String?,
        text: String?,
        subText: String?,
        postedAt: Long,
        updatesInPlace: Boolean = true,
    ) {
        if (title.isNullOrBlank() && text.isNullOrBlank() && subText.isNullOrBlank()) return
        val boundedKey = key.take(MAX_KEY_CHARS)
        val values = ContentValues().apply {
            put("notification_key", boundedKey)
            put("package_name", packageName.take(MAX_PACKAGE_CHARS))
            put("title", title.bounded())
            put("text", text.bounded())
            put("sub_text", subText.bounded())
            put("posted_at", postedAt)
            put("content_hash", contentHash(title.bounded(), text.bounded(), subText.bounded()))
        }
        database.writableDatabase.transaction {
            if (updatesInPlace) {
                delete(TABLE, "notification_key=?", arrayOf(boundedKey))
                insert(TABLE, null, values)
            } else {
                // Identical re-posts (and the listener's reconnect snapshot) are deduplicated.
                insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            delete(TABLE, "posted_at<?", arrayOf((System.currentTimeMillis() - RETENTION_MS).toString()))
            execSQL(
                "DELETE FROM $TABLE WHERE id NOT IN " +
                    "(SELECT id FROM $TABLE ORDER BY posted_at DESC, id DESC LIMIT $MAX_RECORDS)",
            )
        }
    }

    fun search(query: String, packageName: String, maxAgeHours: Int, limit: Int): String {
        val clauses = mutableListOf("posted_at>=?")
        val args = mutableListOf((System.currentTimeMillis() - maxAgeHours * HOUR_MS).toString())
        if (packageName.isNotBlank()) {
            clauses += "package_name=?"
            args += packageName
        }
        if (query.isNotBlank()) {
            clauses += "(title LIKE ? ESCAPE '\\' OR text LIKE ? ESCAPE '\\' OR sub_text LIKE ? ESCAPE '\\')"
            val pattern = "%${query.escapeLike()}%"
            repeat(3) { args += pattern }
        }
        val items = JSONArray()
        database.readableDatabase.query(
            TABLE,
            arrayOf("package_name", "title", "text", "sub_text", "posted_at"),
            clauses.joinToString(" AND "),
            args.toTypedArray(),
            null,
            null,
            "posted_at DESC, id DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items.put(
                    JSONObject()
                        .put("package_name", cursor.getString(0))
                        .put("title", cursor.getString(1))
                        .put("text", cursor.getString(2))
                        .put("sub_text", cursor.getString(3))
                        .put("posted_at", cursor.getLong(4)),
                )
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "search_notification_history")
            .put("items", items)
            .put("count", items.length())
            .put("retention_days", RETENTION_DAYS)
            .put("truncated", items.length() == limit)
            .toString()
    }

    private fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun String?.bounded(): String? = this?.take(MAX_FIELD_CHARS)

    private fun String.escapeLike(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun contentHash(title: String?, text: String?, subText: String?): String =
        MessageDigest.getInstance("SHA-256")
            .digest(listOf(title, text, subText).joinToString("\u0000") { it.orEmpty() }.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class Database(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) = createSchema(db)

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                // v1 kept one row per notification key; existing rows stay unique by key.
                db.execSQL("ALTER TABLE $TABLE RENAME TO ${TABLE}_v1")
                db.execSQL("DROP INDEX IF EXISTS notification_history_posted_at")
                createSchema(db)
                db.execSQL(
                    "INSERT INTO $TABLE (notification_key,package_name,title,text,sub_text,posted_at,content_hash) " +
                        "SELECT notification_key,package_name,title,text,sub_text,posted_at,'v1:'||notification_key " +
                        "FROM ${TABLE}_v1",
                )
                db.execSQL("DROP TABLE ${TABLE}_v1")
            }
        }

        private fun createSchema(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "notification_key TEXT NOT NULL," +
                    "package_name TEXT NOT NULL," +
                    "title TEXT,text TEXT,sub_text TEXT,posted_at INTEGER NOT NULL," +
                    "content_hash TEXT NOT NULL)",
            )
            db.execSQL("CREATE UNIQUE INDEX notification_history_content ON $TABLE(notification_key, content_hash)")
            db.execSQL("CREATE INDEX notification_history_posted_at ON $TABLE(posted_at DESC)")
        }
    }

    private companion object {
        const val DATABASE_NAME = "eta_notification_history.db"
        const val TABLE = "notification_history"
        const val VERSION = 2
        const val MAX_RECORDS = 6_000
        const val RETENTION_DAYS = 7
        const val HOUR_MS = 60L * 60 * 1_000
        const val RETENTION_MS = RETENTION_DAYS * 24L * HOUR_MS
        const val MAX_FIELD_CHARS = 4_000
        const val MAX_KEY_CHARS = 1_000
        const val MAX_PACKAGE_CHARS = 255
    }
}

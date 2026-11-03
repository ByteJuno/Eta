package io.github.mangi.eta.data.repository

import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NotificationHistoryRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: NotificationHistoryRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("eta_notification_history.db")
        repository = NotificationHistoryRepository(context)
    }

    @After
    fun tearDown() {
        context.deleteDatabase("eta_notification_history.db")
    }

    @Test
    fun `query and package filters only return matching notification`() {
        val now = System.currentTimeMillis()
        repository.record("one", "com.example.food", "订单配送中", "骑手即将送达", null, now)
        repository.record("two", "com.example.chat", "新消息", "今晚见", null, now - 1)

        val result = JSONObject(repository.search("骑手", "com.example.food", 24, 20))

        assertEquals(1, result.getInt("count"))
        assertEquals("com.example.food", result.getJSONArray("items").getJSONObject(0).getString("package_name"))
    }

    @Test
    fun `same notification key replaces prior content and expired records are excluded`() {
        val now = System.currentTimeMillis()
        repository.record("same", "com.example.food", "旧状态", null, null, now - 1)
        repository.record("same", "com.example.food", "已送达", null, null, now)
        repository.record("expired", "com.example.food", "很久以前", null, null, now - 8L * 24 * 60 * 60 * 1_000)

        val serialized = repository.search("", "", 168, 20)
        val result = JSONObject(serialized)

        assertEquals(1, result.getInt("count"))
        assertFalse(serialized.contains("旧状态"))
        assertFalse(serialized.contains("很久以前"))
    }

    @Test
    fun `chat updates on the same key keep earlier messages and drop identical reposts`() {
        val now = System.currentTimeMillis()
        repository.record("chat", "com.tencent.mm", "干饭团伙", "[1条]小王: 几点下班", null, now - 2, updatesInPlace = false)
        repository.record("chat", "com.tencent.mm", "干饭团伙", "[2条]小王: 今晚吃啥", null, now - 1, updatesInPlace = false)
        repository.record("chat", "com.tencent.mm", "干饭团伙", "[2条]小王: 今晚吃啥", null, now, updatesInPlace = false)

        val result = JSONObject(repository.search("", "com.tencent.mm", 24, 20))

        assertEquals(2, result.getInt("count"))
        val items = result.getJSONArray("items")
        assertEquals("[2条]小王: 今晚吃啥", items.getJSONObject(0).getString("text"))
        assertEquals("[1条]小王: 几点下班", items.getJSONObject(1).getString("text"))
    }

    @Test
    fun `ongoing status notifications keep a single latest row`() {
        val now = System.currentTimeMillis()
        (1..5).forEach { step ->
            repository.record("steps", "com.huawei.health", "${step * 100} 步", null, null, now - 10 + step)
        }

        val serialized = repository.search("", "com.huawei.health", 24, 20)

        assertEquals(1, JSONObject(serialized).getInt("count"))
        assertTrue(serialized.contains("500 步"))
    }

    @Test
    fun `version one database migrates without losing rows`() {
        context.deleteDatabase("eta_notification_history.db")
        val now = System.currentTimeMillis()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("eta_notification_history.db"), null).use { db ->
            db.execSQL("CREATE TABLE notification_history (notification_key TEXT PRIMARY KEY NOT NULL,package_name TEXT NOT NULL,title TEXT,text TEXT,sub_text TEXT,posted_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX notification_history_posted_at ON notification_history(posted_at DESC)")
            db.execSQL("INSERT INTO notification_history VALUES ('old','com.example.food','旧订单','已送达',NULL,$now)")
            db.version = 1
        }

        val migrated = NotificationHistoryRepository(context)
        migrated.record("old", "com.example.food", "旧订单", "已签收", null, now + 1, updatesInPlace = false)
        val result = JSONObject(migrated.search("", "com.example.food", 24, 20))

        assertEquals(2, result.getInt("count"))
        assertEquals("已签收", result.getJSONArray("items").getJSONObject(0).getString("text"))
        assertEquals("已送达", result.getJSONArray("items").getJSONObject(1).getString("text"))
    }
}

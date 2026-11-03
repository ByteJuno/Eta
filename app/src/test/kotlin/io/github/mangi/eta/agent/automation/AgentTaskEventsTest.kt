package io.github.mangi.eta.agent.automation

import android.content.Context
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.EtaDatabase
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// 与调度器测试一致：不启动 EtaApp，避免启动时的自动任务恢复干扰夹具。
@Config(sdk = [36], application = android.app.Application::class)
class AgentTaskEventsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val dao get() = EtaDatabase.get(context).agentTaskDao()

    @After
    fun tearDown(): Unit = runBlocking {
        AgentTaskScheduler.jobFinished()
        dao.tasks().forEach { dao.remove(it.id) }
        AgentTaskScheduler.refresh(context)
        EtaDatabase.closeForTests()
    }

    @Test
    fun eventFiltersSelectMatchingTasksAndKeepOnlyWhitelistedFields() = runBlocking {
        dao.insertTask(task("app-a", trigger("app_foreground", filters = mapOf("packageName" to "com.a"))))
        dao.insertTask(task("app-b", trigger("app_foreground", filters = mapOf("packageName" to "com.b"))))
        dao.insertTask(task("app-off", trigger("app_foreground"), enabled = false))

        AgentTaskScheduler.publish(
            context,
            "app_foreground",
            "window-1",
            JSONObject().put("packageName", "com.a").put("title", "secret title")
        )
        drainEvents()

        val runs = dao.runs("app-a", 10)
        assertEquals(1, runs.size)
        assertEquals("app_foreground:window-1", runs.single().fireKey)
        val event = JSONObject(runs.single().eventJson)
        assertEquals("com.a", event.getString("packageName"))
        assertTrue("事件快照不应携带通知标题等原始内容", !event.has("title"))
        assertTrue(dao.runs("app-b", 10).isEmpty())
        assertTrue(dao.runs("app-off", 10).isEmpty())
    }

    @Test
    fun taskResultEventsRequireSourceTaskAndSkipTheSourceItself() = runBlocking {
        dao.insertTask(task("source", trigger("task_completed", filters = mapOf("taskId" to "source"))))
        dao.insertTask(task("follower", trigger("task_completed", filters = mapOf("taskId" to "source"))))
        dao.insertTask(task("global", trigger("task_completed")))

        AgentTaskScheduler.publish(
            context,
            "task_completed",
            "run-1",
            JSONObject().put("taskId", "source")
        )
        drainEvents()

        assertEquals(1, dao.runs("follower", 10).size)
        assertTrue("来源任务不能被自身结果再次触发", dao.runs("source", 10).isEmpty())
        assertTrue("未指定 filters.taskId 的任务结果触发会形成循环", dao.runs("global", 10).isEmpty())
    }

    @Test
    fun eventIsIgnoredWhenConditionsDoNotHoldOrRunIsAlreadyActive() = runBlocking {
        dao.insertTask(task("guarded", trigger("screen_on", conditions = unmetConditions())))
        dao.insertTask(task("busy", trigger("screen_on")))

        AgentTaskScheduler.publish(context, "screen_on", "first")
        AgentTaskScheduler.publish(context, "screen_on", "second")
        drainEvents()

        assertTrue(dao.runs("guarded", 10).isEmpty())
        // 第一条事件已排队，第二条在已有活动运行时不再重复排队。
        assertEquals(listOf("screen_on:first"), dao.runs("busy", 10).map { it.fireKey })
    }

    @Test
    fun unknownEventTypesAreDroppedBeforeReachingTheQueue() = runBlocking {
        dao.insertTask(task("location", trigger("screen_on")))
        AgentTaskScheduler.publish(context, "location_enter", "home")
        drainEvents()
        assertTrue(dao.runs("location", 10).isEmpty())
    }

    @Test
    fun dueTimerQueuesOnlyWhenConditionsHoldAndKeepsDueTimeOtherwise() = runBlocking {
        val now = System.currentTimeMillis()
        val due = now - 1_000
        dao.insertTask(task("due-ready", onceAt(due), nextRunAt = due))
        dao.insertTask(task("due-guarded", onceAt(due, unmetConditions()), nextRunAt = due))
        dao.insertTask(task("not-due", onceAt(now + 3_600_000), nextRunAt = now + 3_600_000))

        AgentTaskScheduler.enqueueDue(context, now)

        assertEquals(listOf("time:$due"), dao.runs("due-ready", 10).map { it.fireKey })
        assertEquals(null, dao.task("due-ready")!!.nextRunAt)
        assertTrue(dao.runs("due-guarded", 10).isEmpty())
        assertEquals("条件未满足时保留原定时点用于去重", due, dao.task("due-guarded")!!.nextRunAt)
        assertTrue(dao.runs("not-due", 10).isEmpty())
    }

    /** 事件线程按顺序处理；哨兵任务入队后，之前发布的事件都已处理完。 */
    private suspend fun drainEvents() {
        dao.insertTask(task(SENTINEL, trigger("battery_low")))
        AgentTaskScheduler.publish(context, "battery_low", "sentinel-${System.nanoTime()}")
        val deadline = System.currentTimeMillis() + 10_000
        while (dao.runs(SENTINEL, 1).isEmpty()) {
            if (System.currentTimeMillis() > deadline) fail("事件线程未在 10 秒内处理完成")
            Thread.sleep(20)
        }
    }

    // Robolectric 默认未充电且未锁屏；两个条件同时取反，确保不依赖单一影子状态。
    private fun unmetConditions() = JSONObject().put("charging", true).put("unlocked", false)

    private fun trigger(
        type: String,
        filters: Map<String, String> = emptyMap(),
        conditions: JSONObject? = null,
    ) = JSONObject().put("type", type).apply {
        if (filters.isNotEmpty()) put("filters", JSONObject(filters))
        if (conditions != null) put("conditions", conditions)
    }

    private fun onceAt(at: Long, conditions: JSONObject? = null) = JSONObject()
        .put("type", "once")
        .put("at", Instant.ofEpochMilli(at).toString())
        .apply { if (conditions != null) put("conditions", conditions) }

    private fun task(
        id: String,
        rule: JSONObject,
        enabled: Boolean = true,
        nextRunAt: Long? = null,
    ) = AgentTaskEntity(
        id = id,
        name = id,
        prompt = "Return the test result",
        triggerJson = rule.toString(),
        enabled = enabled,
        nextRunAt = nextRunAt,
        cooldownSeconds = 0,
        maxRuns = 5,
        createdAt = 0,
        updatedAt = 0,
    )

    private companion object {
        const val SENTINEL = "event-sentinel"
    }
}

package io.github.mangi.eta.agent.automation

import android.content.Context
import io.github.mangi.eta.data.db.AgentTaskRunEntity
import io.github.mangi.eta.data.db.EtaDatabase
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
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
// 与调度器测试一致：不启动 EtaApp，避免启动时的自动任务恢复干扰夹具。
@Config(sdk = [36], application = android.app.Application::class)
class AgentTaskToolsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val dao get() = EtaDatabase.get(context).agentTaskDao()
    private val tools get() = AgentTaskTools(context)

    @After
    fun tearDown(): Unit = runBlocking {
        AgentTaskScheduler.jobFinished()
        dao.tasks().forEach { dao.remove(it.id) }
        AgentTaskScheduler.refresh(context)
        EtaDatabase.closeForTests()
    }

    @Test
    fun createRejectsUnknownFieldsPastTimesAndInvalidTriggers() {
        val unknown = call("tasks_create", create().put("priority", 1))
        assertRejected(unknown, "任务包含不支持的字段")
        val past = call("tasks_create", create(at = Instant.now().minusSeconds(60)))
        assertRejected(past, "首次时间需要在未来")
        val shortInterval = call(
            "tasks_create",
            create().put("trigger", JSONObject().put("type", "interval").put("seconds", 60)),
        )
        assertFalse(shortInterval.getBoolean("ok"))
        val blankName = call("tasks_create", create().put("name", "   "))
        assertFalse(blankName.getBoolean("ok"))
        assertTrue(runBlocking { dao.tasks() }.isEmpty())
    }

    @Test
    fun createPersistsTaskWithOnceDefaultsAndListReturnsIt() {
        val created = call("tasks_create", create())
        assertTrue(created.toString(), created.getBoolean("ok"))
        assertEquals(1, created.getInt("maxRuns"))
        assertEquals(900L, created.getLong("cooldownSeconds"))
        val listed = call("tasks_list", JSONObject()).getJSONArray("items")
        assertEquals(1, listed.length())
        assertEquals(created.getString("taskId"), listed.getJSONObject(0).getString("taskId"))
    }

    @Test
    fun updateRequiresCurrentRevisionAndKeepsRunCount() {
        val taskId = call("tasks_create", create()).getString("taskId")
        val stored = runBlocking { requireNotNull(dao.task(taskId)) }
        val stale = call(
            "tasks_update",
            JSONObject().put("taskId", taskId).put("expectedUpdatedAt", stored.updatedAt - 1)
                .put("name", "Stale edit"),
        )
        assertRejected(stale, "任务已变化，请 tasks_list 后重试")
        val renamed = call(
            "tasks_update",
            JSONObject().put("taskId", taskId).put("expectedUpdatedAt", stored.updatedAt)
                .put("name", "Renamed"),
        )
        assertTrue(renamed.toString(), renamed.getBoolean("ok"))
        assertEquals("Renamed", renamed.getString("name"))
        assertTrue(renamed.getLong("updatedAt") > stored.updatedAt)
    }

    @Test
    fun disablingTaskCancelsQueuedRun() = runBlocking {
        val taskId = call("tasks_create", create()).getString("taskId")
        assertTrue(call("tasks_run", JSONObject().put("taskId", taskId)).getBoolean("ok"))
        val queued = dao.runs(taskId, 10).single()
        assertEquals("queued", queued.status)
        val current = requireNotNull(dao.task(taskId))
        val disabled = call(
            "tasks_update",
            JSONObject().put("taskId", taskId).put("expectedUpdatedAt", current.updatedAt)
                .put("enabled", false),
        )
        assertTrue(disabled.toString(), disabled.getBoolean("ok"))
        assertEquals("cancelled", dao.runs(taskId, 10).single().status)
        assertNull(dao.nextQueued())
    }

    @Test
    fun manualRunIsRejectedWhileAnotherRunIsActiveOrRunsAreExhausted() = runBlocking {
        val taskId = call("tasks_create", create()).getString("taskId")
        assertTrue(call("tasks_run", JSONObject().put("taskId", taskId)).getBoolean("ok"))
        assertRejected(call("tasks_run", JSONObject().put("taskId", taskId)), "任务已有运行，或已达到次数上限")
        val run = dao.runs(taskId, 10).single()
        dao.complete(run, "completed", System.currentTimeMillis(), "done")
        val finished = requireNotNull(dao.task(taskId))
        assertEquals(1, finished.runCount)
        assertFalse("单次任务达到上限后应自动停用", finished.enabled)
        assertRejected(call("tasks_run", JSONObject().put("taskId", taskId)), "任务已有运行，或已达到次数上限")
    }

    @Test
    fun configurationRequiredDoesNotConsumeRunBudget() = runBlocking {
        val taskId = call("tasks_create", create()).getString("taskId")
        assertTrue(call("tasks_run", JSONObject().put("taskId", taskId)).getBoolean("ok"))
        dao.complete(dao.runs(taskId, 10).single(), "configuration_required", System.currentTimeMillis(), "")
        val task = requireNotNull(dao.task(taskId))
        assertEquals(0, task.runCount)
        assertTrue(task.enabled)
        assertEquals("configuration_required", task.lastStatus)
    }

    @Test
    fun deleteRemovesTaskAndItsRunHistory() = runBlocking {
        val taskId = call("tasks_create", create()).getString("taskId")
        dao.insertRun(AgentTaskRunEntity("history-run", taskId, "manual:history", status = "completed", queuedAt = 1))
        assertNotNull(dao.task(taskId))
        val deleted = call("tasks_delete", JSONObject().put("taskId", taskId))
        assertTrue(deleted.toString(), deleted.getBoolean("ok"))
        assertNull(dao.task(taskId))
        assertTrue(dao.runs(taskId, 10).isEmpty())
        assertRejected(call("tasks_delete", JSONObject().put("taskId", taskId)), "任务不存在")
    }

    private fun call(name: String, args: JSONObject) = JSONObject(tools.execute(name, args))

    private fun assertRejected(result: JSONObject, message: String) {
        assertFalse(result.toString(), result.getBoolean("ok"))
        assertEquals("TASK_REQUEST_REJECTED", result.getString("code"))
        assertEquals(message, result.getString("message"))
    }

    private fun create(at: Instant = Instant.now().plusSeconds(3_600)) = JSONObject()
        .put("name", "Morning summary")
        .put("prompt", "Summarize today")
        .put("trigger", JSONObject().put("type", "once").put("at", at.toString()))
}

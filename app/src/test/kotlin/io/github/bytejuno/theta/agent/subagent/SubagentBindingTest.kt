package io.github.bytejuno.theta.agent.subagent

import io.github.bytejuno.theta.agent.memory.AgentMemoryContext
import io.github.bytejuno.theta.agent.model.AgentModelClient
import io.github.bytejuno.theta.agent.runtime.AgentRunCancelledException
import io.github.bytejuno.theta.agent.runtime.AgentRunController
import io.github.bytejuno.theta.agent.skill.SkillContext
import io.github.bytejuno.theta.agent.tool.AgentToolCapabilities
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubagentBindingTest {
    @Test
    fun changedAccountOrRevisionRefusesHistoryBeforeConfigOrChildExecutor() {
        for (current in listOf("account-b-revision-1", "account-a-revision-2", null)) {
            assertResumeBinding("account-a-revision-1", current, hasRemoteTools = true, allowed = false)
        }
    }

    @Test
    fun legacyHistoryCannotResumeAgainstRemoteCatalogEvenWithoutCurrentFingerprint() {
        for (current in listOf(null, "", "new-binding")) {
            assertResumeBinding(null, current, hasRemoteTools = true, allowed = false)
        }
        assertResumeBinding("", null, hasRemoteTools = true, allowed = false)
    }

    @Test
    fun matchingBindingAndLegacyLocalOnlyHistoryRemainResumable() {
        assertResumeBinding("binding-v1", "binding-v1", hasRemoteTools = true, allowed = true)
        assertResumeBinding(null, null, hasRemoteTools = false, allowed = true)
        // Removing a binding also invalidates history, even when the remote catalog is now empty.
        assertResumeBinding("binding-v1", null, hasRemoteTools = false, allowed = false)
    }

    @Test
    fun fingerprintSurvivesHistoryUpdatesAndRunningRelease() {
        val key = UUID.randomUUID().toString()
        val entry = SubagentSessionStore.create(key, SubagentProfile.EXPLORE, "binding-v1")
        val history = listOf(AgentModelClient.buildUserHistoryMessage("old context", emptyList()))
        SubagentSessionStore.complete(key, entry.subagentId, history)
        SubagentSessionStore.releaseRunning(key, entry.subagentId)
        val restored = SubagentSessionStore.get(key, entry.subagentId)!!
        assertEquals("binding-v1", restored.toolBindingFingerprint)
        assertSame(history, restored.history)
        assertFalse(restored.running.get())
    }

    @Test
    fun concurrentSiblingToolsKeepChildCancellationAcrossWorkerThreads() {
        val parent = AgentRunController()
        val first = AgentRunController()
        val second = AgentRunController()
        val bindings = listOf(parent.register(first::cancel), parent.register(second::cancel))
        val started = CountDownLatch(2)
        val releaseSecond = CountDownLatch(1)
        val firstCancelled = AtomicBoolean(false)
        val secondCancelled = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(2)
        val sharedClosed = AtomicBoolean(false)
        val sharedLocal = object : AgentModelClient.ToolExecutor, AutoCloseable {
            override fun execute(toolCall: AgentModelClient.ToolCall) = AgentModelClient.ToolResult("local")
            override fun close() { sharedClosed.set(true) }
        }
        try {
            val executor = executor(
                parent = parent,
                base = sharedLocal,
                factory = { child, childId, parentCallId ->
                    val isFirst = child === first
                    assertEquals(if (isFirst) "sub-first" else "sub-second", childId)
                    assertEquals(if (isFirst) "call-first" else "call-second", parentCallId)
                    AgentModelClient.ToolExecutor {
                        val released = if (isFirst) CountDownLatch(1) else releaseSecond
                        val cancelled = if (isFirst) firstCancelled else secondCancelled
                        val binding = child.register {
                            cancelled.set(true)
                            released.countDown()
                        }
                        try {
                            started.countDown()
                            assertTrue(released.await(5, TimeUnit.SECONDS))
                            child.throwIfCancelled()
                            AgentModelClient.ToolResult("second completed")
                        } finally {
                            binding.close()
                        }
                    }
                },
            )
            // Construct on this thread, then dispatch on batch-style workers. No thread-local context.
            val firstTools = executor.createChildToolExecutor(SubagentProfile.EXPLORE, first, "sub-first", "call-first")
            val secondTools = executor.createChildToolExecutor(SubagentProfile.EXPLORE, second, "sub-second", "call-second")
            val firstResult = pool.submit<Boolean> {
                try {
                    firstTools.execute(AgentModelClient.ToolCall("first", "read_file", "{}"))
                    false
                } catch (_: AgentRunCancelledException) {
                    true
                }
            }
            val secondResult = pool.submit<AgentModelClient.ToolResult> {
                secondTools.execute(AgentModelClient.ToolCall("second", "read_file", "{}"))
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            first.cancel()
            assertTrue(firstResult.get(5, TimeUnit.SECONDS))
            assertTrue(firstCancelled.get())
            assertFalse(parent.isCancelled)
            assertFalse(second.isCancelled)
            assertFalse(secondCancelled.get())
            assertFalse(secondResult.isDone)
            releaseSecond.countDown()
            assertEquals("second completed", secondResult.get(5, TimeUnit.SECONDS).content)
            assertFalse(sharedClosed.get())
            parent.cancel()
            assertTrue(second.isCancelled)
            assertFalse(sharedClosed.get())
        } finally {
            releaseSecond.countDown()
            parent.cancel()
            bindings.forEach { it.close() }
            pool.shutdownNow()
        }
    }

    @Test
    fun defaultExecutorStillDelegatesAndProfileFilterStillApplies() {
        var calls = 0
        val executor = executor(base = AgentModelClient.ToolExecutor {
            calls++
            AgentModelClient.ToolResult("shared local")
        })
        val tools = executor.createChildToolExecutor(SubagentProfile.EXPLORE, AgentRunController(), "session", "op")
        assertEquals("shared local", tools.execute(AgentModelClient.ToolCall("allowed", "read_file", "{}")).content)
        val rejected = tools.execute(AgentModelClient.ToolCall("denied", "tap", "{}"))
        assertEquals("SUBAGENT_TOOL_FORBIDDEN", JSONObject(rejected.content).getString("code"))
        assertEquals(1, calls)
    }

    @Test
    fun childFactoryFailureReleasesResumeState() {
        val childResourceClosed = AtomicBoolean(false)
        val parent = AgentRunController()
        val key = UUID.randomUUID().toString()
        val entry = SubagentSessionStore.create(key, SubagentProfile.EXPLORE, "binding-v1")
        SubagentSessionStore.complete(key, entry.subagentId, emptyList())
        val executor = executor(
            key = key,
            fingerprint = "binding-v1",
            config = {
                AgentModelClient.ModelConfig(
                    baseUrl = "https://example.invalid/v1",
                    apiKey = "test-key",
                    model = "test-model",
                    systemPrompt = "",
                )
            },
            parent = parent,
            factory = { child, childId, parentCallId ->
                assertEquals(entry.subagentId, childId)
                assertEquals("resume-call", parentCallId)
                child.register { childResourceClosed.set(true) }
                error("factory failed")
            },
        )
        val result = executor.execute(1, resumeCall(entry.subagentId))
        assertEquals("SUBAGENT_FAILED", JSONObject(result.content).getString("code"))
        assertFalse(entry.running.get())
        assertTrue(childResourceClosed.get())
        assertFalse(parent.isCancelled)
    }

    private fun assertResumeBinding(stored: String?, current: String?, hasRemoteTools: Boolean, allowed: Boolean) {
        val key = UUID.randomUUID().toString()
        val entry = SubagentSessionStore.create(key, SubagentProfile.EXPLORE, stored)
        val history = listOf(AgentModelClient.buildUserHistoryMessage("private old history", emptyList()))
        SubagentSessionStore.complete(key, entry.subagentId, history)
        var configReached = false
        val result = executor(
            key = key,
            fingerprint = current,
            remote = if (hasRemoteTools) JSONArray().put(JSONObject().put("type", "function")) else JSONArray(),
            config = { configReached = true; error("stop before model execution") },
            factory = { _, _, _ -> error("must not execute") },
        ).execute(1, resumeCall(entry.subagentId))
        assertEquals(allowed, configReached)
        assertEquals(
            if (allowed) "SUBAGENT_CONFIG_ERROR" else "SUBAGENT_TOOL_BINDING_MISMATCH",
            JSONObject(result.content).getString("code"),
        )
        assertFalse(result.content.contains("private old history"))
        assertFalse(entry.running.get())
        assertSame(history, entry.history)
    }

    private fun resumeCall(id: String) = AgentModelClient.ToolCall(
        "resume-call", "subagent",
        JSONObject().put("resume", id).put("prompt", "continue").put("description", "resume test").toString(),
    )

    private fun executor(
        key: String = UUID.randomUUID().toString(),
        parent: AgentRunController = AgentRunController(),
        fingerprint: String? = null,
        remote: JSONArray = JSONArray(),
        base: AgentModelClient.ToolExecutor = AgentModelClient.ToolExecutor { error("unexpected base execution") },
        config: () -> AgentModelClient.ModelConfig = { error("unexpected model config") },
        factory: ((AgentRunController, String, String) -> AgentModelClient.ToolExecutor)? = null,
    ) = SubagentExecutor(
        conversationKey = key,
        parentController = parent,
        configProvider = config,
        capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
        baseToolExecutor = base,
        skillContext = SkillContext.EMPTY,
        memoryContext = AgentMemoryContext.DISABLED,
        mcpTools = remote,
        executionContext = null,
        onProgress = {},
        toolBindingFingerprint = fingerprint,
        executorForChild = factory,
    )
}

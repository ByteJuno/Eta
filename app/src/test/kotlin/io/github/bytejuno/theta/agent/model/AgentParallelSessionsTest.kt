package io.github.bytejuno.theta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.bytejuno.theta.agent.runtime.AgentEvent
import io.github.bytejuno.theta.agent.runtime.AgentRunCancelledException
import io.github.bytejuno.theta.agent.runtime.AgentRunController
import io.github.bytejuno.theta.data.model.OpenAiEndpointMode
import io.github.bytejuno.theta.data.model.ProviderTypes
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentParallelSessionsTest {
    @Test
    fun twoSessionsReachTheProviderConcurrentlyAndKeepTheirHistoryAndRepliesSeparate() =
        verifyParallelSessions(cancelFirst = false)

    @Test
    fun cancellingOneWaitingHttpRequestLeavesTheOtherSessionRunningToCompletion() =
        verifyParallelSessions(cancelFirst = true)

    private fun verifyParallelSessions(cancelFirst: Boolean) {
        val receivedBoth = CountDownLatch(2)
        val releaseResponses = CountDownLatch(1)
        val requests = ConcurrentHashMap<String, String>()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val label = if (body.contains("prompt-session-a")) "a" else "b"
                requests[label] = body
                receivedBoth.countDown()
                if (releaseResponses.await(10, TimeUnit.SECONDS)) {
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write(response("answer-for-$label").toByteArray())
                }
            } finally {
                exchange.close()
            }
        }
        server.start()

        val controllers = listOf(AgentRunController(), AgentRunController())
        val resourcesClosed = listOf(AtomicInteger(), AtomicInteger())
        val answers = List(2) { AtomicReference<AgentModelClient.ModelResponse.Text?>() }
        val failures = List(2) { AtomicReference<Throwable?>() }
        val finished = List(2) { CountDownLatch(1) }
        val events = List(2) { CopyOnWriteArrayList<AgentEvent>() }
        controllers.forEachIndexed { index, controller -> controller.register { resourcesClosed[index].incrementAndGet() } }
        val workers = listOf("a", "b").mapIndexed { index, label ->
            thread(name = "parallel-model-$label", isDaemon = true) {
                try {
                    answers[index].set(AgentModelClient.complete(
                        config = AgentModelClient.ModelConfig(
                            baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                            apiKey = "fixture", model = "fixture", systemPrompt = "",
                            providerType = ProviderTypes.OPENAI_COMPATIBLE,
                            openAiEndpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
                            autoCompactionEnabled = false,
                        ),
                        prompt = "prompt-session-$label",
                        history = listOf(AgentModelClient.ConversationMessage(role = "user", content = "history-for-$label")),
                        toolExecutor = AgentModelClient.ToolExecutor { throw AssertionError("No tool calls expected") },
                        runController = controllers[index],
                        sessionId = "session-$label", operationId = "run-$label",
                        onEvent = { events[index] += it },
                    ))
                } catch (failure: Throwable) {
                    failures[index].set(failure)
                } finally {
                    finished[index].countDown()
                }
            }
        }
        try {
            assertTrue("Both sessions must reach HTTP before either response is released", receivedBoth.await(5, TimeUnit.SECONDS))
            assertEquals(2, requests.size)
            assertTrue(requests.getValue("a").contains("history-for-a"))
            assertFalse(requests.getValue("a").contains("history-for-b"))
            assertTrue(requests.getValue("b").contains("history-for-b"))
            assertFalse(requests.getValue("b").contains("history-for-a"))

            if (cancelFirst) {
                controllers[0].cancel()
                assertTrue("Cancelling A must abort its waiting HTTP request promptly", finished[0].await(2, TimeUnit.SECONDS))
                assertTrue(generateSequence(failures[0].get()) { it.cause }.any { it is AgentRunCancelledException })
                assertNull(answers[0].get())
                assertFalse(controllers[1].isCancelled)
                assertEquals(1L, finished[1].count)
                assertEquals(1, resourcesClosed[0].get())
                assertEquals(0, resourcesClosed[1].get())
            }

            releaseResponses.countDown()
            assertTrue("Session B must finish successfully", finished[1].await(5, TimeUnit.SECONDS))
            failures[1].get()?.let { throw AssertionError("Session B failed", it) }
            assertEquals("answer-for-b", answers[1].get()?.content)
            assertFalse(answers[1].get()!!.transcript.toString().contains("answer-for-a"))
            assertFalse(events[1].filterIsInstance<AgentEvent.AssistantBlockDelta>().any { it.delta.contains("answer-for-a") })
            assertFalse(controllers[1].isCancelled)
            assertEquals(0, resourcesClosed[1].get())

            if (!cancelFirst) {
                assertTrue("Session A must finish successfully", finished[0].await(5, TimeUnit.SECONDS))
                failures[0].get()?.let { throw AssertionError("Session A failed", it) }
                assertEquals("answer-for-a", answers[0].get()?.content)
                assertFalse(answers[0].get()!!.transcript.toString().contains("answer-for-b"))
                assertFalse(events[0].filterIsInstance<AgentEvent.AssistantBlockDelta>().any { it.delta.contains("answer-for-b") })
                assertEquals(0, resourcesClosed[0].get())
            }
        } finally {
            releaseResponses.countDown()
            controllers.forEach(AgentRunController::cancel)
            workers.forEach { it.join(2_000) }
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun response(text: String): String {
        val choice = JSONObject().put("index", 0).put("delta", JSONObject().put("content", text)).put("finish_reason", "stop")
        return "data: ${JSONObject().put("choices", JSONArray().put(choice))}\n\ndata: [DONE]\n\n"
    }
}

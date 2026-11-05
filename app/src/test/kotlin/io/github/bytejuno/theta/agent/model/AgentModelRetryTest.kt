package io.github.bytejuno.theta.agent.model

import io.github.bytejuno.theta.agent.runtime.AgentRunCancelledException
import io.github.bytejuno.theta.agent.runtime.AgentEvent
import io.github.bytejuno.theta.agent.runtime.AgentTokenUsage
import io.github.bytejuno.theta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class AgentModelRetryTest {
    @Test
    fun retriesAreBoundedAndBackoffIsPerModelRound() {
        val delays = mutableListOf<Long>()
        val retry = AgentModelRetry { _, delay -> delays += delay }
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            complete(retry, provider { _, _ -> calls++; throw SocketTimeoutException("timeout") })
        }
        assertEquals(4, calls)
        assertEquals(listOf(2_000L, 4_000L, 8_000L), delays)
        assertTrue(failure.message.orEmpty().contains("已重试 3 次"))
        delays.clear()
        calls = 0
        val result = complete(retry, provider { _, _ ->
            if (calls++ == 0) throw IOException("connection reset")
            response()
        })
        assertEquals(2, result.round)
        assertEquals(listOf(2_000L), delays)
    }

    @Test
    fun serverRetryAfterLengthensBackoffButNeverShortensIt() {
        val delays = mutableListOf<Long>()
        val retry = AgentModelRetry { _, delay -> delays += delay }
        var calls = 0
        // 429 asking for 20 s: each wait must be at least 20 s instead of 2/4/8 s.
        val limited = AgentModelFailure.http(429, """{"error":{"code":"rate_limit_exceeded"}}""")
            .withRetryAfter(AgentModelFailure.parseRetryAfter("20"))
        assertThrows(AgentModelFailure::class.java) {
            complete(retry, provider { _, _ -> calls++; throw limited })
        }
        assertEquals(listOf(20_000L, 20_000L, 20_000L), delays)

        delays.clear()
        // A shorter hint never makes us retry faster than our own backoff.
        val brief = AgentModelFailure.http(503, "{}").withRetryAfter(AgentModelFailure.parseRetryAfter("1"))
        assertThrows(AgentModelFailure::class.java) { complete(retry, provider { _, _ -> throw brief }) }
        assertEquals(listOf(2_000L, 4_000L, 8_000L), delays)
    }

    @Test
    fun retryAfterParsingIsBoundedAndIgnoresGarbage() {
        val now = 1_700_000_000_000L
        assertEquals(30_000L, AgentModelFailure.parseRetryAfter("30", now))
        assertEquals(AgentModelFailure.MAX_RETRY_AFTER_MS, AgentModelFailure.parseRetryAfter("86400", now))
        val date = java.time.Instant.ofEpochMilli(now + 15_000).atZone(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        assertEquals(15_000L, AgentModelFailure.parseRetryAfter(date, now))
        val past = java.time.Instant.ofEpochMilli(now - 60_000).atZone(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        assertEquals(0L, AgentModelFailure.parseRetryAfter(past, now))
        listOf(null, "", "  ", "soon", "-5").forEach { assertEquals(it, null, AgentModelFailure.parseRetryAfter(it, now)) }
        // Permanent failures keep no retry hint, so they are never delayed or retried.
        val quota = AgentModelFailure.http(429, """{"error":{"code":"insufficient_quota"}}""").withRetryAfter(5_000)
        assertEquals(null, quota.retryAfterMs)
    }

    @Test
    fun cancellationDuringBackoffStopsBeforeAnotherRequest() {
        val controller = AgentRunController()
        var calls = 0
        assertThrows(AgentRunCancelledException::class.java) {
            complete(
                AgentModelRetry { control, _ -> control.cancel() },
                provider { _, _ -> calls++; throw SocketTimeoutException() },
                controller,
            )
        }
        assertEquals(1, calls)
    }

    @Test
    fun callbackFailuresAndHostedToolFailuresDoNotReplayProvider() {
        val noRetry = AgentModelRetry { _, _ -> fail("不应重试") }
        val callbackFailure = IOException("checkpoint write failed")
        val thrown = assertThrows(IOException::class.java) {
            complete(noRetry, provider { _, emit ->
                emit(ProviderEvent.RequestStarted)
                response()
            }, onProviderEvent = { _, _ -> throw callbackFailure })
        }
        assertSame(callbackFailure, thrown)
        assertThrows(AgentModelFailure::class.java) {
            complete(noRetry, provider { _, emit ->
                emit(ProviderEvent.HostedToolStarted("search-1", "web_search"))
                throw SocketTimeoutException()
            })
        }
    }

    @Test
    fun classifiesTransientFailuresWithoutRetryingPermanentFailures() {
        for (status in listOf(408, 429, 500, 502, 503, 504, 529)) {
            assertTrue(AgentModelFailure.http(status, "").retryable)
        }
        for (status in listOf(400, 401, 403, 404)) {
            assertFalse(AgentModelFailure.http(status, "").retryable)
        }
        assertFalse(AgentModelFailure.http(429, """{"error":{"code":"insufficient_quota"}}""").retryable)
        assertNull(AgentModelFailure.transport(SSLHandshakeException("certificate")))
        assertNull(AgentModelFailure.transport(org.json.JSONException("invalid JSON")))
        assertTrue(AgentModelFailure.stream(JSONObject().put("type", "overloaded_error"), "过载").retryable)
        assertFalse(AgentModelFailure.stream(JSONObject().put("type", "authentication_error"), "认证失败").retryable)
        assertFalse(AgentModelFailure.http(503, "secret request text").message.orEmpty().contains("secret"))
    }

    @Test
    fun averageDurationAndUsageBelongOnlyToTheLatestSuccessfulAttempt() {
        var now = 0L
        var attempts = 0
        val events = mutableListOf<AgentEvent>()
        val retry = AgentModelRetry(nanoTime = { now }, waitBeforeRetry = { _, _ -> now += 8_000_000_000L })
        complete(retry, provider { _, emit ->
            emit(ProviderEvent.RequestStarted)
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 120, contextTokens = 120)))
            if (attempts++ == 0) {
                now += 5_000_000_000L
                throw SocketTimeoutException()
            }
            now += 2_000_000_000L
            emit(ProviderEvent.Usage(AgentTokenUsage(outputTokens = 80)))
            response()
        }, onEvent = { events += it })
        val usage = events.filterIsInstance<AgentEvent.UsageReceived>().single()
        assertEquals(2, usage.round)
        assertEquals(2000L, usage.usage.requestDurationMs)
        assertEquals(120, usage.usage.inputTokens)
        assertEquals(80, usage.usage.outputTokens)
    }

    private fun complete(
        retry: AgentModelRetry,
        provider: AgentProviderClient,
        controller: AgentRunController = AgentRunController(),
        onProviderEvent: (Int, ProviderEvent) -> Unit = { _, _ -> },
        onEvent: (AgentEvent) -> Unit = {},
    ) = retry.complete(
        initialRound = 1,
        request = ProviderRequest(
            AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test-key", model = "test-model", systemPrompt = ""),
            JSONArray(), JSONArray(),
        ),
        provider = provider,
        controller = controller,
        onEvent = onEvent,
        onProviderEvent = onProviderEvent,
        discardAttemptReasoning = {},
    )

    private fun response() = ProviderResponse(JSONObject().put("content", "完成").put("finish_reason", "stop"))

    private fun provider(action: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) =
        object : AgentProviderClient {
            override val id = "test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ) = action(request, onEvent)
        }
}

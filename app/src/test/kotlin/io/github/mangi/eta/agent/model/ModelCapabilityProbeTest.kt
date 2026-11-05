package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilityProbeTest {
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid",
        apiKey = "test-key",
        model = "test-model",
        systemPrompt = "很长的系统提示",
    )

    @Test
    fun reportsEachCapabilityAndStripsSystemPrompt() {
        val requests = mutableListOf<ProviderRequest>()
        val report = ModelCapabilityProbe.run(config, checkVision = true, client = client { request ->
            requests += request
            when {
                request.tools.length() > 0 -> JSONObject().put(
                    "tool_calls",
                    JSONArray().put(
                        JSONObject().put(
                            "function",
                            JSONObject().put("name", "record_number").put("arguments", "{\"value\":42}"),
                        ),
                    ),
                )
                request.messages.toString().contains("image") -> JSONObject().put("content", "红色")
                else -> JSONObject().put("content", "收到")
            }
        })

        assertEquals(ModelCapabilityProbe.Outcome.SUPPORTED, report.text.outcome)
        assertEquals(ModelCapabilityProbe.Outcome.SUPPORTED, report.tools.outcome)
        assertEquals(ModelCapabilityProbe.Outcome.SUPPORTED, report.vision.outcome)
        assertEquals(3, requests.size)
        assertTrue(requests.all { it.config.systemPrompt.isEmpty() })
    }

    @Test
    fun failedTextRequestSkipsTheOtherChecks() {
        var calls = 0
        val report = ModelCapabilityProbe.run(config, checkVision = true, client = client {
            calls++
            throw IllegalStateException("HTTP 401 invalid key\nbody")
        })

        assertEquals(1, calls)
        assertEquals(ModelCapabilityProbe.Outcome.FAILED, report.text.outcome)
        assertEquals("HTTP 401 invalid key", report.text.detail)
        assertEquals(ModelCapabilityProbe.Outcome.SKIPPED, report.tools.outcome)
        assertEquals(ModelCapabilityProbe.Outcome.SKIPPED, report.vision.outcome)
    }

    @Test
    fun plainReplyToToolPromptIsUnsupportedAndRejectedImageIsFailed() {
        val report = ModelCapabilityProbe.run(config, checkVision = true, client = client { request ->
            if (request.messages.toString().contains("image")) throw IllegalArgumentException("image input not supported")
            JSONObject().put("content", "好的")
        })

        assertEquals(ModelCapabilityProbe.Outcome.SUPPORTED, report.text.outcome)
        assertEquals(ModelCapabilityProbe.Outcome.UNSUPPORTED, report.tools.outcome)
        assertEquals(ModelCapabilityProbe.Outcome.FAILED, report.vision.outcome)
    }

    private fun client(reply: (ProviderRequest) -> JSONObject) = object : AgentProviderClient {
        override val id = "test"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ) = ProviderResponse(reply(request))
    }
}

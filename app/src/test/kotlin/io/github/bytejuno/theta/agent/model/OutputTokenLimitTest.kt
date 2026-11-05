package io.github.bytejuno.theta.agent.model

import io.github.bytejuno.theta.data.model.CustomBody
import io.github.bytejuno.theta.data.model.OpenAiEndpointMode
import io.github.bytejuno.theta.data.model.OutputTokenLimit
import io.github.bytejuno.theta.data.model.ProviderTypes
import io.github.bytejuno.theta.data.model.ReasoningEffort
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputTokenLimitTest {
    @Test
    fun readWriteRoundTripKeepsOtherCustomBodyFields() {
        val other = CustomBody("temperature", JsonPrimitive(0.2))
        val written = OutputTokenLimit.write(listOf(other), 8192)
        assertEquals(8192, OutputTokenLimit.read(written))
        assertEquals(listOf(other), OutputTokenLimit.write(written, null))
        assertNull(OutputTokenLimit.read(listOf(CustomBody("max_tokens", JsonPrimitive("8192")))))
    }

    @Test
    fun inputValidationAcceptsBlankAndBoundedIntegers() {
        assertTrue(OutputTokenLimit.isValidInput(""))
        assertTrue(OutputTokenLimit.isValidInput("4096"))
        assertFalse(OutputTokenLimit.isValidInput("0"))
        assertFalse(OutputTokenLimit.isValidInput("1000001"))
    }

    @Test
    fun responsesRequestRenamesLimitToMaxOutputTokens() {
        val request = ResponsesRequestBuilder.build(
            config(ProviderTypes.OPENAI_COMPATIBLE).copy(
                openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
                customBody = OutputTokenLimit.write(emptyList(), 2048),
            ),
            messages(),
            JSONArray(),
        )
        assertFalse(request.has("max_tokens"))
        assertEquals(2048, request.getInt("max_output_tokens"))
    }

    @Test
    fun promptCacheKeyOnlyForOfficialOpenAiAndIsStablePerSession() {
        val official = config(ProviderTypes.OPENAI_COMPATIBLE).copy(
            baseUrl = "https://api.openai.com/v1",
            openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
        )
        val first = ResponsesRequestBuilder.build(official, messages(), JSONArray(), "conv-1").getString("prompt_cache_key")
        val again = ResponsesRequestBuilder.build(official, messages(), JSONArray(), "conv-1").getString("prompt_cache_key")
        val other = ResponsesRequestBuilder.build(official, messages(), JSONArray(), "conv-2").getString("prompt_cache_key")
        assertEquals(first, again)
        assertFalse(first == other)
        assertFalse(first.contains("conv-1"))
        val proxy = official.copy(baseUrl = "https://proxy.example.test/v1")
        assertFalse(ResponsesRequestBuilder.build(proxy, messages(), JSONArray(), "conv-1").has("prompt_cache_key"))
    }

    @Test
    fun geminiRequestMovesLimitIntoGenerationConfig() {
        val request = GeminiInteractionsRequest.build(
            config(ProviderTypes.GEMINI).copy(customBody = OutputTokenLimit.write(emptyList(), 3000)),
            messages(),
            JSONArray(),
        )
        assertFalse(request.has("max_tokens"))
        assertEquals(3000, request.getJSONObject("generation_config").getInt("max_output_tokens"))
    }

    private fun config(type: String) = AgentModelClient.ModelConfig(
        baseUrl = "https://example.test/v1", apiKey = "test-key", model = "test-model",
        providerType = type, systemPrompt = "", reasoningEffort = ReasoningEffort.DEFAULT,
    )

    private fun messages() = JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))
}

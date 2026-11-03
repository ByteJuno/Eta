package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OpenAiEndpointMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-03：fetch_url 顶层 oneOf，经 CLIProxyAPI 走 Kiro 时 400「input_schema does not support oneOf…」。 */
class ToolSchemaCompatTest {
    private fun tool(name: String, parameters: JSONObject) =
        JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", name).put("description", name).put("parameters", parameters))

    private fun oneOfParameters() = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject()
            .put("url", JSONObject().put("type", "string"))
            .put("document_id", JSONObject().put("type", "string")))
        .put("oneOf", JSONArray()
            .put(JSONObject().put("required", JSONArray().put("url")))
            .put(JSONObject().put("required", JSONArray().put("document_id"))))
        .put("additionalProperties", false)

    private fun plainParameters() = JSONObject().put("type", "object")
        .put("properties", JSONObject().put("q", JSONObject().put("type", "string")))

    private fun tools() = JSONArray().put(tool("plain", plainParameters())).put(tool("fetch_url", oneOfParameters()))

    private fun config(mode: String) = AgentModelClient.ModelConfig(
        providerSourceType = "custom", baseUrl = "https://proxy.example/v1", apiKey = "k",
        model = "claude-opus-5.5", systemPrompt = "s", openAiEndpointMode = mode,
    )

    @Test
    fun chatToolsDropTopLevelCombinatorsWithoutTouchingSource() {
        val source = tools()
        val sent = chatToolsWithoutTopLevelCombinators(source)
        val schema = sent.getJSONObject(1).getJSONObject("function").getJSONObject("parameters")
        TOP_LEVEL_SCHEMA_COMBINATORS.forEach { assertFalse(schema.has(it)) }
        assertEquals(setOf("url", "document_id"), schema.getJSONObject("properties").keys().asSequence().toSet())
        assertFalse(schema.getBoolean("additionalProperties"))
        // 本地校验仍用原始定义，二选一约束不能被改掉。
        assertTrue(source.getJSONObject(1).getJSONObject("function").getJSONObject("parameters").has("oneOf"))
        assertSame(source.get(0), sent.get(0))
    }

    @Test
    fun chatToolsWithoutCombinatorsAreReturnedAsIs() {
        val source = JSONArray().put(tool("plain", plainParameters()))
        assertSame(source, chatToolsWithoutTopLevelCombinators(source))
    }

    @Test
    fun chatCompletionsRequestHasNoTopLevelCombinators() {
        val request = OpenAiChatCompletionsProvider.buildRequestJson(
            config(OpenAiEndpointMode.CHAT_COMPLETIONS),
            JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
            tools(),
        )
        val schema = request.getJSONArray("tools").getJSONObject(1).getJSONObject("function").getJSONObject("parameters")
        TOP_LEVEL_SCHEMA_COMBINATORS.forEach { assertFalse(schema.has(it)) }
    }

    @Test
    fun responsesRequestHasNoTopLevelCombinators() {
        val request = ResponsesRequestBuilder.build(
            config(OpenAiEndpointMode.RESPONSES),
            JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
            tools(),
        )
        val sent = request.getJSONArray("tools")
        val schema = (0 until sent.length()).map(sent::getJSONObject).first { it.optString("name") == "fetch_url" }
            .getJSONObject("parameters")
        TOP_LEVEL_SCHEMA_COMBINATORS.forEach { assertFalse(schema.has(it)) }
        assertEquals(setOf("url", "document_id"), schema.getJSONObject("properties").keys().asSequence().toSet())
    }
}

package io.github.bytejuno.theta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 2026-11-03：上下文压缩摘要是 assistant，Kiro 经 CLIProxyAPI 拒绝「history must start with a user message」。 */
class LeadingUserMessageTest {
    private fun msg(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    @Test
    fun placeholderInsertedBeforeLeadingAssistantAfterSystem() {
        val source = JSONArray().put(msg("system", "规则")).put(msg("assistant", "摘要")).put(msg("user", "继续"))
        val fixed = withLeadingUserMessage(OpenAiRequestMessages.forChatCompletions(source))
        assertEquals(listOf("system", "user", "assistant", "user"), (0 until fixed.length()).map { fixed.getJSONObject(it).getString("role") })
        assertEquals(LEADING_USER_PLACEHOLDER, fixed.getJSONObject(1).getString("content"))
        assertEquals(3, source.length())
    }

    @Test
    fun leadingToolOrDeveloperHandled() {
        val fixed = withLeadingUserMessage(JSONArray().put(msg("developer", "d")).put(msg("tool", "r")))
        assertEquals(listOf("developer", "user", "tool"), (0 until fixed.length()).map { fixed.getJSONObject(it).getString("role") })
    }

    @Test
    fun alreadyStartingWithUserOrOnlySystemIsUnchanged() {
        val user = JSONArray().put(msg("system", "规则")).put(msg("user", "你好"))
        assertSame(user, withLeadingUserMessage(user))
        val onlySystem = JSONArray().put(msg("system", "规则"))
        assertSame(onlySystem, withLeadingUserMessage(onlySystem))
    }
}

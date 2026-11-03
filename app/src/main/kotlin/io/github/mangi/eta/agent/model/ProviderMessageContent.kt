package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal fun providerMessageText(content: Any?): String =
    when (content) {
        null, JSONObject.NULL -> ""
        is String -> content
        is JSONArray -> buildString {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                if (item.optString("type") !in TEXT_CONTENT_TYPES) continue
                if (isNotEmpty()) append('\n')
                append(item.optString("text"))
            }
        }
        else -> content.toString()
    }

private val TEXT_CONTENT_TYPES = setOf("text", "input_text", "output_text")

/**
 * 首条非 system 消息必须是 user 时补的占位。上下文压缩后的摘要、角色卡开场白都以 assistant 身份排在最前，
 * Kiro（经 CLIProxyAPI）直接拒绝「conversation history must start with a user message」，Anthropic 也有同样要求。
 * 文字固定不变，补上后每轮前缀一致，不影响缓存。
 */
internal const val LEADING_USER_PLACEHOLDER = "（继续之前的对话）"

/** Chat Completions 消息：首条非 system/developer 消息不是 user 时，在它前面插入占位 user；否则原样返回。 */
internal fun withLeadingUserMessage(messages: JSONArray): JSONArray {
    val first = (0 until messages.length()).firstOrNull {
        messages.optJSONObject(it)?.optString("role") !in setOf("system", "developer")
    } ?: return messages
    if (messages.optJSONObject(first)?.optString("role") == "user") return messages
    return JSONArray().also { out ->
        for (index in 0 until messages.length()) {
            if (index == first) out.put(JSONObject().put("role", "user").put("content", LEADING_USER_PLACEHOLDER))
            out.put(messages.get(index))
        }
    }
}

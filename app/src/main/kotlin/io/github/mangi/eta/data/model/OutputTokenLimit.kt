package io.github.mangi.eta.data.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 模型级最大输出 tokens（上游 #174）：存为模型 customBody 的 `max_tokens`，不新增数据库列。
 *
 * Chat Completions 与 Anthropic 直接使用 `max_tokens`；Responses 与 Gemini 在构造请求时
 * 改写为各自协议的字段，因此切换端点模式后同一设置仍然生效。
 */
object OutputTokenLimit {
    const val CUSTOM_BODY_KEY = "max_tokens"
    const val MAX_VALUE = 1_000_000

    fun read(customBody: List<CustomBody>): Int? = customBody
        .lastOrNull { it.key == CUSTOM_BODY_KEY }
        ?.value
        ?.let { it as? JsonPrimitive }
        ?.takeUnless(JsonPrimitive::isString)
        ?.intOrNull
        ?.takeIf { it > 0 }

    fun write(customBody: List<CustomBody>, limit: Int?): List<CustomBody> {
        val others = customBody.filterNot { it.key == CUSTOM_BODY_KEY }
        return if (limit == null) others else others + CustomBody(CUSTOM_BODY_KEY, JsonPrimitive(limit))
    }

    /** 空串表示跟随服务商默认。 */
    fun isValidInput(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        val value = trimmed.toIntOrNull() ?: return false
        return value in 1..MAX_VALUE
    }
}

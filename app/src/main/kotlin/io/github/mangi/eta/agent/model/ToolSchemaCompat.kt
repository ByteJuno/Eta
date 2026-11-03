package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 发给模型的工具参数 schema 去掉顶层 oneOf/anyOf/allOf。
 *
 * Anthropic Messages 直接拒绝这类 schema（`input_schema does not support oneOf, allOf, or anyOf at the top level`），
 * OpenAI 兼容网关（CLIProxyAPI → Kiro/Claude 等）转发时同样会 400，所以所有接口都按这个最严格的约束发。
 * 只影响线上的 schema 提示；本地 [AgentToolCallValidator] 仍按工具原始定义校验，「二选一」之类的约束不丢。
 */
internal val TOP_LEVEL_SCHEMA_COMBINATORS = listOf("oneOf", "anyOf", "allOf")

internal fun withoutTopLevelCombinators(parameters: JSONObject): JSONObject {
    if (TOP_LEVEL_SCHEMA_COMBINATORS.none(parameters::has)) return parameters
    return JSONObject(parameters.toString()).apply { TOP_LEVEL_SCHEMA_COMBINATORS.forEach(::remove) }
}

/** Chat Completions 形态的 tools（`{"type":"function","function":{"parameters":…}}`）；没有需要改的就原样返回。 */
internal fun chatToolsWithoutTopLevelCombinators(tools: JSONArray): JSONArray {
    val needsChange = (0 until tools.length()).any { index ->
        val parameters = tools.optJSONObject(index)?.optJSONObject("function")?.optJSONObject("parameters")
        parameters != null && TOP_LEVEL_SCHEMA_COMBINATORS.any(parameters::has)
    }
    if (!needsChange) return tools
    return JSONArray().also { out ->
        for (index in 0 until tools.length()) {
            val tool = tools.optJSONObject(index)
            val parameters = tool?.optJSONObject("function")?.optJSONObject("parameters")
            if (parameters == null || TOP_LEVEL_SCHEMA_COMBINATORS.none(parameters::has)) {
                out.put(tools.get(index))
                continue
            }
            val copy = JSONObject(tool.toString())
            copy.getJSONObject("function").put("parameters", withoutTopLevelCombinators(parameters))
            out.put(copy)
        }
    }
}

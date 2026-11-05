package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「测试此模型」：用当前配置发三次极小的真实请求，分别确认文字回复、工具调用和图片输入。
 *
 * 使用用户实际的服务商、自定义请求头与请求体，因此能暴露兼容层对参数或 schema 的拒收；
 * 系统提示清空以节省 token。结果只在编辑弹窗里展示，不改写模型配置。
 */
internal object ModelCapabilityProbe {
    enum class Outcome { SUPPORTED, UNSUPPORTED, FAILED, SKIPPED }

    data class Check(val outcome: Outcome, val detail: String = "", val latencyMs: Long? = null)

    data class Report(val text: Check, val tools: Check, val vision: Check)

    fun run(
        config: AgentModelClient.ModelConfig,
        checkVision: Boolean,
        client: AgentProviderClient = ProviderClientFactory.getClient(config),
        controller: AgentRunController = AgentRunController(),
    ): Report {
        val probeConfig = config.copy(
            systemPrompt = "",
            hostedWebSearchEnabled = false,
        )
        val text = attempt {
            val reply = complete(client, controller, probeConfig, messages(AgentConversationCodec.userTextMessage(TEXT_PROMPT)), JSONArray())
            val content = reply.optString("content").trim()
            if (content.isEmpty()) Check(Outcome.UNSUPPORTED, "没有返回文字内容") else Check(Outcome.SUPPORTED, content.take(40))
        }
        if (text.outcome != Outcome.SUPPORTED) {
            return Report(text, Check(Outcome.SKIPPED), Check(Outcome.SKIPPED))
        }
        val tools = attempt {
            val reply = complete(client, controller, probeConfig, messages(AgentConversationCodec.userTextMessage(TOOL_PROMPT)), JSONArray().put(probeTool()))
            val call = reply.optJSONArray("tool_calls")?.optJSONObject(0)?.optJSONObject("function")
            when {
                call == null -> Check(Outcome.UNSUPPORTED, "模型没有调用工具")
                call.optString("name") != TOOL_NAME -> Check(Outcome.UNSUPPORTED, "调用了错误的工具")
                runCatching { JSONObject(call.optString("arguments")).optInt("value") }.getOrNull() != 42 ->
                    Check(Outcome.UNSUPPORTED, "工具参数没有正确传递")
                else -> Check(Outcome.SUPPORTED)
            }
        }
        val vision = if (!checkVision) Check(Outcome.SKIPPED) else attempt {
            val image = AgentModelClient.ModelImage(reference = RED_PNG, mimeType = "image/png", bytes = RED_PNG.length, width = 32, height = 32, source = "probe")
            val reply = complete(client, controller, probeConfig, messages(AgentConversationCodec.userMessage(VISION_PROMPT, listOf(image))), JSONArray())
            val content = reply.optString("content")
            if (content.contains("红") || content.contains("red", ignoreCase = true)) Check(Outcome.SUPPORTED)
            else Check(Outcome.UNSUPPORTED, "请求被接受，但没有认出图片颜色")
        }
        return Report(text, tools, vision)
    }

    private inline fun attempt(block: () -> Check): Check {
        val started = System.nanoTime()
        val check = try {
            block()
        } catch (failure: Throwable) {
            if (failure is InterruptedException) throw failure
            Check(Outcome.FAILED, (failure.message ?: failure.javaClass.simpleName).lineSequence().first().take(160))
        }
        return check.copy(latencyMs = (System.nanoTime() - started) / 1_000_000)
    }

    private fun complete(
        client: AgentProviderClient,
        controller: AgentRunController,
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray,
    ): JSONObject = client.complete(ProviderRequest(config, messages, tools), controller).assistantMessage

    private fun messages(user: JSONObject) = JSONArray().put(user)

    private fun probeTool() = JSONObject().put("type", "function").put(
        "function",
        JSONObject().put("name", TOOL_NAME)
            .put("description", "记录一个整数。")
            .put(
                "parameters",
                JSONObject().put("type", "object")
                    .put("properties", JSONObject().put("value", JSONObject().put("type", "integer").put("description", "要记录的整数")))
                    .put("required", JSONArray().put("value")),
            ),
    )

    private const val TOOL_NAME = "record_number"
    private const val TEXT_PROMPT = "只回复两个字：收到"
    private const val TOOL_PROMPT = "请调用 record_number 工具记录 value=42，不要输出其他文字。"
    private const val VISION_PROMPT = "这张图片是什么颜色？只用一个词回答。"
    private const val RED_PNG =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAKklEQVR4nGO4IyJCU8QwasGoBaMWjFowasGoBaMWjFowasGoBaMWDBULAJ3LED3QqzauAAAAAElFTkSuQmCC"
}

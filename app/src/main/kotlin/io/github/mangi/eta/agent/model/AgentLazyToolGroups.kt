package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 可选的精简工具目录：低频大块工具先只以名称出现在 [LOADER_NAME] 的描述里，
 * 模型调用加载后再追加完整定义。目录在一次运行内只增不减，与 toolsForRound 的缓存策略一致；
 * 每次新运行重新从精简目录开始。
 */
internal object AgentLazyToolGroups {
    const val LOADER_NAME = "tools_load"

    private data class Group(val id: String, val summary: String, val member: (String) -> Boolean)

    private val skillAdminNames = setOf(
        "skills_manage", "skills_list_curated", "skills_inspect_github", "skills_install_from_github",
    )

    /** 敏感设备工具 = 全部设备工具减去默认直连工具，随目录定义自动更新。 */
    private val personalDeviceNames: Set<String> by lazy {
        fun names(sensitive: Boolean) = JSONArray().also {
            AgentDeviceToolCatalog.appendTo(it, directTools = true, sensitiveReadTools = sensitive, sensitiveActionTools = sensitive)
        }.let { tools -> (0 until tools.length()).mapNotNullTo(mutableSetOf()) { toolName(tools.getJSONObject(it)) } }
        names(sensitive = true) - names(sensitive = false)
    }

    private val groups = listOf(
        Group("personal_data", "日历、闹钟、便签、账单、待办、出行、通知历史、应用使用、位置与系统设置等个人数据和敏感操作") {
            it in personalDeviceNames
        },
        Group("automation", "定时与事件自动任务的查询、创建、修改、运行和历史") { it.startsWith("tasks_") },
        Group("skill_admin", "技能的管理、精选目录与从 GitHub 检查或安装") { it in skillAdminNames },
    )

    fun groupOf(name: String): String? = groups.firstOrNull { it.member(name) }?.id

    /** 返回去掉未加载分组后的目录；有可延迟分组时在末尾追加加载器。 */
    fun project(tools: JSONArray, loaded: Set<String>): JSONArray {
        val deferred = linkedMapOf<String, MutableList<String>>()
        val result = JSONArray()
        for (index in 0 until tools.length()) {
            val schema = tools.getJSONObject(index)
            val name = toolName(schema) ?: continue
            val group = groupOf(name)
            if (group != null) deferred.getOrPut(group, ::mutableListOf) += name
            if (group == null || group in loaded) result.put(schema)
        }
        if (deferred.isNotEmpty()) result.put(loader(deferred))
        return result
    }

    fun load(argumentsJson: String, available: Set<String>, loaded: MutableSet<String>): String {
        val group = runCatching { JSONObject(argumentsJson).optString("group") }.getOrDefault("")
        if (group !in available) {
            return JSONObject().put("ok", false).put("code", "UNKNOWN_TOOL_GROUP")
                .put("message", "可加载的分组：${available.joinToString()}").toString()
        }
        loaded += group
        return JSONObject().put("ok", true).put("group", group)
            .put("message", "已加载，下一步即可直接调用该分组的工具").toString()
    }

    /** 加载器描述只依赖本次运行的初始目录，加载后不变，避免改写请求前缀。 */
    private fun loader(deferred: Map<String, List<String>>): JSONObject {
        val description = buildString {
            append("按需加载工具分组。下列工具未展开定义，需要时先调用本工具加载对应分组，再直接调用其中的工具；")
            append("同一次回复内加载一次即可，不确定是否需要时不要加载。\n")
            deferred.forEach { (id, names) ->
                val summary = groups.first { it.id == id }.summary
                append("- ").append(id).append("：").append(summary).append("。工具：").append(names.joinToString()).append('\n')
            }
        }.trimEnd()
        return JSONObject().put("type", "function").put(
            "function",
            JSONObject().put("name", LOADER_NAME).put("description", description).put(
                "parameters",
                JSONObject().put("type", "object")
                    .put("properties", JSONObject().put("group", JSONObject().put("type", "string").put("enum", JSONArray(deferred.keys.toList()))))
                    .put("required", JSONArray().put("group"))
                    .put("additionalProperties", false),
            ),
        )
    }

    fun deferredGroups(tools: JSONArray): Set<String> =
        (0 until tools.length()).mapNotNullTo(linkedSetOf()) { toolName(tools.getJSONObject(it))?.let(::groupOf) }

    private fun toolName(schema: JSONObject): String? =
        schema.optJSONObject("function")?.optString("name")?.takeIf(String::isNotBlank)
}

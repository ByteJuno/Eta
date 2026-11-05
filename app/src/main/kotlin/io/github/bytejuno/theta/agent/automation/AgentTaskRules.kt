package io.github.bytejuno.theta.agent.automation

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/** 固定结构的本地规则。匹配事件不调用模型。 */
internal object AgentTaskRules {
    val timeTypes = setOf("once", "interval", "daily")
    val eventTypes = setOf(
        "notification_posted",
        "notification_removed",
        "app_foreground",
        "device_unlocked",
        "screen_on",
        "screen_off",
        "charging_connected",
        "charging_disconnected",
        "battery_low",
        "battery_okay",
        "network_connected",
        "network_disconnected",
        "bluetooth_connected",
        "bluetooth_disconnected",
        "headset_connected",
        "headset_disconnected",
        "device_boot",
        "memory_updated",
        "task_completed",
        "task_failed",
        "shared_content",
        "battery_level",
    )
    private val filterFields =
        setOf("packageName", "titleContains", "textContains", "taskId", "transport", "ssid", "deviceName", "kind")
    val sharedContentKinds = setOf("text", "link", "image", "file")

    fun validate(raw: JSONObject): JSONObject {
        require(raw.toString().length <= 4000) { "触发规则过长" }
        val type = raw.getString("type")
        require(type in timeTypes || type in eventTypes) { "触发器尚未实现，请先 tasks_triggers" }
        val fields = when (type) {
            "once" -> setOf("type", "at")
            "interval" -> setOf("type", "seconds", "startAt")
            "daily" -> setOf("type", "time", "timeZone", "weekdays")
            "battery_level" -> setOf("type", "below", "above")
            else -> setOf("type", "filters")
        } + setOf("conditions", "conditionsAny")
        require(raw.keys().asSequence().all { it in fields }) { "触发规则包含不支持的字段" }
        when (type) {
            "once" -> parseInstant(raw.getString("at"))
            "interval" -> {
                integer(raw, "seconds", 900, 31_536_000)
                if (raw.has("startAt")) parseInstant(raw.getString("startAt"))
            }

            "daily" -> {
                require(Regex("[0-2][0-9]:[0-5][0-9]").matches(raw.getString("time"))) { "time 需要 HH:mm" }
                LocalTime.parse(raw.getString("time"))
                ZoneId.of(raw.getString("timeZone"))
                raw.optJSONArray("weekdays")?.let { days ->
                    require(days.length() in 1..7) { "weekdays 需要 1–7 个星期序号" }
                    for (i in 0 until days.length()) {
                        val day = days.get(i)
                        require(
                            day is Number && day.toDouble() == day.toLong()
                                .toDouble() && day.toLong() in 1..7
                        ) { "星期序号为 1（周一）至 7" }
                    }
                }
                require(!raw.has("weekdays") || raw.optJSONArray("weekdays") != null) { "weekdays 需要数组" }
            }

            "battery_level" -> {
                require(raw.has("below") != raw.has("above")) { "battery_level 需要 below 或 above 其中之一" }
                if (raw.has("below")) integer(raw, "below", 1, 99) else integer(raw, "above", 1, 99)
            }
        }
        raw.optJSONObject("filters")?.let { filters ->
            require(
                filters.keys().asSequence().all { it in filterFields }) { "不支持此事件过滤字段" }
            filters.keys().forEach { name ->
                require(
                    filters.get(name) is String && filters.getString(name)
                        .isNotBlank() && filters.getString(name).length <= 200
                ) { "过滤值需要 1–200 字符" }
            }
            val allowed = when (type) {
                "notification_posted", "notification_removed" -> setOf(
                    "packageName",
                    "titleContains",
                    "textContains"
                )

                "app_foreground" -> setOf("packageName")
                "task_completed", "task_failed" -> setOf("taskId")
                "network_connected", "network_disconnected" -> setOf("transport", "ssid")
                "bluetooth_connected", "bluetooth_disconnected" -> setOf("deviceName")
                "shared_content" -> setOf("kind", "textContains")
                else -> emptySet()
            }
            require(
                filters.keys().asSequence().all { it in allowed }) { "该触发器不支持此过滤字段" }
            filters.optString("kind").takeIf(String::isNotEmpty)?.let { kind ->
                require(kind in sharedContentKinds) { "kind 需要 text、link、image 或 file" }
            }
        }
        require(!raw.has("filters") || raw.optJSONObject("filters") != null) { "filters 需要对象" }
        for (field in listOf("conditions", "conditionsAny")) {
            raw.optJSONObject(field)?.let { conditions ->
                require(conditions.keys().asSequence().all { it in conditionNames }) { "不支持此附加条件" }
                conditions.keys()
                    .forEach { require(conditions.get(it) is Boolean) { "条件值需要 boolean" } }
                if (field == "conditionsAny") require(conditions.length() >= 2) { "conditionsAny 至少需要两个条件" }
            }
            require(!raw.has(field) || raw.optJSONObject(field) != null) { "$field 需要对象" }
        }
        return JSONObject(raw.toString())
    }

    fun nextTime(rule: JSONObject, after: Long, initial: Boolean): Long? =
        when (rule.getString("type")) {
            "once" -> parseInstant(rule.getString("at")).takeIf { initial }
            "interval" -> {
                val interval = integer(rule, "seconds", 900, 31_536_000) * 1000
                val anchor =
                    rule.optString("startAt").takeIf { it.isNotBlank() }?.let(::parseInstant)
                when {
                    anchor == null -> Math.addExact(after, interval)
                    anchor > after -> anchor
                    else -> Math.addExact(
                        anchor,
                        Math.multiplyExact((after - anchor) / interval + 1, interval)
                    )
                }
            }

            "daily" -> {
                val zone = ZoneId.of(rule.getString("timeZone"))
                val local = Instant.ofEpochMilli(after).atZone(zone)
                val time = LocalTime.parse(rule.getString("time"))
                val days = rule.optJSONArray("weekdays")?.let { arr ->
                    (0 until arr.length()).map { DayOfWeek.of(arr.getInt(it)) }.toSet()
                }
                (0L..7L).map { offset ->
                    local.toLocalDate().plusDays(offset).atTime(time).atZone(zone)
                }
                    .first {
                        it.toInstant()
                            .toEpochMilli() > after && (days == null || it.dayOfWeek in days)
                    }.toInstant().toEpochMilli()
            }

            else -> null
        }

    fun matches(rule: JSONObject, type: String, event: JSONObject): Boolean {
        if (rule.optString("type") != type) return false
        if (type == "battery_level") return batteryCrossed(rule, event)
        val filters = rule.optJSONObject("filters") ?: JSONObject()
        return filters.keys().asSequence().all { field ->
            val value = filters.getString(field)
            when (field) {
                "titleContains" -> event.optString("title").contains(value, ignoreCase = true)
                "textContains" -> event.optString("text").contains(value, ignoreCase = true)
                // 名称由用户输入，大小写与系统展示可能不同。
                "ssid", "deviceName" -> event.optString(field).equals(value, ignoreCase = true)
                else -> event.optString(field) == value
            }
        }
    }

    /** 只在越过阈值的那一次触发，电量在阈值附近波动时不会反复运行。 */
    private fun batteryCrossed(rule: JSONObject, event: JSONObject): Boolean {
        if (!event.has("level") || !event.has("previous")) return false
        val level = event.optInt("level")
        val previous = event.optInt("previous")
        return if (rule.has("below")) {
            val threshold = rule.getInt("below")
            level < threshold && previous >= threshold
        } else {
            val threshold = rule.getInt("above")
            level > threshold && previous <= threshold
        }
    }

    val conditionNames = setOf("charging", "unlocked", "networkConnected")

    /** conditions 全部满足，且 conditionsAny（若有）至少一项满足。 */
    fun conditionsMatch(rule: JSONObject, state: Map<String, Boolean>): Boolean {
        val all = rule.optJSONObject("conditions")?.let { conditions ->
            conditions.keys().asSequence()
                .all { name -> state[name] == conditions.getBoolean(name) }
        } ?: true
        val any = rule.optJSONObject("conditionsAny")?.let { conditions ->
            conditions.keys().asSequence()
                .any { name -> state[name] == conditions.getBoolean(name) }
        } ?: true
        return all && any
    }

    /**
     * 任务结果触发把 filters.taskId 指向的任务作为上游。每个任务只有一个触发器，依赖是单链，
     * 从候选任务向上游走回到自身即成环。返回环上的任务 ID（从候选开始），无环返回 null。
     * 只考虑启用的任务：停用任务在重新启用时会再次检查。
     */
    fun findDependencyCycle(
        enabledRules: Map<String, JSONObject>,
        candidateId: String,
        candidateRule: JSONObject,
    ): List<String>? {
        val rules = enabledRules + (candidateId to candidateRule)
        val path = mutableListOf(candidateId)
        var upstream = upstreamTaskId(candidateRule)
        while (upstream != null) {
            if (upstream == candidateId) return path
            if (upstream in path) return null
            path += upstream
            upstream = rules[upstream]?.let(::upstreamTaskId)
        }
        return null
    }

    private fun upstreamTaskId(rule: JSONObject): String? =
        rule.takeIf { it.optString("type") in setOf("task_completed", "task_failed") }
            ?.optJSONObject("filters")
            ?.optString("taskId")
            ?.takeIf(String::isNotBlank)

    fun integer(
        args: JSONObject,
        name: String,
        minimum: Long,
        maximum: Long,
        default: Long? = null
    ): Long {
        if (!args.has(name) && default != null) return default
        val value = args.get(name)
        require(
            value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong()
                .toDouble() && value.toLong() in minimum..maximum
        ) { "$name 需要 $minimum–$maximum 的整数" }
        return value.toLong()
    }

    private fun parseInstant(value: String): Long {
        require(value.length <= 64) { "时间参数过长" }
        return OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }
}

package io.github.bytejuno.theta.agent.automation

import io.github.bytejuno.theta.ui.IncomingShareRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentTaskNewTriggersTest {
    @Test
    fun batteryLevelFiresOnlyWhenCrossingThreshold() {
        val below = AgentTaskRules.validate(JSONObject().put("type", "battery_level").put("below", 30))
        assertTrue(AgentTaskRules.matches(below, "battery_level", battery(29, 30)))
        assertFalse(AgentTaskRules.matches(below, "battery_level", battery(28, 29)))
        assertFalse(AgentTaskRules.matches(below, "battery_level", battery(31, 30)))
        val above = AgentTaskRules.validate(JSONObject().put("type", "battery_level").put("above", 80))
        assertTrue(AgentTaskRules.matches(above, "battery_level", battery(81, 80)))
        assertFalse(AgentTaskRules.matches(above, "battery_level", battery(82, 81)))
    }

    @Test
    fun batteryLevelRequiresExactlyOneBoundedThreshold() {
        listOf(
            JSONObject().put("type", "battery_level"),
            JSONObject().put("type", "battery_level").put("below", 30).put("above", 80),
            JSONObject().put("type", "battery_level").put("below", 100),
            JSONObject().put("type", "battery_level").put("below", 30).put("filters", JSONObject()),
        ).forEach { rule ->
            assertThrows(IllegalArgumentException::class.java) { AgentTaskRules.validate(rule) }
        }
    }

    @Test
    fun ssidAndDeviceNameFiltersMatchIgnoringCase() {
        val wifi = AgentTaskRules.validate(rule("network_connected", "ssid" to "Home-5G"))
        assertTrue(AgentTaskRules.matches(wifi, "network_connected", JSONObject().put("transport", "wifi").put("ssid", "home-5g")))
        assertFalse(AgentTaskRules.matches(wifi, "network_connected", JSONObject().put("transport", "wifi")))
        val headset = AgentTaskRules.validate(rule("bluetooth_connected", "deviceName" to "HUAWEI FreeBuds"))
        assertTrue(AgentTaskRules.matches(headset, "bluetooth_connected", JSONObject().put("deviceName", "huawei freebuds")))
        assertThrows(IllegalArgumentException::class.java) {
            AgentTaskRules.validate(rule("screen_on", "deviceName" to "x"))
        }
    }

    @Test
    fun sharedContentKindsAndValidation() {
        assertEquals("link", SharedContentTrigger.describe(IncomingShareRequest(text = "标题 https://example.com/a"))!!.getString("kind"))
        assertEquals("text", SharedContentTrigger.describe(IncomingShareRequest(text = "一段笔记\n第二行"))!!.getString("kind"))
        val images = SharedContentTrigger.describe(IncomingShareRequest(uris = listOf("content://a/1", "content://a/2"), mimeType = "image/*"))!!
        assertEquals("image", images.getString("kind"))
        assertEquals(2, images.getInt("count"))
        assertFalse(images.has("text"))
        assertEquals("file", SharedContentTrigger.describe(IncomingShareRequest(uris = listOf("content://a/1"), mimeType = "application/pdf"))!!.getString("kind"))
        assertNull(SharedContentTrigger.describe(IncomingShareRequest(text = "  ")))

        val links = AgentTaskRules.validate(rule("shared_content", "kind" to "link"))
        assertTrue(AgentTaskRules.matches(links, "shared_content", JSONObject().put("kind", "link")))
        assertFalse(AgentTaskRules.matches(links, "shared_content", JSONObject().put("kind", "text")))
        assertThrows(IllegalArgumentException::class.java) {
            AgentTaskRules.validate(rule("shared_content", "kind" to "video"))
        }
    }

    @Test
    fun ssidNormalizationDropsQuotesAndUnknown() {
        assertEquals("Home", AgentTriggerMonitorService.normalizeSsid("\"Home\""))
        assertNull(AgentTriggerMonitorService.normalizeSsid("<unknown ssid>"))
        assertNull(AgentTriggerMonitorService.normalizeSsid("\"\""))
    }

    private fun battery(level: Int, previous: Int) = JSONObject().put("level", level).put("previous", previous)

    private fun rule(type: String, filter: Pair<String, String>) =
        JSONObject().put("type", type).put("filters", JSONObject().put(filter.first, filter.second))
}

class AgentTaskDependencyTest {
    private fun after(taskId: String) =
        JSONObject().put("type", "task_completed").put("filters", JSONObject().put("taskId", taskId))

    @org.junit.Test
    fun detectsCycleThroughCandidateOnly() {
        val rules = mapOf("a" to after("c"), "b" to after("a"))
        // c 由 b 触发：c ← b ← a ← c
        org.junit.Assert.assertEquals(listOf("c", "b", "a"), AgentTaskRules.findDependencyCycle(rules, "c", after("b")))
        org.junit.Assert.assertNull(AgentTaskRules.findDependencyCycle(rules, "c", JSONObject().put("type", "screen_on")))
        org.junit.Assert.assertEquals(listOf("a"), AgentTaskRules.findDependencyCycle(emptyMap(), "a", after("a")))
        // 已有的、不经过候选的环不归咎于候选任务，也不会死循环。
        val existing = mapOf("x" to after("y"), "y" to after("x"))
        org.junit.Assert.assertNull(AgentTaskRules.findDependencyCycle(existing, "z", after("x")))
    }

    @org.junit.Test
    fun conditionsAnyRequiresOneMatch() {
        val rule = AgentTaskRules.validate(
            JSONObject().put("type", "screen_on")
                .put("conditions", JSONObject().put("unlocked", true))
                .put("conditionsAny", JSONObject().put("charging", true).put("networkConnected", true)),
        )
        org.junit.Assert.assertTrue(AgentTaskRules.conditionsMatch(rule, mapOf("unlocked" to true, "charging" to false, "networkConnected" to true)))
        org.junit.Assert.assertFalse(AgentTaskRules.conditionsMatch(rule, mapOf("unlocked" to true, "charging" to false, "networkConnected" to false)))
        org.junit.Assert.assertFalse(AgentTaskRules.conditionsMatch(rule, mapOf("unlocked" to false, "charging" to true, "networkConnected" to true)))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AgentTaskRules.validate(JSONObject().put("type", "screen_on").put("conditionsAny", JSONObject().put("charging", true)))
        }
    }
}

package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLazyToolGroupsTest {
    private val full = AgentToolCatalog.build(
        terminalTools = true, browserTools = true, deviceSensitiveReadTools = true, deviceSensitiveActionTools = true,
        skillGitHubDiscovery = true, skillGitHubInstall = true, memoryTools = true,
    )

    @Test
    fun projectDefersLowFrequencyGroupsBehindLoader() {
        val projected = AgentLazyToolGroups.project(full, emptySet())
        val names = names(projected)
        assertTrue(AgentLazyToolGroups.LOADER_NAME in names)
        assertFalse("tasks_create" in names)
        assertFalse("search_bills" in names)
        assertFalse("skills_install_from_github" in names)
        assertTrue("tap" in names && "set_flashlight" in names && "skills_read" in names && "read_file" in names)
        // 目录体积至少减少三成。
        assertTrue(projected.toString().length < full.toString().length * 7 / 10)
        val loader = projected.getJSONObject(projected.length() - 1).getJSONObject("function")
        assertTrue(loader.getString("description").contains("tasks_create"))
    }

    @Test
    fun loadingAppendsGroupAndKeepsLoaderStable() {
        val loaded = mutableSetOf<String>()
        val before = AgentLazyToolGroups.project(full, loaded)
        val available = AgentLazyToolGroups.deferredGroups(full)
        assertEquals(setOf("personal_data", "automation", "skill_admin"), available)
        assertTrue(JSONObject(AgentLazyToolGroups.load("""{"group":"automation"}""", available, loaded)).getBoolean("ok"))
        assertFalse(JSONObject(AgentLazyToolGroups.load("""{"group":"nope"}""", available, loaded)).getBoolean("ok"))
        val after = AgentLazyToolGroups.project(full, loaded)
        assertTrue("tasks_create" in names(after))
        assertFalse("search_bills" in names(after))
        assertEquals(loaderOf(before).toString(), loaderOf(after).toString())
    }

    @Test
    fun noLoaderWhenNothingIsDeferrable() {
        // 未开启敏感设备工具、无 GitHub 技能工具且不可写（无任务工具）时没有可延迟分组。
        val core = AgentToolCatalog.build(terminalTools = false, browserTools = false, memoryWritable = false)
        assertTrue(AgentLazyToolGroups.deferredGroups(core).isEmpty())
        assertFalse(AgentLazyToolGroups.LOADER_NAME in names(AgentLazyToolGroups.project(core, emptySet())))
    }

    private fun loaderOf(tools: JSONArray): JSONObject =
        (0 until tools.length()).map(tools::getJSONObject).first { it.getJSONObject("function").getString("name") == AgentLazyToolGroups.LOADER_NAME }

    private fun names(tools: JSONArray): Set<String> =
        (0 until tools.length()).mapTo(mutableSetOf()) { tools.getJSONObject(it).getJSONObject("function").getString("name") }
}

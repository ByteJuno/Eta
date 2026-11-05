package io.github.bytejuno.theta.agent.plugin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginRegistryTest {
    @Test
    fun sameToolOnTwoConnectionsHasIndependentIdentity() {
        val bindings = PluginRegistry.buildRunBindings(
            listOf(descriptor("account-a", "model_a"), descriptor("account-b", "model_b")),
            emptySet(),
        )

        assertEquals(2, bindings.size)
        assertEquals("account-a", bindings.resolve("model_a")?.identity?.connectionId)
        assertEquals("account-b", bindings.resolve("model_b")?.identity?.connectionId)
        assertNull(bindings.resolve("mcp_unknown"))
        assertNull(bindings.resolve("search"))
    }

    @Test
    fun duplicateModelNameIsRejectedRatherThanOverwritten() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginRegistry.buildRunBindings(
                listOf(descriptor("a", "same"), descriptor("b", "same")),
                emptySet(),
            )
        }
    }

    @Test
    fun duplicateIdentityUnderDifferentNamesIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginRegistry.buildRunBindings(
                listOf(descriptor("a", "first"), descriptor("a", "second")),
                emptySet(),
            )
        }
    }

    @Test
    fun coreAndExistingLocalNamesCannotBeRegistered() {
        listOf("batch", "tools_load", "subagent", "subagent_profile", "conversation_history", "read_file")
            .forEach { name ->
                assertThrows(IllegalArgumentException::class.java) {
                    PluginRegistry.buildRunBindings(listOf(descriptor("a", name)), setOf("read_file"))
                }
            }
    }

    @Test
    fun mutableInputAndReturnedJsonCannotChangeFrozenBindings() {
        val original = descriptor("a", "original")
        val input = mutableListOf(original)
        val occupied = mutableSetOf("read_file")
        val bindings = PluginRegistry.buildRunBindings(input, occupied)
        input.clear()
        occupied.add("original")
        bindings.modelTools().getJSONObject(0).getJSONObject("function").put("name", "changed")

        assertEquals(1, bindings.size)
        assertEquals(original, bindings.resolve("original"))
        assertEquals("original", bindings.modelTools().getJSONObject(0).getJSONObject("function").getString("name"))
        assertNull(bindings.resolve("changed"))
    }

    @Test
    fun schemaNameMustMatchBindingName() {
        assertThrows(IllegalArgumentException::class.java) {
            descriptor("a", "original").copy(modelName = "different")
        }
    }

    @Test
    fun emptyCatalogAndBlankIdentityAreHandled() {
        assertEquals(0, PluginRegistry.buildRunBindings(emptyList(), emptySet()).modelTools().length())
        assertThrows(IllegalArgumentException::class.java) {
            PluginToolIdentity("connector.mcp", " ", "search")
        }
    }

    private fun descriptor(connection: String, modelName: String): PluginToolDescriptor =
        PluginToolDescriptor(
            identity = PluginToolIdentity("connector.mcp", connection, "search"),
            modelName = modelName,
            modelSchemaJson = JSONObject()
                .put("type", "function")
                .put("function", JSONObject()
                    .put("name", modelName)
                    .put("parameters", JSONObject().put("type", "object")))
                .toString(),
        )
}

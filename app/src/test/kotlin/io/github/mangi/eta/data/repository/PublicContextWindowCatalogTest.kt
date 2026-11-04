package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The failure path logs through android.util.Log, which needs Robolectric on the JVM.
@RunWith(RobolectricTestRunner::class)
class PublicContextWindowCatalogTest {
    private val index = PublicContextWindowCatalog.parseIndex(
        """
        {
          "openai": {"models": {
            "gpt-6.1-sol": {"limit": {"context": 1050000, "output": 128000}},
            "gpt-image-1.5": {"limit": {"context": 128000}}
          }},
          "anthropic": {"models": {"claude-opus-5.5": {"limit": {"context": 1000000}}}},
          "zhipu": {"models": {"glm-5.3": {"limit": {"context": 1000000}}}},
          "google": {"models": {"gemini-4-argon": {"limit": {"context": 1048576}}}},
          "broken": {"models": {
            "tiny": {"limit": {"context": 12}},
            "no-limit": {},
            "string-limit": {"limit": {"context": "262144"}}
          }},
          "openrouter": {"models": {"anthropic/claude-opus-5.5": {"limit": {"context": 200000}}}}
        }
        """.trimIndent(),
    )

    @Test
    fun proxyDecorationsResolveToThePublicModel() {
        // Real ids from a CLIProxy /models response that carried no window fields.
        assertEquals(1_050_000, PublicContextWindowCatalog.lookup(index, "gpt-6.1-sol"))
        assertEquals(1_050_000, PublicContextWindowCatalog.lookup(index, "global:gpt-6.1-sol"))
        assertEquals(1_000_000, PublicContextWindowCatalog.lookup(index, "wb-glm-5.3"))
        assertEquals(1_000_000, PublicContextWindowCatalog.lookup(index, "kiro/claude-opus-5.5"))
        assertEquals(1_000_000, PublicContextWindowCatalog.lookup(index, "cf-opus-5.5"))
        assertEquals(1_048_576, PublicContextWindowCatalog.lookup(index, "gemini-4-argon-high"))
        assertEquals(262_144, PublicContextWindowCatalog.lookup(index, "string-limit"))
    }

    @Test
    fun firstVendorWinsAndImplausibleOrUnknownValuesAreRejected() {
        // The vendor-namespaced OpenRouter duplicate must not replace the first listing.
        assertEquals(1_000_000, PublicContextWindowCatalog.lookup(index, "claude-opus-5.5"))
        assertNull(PublicContextWindowCatalog.lookup(index, "tiny"))
        assertNull(PublicContextWindowCatalog.lookup(index, "no-limit"))
        assertNull(PublicContextWindowCatalog.lookup(index, "国内deepseek4.1flash"))
        assertNull(PublicContextWindowCatalog.lookup(index, ""))
    }

    @Test
    fun onlyModelsWithoutAnyWindowAreFilledAndIndexIsFetchedOnce() = runBlocking {
        withServer(200, """{"openai":{"models":{"gpt-6.1-sol":{"limit":{"context":1050000}}}}}""") { requests ->
            val models = listOf(
                model("gpt-6.1-sol"),
                model("gpt-6.1-sol").copy(id = "provider", contextWindow = 400_000),
                model("gpt-6.1-sol").copy(id = "user", contextWindowOverride = 256_000),
                model("unknown-model"),
            )

            val filled = PublicContextWindowCatalog.fillMissing(models)
            PublicContextWindowCatalog.fillMissing(models)

            assertEquals(1_050_000, filled[0].contextWindow)
            assertEquals(400_000, filled[1].contextWindow)
            assertEquals(256_000, filled[2].contextWindowOverride)
            assertNull(filled[2].contextWindow)
            assertNull(filled[3].contextWindow)
            assertEquals(1, requests.get())
        }
    }

    @Test
    fun noRequestIsMadeWhenEveryModelAlreadyHasAWindow() = runBlocking {
        withServer(200, "{}") { requests ->
            val models = listOf(model("a").copy(contextWindow = 8_192), model("b").copy(contextWindowOverride = 4_096))
            assertEquals(models, PublicContextWindowCatalog.fillMissing(models))
            assertEquals(0, requests.get())
        }
    }

    @Test
    fun unreachableIndexLeavesModelsUnchanged() = runBlocking {
        withServer(503, "unavailable") {
            val models = listOf(model("gpt-6.1-sol"))
            assertEquals(models, PublicContextWindowCatalog.fillMissing(models))
        }
    }

    private suspend fun withServer(code: Int, body: String, block: suspend (AtomicInteger) -> Unit) {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api.json") { exchange ->
            requests.incrementAndGet()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            PublicContextWindowCatalog.resetForTest("http://127.0.0.1:${server.address.port}/api.json")
            block(requests)
        } finally {
            PublicContextWindowCatalog.resetForTest(null)
            server.stop(0)
        }
    }

    private fun model(modelId: String) = Model(
        id = modelId, modelId = modelId, displayName = modelId, source = ModelSource.REMOTE,
    )
}

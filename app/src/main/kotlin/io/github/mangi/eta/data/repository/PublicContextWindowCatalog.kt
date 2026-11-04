package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.Model
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Looks up context windows for models whose provider `/models` endpoint only returns ids,
 * which is common for self-hosted proxies (one-api, new-api, CLIProxy, LiteLLM...).
 *
 * The public models.dev index is fetched only when at least one model still lacks a window,
 * cached in memory per process, and never overrides a value from the provider itself or the
 * user. Ids are normalized for common proxy decorations (vendor prefixes, routing prefixes,
 * reasoning suffixes) and only exact normalized matches are accepted.
 */
internal object PublicContextWindowCatalog {
    private const val DEFAULT_INDEX_URL = "https://models.dev/api.json"
    private const val MAX_INDEX_BYTES = 16L * 1024 * 1024
    private const val MIN_WINDOW = 1_024
    private const val MAX_WINDOW = 100_000_000
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cached: Map<String, Int>? = null
    @Volatile private var indexUrl: String = DEFAULT_INDEX_URL

    /** Test hook: point at a local server and drop the in-memory cache. */
    internal fun resetForTest(url: String?) {
        indexUrl = url ?: DEFAULT_INDEX_URL
        cached = null
    }

    /** Fills only models that have neither a provider-reported nor a user-configured window. */
    suspend fun fillMissing(models: List<Model>, client: OkHttpClient = AgentHttpClient.client): List<Model> {
        if (models.none { it.needsWindow() }) return models
        val index = index(client) ?: return models
        return models.map { model ->
            if (!model.needsWindow()) model
            else lookup(index, model.modelId)?.let { model.copy(contextWindow = it) } ?: model
        }
    }

    internal fun lookup(index: Map<String, Int>, modelId: String): Int? =
        candidates(modelId).firstNotNullOfOrNull(index::get)

    /** Exposed for tests: builds the normalized lookup table from the raw models.dev document. */
    internal fun parseIndex(body: String): Map<String, Int> {
        val root = json.parseToJsonElement(body).jsonObject
        val result = HashMap<String, Int>()
        root.values.forEach { providerElement ->
            val models = (providerElement as? JsonObject)?.get("models") as? JsonObject ?: return@forEach
            models.forEach { (id, modelElement) ->
                val limit = (modelElement as? JsonObject)?.get("limit") as? JsonObject
                val window = limit?.get("context")?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
                    ?.takeIf { it in MIN_WINDOW..MAX_WINDOW } ?: return@forEach
                // Several vendors list the same model; the first (largest-catalog) value wins.
                result.putIfAbsent(normalize(id), window)
            }
        }
        return result
    }

    internal fun candidates(modelId: String): List<String> {
        val base = normalize(modelId)
        if (base.isEmpty()) return emptyList()
        return buildList {
            add(base)
            REASONING_SUFFIX.find(base)?.let { add(base.removeRange(it.range)) }
            // Some proxies drop the vendor name: `cf-opus-5` is listed as `claude-opus-5`.
            if (CLAUDE_FAMILIES.any { base.startsWith(it) }) add("claude-$base")
        }.distinct()
    }

    private fun normalize(id: String): String {
        var value = id.trim().lowercase()
            .substringAfterLast('/')
            .substringAfterLast(':')
        ROUTING_PREFIX.find(value)?.let { value = value.removeRange(it.range) }
        return value.trim()
    }

    private suspend fun index(client: OkHttpClient): Map<String, Int>? {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(indexUrl).header("Accept", "application/json").build()
                client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "HTTP ${response.code}" }
                    val body = response.peekBody(MAX_INDEX_BYTES + 1).bytes()
                    require(body.size <= MAX_INDEX_BYTES) { "index too large" }
                    parseIndex(body.toString(Charsets.UTF_8))
                }
            }.onSuccess { cached = it }
                .onFailure { failure ->
                    // Offline or blocked: keep the models unchanged; the user can still set a value.
                    AndroidAgentLogger.warn("Public context window index unavailable: type=${failure.safeLogType()}")
                }
                .getOrNull()
        }
    }

    private fun Model.needsWindow(): Boolean = contextWindow == null && contextWindowOverride == null

    /** Routing prefixes added by aggregators, e.g. `wb-glm-5.3`, `cf-opus-5`, `kiro/…`. */
    private val ROUTING_PREFIX = Regex("^(wb|cf|kiro|global|cn|us|eu)[-_]")
    private val CLAUDE_FAMILIES = listOf("opus-", "sonnet-", "haiku-")
    private val REASONING_SUFFIX = Regex("-(minimal|low|medium|high|xhigh|max|thinking)$")
}

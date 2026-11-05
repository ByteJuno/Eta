package io.github.mangi.eta.agent.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 按「日期 × 模型」累计每次成功模型请求的 token 用量，供设置页的用量统计使用。
 *
 * 会话消息只把用量挂在有文字的那一轮，纯工具调用轮与已删除的会话都会漏算，因此单独记账。
 * 各进程都可能发请求，读改写用文件锁串行；只保留最近 [RETENTION_DAYS] 天。
 */
internal object AgentUsageLedger {
    data class Bucket(
        val day: String,
        val model: String,
        val requests: Long = 0,
        val promptTokens: Long = 0,
        val outputTokens: Long = 0,
        val reasoningTokens: Long = 0,
        /** 只统计上报了缓存字段的请求，未上报的不计入命中率分母。 */
        val cacheReportedPromptTokens: Long = 0,
        val cachedTokens: Long = 0,
    ) {
        fun plus(other: Bucket) = copy(
            requests = requests + other.requests,
            promptTokens = promptTokens + other.promptTokens,
            outputTokens = outputTokens + other.outputTokens,
            reasoningTokens = reasoningTokens + other.reasoningTokens,
            cacheReportedPromptTokens = cacheReportedPromptTokens + other.cacheReportedPromptTokens,
            cachedTokens = cachedTokens + other.cachedTokens,
        )

        /** 缓存命中率；没有可比数据时为 null。 */
        val cacheHitRate: Double?
            get() = if (cacheReportedPromptTokens > 0) cachedTokens.toDouble() / cacheReportedPromptTokens else null
    }

    @Volatile private var file: File? = null

    fun init(context: Context) {
        file = File(context.applicationContext.filesDir, FILE_NAME)
    }

    /**
     * Anthropic 的 input_tokens 不含缓存读写，contextTokens 才是完整输入；
     * 其余协议的 input_tokens 已包含缓存命中部分。
     */
    fun promptTokens(usage: AgentTokenUsage, cacheExcludedFromInput: Boolean): Int? =
        if (cacheExcludedFromInput) usage.contextTokens ?: usage.inputTokens?.plus(usage.cachedTokens ?: 0)
        else usage.inputTokens

    fun record(model: String, usage: AgentTokenUsage, cacheExcludedFromInput: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val target = file ?: return
        if (usage.isEmpty) return
        val prompt = promptTokens(usage, cacheExcludedFromInput)?.coerceAtLeast(0)?.toLong() ?: 0L
        val cached = usage.cachedTokens?.coerceAtLeast(0)?.toLong()
        val delta = Bucket(
            day = dayOf(nowMs),
            model = model.ifBlank { "unknown" }.take(MAX_MODEL_CHARS),
            requests = 1,
            promptTokens = prompt,
            outputTokens = usage.outputTokens?.coerceAtLeast(0)?.toLong() ?: 0,
            reasoningTokens = usage.reasoningTokens?.coerceAtLeast(0)?.toLong() ?: 0,
            cacheReportedPromptTokens = if (cached != null) prompt else 0,
            cachedTokens = cached ?: 0,
        )
        runCatching { update(target, nowMs) { buckets -> merge(buckets, delta) } }
    }

    fun read(): List<Bucket> {
        val target = file ?: return emptyList()
        return runCatching { withLock(target) { raf -> decode(readText(raf)) } }.getOrDefault(emptyList())
    }

    fun clear() {
        val target = file ?: return
        runCatching { update(target, System.currentTimeMillis()) { emptyList() } }
    }

    internal fun merge(buckets: List<Bucket>, delta: Bucket): List<Bucket> {
        var found = false
        val merged = buckets.map { bucket ->
            if (bucket.day == delta.day && bucket.model == delta.model) {
                found = true
                bucket.plus(delta)
            } else bucket
        }
        return if (found) merged else merged + delta
    }

    internal fun prune(buckets: List<Bucket>, nowMs: Long): List<Bucket> {
        val oldest = LocalDate.parse(dayOf(nowMs)).minusDays(RETENTION_DAYS - 1L).toString()
        return buckets.filter { it.day >= oldest }
    }

    internal fun encode(buckets: List<Bucket>): String = JSONObject().put("version", 1).put(
        "buckets",
        org.json.JSONArray().apply {
            buckets.forEach { b ->
                put(
                    JSONObject().put("day", b.day).put("model", b.model).put("requests", b.requests)
                        .put("prompt", b.promptTokens).put("output", b.outputTokens).put("reasoning", b.reasoningTokens)
                        .put("cacheBase", b.cacheReportedPromptTokens).put("cached", b.cachedTokens),
                )
            }
        },
    ).toString()

    internal fun decode(text: String): List<Bucket> {
        if (text.isBlank()) return emptyList()
        val array = runCatching { JSONObject(text).optJSONArray("buckets") }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            val day = o.optString("day").takeIf { DAY.matches(it) } ?: return@mapNotNull null
            Bucket(
                day = day,
                model = o.optString("model").ifBlank { "unknown" },
                requests = o.optLong("requests"),
                promptTokens = o.optLong("prompt"),
                outputTokens = o.optLong("output"),
                reasoningTokens = o.optLong("reasoning"),
                cacheReportedPromptTokens = o.optLong("cacheBase"),
                cachedTokens = o.optLong("cached"),
            )
        }
    }

    /** 把 [sinceDay]（含）以来的桶按 [key] 合并，按总 token 降序。 */
    fun summarize(buckets: List<Bucket>, sinceDay: String, key: (Bucket) -> String): List<Bucket> =
        buckets.filter { it.day >= sinceDay }
            .groupBy(key)
            .map { (name, group) -> group.reduce { a, b -> a.plus(b) }.copy(day = name, model = name) }
            .sortedByDescending { it.promptTokens + it.outputTokens }

    fun total(buckets: List<Bucket>, sinceDay: String): Bucket =
        buckets.filter { it.day >= sinceDay }.fold(Bucket(day = sinceDay, model = "")) { a, b -> a.plus(b) }

    fun daysAgo(days: Long, nowMs: Long = System.currentTimeMillis()): String =
        LocalDate.parse(dayOf(nowMs)).minusDays(days).toString()

    fun dayOf(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun update(target: File, nowMs: Long, transform: (List<Bucket>) -> List<Bucket>) {
        withLock(target) { raf ->
            val next = prune(transform(decode(readText(raf))), nowMs)
            val bytes = encode(next).toByteArray(Charsets.UTF_8)
            raf.setLength(0)
            raf.seek(0)
            raf.write(bytes)
            raf.fd.sync()
        }
    }

    private fun <T> withLock(target: File, block: (RandomAccessFile) -> T): T {
        target.parentFile?.mkdirs()
        return RandomAccessFile(target, "rw").use { raf ->
            raf.channel.lock().use { block(raf) }
        }
    }

    private fun readText(raf: RandomAccessFile): String {
        val length = raf.length().coerceAtMost(MAX_FILE_BYTES).toInt()
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        raf.seek(0)
        raf.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")
    private const val FILE_NAME = "model_usage_ledger.json"
    private const val RETENTION_DAYS = 90
    private const val MAX_MODEL_CHARS = 120
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
}

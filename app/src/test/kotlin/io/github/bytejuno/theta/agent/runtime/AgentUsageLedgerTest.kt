package io.github.bytejuno.theta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentUsageLedgerTest {
    @Test
    fun anthropicPromptIncludesCacheWhileOthersUseInputTokens() {
        val usage = AgentTokenUsage(contextTokens = 1_200, inputTokens = 200, cachedTokens = 1_000)
        assertEquals(1_200, AgentUsageLedger.promptTokens(usage, cacheExcludedFromInput = true))
        assertEquals(200, AgentUsageLedger.promptTokens(usage, cacheExcludedFromInput = false))
    }

    @Test
    fun mergeAccumulatesSameDayAndModelAndRoundTrips() {
        val a = AgentUsageLedger.Bucket("2026-11-05", "m", 1, 100, 10, 0, 100, 40)
        val b = AgentUsageLedger.Bucket("2026-11-05", "m", 1, 300, 30, 5, 0, 0)
        val c = AgentUsageLedger.Bucket("2026-11-04", "m", 1, 50, 5, 0, 50, 50)
        val merged = AgentUsageLedger.merge(AgentUsageLedger.merge(listOf(a), b), c)

        assertEquals(2, merged.size)
        assertEquals(AgentUsageLedger.Bucket("2026-11-05", "m", 2, 400, 40, 5, 100, 40), merged[0])
        assertEquals(merged, AgentUsageLedger.decode(AgentUsageLedger.encode(merged)))
        // 未上报缓存的请求不进入命中率分母。
        assertEquals(0.4, merged[0].cacheHitRate!!, 1e-9)
        assertNull(b.cacheHitRate)
    }

    @Test
    fun summarizeGroupsByKeyAndPruneDropsOldDays() {
        val buckets = listOf(
            AgentUsageLedger.Bucket("2026-11-05", "a", 1, 10, 1),
            AgentUsageLedger.Bucket("2026-11-04", "a", 1, 10, 1),
            AgentUsageLedger.Bucket("2026-11-05", "b", 1, 100, 1),
            AgentUsageLedger.Bucket("2026-08-01", "a", 1, 10, 1),
        )
        val byModel = AgentUsageLedger.summarize(buckets, "2026-11-04") { it.model }
        assertEquals(listOf("b", "a"), byModel.map { it.model })
        assertEquals(2L, byModel[1].requests)
        assertEquals(4L, AgentUsageLedger.total(buckets, "2000-01-01").requests)

        val now = java.time.LocalDate.parse("2026-11-05").atTime(12, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(3, AgentUsageLedger.prune(buckets, now).size)
    }

    @Test
    fun corruptLedgerDecodesToEmpty() {
        assertEquals(emptyList<AgentUsageLedger.Bucket>(), AgentUsageLedger.decode("{not json"))
        assertEquals(emptyList<AgentUsageLedger.Bucket>(), AgentUsageLedger.decode(""))
    }
}

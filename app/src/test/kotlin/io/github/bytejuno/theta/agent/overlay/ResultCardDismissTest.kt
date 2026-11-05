package io.github.bytejuno.theta.agent.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultCardDismissTest {
    @Test
    fun shortAnswersKeepTheUpstreamEightSeconds() {
        assertEquals(8_000L, resultCardAutoDismissMs("已完成", failed = false))
        assertEquals(8_000L, resultCardAutoDismissMs("", failed = false))
    }

    @Test
    fun longerAnswersGetReadingTimeButAreCapped() {
        val medium = resultCardAutoDismissMs("字".repeat(240), failed = false)!!
        assertEquals(20_000L, medium)
        assertTrue(medium > 8_000L)
        assertEquals(45_000L, resultCardAutoDismissMs("字".repeat(10_000), failed = false))
    }

    @Test
    fun failuresStayUntilTheUserDismissesThem() {
        assertNull(resultCardAutoDismissMs("模型接口暂时限流", failed = true))
        assertNull(resultCardAutoDismissMs("", failed = true))
    }

    @Test
    fun surroundingWhitespaceDoesNotInflateTheTimer() {
        assertEquals(
            resultCardAutoDismissMs("字".repeat(240), failed = false),
            resultCardAutoDismissMs("\n\n   " + "字".repeat(240) + "   \n", failed = false),
        )
    }
}

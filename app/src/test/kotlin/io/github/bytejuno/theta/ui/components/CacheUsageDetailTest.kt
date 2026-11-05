package io.github.bytejuno.theta.ui.components

import org.junit.Assert.*
import org.junit.Test

class CacheUsageDetailTest {
    @Test fun missingIsUnknownButMeasuredZeroIsNot() {
        assertEquals("unknown", cacheUsageDetail(1000, null, "unknown"))
        assertTrue(cacheUsageDetail(1000, 0, "unknown").startsWith("0"))
        assertFalse(cacheUsageDetail(1000, 0, "unknown").contains("unknown"))
        assertTrue(cacheUsageDetail(1000, 500, "unknown").contains("50"))
        assertEquals("500", cacheUsageDetail(null, 500, "unknown"))
    }
}

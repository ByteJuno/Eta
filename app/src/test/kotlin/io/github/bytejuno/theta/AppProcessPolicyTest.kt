package io.github.bytejuno.theta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessPolicyTest {
    @Test
    fun `仅主进程初始化完整 Runtime 依赖`() {
        assertTrue(AppProcessPolicy.shouldInitializeFullRuntime("io.github.bytejuno.theta", "io.github.bytejuno.theta"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.bytejuno.theta:voice", "io.github.bytejuno.theta"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.bytejuno.theta:voice_session", "io.github.bytejuno.theta"))
    }
}

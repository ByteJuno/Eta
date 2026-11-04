package io.github.mangi.eta.agent.terminal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalExitHintTest {

    @Test
    fun androidExit127PointsAtMissingCommandAndLinuxDevelopmentTools() {
        val hint = TerminalExitHint.forExitCode(127, TerminalEnvironment.ANDROID)
        assertNotNull(hint)
        val text = hint.orEmpty()

        assertTrue(text, text.contains("127"))
        // 只提示常见成因，不能断言该命令确实缺失。
        assertTrue(text, text.contains("通常"))
        assertTrue(text, text.contains("toybox"))
        assertTrue(text, text.contains("Python"))
        assertTrue(text, text.contains("Node.js"))
        assertTrue(text, text.contains("git"))
        assertTrue(text, text.contains("jq"))
        assertTrue(text, text.contains("environment=linux"))
        // 明确说明不会自动重跑或切换环境。
        assertTrue(text, text.contains("未自动重试"))
        assertTrue(text, text.contains("不会自动切换环境"))
    }

    @Test
    fun linuxExit127SuggestsCheckingInstallationAndPath() {
        val hint = TerminalExitHint.forExitCode(127, TerminalEnvironment.DEBIAN)
        assertNotNull(hint)
        val text = hint.orEmpty()

        assertTrue(text, text.contains("127"))
        assertTrue(text, text.contains("PATH"))
        assertTrue(text, text.contains("安装"))
        assertTrue(text, text.contains("busybox"))
        assertTrue(text, text.contains("未自动重试"))
        // Linux 环境内的 127 不该再引导切换到 Linux 环境。
        assertFalse(text, text.contains("environment=linux"))
    }

    @Test
    fun onlyExit127ProducesHint() {
        val exitCodes = listOf(0, 1, 2, 3, 126, 128, 130, 255, -1, -2, -3)
        for (exitCode in exitCodes) {
            assertNull(
                "exit=$exitCode android",
                TerminalExitHint.forExitCode(exitCode, TerminalEnvironment.ANDROID),
            )
            assertNull(
                "exit=$exitCode debian",
                TerminalExitHint.forExitCode(exitCode, TerminalEnvironment.DEBIAN),
            )
        }
        assertNull(TerminalExitHint.forExitCode(0, TerminalEnvironment.ALPINE))
        assertNotNull(TerminalExitHint.forExitCode(127, TerminalEnvironment.ALPINE))
    }

    @Test
    fun attachingHintKeepsExitCodeAndOkFields() {
        val failed = JSONObject()
            .put("ok", false)
            .put("tool", "terminal")
            .put("exit_code", 127)
            .put("stdout", "line")
        val attached = TerminalExitHint.attachTo(failed, 127, TerminalEnvironment.ANDROID)

        assertEquals(127, attached.getInt("exit_code"))
        assertFalse(attached.getBoolean("ok"))
        assertEquals("line", attached.getString("stdout"))
        assertTrue(attached.toString(), attached.has("hint"))
        assertTrue(attached.getString("hint").isNotBlank())
        // 只新增 hint，其他字段集合保持原样。
        assertEquals(5, attached.length())
    }

    @Test
    fun successfulResultGetsNoHintField() {
        val succeeded = JSONObject().put("ok", true).put("exit_code", 0).put("stdout", "")
        TerminalExitHint.attachTo(succeeded, 0, TerminalEnvironment.ANDROID)

        assertFalse(succeeded.toString(), succeeded.has("hint"))
        assertTrue(succeeded.getBoolean("ok"))
        assertEquals(0, succeeded.getInt("exit_code"))
    }

    @Test
    fun hintsStayBoundedSingleLineAndFreeOfPaths() {
        val hints = listOf(
            TerminalExitHint.forExitCode(127, TerminalEnvironment.ANDROID),
            TerminalExitHint.forExitCode(127, TerminalEnvironment.ALPINE),
            TerminalExitHint.forExitCode(127, TerminalEnvironment.DEBIAN),
        )
        for (hint in hints) {
            val text = hint.orEmpty()
            assertTrue(text, text.isNotBlank())
            assertTrue("hint 超出上限：${text.length}", text.length <= TerminalExitHint.MAX_HINT_CHARS)
            assertFalse(text, text.contains("\n"))
            // 提示是静态文案，不应携带命令、工作目录或应用私有路径。
            assertFalse(text, text.contains("/storage"))
            assertFalse(text, text.contains("/data/"))
            assertFalse(text, text.contains("/workspace"))
        }
    }

    @Test
    fun androidAndLinuxHintsDiffer() {
        val android = TerminalExitHint.forExitCode(127, TerminalEnvironment.ANDROID)
        val linux = TerminalExitHint.forExitCode(127, TerminalEnvironment.ALPINE)
        assertNotNull(android)
        assertNotNull(linux)
        assertNotEquals(android, linux)
    }
}

package io.github.bytejuno.theta.core

import android.app.Application
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DiagnosticsBundleTest {
    @Test fun excludesExceptionMessagesAndLocalFilePaths() {
        val error = IllegalStateException("secret-token chat text", RuntimeException("private password"))
        error.stackTrace = arrayOf(StackTraceElement("example.Class", "call", "/private/secret/file", 42))
        val text = DiagnosticsBundle.safeStack(error)
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("password"))
        assertTrue(text.contains("example.Class.call:42"))
    }
    @Test fun exportsOnlyAllowlistedEntries() {
        val bytes = ByteArrayOutputStream()
        DiagnosticsBundle.export(RuntimeEnvironment.getApplication(), bytes)
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; names += entry.name }
        }
        assertTrue("status.json" in names)
        assertTrue(names.all { it in setOf("status.json", "last-crash.txt") })
    }
}

package io.github.bytejuno.theta.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** Deliberate allowlist: no logcat, databases, identifiers, configuration or exception messages. */
internal object DiagnosticsBundle {
    fun safeStack(error: Throwable): String = buildString {
        var current: Throwable? = error
        repeat(4) {
            val failure = current ?: return@repeat
            appendLine(failure.javaClass.name)
            failure.stackTrace.take(30).forEach { frame ->
                appendLine("${frame.className}.${frame.methodName}:${frame.lineNumber}")
            }
            current = failure.cause
        }
    }

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val file = File(context.noBackupFilesDir, "diagnostics/last-crash.txt")
                file.parentFile?.mkdirs()
                file.writeText(safeStack(error))
            } catch (_: Exception) { /* Never mask the original crash. */ }
            finally { previous?.uncaughtException(thread, error) }
        }
    }

    fun export(context: Context, output: OutputStream) {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val status = JSONObject().put("format", "eta-diagnostics-v1")
            .put("appVersion", version).put("sdk", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
            .put("scope", "App version, device model, API level, redacted last crash stack only")
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("status.json"))
            zip.write(status.toString(2).toByteArray()); zip.closeEntry()
            val crash = File(context.noBackupFilesDir, "diagnostics/last-crash.txt")
            if (crash.isFile) {
                zip.putNextEntry(ZipEntry("last-crash.txt"))
                crash.inputStream().use { input -> zip.write(input.readNBytes(32768)) }
                zip.closeEntry()
            }
        }
    }
}

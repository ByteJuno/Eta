package io.github.bytejuno.theta.ui.screens.backup

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bytejuno.theta.R
import io.github.bytejuno.theta.core.DiagnosticsBundle
import io.github.bytejuno.theta.data.repository.AutoBackupScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun BackupMaintenanceControls() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(AutoBackupScheduler.enabled(context)) }
    var status by remember { mutableStateOf(AutoBackupScheduler.status(context)) }
    var busy by remember { mutableStateOf(false) }
    fun outcome(ok: Boolean) {
        status = AutoBackupScheduler.status(context)
        Toast.makeText(context, if (ok) R.string.maintenance_success else R.string.maintenance_failed,
            Toast.LENGTH_LONG).show()
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    AutoBackupScheduler.configure(context, uri)
                }
                enabled = true
                outcome(true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { outcome(false) }
            finally { busy = false }
        }
    }
    val diagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { DiagnosticsBundle.export(context, it) }
                        ?: error("OUTPUT_UNAVAILABLE")
                }
                outcome(true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { outcome(false) }
            finally { busy = false }
        }
    }
    Card(
        modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
    ) {
        BasicComponent(title = stringResource(R.string.auto_backup_warning_title),
            summary = stringResource(R.string.auto_backup_warning))
        ArrowPreference(title = stringResource(if (enabled) R.string.auto_backup_disable else R.string.auto_backup_enable),
            summary = stringResource(R.string.auto_backup_summary), enabled = !busy,
            onClick = {
                if (enabled) {
                    AutoBackupScheduler.configure(context, null)
                    enabled = false
                } else folder.launch(null)
            })
        ArrowPreference(title = stringResource(R.string.auto_backup_now),
            summary = if (busy) stringResource(R.string.data_backup_working) else status,
            enabled = enabled && !busy,
            onClick = {
                scope.launch {
                    busy = true
                    try { withContext(Dispatchers.IO) { AutoBackupScheduler.run(context) }; outcome(true) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { AutoBackupScheduler.failed(context); outcome(false) }
                    finally { busy = false }
                }
            })
        ArrowPreference(title = stringResource(R.string.diagnostics_export),
            summary = stringResource(R.string.diagnostics_summary), enabled = !busy,
            onClick = { diagnostics.launch("eta-diagnostics.zip") })
    }
}

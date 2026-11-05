package io.github.bytejuno.theta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bytejuno.theta.R
import io.github.bytejuno.theta.agent.plugin.PluginCallJournal
import io.github.bytejuno.theta.agent.plugin.calendar.CalendarOperationState
import io.github.bytejuno.theta.agent.plugin.calendar.CalendarSqliteOperationLedger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ExtensionOperationHistory() {
    val context = LocalContext.current.applicationContext
    val resources = LocalResources.current
    OperationHistoryCard(
        title = R.string.calendar_unknown_title,
        summary = R.string.calendar_unknown_summary,
        notice = R.string.calendar_unknown_notice,
        empty = R.string.calendar_unknown_empty,
    ) {
        CalendarSqliteOperationLedger(context).use { ledger ->
            ledger.list(CalendarOperationState.UNKNOWN, limit = 100).map { operation ->
                resources.getString(R.string.calendar_unknown_record,
                    operation.operationId, operation.runId, operation.tool, operation.connectionId)
            }
        }
    }
}

@Composable
internal fun RecentPluginCalls() {
    val context = LocalContext.current.applicationContext
    val resources = LocalResources.current
    OperationHistoryCard(
        title = R.string.plugin_calls_title,
        summary = R.string.plugin_calls_summary,
        notice = R.string.plugin_calls_notice,
        empty = R.string.plugin_calls_empty,
    ) {
        PluginCallJournal(context).use { journal ->
            journal.list(limit = 100, connectionId = null).map { record ->
                resources.getString(R.string.plugin_calls_record,
                    record.status.name, record.connectionId, record.toolId,
                    record.runId, record.callId, record.revision.toString(),
                    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(record.startedAt)),
                    record.finishedAt?.let { (it - record.startedAt).coerceAtLeast(0).toString() } ?: "—")
            }
        }
    }
}

/** Bounded metadata-only view; never reads event content, executes tools or retries writes. */
@Composable
private fun OperationHistoryCard(
    title: Int,
    summary: Int,
    notice: Int,
    empty: Int,
    load: () -> List<String>,
) {
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf(false) }
    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
        ArrowPreference(
            title = stringResource(title),
            summary = stringResource(summary),
            enabled = !loading,
            onClick = {
                shown = true
                loading = true
                error = false
                rows = emptyList()
                scope.launch {
                    try {
                        rows = withContext(Dispatchers.IO) { load() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        error = true
                    } finally {
                        loading = false
                    }
                }
            },
        )
    }
    WindowDialog(
        show = shown,
        title = stringResource(title),
        onDismissRequest = { shown = false },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(notice), style = MiuixTheme.textStyles.body2)
            when {
                loading -> Text(stringResource(R.string.extension_history_loading))
                error -> Text(stringResource(R.string.extension_history_failed), color = MiuixTheme.colorScheme.error)
                rows.isEmpty() -> Text(stringResource(empty))
                else -> rows.forEach { Text(it, style = MiuixTheme.textStyles.footnote2) }
            }
        }
        TextButton(
            text = stringResource(R.string.action_close),
            onClick = { shown = false },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
    }
}

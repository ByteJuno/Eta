package io.github.bytejuno.theta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.bytejuno.theta.R
import io.github.bytejuno.theta.agent.runtime.AgentUsageLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

/** 设置页「用量统计」：本机记账的模型请求 token 用量，按时间段、模型和日期汇总。 */
@Composable
internal fun UsageStatsDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var buckets by remember { mutableStateOf<List<AgentUsageLedger.Bucket>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        buckets = withContext(Dispatchers.IO) { AgentUsageLedger.read() }
    }
    OverlayDialog(
        show = true,
        title = stringResource(R.string.usage_stats_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            val data = buckets
            when {
                data == null -> Footnote(stringResource(R.string.usage_stats_loading))
                data.isEmpty() -> Footnote(stringResource(R.string.usage_stats_empty))
                else -> UsageStatsBody(data)
            }
            Footnote(stringResource(R.string.usage_stats_note), Modifier.padding(top = 12.dp))
        }
        MiuixDialogActions(
            confirmText = stringResource(R.string.action_close_usage_stats),
            cancelText = stringResource(if (confirmClear) R.string.usage_stats_clear_confirm else R.string.usage_stats_clear),
            cancelEnabled = !buckets.isNullOrEmpty(),
            onCancel = {
                if (!confirmClear) {
                    confirmClear = true
                } else {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { AgentUsageLedger.clear() }
                        buckets = emptyList()
                    }
                }
            },
            onConfirm = onDismiss,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun UsageStatsBody(buckets: List<AgentUsageLedger.Bucket>) {
    val periods = listOf(
        R.string.usage_stats_today to AgentUsageLedger.daysAgo(0),
        R.string.usage_stats_7_days to AgentUsageLedger.daysAgo(6),
        R.string.usage_stats_30_days to AgentUsageLedger.daysAgo(29),
    )
    Heading(stringResource(R.string.usage_stats_overview), top = 4)
    periods.forEach { (label, since) ->
        UsageRow(stringResource(label), AgentUsageLedger.total(buckets, since))
    }
    Heading(stringResource(R.string.usage_stats_by_model), top = 12)
    AgentUsageLedger.summarize(buckets, AgentUsageLedger.daysAgo(29)) { it.model }.take(MAX_MODELS).forEach { bucket ->
        UsageRow(bucket.model, bucket)
    }
    Heading(stringResource(R.string.usage_stats_by_day), top = 12)
    AgentUsageLedger.summarize(buckets, AgentUsageLedger.daysAgo(6)) { it.day }.sortedByDescending { it.day }.forEach { bucket ->
        UsageRow(bucket.day.substring(5), bucket)
    }
}

@Composable
private fun UsageRow(label: String, bucket: AgentUsageLedger.Bucket) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Row {
            Text(
                text = label,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Footnote(usageLine(bucket))
    }
}

@Composable
private fun usageLine(bucket: AgentUsageLedger.Bucket): String {
    val hit = bucket.cacheHitRate?.let { String.format(Locale.ROOT, "%.0f%%", it * 100) }
        ?: stringResource(R.string.usage_stats_cache_unknown)
    return stringResource(
        R.string.usage_stats_line,
        bucket.requests,
        compactTokens(bucket.promptTokens),
        compactTokens(bucket.outputTokens),
        hit,
    )
}

@Composable
private fun Heading(text: String, top: Int) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.subtitle,
        color = MiuixTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = top.dp, bottom = 4.dp),
    )
}

@Composable
private fun Footnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier.padding(start = 4.dp, top = 2.dp),
    )
}

internal fun compactTokens(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.ROOT, "%.2fM", value / 1_000_000.0)
    value >= 10_000 -> String.format(Locale.ROOT, "%.0fK", value / 1_000.0)
    value >= 1_000 -> String.format(Locale.ROOT, "%.1fK", value / 1_000.0)
    else -> value.toString()
}

private const val MAX_MODELS = 12

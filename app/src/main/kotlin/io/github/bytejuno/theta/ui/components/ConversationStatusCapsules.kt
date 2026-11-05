package io.github.bytejuno.theta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import top.yukonga.miuix.kmp.window.WindowDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.bytejuno.theta.R
import io.github.bytejuno.theta.ui.model.AgentChatMessageUi
import io.github.bytejuno.theta.ui.model.AgentMessageUi
import io.github.bytejuno.theta.ui.model.AgentModelOptionUi
import io.github.bytejuno.theta.ui.model.formatCompactTokenCount
import io.github.bytejuno.theta.ui.model.latestContextUsage
import io.github.bytejuno.theta.ui.model.TokenUsageUi
import java.text.NumberFormat
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ConversationStatusCapsules(messages: List<AgentChatMessageUi>, model: AgentModelOptionUi?, lastUsage: TokenUsageUi?) {
    val usage = latestContextUsage(messages, model).let { it.copy(contextTokens = lastUsage?.contextTokens ?: it.contextTokens) }
    val count = usage.contextTokens?.let { formatCompactTokenCount(it) } ?: "—"
    val limit = usage.contextWindow?.takeIf { it > 0 }?.let { formatCompactTokenCount(it) }
    val percent = usage.contextWindow?.takeIf { it > 0 }?.let { window ->
        usage.contextTokens?.takeIf { it >= 0 }?.let { tokens ->
            NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }.format(tokens.toDouble() / window)
        }
    }
    val contextText = count + (limit?.let { "/$it" } ?: "") + (percent?.let { " · $it" } ?: "")
    val contextDescription = stringResource(R.string.conversation_context_capsule) + " " + contextText
    val latestUsage = lastUsage ?: messages.asReversed().filterIsInstance<AgentMessageUi>()
        .firstOrNull { it.usage != null }?.usage
    var showUsage by remember { mutableStateOf(false) }
    val speed = latestUsage?.averageTokensPerSecond
    val speedText = speed?.let {
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(it)
    } ?: "—"
    Row(Modifier.widthIn(max = 232.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusCapsule(contextText, Modifier.weight(1f, fill = false).clickable { showUsage = true }, contextDescription)
        StatusCapsule("$speedText tok/s", Modifier, stringResource(R.string.conversation_speed_description, speedText))
    }
    if (showUsage) {
        WindowDialog(show = true, title = stringResource(R.string.usage_details_title), onDismissRequest = { showUsage = false }) {
            val unknown = stringResource(R.string.usage_not_reported)
            val input = latestUsage?.inputTokens
            val cache = latestUsage?.cachedTokens
            Text(stringResource(R.string.usage_input_label) + ": " + (input?.toString() ?: unknown))
            Text(stringResource(R.string.usage_output_label) + ": " + (latestUsage?.outputTokens?.toString() ?: unknown))
            Text(stringResource(R.string.usage_cache_label) + ": " + cacheUsageDetail(input, cache, unknown))
            if (cache == null) Text(stringResource(R.string.usage_cache_unknown_hint))
        }
    }
}

/** A missing provider field is unknown, never a measured zero or a cache miss. */
internal fun cacheUsageDetail(input: Int?, cached: Int?, unknown: String): String {
    if (cached == null) return unknown
    val ratio = input?.takeIf { it > 0 && cached in 0..it }?.let {
        NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }.format(cached.toDouble() / it)
    }
    return cached.toString() + (ratio?.let { " ($it)" } ?: "")
}

@Composable
private fun StatusCapsule(text: String, modifier: Modifier, description: String = text) {
    Text(text,
        modifier = modifier.background(MiuixTheme.colorScheme.surfaceContainer, CircleShape)
            .semantics { contentDescription = description }.padding(horizontal = 8.dp, vertical = 5.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.footnote1, fontSize = 11.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

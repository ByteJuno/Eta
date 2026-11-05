package io.github.bytejuno.theta.ui.components

import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.bytejuno.theta.R
import io.github.bytejuno.theta.agent.plugin.calendar.CalendarApprovalBridge
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** Activity content only, never a background window or overlay service. */
@Composable
internal fun CalendarApprovalHost(runId: String?, visible: Boolean) {
    val owner = LocalLifecycleOwner.current
    val lifecycleState by owner.lifecycle.currentStateAsState()
    val view = LocalView.current
    val requests by CalendarApprovalBridge.requests.collectAsState()
    DisposableEffect(owner, view, runId, visible) {
        var lease: AutoCloseable? = null
        fun sync() {
            val present = visible && !runId.isNullOrBlank() && view.isShown &&
                view.windowVisibility == View.VISIBLE &&
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (present && lease == null) lease = CalendarApprovalBridge.registerForeground(requireNotNull(runId))
            if (!present) { lease?.close(); lease = null }
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        val layout = android.view.ViewTreeObserver.OnGlobalLayoutListener { sync() }
        owner.lifecycle.addObserver(observer)
        view.viewTreeObserver.addOnGlobalLayoutListener(layout)
        sync()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            view.viewTreeObserver.removeOnGlobalLayoutListener(layout)
            lease?.close()
        }
    }
    val request = requests.firstOrNull { it.operation.runId == runId } ?: return
    if (!visible || !lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return
    key(request.operation.operationId) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.calendar_approval_title),
            onDismissRequest = { CalendarApprovalBridge.deny(request) },
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.calendar_approval_scope), style = MiuixTheme.textStyles.body2)
                Text(stringResource(R.string.calendar_approval_tool, request.plan.tool))
                Text(stringResource(R.string.calendar_approval_identity,
                    request.operation.connectionId, request.operation.operationId))
                request.plan.targetPreviewJson?.let { preview ->
                    Text(stringResource(R.string.calendar_approval_target))
                    Text(formatCalendarApprovalJson(preview), style = MiuixTheme.textStyles.body2)
                }
                Text(stringResource(R.string.calendar_approval_fields))
                Text(formatCalendarApprovalJson(request.plan.normalizedArguments), style = MiuixTheme.textStyles.body2)
                Text(stringResource(R.string.calendar_approval_expiry,
                    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(request.operation.expiresAt))),
                    style = MiuixTheme.textStyles.footnote2)
            }
            MiuixDialogActions(
                modifier = Modifier.padding(top = 16.dp),
                confirmText = stringResource(R.string.calendar_approval_confirm),
                cancelText = stringResource(R.string.calendar_approval_deny),
                destructive = request.plan.tool == "delete_calendar_event",
                onConfirm = {
                    if (visible && view.isShown && view.windowVisibility == View.VISIBLE &&
                        owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        CalendarApprovalBridge.approve(request)
                    } else CalendarApprovalBridge.deny(request)
                },
                onCancel = { CalendarApprovalBridge.deny(request) },
            )
        }
    }
}

/** Show every normalized field and every batch member; never truncate an approval plan. */
internal fun formatCalendarApprovalJson(raw: String): String = runCatching {
    if (raw.trimStart().startsWith("[")) JSONArray(raw).toString(2) else JSONObject(raw).toString(2)
}.getOrDefault(raw)

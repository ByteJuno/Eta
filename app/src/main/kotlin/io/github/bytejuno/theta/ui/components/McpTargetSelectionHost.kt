package io.github.bytejuno.theta.ui.components

import android.view.View
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.bytejuno.theta.R
import io.github.bytejuno.theta.agent.plugin.McpTargetSelectionBridge
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun McpTargetSelectionHost(runId: String?, visible: Boolean) {
    val owner = LocalLifecycleOwner.current
    val lifecycleState by owner.lifecycle.currentStateAsState()
    val view = LocalView.current
    val requests by McpTargetSelectionBridge.requests.collectAsState()
    DisposableEffect(owner, view, runId, visible) {
        var lease: AutoCloseable? = null
        fun sync() {
            val present = visible && !runId.isNullOrBlank() && view.isShown &&
                view.windowVisibility == View.VISIBLE &&
                owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (present && lease == null) lease = McpTargetSelectionBridge.registerForeground(requireNotNull(runId))
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
    val request = requests.firstOrNull { it.runId == runId } ?: return
    if (!visible || !lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return
    key(request.id) {
        var selected by remember { mutableStateOf(emptySet<String>()) }
        WindowDialog(
            show = true,
            title = stringResource(R.string.mcp_target_title),
            onDismissRequest = { request.deny() },
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.mcp_target_scope), modifier = Modifier.padding(bottom = 12.dp))
                request.connections.forEach { (id, alias) ->
                    SwitchPreference(
                        title = alias.ifBlank { id },
                        summary = stringResource(R.string.mcp_target_connection_id, id),
                        checked = id in selected,
                        onCheckedChange = { checked -> selected = if (checked) selected + id else selected - id },
                    )
                }
            }
            MiuixDialogActions(
                modifier = Modifier.padding(top = 16.dp),
                confirmText = stringResource(R.string.mcp_target_confirm),
                cancelText = stringResource(R.string.mcp_target_deny),
                confirmEnabled = selected.isNotEmpty(),
                onConfirm = {
                    if (visible && view.isShown && view.windowVisibility == View.VISIBLE &&
                        owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        request.approve(selected)
                    } else request.deny()
                },
                onCancel = { request.deny() },
            )
        }
    }
}
